#include "cmd.h"
#include "doctor.h"
#include "fs.h"
#include "json.h"
#include "manifest.h"
#include "path.h"
#include "pipeline.h"
#include "provider.h"
#include "repo.h"
#include "state.h"
#include "txn.h"
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include "main.h"

/* LinuxHandler.uninstall semantics: portable kinds drop the launcher,
   installer kinds delete the binary under the install root; then the
   manifest and the whole app state directory. The success line names
   the ref. */
bool uninstall_one(const state_ctx *st, manifest_entry *m)
{
    bool portable = m->kind == NULL
        || !strcmp(m->kind, "appimage") || !strcmp(m->kind, "portable-exe")
        || !strcmp(m->kind, "portable") || !strcmp(m->kind, "ape");
    bool removed = true;
    if (portable) {
        if (m->launcher != NULL) {
            struct stat sb;
            if (lstat(m->launcher, &sb) == 0 && !S_ISDIR(sb.st_mode)
                && unlink(m->launcher) != 0 && errno != ENOENT) {
                ui_msg(stderr, UI_ERROR,
                    "Failed to uninstall portable package: %s",
                    strerror(errno));
                removed = false;
            }
        }
    } else {
        const char *upath = m->launcher != NULL ? m->launcher : m->path;
        size_t rl = strlen(st->install_root);
        bool inside = strncmp(upath, st->install_root, rl) == 0
            && upath[rl] == '/';
        // a textual prefix is not containment: .. segments can point
        // outside the install root while still starting with it
        if (inside && access(upath, F_OK) == 0)
            inside = path_under(upath, st->install_root);
        if (!inside) {
            ui_msg(stderr, UI_ERROR, "Refusing to uninstall path outside %s: %s",
                st->install_root, upath);
            removed = false;
        } else if (access(upath, F_OK) == 0 && unlink(upath) != 0) {
            ui_msg(stderr, UI_ERROR, "Failed to uninstall: %s", strerror(errno));
            removed = false;
        }
    }
    // a recorded desktop entry is ours whichever kind produced it (adopted
    // packages record one too), so it goes with every install
    if (removed && m->desktop != NULL && unlink(m->desktop) != 0
        && errno != ENOENT) {
        ui_msg(stderr, UI_ERROR, "Failed to uninstall: %s", strerror(errno));
        removed = false;
    }
    if (!removed) {
        ui_msg(stderr, UI_ERROR, "Failed to uninstall %s.", m->repo);
        return false;
    }
    // re-scan first (the extra manifest warnings are on purpose), then
    // delete the manifest file
    state_scan_manifests_quiet(st->root);
    if (remove(m->file) != 0) {
        ui_msg(stderr, UI_ERROR,
            "Uninstalled %s, but failed to remove its manifest.",
            m->repo);
        return false;
    }
    if (!manifest_remove_app_dir(st->root, m->provider, m->repo))
        ui_msg(stderr, UI_ERROR, "Uninstalled %s, but failed to update state.",
            m->repo);
    return true;
}

// write the uninstall journal for a phase (prepared/committing); old-side
// paths come from the manifest being removed
static bool uninstall_txn_write(const state_ctx *st,
    const manifest_entry *m, const char *ref, const char *phase,
    const char *id, const char *created)
{
    txn t;
    txn_init(&t);
    t.id = strdup(id);
    t.op = strdup("uninstall");
    t.ref = strdup(ref);
    t.phase = strdup(phase);
    t.created_at = strdup(created);
    if (m->file != NULL) t.old_manifest_path = strdup(m->file);
    if (m->launcher != NULL) t.old_launcher = strdup(m->launcher);
    if (m->path != NULL) {
        t.old_binary = strdup(m->path);
        const char *sl = strrchr(m->path, '/');
        if (sl != NULL)
            t.old_generation = strndup(m->path, (size_t)(sl - m->path));
    }
    char *j = txn_build(&t);
    bool ok = j != NULL && txn_write(st->root, j);
    free(j);
    txn_free(&t);
    return ok;
}

