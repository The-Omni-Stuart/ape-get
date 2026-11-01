// The install command's entry point.

#include "pipeline.h"

#include "pipeline/internal.h"
#include "manifest.h"
#include "path.h"
#include "repo.h"
#include "state.h"
#include "ui.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// -l/--lock: arm the lock right after a successful install so
// update --all skips the release from the very next run
static void post_install_lock(const cmd_opts *o, state_ctx *st,
    const char *prov, const char *path)
{
    if (!o->lock) return;
    char *ad = manifest_app_dir(st->root, prov, path);
    char *mf = ad != NULL ? path_join(ad, "grm_manifest.json") : NULL;
    bool ok = mf != NULL && manifest_set_locked(mf, true);
    if (!o->json && !o->quiet) {
        if (ok) ui_msg(stderr, UI_OK, "Locked %s:%s.", prov, path);
        else ui_msg(stderr, UI_WARN, "Failed to lock %s:%s.", prov, path);
    }
    free(mf);
    free(ad);
}

int pipeline_install(const cmd_opts *o, state_ctx *st)
{
    const char *arg = o->nargs >= 2 ? o->args[1] : NULL;
    char prov[128], path[512], ref[700];
    if (arg == NULL || *arg == '\0') {
        run_help(NULL);
        return 2;
    }
    char resolved[700];
    if (strchr(arg, ':') == NULL) {
        if (!resolve_source_ref(o, st, arg, resolved, sizeof(resolved)))
            return 1;
        arg = resolved;
    }
    if (!repo_parse_ref(arg, prov, sizeof(prov), path, sizeof(path)))
        return 1;
    snprintf(ref, sizeof(ref), "%s:%s", prov, path);

    // --reinstall: an installed release re-runs through the update
    // pipeline (backups, journal, replace); a missing one installs as new
    if (o->reinstall) {
        manifest_entry *ms = NULL;
        size_t mc = 0;
        if (manifests_scan(st->root, &ms, &mc) != 0) {
            ui_msg(stderr, UI_ERROR,
                "Failed to load application state: state scan failed");
            return 1;
        }
        size_t hit = mc;
        for (size_t k = 0; k < mc; k++)
            if (!strcmp(ms[k].provider, prov) && !strcmp(ms[k].repo, path)) {
                hit = k;
                break;
            }
        int rv;
        if (hit == mc)
            rv = run_pipeline(o, st, prov, path, ref, NULL, true, true,
                o->version, NULL);
        else
            rv = run_pipeline(o, st, prov, path, ref, &ms[hit], true, true,
                o->version, NULL);
        bool failed = rv == RES_FAILED;
        if (!failed) post_install_lock(o, st, prov, path);
        for (size_t k = 0; k < mc; k++) manifest_entry_free(&ms[k]);
        free(ms);
        return failed ? 1 : 0;
    }

    int rv = run_pipeline(o, st, prov, path, ref, NULL, true, true,
        o->version, NULL) == RES_FAILED;
    if (rv == 0) post_install_lock(o, st, prov, path);
    return rv;
}
