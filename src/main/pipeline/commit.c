// The journal's commit path: prepare stages the transaction, install
// renames through the commit point, record lands the manifest.

#include "pipeline/internal.h"

#include "compat.h"
#include "crypto.h"
#include "fs.h"
#include "json.h"
#include "manifest.h"
#include "path.h"
#include "state.h"
#include "txn.h"
#include "ui.h"
#include "verify.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

// static: main/util.c exports an identical now_instant; making this one
// global would collide with it at the final link
static void now_instant(char *buf, size_t cap)
{
    time_t t = time(NULL);
    struct tm tmv;
    gmtime_r(&t, &tmv);
    snprintf(buf, cap, "%04d-%02d-%02dT%02d:%02d:%02dZ",
        tmv.tm_year + 1900, tmv.tm_mon + 1, tmv.tm_mday, tmv.tm_hour,
        tmv.tm_min, tmv.tm_sec);
}

// Test hook (APEGET_TEST_CRASH): stop at a named commit point so a
// scenario can inspect — and a later run recover from — the interrupted state.
static void test_crash_point(const char *which)
{
    const char *v = getenv("APEGET_TEST_CRASH");
    if (v == NULL || strcmp(v, which) != 0) return;
    fflush(NULL);
    _exit(70);
}

// Field order is the frozen record; its bytes must re-scan as valid. The
// caller resolves `entry` first so the record can stage ahead of renames.
static char *manifest_text(const pipe_ctx *pc, const char *now)
{
    const prov_release rel = pc->rel;
    const vresult vr = pc->vr;
    const sig_result sres = pc->sres;
    const prov_asset *asset = pc->asset;
    const provider_entry *pe = pc->pe;
    const manifest_entry *prev = pc->prev;
    const char *prov = pc->prov, *path = pc->path, *target = pc->target;
    const char *app_dir = pc->app_dir, *command = pc->command;
    const char *entry = desktop_recorded(pc->o, pc->entry);
    const char *launcher = pc->launcher;
    const char *backups = pc->backups;
    int kind = pc->kind;
    bool updating = pc->updating, compat_run = pc->compat_run;
    bool sres_used = pc->sres_used;
    jbuf b = {0};
    jb_lit(&b, "{\"kind\":\"grm.manifest\",\"schema_version\":1,");
    jb_lit(&b, "\"provider\":");
    jb_json_string(&b, prov);
    jb_lit(&b, ",\"repo\":");
    jb_json_string(&b, path);
    bool f = false;
    jb_kv(&b, "version", rel.version, &f);
    jb_lit(&b, ",\"release_id\":");
    if (rel.release_id != NULL) jb_lit(&b, rel.release_id);
    else jb_lit(&b, "null");
    jb_lit(&b, ",\"asset\":{\"name\":");
    jb_json_string(&b, asset->name);
    jb_lit(&b, ",\"url\":");
    jb_json_string(&b, asset->url);
    jb_lit(&b, ",\"sha256\":");
    if (vr.status == V_VERIFIED && vr.expected != NULL)
        jb_json_string(&b, vr.expected);
    else jb_lit(&b, "null");
    jb_lit(&b, "},\"install\":{\"kind\":");
    jb_json_string(&b, kind_manifest_name(kind));
    jb_lit(&b, ",\"path\":");
    jb_json_string(&b, target);
    jb_lit(&b, ",\"root\":");
    jb_json_string(&b, app_dir);
    jb_lit(&b, ",\"command\":");
    json_null_or_string(&b, command);
    jb_lit(&b, ",\"desktop_entry\":");
    json_null_or_string(&b, entry);
    jb_lit(&b, ",\"uninstall\":{\"path\":");
    jb_json_string(&b, launcher != NULL ? launcher : target);
    jb_lit(&b, ",\"args\":[],\"product_code\":null}},"
             "\"verification\":{\"status\":");
    jb_json_string(&b, vr.status == V_VERIFIED ? "verified"
        : vr.status == V_MISSING ? "missing"
        : vr.status == V_UNKNOWN_ASSET ? "unknown_asset"
        : vr.status == V_INVALID ? "invalid" : "mismatch");
    jb_lit(&b, ",\"method\":\"sha256\",\"checksum_asset\":");
    if (vr.status == V_MISSING) jb_lit(&b, "null");
    else jb_json_string(&b,
        vr.checksum_asset != NULL ? vr.checksum_asset : "");
    jb_lit(&b, ",\"expected\":");
    json_null_or_string(&b, vr.expected);
    jb_lit(&b, ",\"actual\":");
    json_null_or_string(&b, vr.actual);
    jb_lit(&b, "},\"backups\":");
    jb_lit(&b, updating && backups != NULL ? backups : "[]");
    jb_lit(&b, ",\"installed_at\":");
    jb_json_string(&b,
        updating && prev->installed != NULL ? prev->installed : now);
    jb_lit(&b, ",\"updated_at\":");
    jb_json_string(&b, now);
    {
        const char *ss = !sres_used || sres.status == S_MISSING
            ? "missing" : sig_status_str(sres.status);
        jb_lit(&b, ",\"signature\":{\"status\":");
        jb_json_string(&b, ss);
        jb_lit(&b, ",\"mechanism\":");
        json_null_or_string(&b, sres_used ? sres.mechanism : NULL);
        jb_lit(&b, ",\"signer\":");
        json_null_or_string(&b, sres_used
            && sres.status == S_VERIFIED ? sres.signer : NULL);
        jb_lit(&b, ",\"identity\":");
        json_null_or_string(&b, sres_used
            && sres.status == S_VERIFIED ? sres.identity : NULL);
        jb_lit(&b, ",\"notarized\":null},\"provider_type\":");
    }
    jb_json_string(&b, pe->type);
    jb_lit(&b, ",\"provider_api\":");
    jb_json_string(&b, pe->api);
    if (compat_run) {
        jb_lit(&b, ",\"compat\":");
        jb_json_string(&b, COMPAT_WINE);
    }
    jb_lit(&b, "}");
    return b.p;
}