int uninstall_run(const cmd_opts *o, state_ctx *st)
{
    const char *arg = o->nargs >= 2 ? o->args[1] : NULL;
    char prov[128], path[512], ref[700];
    if (arg == NULL || *arg == '\0') return run_help("uninstall");
    bool bare = strchr(arg, ':') == NULL;

    manifest_entry *ms = NULL;
    size_t mc = 0;
    if (manifests_scan(st->root, &ms, &mc) != 0) {
        put_fail("Failed to load application state: state scan failed");
        oplog_append(st, "uninstall", "-", "-",
            "Failed to load application state: state scan failed");
        return 1;
    }
    if (bare) {
        // short-name resolution: a miss keeps the parser's
        // provider-required error byte-identical
        bool many = false;
        manifest_entry *r = resolve_installed(o, arg, ms, mc, &many);
        if (r == NULL && many) {
            put_fail("Uninstall with provider:owner/repo to choose one.");
            manifest_list_free(ms, mc);
            oplog_append(st, "uninstall", arg, "-",
                "Ambiguous package name.");
            return 1;
        }
        if (r != NULL) {
            snprintf(prov, sizeof(prov), "%s", r->provider);
            snprintf(path, sizeof(path), "%s", r->repo);
        } else {
            parse_ref(arg, prov, sizeof(prov), path,
                sizeof(path)); // emits the provider-required error
            manifest_list_free(ms, mc);
            return 1;
        }
    } else if (!parse_ref(arg, prov, sizeof(prov), path, sizeof(path))) {
        return 1;
    }
    snprintf(ref, sizeof(ref), "%s:%s", prov, path);

    manifest_entry *m = NULL;
    for (size_t k = 0; k < mc; k++)
        if (m == NULL && !strcmp(ms[k].provider, prov)
            && !strcmp(ms[k].repo, path))
            m = &ms[k];
    if (m == NULL) {
        char msg[768];
        snprintf(msg, sizeof(msg), "Release %s is not found.", ref);
        put_fail(msg);
        manifest_list_free(ms, mc);
        oplog_append(st, "uninstall", ref, "-", msg);
        return 1;
    }
    if (!strcmp(prov, "self")) {
        // the self entry owns the running tool; always refuse
        const char *msg = "ape-get cannot uninstall itself.";
        put_s(stderr, UI_ERROR, "%s", msg);
        manifest_list_free(ms, mc);
        oplog_append(st, "uninstall", ref, "-", msg);
        return 1;
    }
    const char *upath = m->launcher != NULL ? m->launcher : m->path;

    if (o->dry_run) {
        if (o->json) {
            jbuf b = {0};
            jb_lit(&b, "{\"dry_run\":true,\"action\":\"uninstall\","
                "\"provider\":");
            jb_json_string(&b, prov);
            jb_lit(&b, ",\"repo\":");
            jb_json_string(&b, path);
            jb_lit(&b, ",\"path\":");
            jb_json_string(&b, upath);
            jb_lit(&b, "}");
            puts(b.p);
            jb_free(&b);
        } else {
            printf("Dry run: would uninstall %s.\n", ref);
            printf("  Path: %s\n", upath);
            puts("No changes were made.");
        }
        manifest_list_free(ms, mc);
        return 0;
    }

    char q[1400];
    snprintf(q, sizeof(q), "Uninstall %s %s from %s?", ref,
        m->version != NULL ? m->version : "null", upath);
    if (!confirm_action(o, q, true)) {
        put_fail("Uninstall declined.");
        manifest_list_free(ms, mc);
        oplog_append(st, "uninstall", ref, "-", "Uninstall declined.");
        return 1;
    }

    // journal: prepared before touching anything; committing is the last
    // write before the deletions (its own commit point)
    char jid[17], jc[40];
    txn_new_id(jid);
    {
        time_t jt = time(NULL);
        struct tm jtm;
        gmtime_r(&jt, &jtm);
        snprintf(jc, sizeof(jc), "%04d-%02d-%02dT%02d:%02d:%02dZ",
            jtm.tm_year + 1900, jtm.tm_mon + 1, jtm.tm_mday,
            jtm.tm_hour, jtm.tm_min, jtm.tm_sec);
    }
    bool j_on = uninstall_txn_write(st, m, ref, "prepared", jid, jc);
    if (!j_on) {
        // every deletion below is unrecoverable without the journal, so a
        // journal that cannot be written stops the command here
        put_fail("Failed to uninstall package.");
        oplog_append(st, "uninstall", ref, "-",
            "Failed to uninstall package.");
        manifest_list_free(ms, mc);
        return 1;
    }
    if (!uninstall_txn_write(st, m, ref, "committing", jid, jc)) {
        put_fail("Failed to uninstall package.");
        oplog_append(st, "uninstall", ref, "-",
            "Failed to uninstall package.");
        manifest_list_free(ms, mc);
        return 1;   // a journal may exist; recovery handles it
    }

    bool ok = uninstall_one(st, m);
    // the JSON tail prints this path after the entry list is freed
    char *done_path = strdup(upath);
    manifest_list_free(ms, mc);
    if (ok)
        state_rebuild_index(st);
    if (!ok) {
        // past the commit point: the journal stays for recovery
        oplog_append(st, "uninstall", ref, "-",
            "Failed to uninstall package.");
        free(done_path);
        return 1;
    }
    {
        char *jp = path_join(st->root, "grm_transaction.json");
        if (jp != NULL) { remove(jp); free(jp); }
    }
    if (o->json) {
        jbuf b = {0};
        jb_lit(&b, "{\"uninstalled\":{\"provider\":");
        jb_json_string(&b, prov);
        jb_lit(&b, ",\"repo\":");
        jb_json_string(&b, path);
        jb_lit(&b, ",\"path\":");
        jb_json_string(&b, done_path != NULL ? done_path : "");
        jb_lit(&b, "}}");
        puts(b.p);
        jb_free(&b);
        free(done_path);
    } else if (!o->quiet) {
        put_s(stderr, UI_OK, "Uninstalled %s.", ref);
    }
    oplog_append(st, "uninstall", ref, "-", NULL);
    return 0;
}
