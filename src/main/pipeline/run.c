/* Install/update pipeline: choose an asset, download
   it, verify, install, record. The original Java summed this up as
   "downloading the asset and passing it to the platform handler for
   installation" — the handler is LinuxHandler.installPortable below. */

#include "pipeline.h"

#include "manifest.h"
#include "path.h"
#include "pipeline/internal.h"
#include "provider.h"
#include "state.h"
#include "ui.h"
#include "verify.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int phase_download(pipe_ctx *pc)
{
    char msg[700] = "";
    dl_ctx dl = { pc->asset->name, -1, 0, 0, 0, 0.0, NULL };
    (void)dl.seen;
    dl.line = dl_line_buf;
    if (!provider_download(pc->pe, pc->asset->url, pc->asset->name, pc->timeout_ms,
                           pc->j_on ? pc->j_staged : NULL, dl_progress, &dl, &pc->tmp,
                           msg, sizeof(msg))) {
        ui_progress_done();
        pc->detail = path_fmt("Failed to download %s:%s: %s", pc->pe->name,
            pc->asset->name, msg);
        ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "download failed");
        oplog_append(pc->st, pc->op, pc->ref, "-",
            pc->detail != NULL ? pc->detail : "download failed");
        free(pc->detail);
        pc->detail = NULL;
        return PIPE_DONE;
    }
    ui_progress_done();
    return PIPE_GO;
}

static void pipe_ctx_free(pipe_ctx *pc)
{
    free(pc->detail);
    free(pc->backups);
    if (pc->j_on && !pc->j_committed) {
        // crash-window equivalent: undo the transaction, removing the
        // journal a committed finish would have deleted
        char *jp = path_join(pc->st->root, "grm_transaction.json");
        if (jp != NULL) { remove(jp); free(jp); }
    }
    free(pc->j_staged);
    free(pc->j_vdir);
    free(pc->j_mfile);
    free(pc->tmp);
    free(pc->entry);
    free(pc->native_name);
    if (pc->compat_tmp != NULL) {
        remove(pc->compat_tmp); // renamed away on success; gone either way
        free(pc->compat_tmp);
    }
    if (pc->mtmp != NULL) {
        remove(pc->mtmp);       // renamed away on success; gone either way
        free(pc->mtmp);
    }
    free(pc->wine_abs);
    sig_result_free(&pc->sres);
    free(pc->launcher);
    free(pc->command);
    free(pc->vr.expected);
    free(pc->vr.actual);
    free(pc->vr.checksum_asset);
    free(pc->app_dir);
    free(pc->target);
    free(pc->pool);
    prov_release_free(&pc->rel);
    providers_free(pc->plist, pc->pn);
    for (size_t k = 0; k < pc->mc; k++) manifest_entry_free(&pc->ms[k]);
    free(pc->ms);
}

// install (prev == NULL) or update: updates pick the
// same-named asset, carry kind/command, back up, and keep installed_at
int run_pipeline_at(const cmd_opts *o, state_ctx *st,
    const char *prov_in, const char *path_in, const char *ref,
    const char *fetch_prov, const char *fetch_path,
    const manifest_entry *prev, bool report, bool ask, const char *version,
    char **to_out)
{
    pipe_ctx c;
    memset(&c, 0, sizeof(c));
    c.o = o;
    c.st = st;
    c.ref = ref;
    c.prev = prev;
    c.report = report;
    c.ask = ask;
    c.version = version;
    c.to_out = to_out;
    c.updating = prev != NULL;
    c.op = c.updating ? "update" : "install";
    snprintf(c.prov, sizeof(c.prov), "%s", prov_in);
    snprintf(c.path, sizeof(c.path), "%s", path_in);
    snprintf(c.fprov, sizeof(c.fprov), "%s",
        fetch_prov != NULL ? fetch_prov : prov_in);
    snprintf(c.fpath, sizeof(c.fpath), "%s",
        fetch_path != NULL ? fetch_path : path_in);
    c.rv = RES_FAILED;

    int rc = phase_preflight(&c);
    if (rc == PIPE_GO) rc = phase_prepare(&c);
    if (rc == PIPE_GO) rc = phase_download(&c);
    if (rc == PIPE_GO) rc = phase_checksum(&c);
    if (rc == PIPE_GO) rc = phase_verify(&c);
    if (rc == PIPE_GO) rc = phase_install(&c);
    if (rc == PIPE_CLEANUP) {
        if (c.tmp != NULL) {
            remove(c.tmp);
            free(c.tmp);
            c.tmp = NULL;
        }
        if (!c.failed) phase_record(&c);
    }
    pipe_ctx_free(&c);
    return c.rv;
}

int run_pipeline(const cmd_opts *o, state_ctx *st, const char *prov_in,
    const char *path_in, const char *ref, const manifest_entry *prev,
    bool report, bool ask, const char *version, char **to_out)
{
    return run_pipeline_at(o, st, prov_in, path_in, ref, NULL, NULL,
        prev, report, ask, version, to_out);
}
