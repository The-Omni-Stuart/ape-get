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

// lock / unlock (C-only)

int lock_run(const cmd_opts *o, state_ctx *st, bool locking)
{
    const char *cmd = locking ? "lock" : "unlock";
    if (o->nargs != 2) return run_help(cmd);
    char prov[128], path[512], ref[700];
    const char *arg = o->args[1];
    if (strchr(arg, ':') == NULL) {
        // short-name resolution (see uninstall); a miss reproduces the
        // parser's provider-required error and exit code
        manifest_entry *ms = NULL;
        size_t mc = 0;
        if (manifests_scan(st->root, &ms, &mc) != 0) {
            put_fail("Failed to load application state: state scan failed");
            return 1;
        }
        bool many = false;
        manifest_entry *r = resolve_installed(o, arg, ms, mc, &many);
        if (r != NULL) {
            snprintf(prov, sizeof(prov), "%s", r->provider);
            snprintf(path, sizeof(path), "%s", r->repo);
        } else if (many) {
            put_fail("Lock with provider:owner/repo to choose one.");
            manifest_list_free(ms, mc);
            return 1;
        } else {
            parse_ref(arg, prov, sizeof(prov), path, sizeof(path));
            manifest_list_free(ms, mc);
            return 2;
        }
        manifest_list_free(ms, mc);
    } else if (!parse_ref(arg, prov, sizeof(prov), path, sizeof(path)))
        return 2;
    snprintf(ref, sizeof(ref), "%s:%s", prov, path);

    char *file = manifest_path(st->root, prov, path);
    bool locked = false;
    bool found = file != NULL && manifest_get_locked(file, &locked);
    if (!found) {
        char msg[768];
        snprintf(msg, sizeof(msg), "Release %s is not found.", ref);
        put_fail(msg);
        oplog_append(st, cmd, ref, "-", msg);
        free(file);
        return 1;
    }

    const char *result = NULL;
    bool now = false;
    static char done[800];
    if (locking) {
        now = true;
        if (locked) {
            snprintf(done, sizeof(done), "Release %s is already locked.",
                ref);
            result = done;
        } else if (!manifest_set_locked(file, true)) {
            put_fail("Failed to update the release state.");
            oplog_append(st, cmd, ref, "-",
                "Failed to update the release state.");
            free(file);
            return 1;
        }
    } else {
        if (!locked) {
            snprintf(done, sizeof(done), "Release %s is not locked.", ref);
            result = done;
        } else if (!manifest_set_locked(file, false)) {
            put_fail("Failed to update the release state.");
            oplog_append(st, cmd, ref, "-",
                "Failed to update the release state.");
            free(file);
            return 1;
        }
    }

    if (result == NULL) {
        if (locking)
            snprintf(done, sizeof(done), "Locked %s. It will be skipped by "
                "update --all.", ref);
        else
            snprintf(done, sizeof(done), "Unlocked %s.", ref);
        result = done;
    }
    if (o->json) {
        jbuf b = {0};
        jb_lit(&b, "{\"ref\":");
        jb_json_string(&b, ref);
        jb_lit(&b, ",\"locked\":");
        jb_lit(&b, now ? "true" : "false");
        jb_lit(&b, "}");
        puts(b.p);
        jb_free(&b);
    } else {
        put_s(stderr, UI_OK, "%s", result);
    }
    oplog_append(st, cmd, ref, "-", NULL);
    free(file);
    return 0;
}