int phase_prepare(pipe_ctx *pc)
{
    // journal before the first staged path; the staged file is named
    // <final>.tmp-<txn-id>, corrected to the assimilated target at commit
    txn_new_id(pc->j_id);
    now_instant(pc->j_created, sizeof(pc->j_created));
    char *app0 = manifest_app_dir(pc->st->root, pc->prov, pc->path);
    char *sver = sanitize_version(pc->rel.version);
    pc->j_vdir = app0 != NULL && sver != NULL
        ? path_fmt("%s/versions/%s", app0, sver) : NULL;
    const char *base = strrchr(pc->asset->name, '/') != NULL
        ? strrchr(pc->asset->name, '/') + 1 : pc->asset->name;
    pc->j_staged = pc->j_vdir != NULL
        ? path_fmt("%s/%s.tmp-%s", pc->j_vdir, base, pc->j_id) : NULL;
    pc->j_mfile = manifest_path(pc->st->root, pc->prov, pc->path);
    bool ok = pc->j_staged != NULL && pc->j_mfile != NULL
        && journal_phase_write(pc->st, pc->op, pc->ref, pc->j_id, pc->j_created,
            "prepared", pc->j_vdir, pc->j_mfile, NULL, pc->j_staged, NULL, NULL,
            false, pc->updating ? pc->prev : NULL);
    free(app0);
    if (ok) ok = (pc->app_dir = manifest_app_dir(pc->st->root, pc->prov, pc->path))
            != NULL && mkdir_p(pc->app_dir) && mkdir_p(pc->j_vdir);
    if (!ok) {
        char *jp = path_join(pc->st->root, "grm_transaction.json");
        if (jp != NULL) { remove(jp); free(jp); }
        pc->detail = path_fmt("%s failed to begin: cannot prepare the "
            "transaction.", pc->updating ? "Update" : "Install");
        ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "txn prepare");
        oplog_append(pc->st, pc->op, pc->ref, "-",
            pc->detail != NULL ? pc->detail : "txn prepare");
        free(pc->detail);
        pc->detail = NULL;
        pc->failed = true;
        return PIPE_DONE;
    }
    pc->j_on = true;
    return PIPE_GO;
}

