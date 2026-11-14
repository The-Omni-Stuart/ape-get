#include "cmd.h"
#include "compat.h"
#include "crypto.h"
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

// stage binary + manifest, journal committing, then rename in; a crash past
// the journal rolls forward, and whichever side finishes writes history.

// test hook: APEGET_TEST_CRASH=<which> aborts the process at this point
static void test_crash_point(const char *which)
{
    const char *v = getenv("APEGET_TEST_CRASH");
    if (v == NULL || strcmp(v, which) != 0) return;
    fflush(NULL);
    _exit(70);
}

static bool rollback_journal_write(const state_ctx *st, const char *ref,
    const char *id, const char *created, const char *bin_tmp,
    const char *man_tmp, const char *man)
{
    txn t;
    txn_init(&t);
    t.id = strdup(id);
    t.op = strdup("rollback");
    t.ref = strdup(ref);
    t.phase = strdup("committing");
    t.created_at = strdup(created);
    t.new_binary_tmp = strdup(bin_tmp);
    t.new_manifest_tmp = strdup(man_tmp);
    t.new_manifest = strdup(man);
    char sha[65];
    bool ready = crypto_sha256_file(bin_tmp, sha)
        && (t.new_asset_sha256 = strdup(sha)) != NULL
        && crypto_sha256_file(man_tmp, sha)
        && (t.new_manifest_sha256 = strdup(sha)) != NULL;
    char *j = ready ? txn_build(&t) : NULL;
    bool ok = j != NULL && txn_write(st->root, j);
    free(j);
    txn_free(&t);
    return ok;
}

// the backup's install.uninstall.path: the launcher this generation was activated with
static char *backup_launcher(const jobject *broot)
{
    int ii = jobject_find(broot, "install");
    if (ii < 0) return NULL;
    char *it = span_dup(broot->vals[ii], broot->vends[ii]);
    jobject io = {0};
    char *out = NULL;
    if (it != NULL && jobject_parse(it, &io)) {
        int ui = jobject_find(&io, "uninstall");
        if (ui >= 0) {
            char *ut = span_dup(io.vals[ui], io.vends[ui]);
            jobject uo = {0};
            if (ut != NULL && jobject_parse(ut, &uo)) out = jstr(&uo, "path");
            jobject_free(&uo);
            free(ut);
        }
    }
    jobject_free(&io);
    free(it);
    return out;
}

