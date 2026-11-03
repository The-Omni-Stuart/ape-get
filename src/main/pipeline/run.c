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
#include <unistd.h>

static int phase_download(pipe_ctx *pc)
{
    char msg[700] = "";
    dl_ctx dl = { pc->asset->name, -1, 0, 0, 0, 0.0, NULL };
    (void)dl.seen;
    dl.line = dl_line_buf;
    // I2: an .xz envelope that outgrows the 16 MiB wire cap is refused
    // mid-stream, before airtime and disk are spent on padding
    long long cap = ci_endswith(pc->asset->name, ".xz")
        ? XFER_ENVELOPE_MAX : 0;
    if (!provider_download(pc->pe, pc->asset->url, pc->asset->name, pc->timeout_ms, cap,
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

// failure inside a delta pass is the normal path taken differently (I3):
// ask run.c for the single re-selection, silently. Without a pending
// delta it is the plain hard refusal.
static int decode_failed(pipe_ctx *pc)
{
    if (pc->delta_pass && pc->fb_ok && !pc->delta_fell_back) {
        pc->delta_retry = true;
        return PIPE_DONE;
    }
    ui_msg(stderr, UI_ERROR, "Asset %s failed to decode; refusing to install.",
        pc->asset->name);
    oplog_append(pc->st, pc->op, pc->ref, "-", "transport decode failed");
    return PIPE_DONE;
}

// transport envelope: decode .xz to a scratch sharing the journal
// .tmp-<id> suffix (crash-sweepable), then rename onto the staged path
// so every later phase sees pc->tmp holding the final artifact bytes
static int phase_decode(pipe_ctx *pc)
{
    if (ci_endswith(pc->asset->name, ".xz")) {
        char *sc = path_fmt("%s.xdec.tmp-%s", pc->tmp, pc->j_id);
        if (sc == NULL) return PIPE_DONE;
        if (xz_decode_file(pc->tmp, sc) != 0 || rename(sc, pc->tmp) != 0) {
            remove(sc);
            free(sc);
            return decode_failed(pc);
        }
        free(sc);
    }
    if (pc->delta_pass) {
        char *sc = path_fmt("%s.xdel.tmp-%s", pc->tmp, pc->j_id);
        if (sc == NULL) return PIPE_DONE;
        if (xdelta_apply_fd(pc->delta_fd, pc->tmp, sc) != 0
            || rename(sc, pc->tmp) != 0) {
            remove(sc);
            free(sc);
            return decode_failed(pc);
        }
        free(sc);
    }
    return PIPE_GO;
}

static void pipe_ctx_free(pipe_ctx *pc)
{
    free(pc->detail);
    free(pc->backups);
    if (pc->delta_fd >= 0) close(pc->delta_fd);
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
    c.delta_fd = -1;
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
    while (rc == PIPE_GO) {
        rc = phase_download(&c);
        if (rc == PIPE_GO) rc = phase_decode(&c);
        if (rc != PIPE_GO && c.delta_retry) {
            // I3: exactly one re-selection of the smallest full variant,
            // fresh files and no state carried beyond the journal
            c.delta_retry = false;
            c.delta_fell_back = true;
            c.delta_pass = false;
            if (c.delta_fd >= 0) { close(c.delta_fd); c.delta_fd = -1; }
            if (c.tmp != NULL) { remove(c.tmp); free(c.tmp); c.tmp = NULL; }
            c.asset = &c.fb_asset;
            ui_msg(stderr, UI_DIM, "Selected asset: %s", c.asset->name);
            oplog_append(c.st, c.op, c.ref, "-",
                "delta decode failed; re-selected the full variant");
            rc = PIPE_GO;
            continue;
        }
        break;
    }
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