int phase_install(pipe_ctx *pc)
{
    bool inst_failed = false;
    // backups for updates only, before the commit point;
    // pruning runs at create time
    if (pc->updating) {
        if (pc->st->backup_count > 0) {
            char berr[512] = "";
            if (!backup_create_and_prune(pc->st, pc->prev, pc->st->backup_count,
                    &pc->backups, berr, sizeof(berr))) {
                pc->detail = path_fmt("Failed to create backup; update "
                    "aborted: %s", berr);
                ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "backup failed");
                oplog_append(pc->st, pc->op, pc->ref, "-",
                    pc->detail != NULL ? pc->detail : "backup failed");
                free(pc->detail);
                pc->detail = NULL;
                pc->failed = true;
                return PIPE_CLEANUP;
            }
        } else {
            pc->backups = manifest_backups_array(pc->prev->file);
        }
    }

    // the staged APE is assimilated first; the native file then
    // replaces it as the install source
    if (pc->o->assimilate) {
        const char *base = strrchr(pc->asset->name, '/') != NULL
            ? strrchr(pc->asset->name, '/') + 1 : pc->asset->name;
        char *err = NULL;
        char *nat = assimilate_asset(pc->tmp, base, &err);
        if (nat == NULL) {
            ui_msg(stderr, UI_ERROR, "Installation failed.");
            oplog_append(pc->st, pc->op, pc->ref, "-",
                err != NULL ? err : "Failed to assimilate.");
            free(err);
            pc->failed = true;
            return PIPE_CLEANUP;
        }
        ui_msg(stderr, UI_OK, "Assimilated %s into a native binary.", base);
        free(err);
        free(pc->tmp);
        pc->tmp = nat;
        // the target is named after the asset, not after the
        // random staging file
        pc->native_name = path_fmt("%s.native", base);
    }

    if (pc->app_dir == NULL) pc->app_dir = manifest_app_dir(pc->st->root, pc->prov, pc->path);
    const char *fname = pc->native_name != NULL ? pc->native_name
        : (strrchr(pc->asset->name, '/') != NULL
            ? strrchr(pc->asset->name, '/') + 1 : pc->asset->name);
    if (pc->launcher != NULL && path_is_dir(pc->launcher)) {
        pc->detail = path_fmt("Refusing to replace directory %s", pc->launcher);
        ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "directory");
        free(pc->detail);
        pc->detail = NULL;
        inst_failed = true;
    }
    if (!inst_failed && pc->launcher != NULL && path_exists(pc->launcher)) {
        if (owned_by_other(pc->ms, pc->mc, pc->prov, pc->path, pc->launcher)) {
            pc->detail = path_fmt("Refusing to overwrite %s owned by "
                "another release.", pc->launcher);
            ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "owned");
            free(pc->detail);
            pc->detail = NULL;
            inst_failed = true;
        } else {
            // an existing launcher is replaceable while this ref still has a
            // manifest (the update case); install guards it out earlier
            bool own = false;
            for (size_t k = 0; k < pc->mc; k++)
                own = own || (!strcmp(pc->ms[k].provider, pc->prov)
                    && !strcmp(pc->ms[k].repo, pc->path));
            if (!own) {
                pc->detail = path_fmt("Refusing to overwrite unmanaged "
                    "command %s.", pc->launcher);
                ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "unmanaged");
                free(pc->detail);
                pc->detail = NULL;
                inst_failed = true;
            }
        }
    }
    char *sver = inst_failed ? NULL : sanitize_version(pc->rel.version);
    char *vdir = inst_failed ? NULL
        : path_fmt("%s/versions/%s", pc->app_dir, sver != NULL ? sver : "");
    free(sver);
    if (!inst_failed && (vdir == NULL || !mkdir_p(vdir)))
        inst_failed = true;
    if (!inst_failed) {
        pc->target = path_fmt("%s/%s", vdir, fname);
        if (pc->target == NULL) inst_failed = true;
    }
    if (!inst_failed) {
        // commit point: re-stage under the final deterministic name, then
        // flip the journal; a journal write failure aborts before any rename
        if (pc->j_on) {
            char *rf = path_fmt("%s.tmp-%s", pc->target, pc->j_id);
            if (rf != NULL && strcmp(rf, pc->tmp) != 0) {
                if (rename(pc->tmp, rf) == 0) {
                    free(pc->tmp);
                    pc->tmp = rf; // ownership moves to tmp
                } else {
                    inst_failed = true;
                    free(rf);
                }
            } else if (rf != NULL) free(rf);
        }
    }
    // stage the wrapper (it only references the target path) beside the
    // untouched old launcher and syntax-check it before the commit point
    if (!inst_failed && pc->compat_run && pc->launcher != NULL) {
        char *cerr = NULL;
        mkdir_p(pc->st->install_root);
        pc->compat_tmp = compat_wrapper_stage(pc->launcher, pc->j_id, pc->wine_abs,
            pc->target, &cerr);
        if (pc->compat_tmp == NULL) {
            ui_msg(stderr, UI_ERROR, "%s",
                cerr != NULL ? cerr : "Failed to create launcher.");
            free(cerr);
            inst_failed = true;
        }
    }
    // stage the record before the commit point: once the journal says
    // "committing", recovery can finish the install if this process dies
    if (!inst_failed) {
        pc->entry = desktop_entry_path(pc->st, pc->command);
        char now[40];
        now_instant(now, sizeof(now));
        char *text = manifest_text(pc, now);
        pc->mtmp = path_fmt("%s.tmp-%s", pc->j_mfile, pc->j_id);
        if (pc->mtmp == NULL || text == NULL || !fs_write_file(pc->mtmp, text)) {
            free(text);
            ui_msg(stderr, UI_ERROR, "Installation failed.");
            oplog_append(pc->st, pc->op, pc->ref, "-", "Installation failed.");
            inst_failed = true;
        }
        free(text);
    }
    if (!inst_failed && pc->j_on) {
        char sha[65] = "";
        crypto_sha256_file(pc->tmp, sha);
        char *bk = NULL;
        if (pc->backups != NULL) {
            jspan *sp = NULL;
            if (json_array_spans(pc->backups, &sp) > 0) {
                json_span_string(sp[0].s, sp[0].e, &bk);
                free(sp);
            }
        }
        bool w = journal_phase_write(pc->st, pc->op, pc->ref, pc->j_id, pc->j_created,
            "committing", pc->j_vdir, pc->j_mfile, pc->mtmp, pc->tmp, sha, bk, false,
            pc->updating ? pc->prev : NULL);
        free(bk);
        if (w) pc->j_committed = true;
        else {
            ui_msg(stderr, UI_ERROR, "Installation failed.");
            oplog_append(pc->st, pc->op, pc->ref, "-", "Installation failed.");
            inst_failed = true;
        }
    }
    if (!inst_failed) {
        struct stat sb;
        if (stat(pc->tmp, &sb) != 0) {
            inst_failed = true;
        } else if (rename(pc->tmp, pc->target) != 0) {
            size_t n = 0;
            char *data = fs_read_file(pc->tmp, &n);
            bool wr = data != NULL && fs_write_file(pc->target, data);
            free(data);
            if (!wr) inst_failed = true;
        }
        if (!inst_failed) test_crash_point("binary");
    }
    if (!inst_failed) {
        chmod(pc->target, 0755);
        const char *vname = strrchr(vdir, '/') != NULL
            ? strrchr(vdir, '/') + 1 : vdir;
        char *cur = path_fmt("%s/current", pc->app_dir);
        char *cur_rel = path_fmt("versions/%s", vname);
        bool link_ok = cur != NULL && cur_rel != NULL;
        if (link_ok) {
            unlink(cur);
            link_ok = symlink(cur_rel, cur) == 0;
        }
        free(cur_rel);
        if (link_ok && pc->launcher != NULL) {
            mkdir_p(pc->st->install_root);
            if (pc->compat_tmp != NULL) {
                // the staged, sh -n-checked wrapper replaces the launcher
                // atomically (no symlink on this path)
                unlink(pc->launcher);
                link_ok = fs_rename(pc->compat_tmp, pc->launcher);
            } else {
                char *via = path_fmt("%s/current/%s", pc->app_dir, fname);
                if (via != NULL) {
                    unlink(pc->launcher);
                    link_ok = symlink(via, pc->launcher) == 0;
                    free(via);
                } else {
                    link_ok = false;
                }
            }
        }
        free(cur);
        if (!link_ok) inst_failed = true;
    }
    free(vdir);
    if (!inst_failed) {
        // --no-desktop removes, --desktop writes, both before the
        // Installed to line; the path itself was resolved earlier
        if (pc->o->no_desktop) {
            if (pc->entry != NULL) unlink(pc->entry);
        } else if (pc->o->desktop) {
            char *ic = desktop_icon_copy(pc->o->icon, pc->app_dir);
            desktop_entry_write(pc->entry, pc->command, pc->launcher, pc->path, ic,
                pc->o->terminal != 0);
            free(ic);
        }
        if (!pc->o->json && !pc->o->quiet) {
            pc->detail = path_fmt("Installed to: %s", pc->target);
            ui_msg(stderr, UI_OK, "%s", pc->detail != NULL ? pc->detail : "installed");
            free(pc->detail);
            pc->detail = NULL;
        }
        if (pc->launcher != NULL && !dir_on_path(pc->st->install_root)) {
            pc->detail = path_fmt("Note: %s is not on PATH; run %s "
                "directly.", pc->st->install_root, pc->launcher);
            ui_msg(stderr, UI_ITALIC, "%s",
                pc->detail != NULL ? pc->detail : "path");
            free(pc->detail);
            pc->detail = NULL;
        }
    } else {
        ui_msg(stderr, UI_ERROR, "Installation failed.");
        oplog_append(pc->st, pc->op, pc->ref, "-", "Installation failed.");
        pc->failed = true;
    }
    return PIPE_CLEANUP;
}