// restore the latest backup over the live install, then re-save the
// restored manifest with the current backups
int rollback_run(const cmd_opts *o, state_ctx *st)
{
    const char *arg = o->nargs >= 2 ? o->args[1] : NULL;
    char prov[128], path[512], ref[700];
    if (arg == NULL || *arg == '\0') return run_help("rollback");
    if (!parse_ref(arg, prov, sizeof(prov), path, sizeof(path))) return 1;
    snprintf(ref, sizeof(ref), "%s:%s", prov, path);

    manifest_entry *ms = NULL;
    size_t mc = 0;
    if (manifests_scan(st->root, &ms, &mc) != 0) {
        put_fail("Failed to load application state: state scan failed");
        oplog_append(st, "rollback", ref, "-",
            "Failed to load application state: state scan failed");
        return 1;
    }
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
        oplog_append(st, "rollback", ref, "-", msg);
        return 1;
    }

    size_t tn = 0;
    char *text = fs_read_file(m->file, &tn);
    jobject root = {0};
    if (text == NULL || !jobject_parse(text, &root)) {
        put_fail("Failed to load application state: manifest unreadable");
        free(text);
        manifest_list_free(ms, mc);
        return 1;
    }

    int rv = 1;
    char *bart = NULL, *bver = NULL, *binst = NULL, *bman = NULL;
    int bi = jobject_find(&root, "backups");
    jspan *bsp = NULL;
    int bc = 0;
    char *bt = NULL;
    int best = -1;
    char *best_created = NULL;
    if (bi >= 0 && (bt = span_dup(root.vals[bi], root.vends[bi])) != NULL) {
        bc = json_array_spans(bt, &bsp);
        if (bc < 0) bc = 0;
    }
    for (int k = 0; k < bc; k++) {
        char *el = span_dup(bsp[k].s, bsp[k].e);
        jobject bo = {0};
        if (el != NULL && jobject_parse(el, &bo)) {
            char *cr = jstr(&bo, "created_at");
            if (cr != NULL
                && (best < 0 || (best_created != NULL && strcmp(cr, best_created) > 0))) {
                if (best >= 0) {
                    free(bart); free(bver); free(binst); free(bman);
                    free(best_created);
                }
                best = k;
                best_created = cr;
                bart = jstr(&bo, "artifact");
                bver = jstr(&bo, "version");
                binst = jstr(&bo, "install_path");
                bman = jstr(&bo, "manifest");
                // cr/bart/bver/binst/bman are independent json_span_string
                // copies we retain; only bo and el are loop-owned
                jobject_free(&bo);
                free(el);
                continue;
            }
            free(cr);
        }
        jobject_free(&bo);
        free(el);
    }
    if (best < 0) {
        char msg[768];
        snprintf(msg, sizeof(msg), "No backups are available for %s.", ref);
        put_s(stderr, UI_ERROR, "%s", msg);
        oplog_append(st, "rollback", ref, "-", msg);
        goto out;
    }
    const char *cur = m->version != NULL ? m->version : "unknown";
    const char *dst = bver != NULL ? bver : "unknown";

    if (o->dry_run) {
        if (o->json) {
            jbuf b = {0};
            jb_lit(&b, "{\"dry_run\":true,\"action\":\"rollback\","
                "\"provider\":");
            jb_json_string(&b, prov);
            jb_lit(&b, ",\"repo\":");
            jb_json_string(&b, path);
            bool first = false;
            jb_kv(&b, "from", m->version, &first);
            first = false;
            jb_kv(&b, "to", bver, &first);
            jb_lit(&b, ",\"backup\":");
            json_null_or_string(&b, bart);
            jb_lit(&b, "}");
            puts(b.p);
            jb_free(&b);
        } else {
            printf("Dry run: would roll back %s from %s to %s.\n", ref, cur,
                dst);
            printf("  Backup: %s\n", bart != NULL ? bart : "null");
            puts("No changes were made.");
        }
        rv = 0;
        goto out;
    }

    char *q = path_fmt("Roll back %s from %s to %s?\nThis will replace:\n"
        "  %s\nwith the backup at:\n  %s", ref, cur, dst, m->path,
        bart != NULL ? bart : "null");
    if (!confirm_action(o, q != NULL ? q : "", true)) {
        put_s(stderr, UI_WARN, "Rollback declined.");
        free(q);
        oplog_append(st, "rollback", ref, "-", "Rollback declined.");
        goto out;
    }
    free(q);

    // restore order: embedded manifest first (missing or invalid aborts
    // before any file is touched), then the artefact
    if (bman == NULL) {
        put_fail("Backup manifest is invalid.");
        oplog_append(st, "rollback", ref, "-", "Backup manifest is invalid.");
        goto out;
    }
    size_t bn = 0;
    char *btext = fs_read_file(bman, &bn);
    jobject broot = {0};
    if (btext == NULL) {
        char *msg = path_fmt(
            "Rollback failed: %s (No such file or directory)", bman);
        put_fail(msg != NULL ? msg : "Rollback failed");
        free(msg);
        oplog_append(st, "rollback", ref, "-", "Rollback failed");
        goto out;
    }
    if (!jobject_parse(btext, &broot)) {
        put_s(stderr, UI_ERROR, "Rollback failed: cannot read the backup manifest.");
        free(btext);
        jobject_free(&broot);
        oplog_append(st, "rollback", ref, "-",
            "Rollback failed: cannot read the backup manifest.");
        goto out;
    }
    if (!manifest_json_valid(&broot)) {
        put_fail("Backup manifest is invalid.");
        free(btext);
        jobject_free(&broot);
        oplog_append(st, "rollback", ref, "-", "Backup manifest is invalid.");
        goto out;
    }

    struct stat sb;
    if (bart == NULL || stat(bart, &sb) != 0) {
        char *msg = path_fmt("Rollback failed: Backup artefact is missing: %s",
            bart != NULL ? bart : "null");
        put_fail(msg != NULL ? msg : "Rollback failed");
        free(msg);
        free(btext);
        jobject_free(&broot);
        oplog_append(st, "rollback", ref, "-", "Rollback failed");
        goto out;
    }
    const char *target = (binst != NULL && *binst != '\0')
        ? binst : m->path;
    // containment: a crafted manifest must not restore outside the app's
    // roots; checked lexically — the target may not exist yet
    {
        char *nt = path_norm_dup(target);
        char *ar = manifest_app_dir(st->root, prov, path);
        char *na = path_norm_dup(ar);
        char *nr = path_norm_dup(st->install_root);
        bool inside = nt != NULL && ((na != NULL
                && strncmp(nt, na, strlen(na)) == 0 && nt[strlen(na)] == '/')
            || (nr != NULL
                && strncmp(nt, nr, strlen(nr)) == 0 && nt[strlen(nr)] == '/'));
        free(nt);
        free(na);
        free(nr);
        free(ar);
        if (!inside) {
            char *msg = path_fmt("Rollback failed: refusing to restore "
                "outside the managed roots: %s", target);
            put_fail(msg != NULL ? msg : "Rollback failed");
            free(msg);
            free(btext);
            jobject_free(&broot);
            oplog_append(st, "rollback", ref, "-", "Rollback failed");
            goto out;
        }
    }
    bool fail = false;
    char *rman = NULL;   // restored-manifest text until it is staged
    char *mtmp = NULL;   // staged m->file, renamed after the journal
    bool keep_journal = false; // a failure recovery must finish
    char *act_err = NULL; // activation failure wording that is no errno
    char *blaunch = NULL; // launcher path of the restored generation
    char *cwrap = NULL;   // staged compat wrapper, renamed after the journal
    if (!S_ISREG(sb.st_mode)) {
        put_fail("Rollback failed: only file backups are supported by "
            "this build.");
        fail = true;
    } else {
        // stage both artefacts, journal, then rename them in
        char jid[17], jc[40];
        txn_new_id(jid);
        now_instant(jc, sizeof(jc));
        char *tt = strdup(target);
        char *slash = tt != NULL ? strrchr(tt, '/') : NULL;
        char *parent = slash != NULL
            ? strndup(tt, (size_t)(slash - tt)) : strdup(".");
        free(tt);
        if (parent != NULL && *parent != '\0') mkdir(parent, 0777);
        char *tmp = path_fmt("%s.tmp-%s", target, jid);
        size_t rn = 0;
        char *data = fs_read_file(bart, &rn);
        // 0600 during staging: chmod below sets the final mode, and a
        // 0777 create would leave the file world-writable in between
        int fd = data != NULL && tmp != NULL
            ? open(tmp, O_WRONLY | O_CREAT | O_TRUNC, 0600) : -1;
        if (fd >= 0) {
            if (write(fd, data, rn) != (ssize_t)rn) fail = true;
            // the committing journal names this stage; its bytes must be
            // durable before txn_write announces them
            else if (fsync(fd) != 0) fail = true;
            close(fd);
            if (!fail && chmod(tmp, sb.st_mode & 07777) != 0) fail = true;
        } else {
            fail = true;
        }
        if (!fail) {
            // restored record + current backups + updated_at now;
            // member order preserved
            jbuf b = {0};
            jb_lit(&b, "{");
            bool first = true;
            for (int k = 0; k < broot.n; k++) {
                if (!strcmp(broot.keys[k], "backups")
                    || !strcmp(broot.keys[k], "updated_at"))
                    continue;
                if (!first) jb_lit(&b, ",");
                first = false;
                jb_json_string(&b, broot.keys[k]);
                jb_lit(&b, ":");
                char *raw = span_dup(broot.vals[k], broot.vends[k]);
                jb_lit(&b, raw != NULL ? raw : "null");
                free(raw);
            }
            jb_lit(&b, ",\"backups\":");
            if (bt != NULL && bc > 0) jb_lit(&b, bt);
            else jb_lit(&b, "[]");
            char now[40];
            now_instant(now, sizeof(now));
            jb_lit(&b, ",\"updated_at\":\"");
            jb_lit(&b, now);
            jb_lit(&b, "\"}");
            rman = b.p != NULL ? strdup(b.p) : NULL;
            jb_free(&b);
            if (rman == NULL) fail = true;
        }
        if (!fail) {
            mtmp = path_fmt("%s.tmp-%s", m->file, jid);
            if (mtmp == NULL || !fs_write_file(mtmp, rman)) fail = true;
        }
        if (!fail) blaunch = backup_launcher(&broot);
        // like install, a compat wrapper is verified before any rename happens
        if (!fail && blaunch != NULL && strcmp(blaunch, target) != 0
            && jobject_find(&broot, "compat") >= 0) {
            char *cerr = NULL;
            char *wine = compat_detect_wine(st->wine_path, &cerr);
            if (wine == NULL) {
                act_err = cerr;
                fail = true;
            } else {
                cwrap = compat_wrapper_stage(blaunch, jid, wine, target,
                    &cerr);
                free(wine);
                if (cwrap == NULL) {
                    act_err = cerr;
                    fail = true;
                }
            }
        }
        if (!fail)
            fail = !rollback_journal_write(st, ref, jid, jc, tmp,
                mtmp, m->file);
        if (!fail) test_crash_point("rollback");
        if (!fail) {
            if (fs_rename(mtmp, m->file)) {
                if (!fs_rename(tmp, target)) {
                    // undo the record rewrite; if that fails too the
                    // journal and stage stay for recovery to finish
                    if (!fs_write_file(m->file, text))
                        keep_journal = true;
                    fail = true;
                }
            } else {
                fail = true;
            }
        }
        // mirror commit.c: current and the launcher follow the restored artifact
        if (!fail) {
            char *ad = manifest_app_dir(st->root, prov, path);
            size_t adn = ad != NULL ? strlen(ad) : 0;
            const char *last = strrchr(target, '/');
            char *vrel = NULL;
            char *cur = ad != NULL ? path_fmt("%s/current", ad) : NULL;
            if (ad != NULL && cur != NULL && strlen(target) > adn + 9
                && target[adn] == '/'
                && !strncmp(target + adn + 1, "versions/", 9)
                && last != NULL && last > target + adn + 10) {
                vrel = strndup(target + adn + 1,
                    (size_t)(last - target - adn - 1));
                if (vrel != NULL) {
                    unlink(cur);
                    if (symlink(vrel, cur) != 0) fail = true;
                } else fail = true;
            }
            if (!fail && blaunch != NULL && strcmp(blaunch, target) != 0) {
                bool act = false;
                if (cwrap != NULL) {
                    unlink(blaunch);
                    act = fs_rename(cwrap, blaunch);
                } else {
                    const char *fn = last != NULL ? last + 1 : target;
                    char *via = vrel != NULL
                        ? path_fmt("%s/current/%s", ad, fn)
                        : strdup(target);
                    if (via != NULL) {
                        unlink(blaunch);
                        act = symlink(via, blaunch) == 0;
                        free(via);
                    }
                }
                if (!act) fail = true;
            }
            free(vrel);
            free(cur);
            free(ad);
        }
        if (fail) {
            char *msg = act_err != NULL ? act_err
                : path_fmt("Rollback failed: %s", strerror(errno));
            put_fail(msg != NULL ? msg : "Rollback failed");
            if (msg != act_err) free(msg);
            if (!keep_journal) {
                char *jp = path_join(st->root, "grm_transaction.json");
                if (jp != NULL) { remove(jp); free(jp); }
            }
        }
        // renamed away on success; gone either way. With keep_journal the
        // staged binary must survive for recovery to finish
        if (!keep_journal && tmp != NULL) remove(tmp);
        if (mtmp != NULL) remove(mtmp);
        if (cwrap != NULL) remove(cwrap);
        free(act_err);
        free(blaunch);
        free(cwrap);
        free(rman);
        free(data);
        free(tmp);
        free(mtmp);
        free(parent);
    }
    if (fail) {
        free(btext);
        jobject_free(&broot);
        oplog_append(st, "rollback", ref, "-", "Rollback failed");
        goto out;
    }
    {
        char *jp = path_join(st->root, "grm_transaction.json");
        if (jp != NULL) { remove(jp); free(jp); }
    }
    free(btext);
    jobject_free(&broot);
    if (!state_rebuild_index(st))
        put_fail("Manifest saved, but failed to rebuild index: "
            "cannot write state");

    // --lock pins the restored state: the newer release broke, so it must
    // not ride back in on the next update --all
    bool locked = false;
    if (o->lock)
        locked = manifest_set_locked(st->root, m->file, true);

    if (o->json) {
        jbuf b = {0};
        jb_lit(&b, "{\"rolled_back\":{\"provider\":");
        jb_json_string(&b, prov);
        jb_lit(&b, ",\"repo\":");
        jb_json_string(&b, path);
        jb_lit(&b, ",\"version\":");
        json_null_or_string(&b, bver);
        jb_lit(&b, "}}");
        puts(b.p);
        jb_free(&b);
    } else if (!o->quiet) {
        char *msg = locked
            ? path_fmt("Rolled back %s to %s (locked).", ref, dst)
            : path_fmt("Rolled back %s to %s.", ref, dst);
        put_s(stderr, UI_OK, "%s", msg != NULL ? msg : "Rolled back");
        free(msg);
        if (locked)
            put_s(stderr, UI_ITALIC,
                "Run 'ape-get unlock %s' to allow updates again.", ref);
    }
    oplog_append(st, "rollback", ref, "-", NULL);
    rv = 0;

out:
    free(best_created);
    free(bman);
    free(binst);
    free(bver);
    free(bart);
    free(bsp);
    free(bt);
    jobject_free(&root);
    free(text);
    manifest_list_free(ms, mc);
    return rv;
}