void phase_record(pipe_ctx *pc)
{
    // the record was staged before the commit point, so it lands with one
    // rename; a crash before this line leaves the staged file for recovery
    char *mfile = manifest_path(pc->st->root, pc->prov, pc->path);
    bool saved = pc->mtmp != NULL && fs_rename(pc->mtmp, mfile);
    if (saved) chmod(mfile, 0600); // like the other state files
    free(mfile);
    if (!saved) {
        ui_msg(stderr, UI_ERROR, "Installed, but failed to write "
            "manifest: cannot write state");
        oplog_append(pc->st, pc->op, pc->ref, "-",
            "Installed, but failed to write manifest");
        pc->failed = true;
    } else {
        if (!state_rebuild_index(pc->st))
            ui_msg(stderr, UI_ERROR, "Manifest saved, but failed to "
                "rebuild index: cannot write state");
        if (pc->report) {
        if (pc->o->json) {
            jbuf r = {0};
            jb_lit(&r, pc->updating
                ? "{\"updated\":{\"provider\":"
                : "{\"installed\":{\"provider\":");
            jb_json_string(&r, pc->prov);
            jb_lit(&r, ",\"repo\":");
            jb_json_string(&r, pc->path);
            bool g = false;
            jb_kv(&r, "version", pc->rel.version, &g);
            jb_kv(&r, "asset", pc->asset->name, &g);
            jb_lit(&r, ",\"path\":");
            jb_json_string(&r, pc->target);
            jb_kv(&r, "launcher", pc->launcher, &g);
            jb_lit(&r, ",\"kind\":");
            jb_json_string(&r, kind_manifest_name(pc->kind));
            jb_kv(&r, "command", pc->command, &g);
            jb_lit(&r, "}}");
            puts(r.p);
            jb_free(&r);
        } else if (!pc->o->quiet) {
            ui_msg(stderr, UI_OK, pc->updating
                ? "Successfully updated." : "Installation successful.");
        }
        }
        char odet[160];
        if (pc->updating)
            snprintf(odet, sizeof(odet), "%s -> %s",
                pc->prev->version != NULL ? pc->prev->version : "unknown",
                pc->rel.version != NULL ? pc->rel.version : "null");
        else if (pc->rel.version != NULL)
            snprintf(odet, sizeof(odet), "- -> %s", pc->rel.version);
        else
            snprintf(odet, sizeof(odet), "-");
        oplog_append(pc->st, pc->op, pc->ref, odet, NULL);
        if (pc->j_on) {
            bool w = journal_phase_write(pc->st, pc->op, pc->ref, pc->j_id, pc->j_created,
                "committed", pc->j_vdir, pc->j_mfile, NULL, NULL, NULL,
                NULL, true, pc->updating ? pc->prev : NULL);
            if (w) {
                char *jp = path_join(pc->st->root,
                    "grm_transaction.json");
                if (jp != NULL) { remove(jp); free(jp); }
            }
        }
        if (pc->to_out != NULL && pc->rel.version != NULL)
            *pc->to_out = strdup(pc->rel.version);
        pc->rv = RES_OK;
    }
}
