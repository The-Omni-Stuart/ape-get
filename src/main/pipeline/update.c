// Update / update --all / list --outdated.

#include "pipeline.h"
#include "pipeline/internal.h"
#include "prompt.h"

#include "compat.h"
#include "crypto.h"
#include "pipeline/checksums.h"
#include "fs.h"
#include "json.h"
#include "manifest.h"
#include "path.h"
#include "proc.h"
#include "provider.h"
#include "ui.h"
#include "repo.h"
#include "state.h"
#include "txn.h"
#include "verify.h"

#include <ctype.h>
#include <errno.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static int update_one(const cmd_opts *o, state_ctx *st,
                      const manifest_entry *prev, bool report, bool ask,
                      const char *version, char **to_out)
{
    char prov[128], path[512], ref[700];
    snprintf(prov, sizeof(prov), "%s", prev->provider);
    snprintf(path, sizeof(path), "%s", prev->repo);
    snprintf(ref, sizeof(ref), "%s:%s", prev->provider, prev->repo);
    if (!strcmp(prev->provider, "self")) {
        // the self entry updates from the release repo but stays self:ape-get
        char fp[128], fq[512];
        if (!self_upstream(fp, sizeof fp, fq, sizeof fq)) {
            ui_msg(stderr, UI_ERROR, "Invalid self-update upstream.");
            oplog_append(st, "update", ref, "-", "invalid self upstream");
            return RES_FAILED;
        }
        return run_pipeline_at(o, st, prov, path, ref, fp, fq, prev, report,
            ask, version, to_out);
    }
    return run_pipeline(o, st, prov, path, ref, prev, report, ask, version,
        to_out);
}

static const char *status_id(int rv)
{
    switch (rv) {
    case RES_OK: return "updated";
    case RES_UP_TO_DATE: return "up-to-date";
    case RES_PLANNED: return "planned";
    default: return "failed";
    }
}

int pipeline_update(const cmd_opts *o, state_ctx *st)
{
    char prov[128], path[512], ref[700];
    if (strcmp(o->args[1], "ape-get") == 0) {
        // `update ape-get` names the self entry without its provider prefix
        snprintf(prov, sizeof(prov), "self");
        snprintf(path, sizeof(path), "ape-get");
    } else if (!repo_parse_ref(o->args[1], prov, sizeof(prov),
        path, sizeof(path)))
        return 1;
    snprintf(ref, sizeof(ref), "%s:%s", prov, path);

    manifest_entry *ms = NULL;
    size_t mc = 0;
    if (manifests_scan(st->root, &ms, &mc) != 0) {
        ui_msg(stderr, UI_ERROR,
            "Failed to load application state: state scan failed");
        return 1;
    }
    manifest_entry *prev = NULL;
    for (size_t k = 0; k < mc; k++)
        if (!strcmp(ms[k].provider, prov) && !strcmp(ms[k].repo, path)) {
            prev = &ms[k];
            break;
        }
    int rv = 1;
    if (prev == NULL) {
        char *d = path_fmt("Release %s is not found.", ref);
        ui_msg(stderr, UI_ERROR, "%s", d != NULL ? d : "not found");
        free(d);
    } else if (prev->locked) {
        char *d = path_fmt("Release %s is locked; unlock it first.", ref);
        ui_msg(stderr, UI_ERROR, "%s", d != NULL ? d : "locked");
        free(d);
    } else {
        rv = update_one(o, st, prev, true, true, o->version, NULL)
            == RES_FAILED ? 1 : 0;
    }
    for (size_t k = 0; k < mc; k++) manifest_entry_free(&ms[k]);
    free(ms);
    return rv;
}

int pipeline_update_all(const cmd_opts *o, state_ctx *st)
{
    manifest_entry *ms = NULL;
    size_t mc = 0;
    if (manifests_scan(st->root, &ms, &mc) != 0) {
        ui_msg(stderr, UI_ERROR,
            "Failed to load applications: state scan failed");
        return 1;
    }
    // locked releases never ride the batch; self does, like any package
    size_t n_locked = 0;
    for (size_t k = 0; k < mc; ) {
        if (ms[k].locked) {
            manifest_entry_free(&ms[k]);
            memmove(&ms[k], &ms[k + 1], (mc - k - 1) * sizeof(*ms));
            mc--;
            n_locked++;
        } else k++;
    }
    if (mc == 0) {
        if (o->json) puts("{\"updates\":[]}");
        else if (n_locked > 0) puts("No unlocked releases installed.");
        else puts("No releases installed.");
        for (size_t k = 0; k < mc; k++) manifest_entry_free(&ms[k]);
        free(ms);
        return 0;
    }

    // dry-run answers yes with no question
    if (!o->dry_run) {
        char *q = path_fmt("Update %zu installed package(s)?", mc);
        bool ok = confirm_action(o, q != NULL ? q : "", false);
        free(q);
        if (!ok) {
            ui_msg(stderr, UI_WARN, "Update declined.");
            // no single ref, so nothing for the op log
            for (size_t k = 0; k < mc; k++) manifest_entry_free(&ms[k]);
            free(ms);
            return 1;
        }
    }
    if (n_locked > 0 && !o->json && !o->quiet)
        ui_msg(stderr, UI_ITALIC, "Skipped %zu locked release(s).",
            n_locked);

    int *stt = calloc(mc, sizeof(*stt));
    char **tos = calloc(mc, sizeof(*tos));
    int fails = 0;
    if (stt == NULL || tos == NULL) {
        ui_msg(stderr, UI_ERROR, "Failed to load applications: out of memory");
        fails = (int)mc;
    }
    for (size_t k = 0; stt != NULL && tos != NULL && k < mc; k++) {
        char *to = NULL;
        stt[k] = update_one(o, st, &ms[k], false, false, NULL, &to);
        tos[k] = to;
        if (stt[k] == RES_FAILED) fails++;
    }

    if (o->json) {
        jbuf b = {0};
        jb_lit(&b, "{\"updates\":[");
        for (size_t k = 0; stt != NULL && k < mc; k++) {
            jb_lit(&b, k ? ",{" : "{");
            jb_lit(&b, "\"provider\":");
            jb_json_string(&b, ms[k].provider);
            jb_lit(&b, ",\"repo\":");
            jb_json_string(&b, ms[k].repo);
            jb_lit(&b, ",\"from\":");
            json_null_or_string(&b, ms[k].version);
            jb_lit(&b, ",\"to\":");
            json_null_or_string(&b, tos != NULL ? tos[k] : NULL);
            jb_lit(&b, ",\"status\":");
            jb_json_string(&b, status_id(stt[k]));
            jb_lit(&b, "}");
        }
        jb_lit(&b, "]}");
        if (b.p != NULL) puts(b.p);
        jb_free(&b);
    } else if (stt != NULL) {
        for (size_t k = 0; k < mc; k++) {
            char ref[700];
            snprintf(ref, sizeof(ref), "%s:%s", ms[k].provider, ms[k].repo);
            const char *from = ms[k].version != NULL ? ms[k].version : "null";
            const char *to = tos != NULL && tos[k] != NULL ? tos[k] : "null";
            if (stt[k] == RES_OK)
                ui_msg(stdout, UI_OK, "%s %s -> %s", ref, from, to);
            else if (stt[k] == RES_UP_TO_DATE)
                ui_msg(stdout, UI_OK, "%s %s (up to date)", ref, from);
            else if (stt[k] == RES_PLANNED)
                printf("%s %s -> %s (dry run)\n", ref, from, to);
            else {
                char line[768];
                snprintf(line, sizeof(line), "%s update failed.", ref);
                ui_msg(stderr, UI_ERROR, "%s", line);
            }
        }
        if (fails > 0) {
            char line[128];
            snprintf(line, sizeof(line),
                "Failed to update %d of %zu package(s).", fails, mc);
            ui_msg(stderr, UI_ERROR, "%s", line);
        }
    }
    for (size_t k = 0; tos != NULL && k < mc; k++) free(tos[k]);
    free(tos);
    free(stt);
    for (size_t k = 0; k < mc; k++) manifest_entry_free(&ms[k]);
    free(ms);
    return fails > 0 ? 1 : 0;
}

int pipeline_outdated(const cmd_opts *o, state_ctx *st)
{
    bool beta = st->update_channel != NULL
        && !strcasecmp(st->update_channel, "beta");
    if (!blank(o->channel)) {
        if (!strcasecmp(o->channel, "beta")) beta = true;
        else if (strcasecmp(o->channel, "stable")) {
            char *d = path_fmt("Unknown channel: %s", o->channel);
            ui_msg(stderr, UI_ERROR, "%s", d != NULL ? d : "bad channel");
            free(d);
            return 1;
        }
    }

    manifest_entry *ms = NULL;
    size_t mc = 0;
    if (manifests_scan(st->root, &ms, &mc) != 0) {
        ui_msg(stderr, UI_ERROR,
            "Failed to load applications: state scan failed");
        return 1;
    }
    provider_entry *plist = NULL;
    int pn = providers_load(st, &plist);
    int timeout_ms = (st->timeout_seconds > 0 ? st->timeout_seconds : 30)
        * 1000;

    char **cur = calloc(mc > 0 ? mc : 1, sizeof(*cur));
    char **latest = calloc(mc > 0 ? mc : 1, sizeof(*latest));
    char **asset = calloc(mc > 0 ? mc : 1, sizeof(*asset));
    char **refv = calloc(mc > 0 ? mc : 1, sizeof(*refv));
    bool *lockv = calloc(mc > 0 ? mc : 1, sizeof(*lockv));
    size_t oc = 0;
    int failed = 0;

    for (size_t k = 0; k < mc; k++) {
        bool isself = !strcmp(ms[k].provider, "self");
        char fprov[128], fpath[512];
        const char *up = ms[k].provider, *ur = ms[k].repo;
        if (isself) {
            if (!self_upstream(fprov, sizeof fprov, fpath, sizeof fpath)) {
                failed = 1;
                continue;
            }
            up = fprov;
            ur = fpath;
        }
        provider_entry *pe = providers_find(plist, pn, up);
        if (pe == NULL) {
            char *d = path_fmt("Provider '%s' is not configured.", up);
            ui_msg(stderr, UI_ERROR, "%s", d != NULL ? d : "provider missing");
            free(d);
            failed = 1;
            continue;
        }
        char ref[700];
        snprintf(ref, sizeof(ref), "%s:%s", ms[k].provider, ms[k].repo);
        prov_release rel;
        memset(&rel, 0, sizeof(rel));
        if (!provider_release_fetch(pe, ur, NULL, beta, timeout_ms,
                &rel)) {
            char *d = path_fmt("Failed to read releases from %s: %s", ref,
                rel.message);
            ui_msg(stderr, UI_ERROR, "%s", d != NULL ? d : "fetch failed");
            free(d);
            prov_release_free(&rel);
            failed = 1;
            continue;
        }
        prov_asset *pool = calloc((size_t)(rel.n > 0 ? rel.n : 1),
            sizeof(*pool));
        int pool_n = 0;
        for (int a = 0; pool != NULL && a < rel.n; a++) {
            char b[512], from[128];
            // patches never surface as list targets; the update
            // pipeline reaches them through their group
            if (artifact_delta_from(rel.assets[a].name, from, sizeof from))
                continue;
            artifact_base_name(rel.assets[a].name, b, sizeof b);
            if (ci_endswith(b, ".appimage")
                || ci_endswith(b, ".ape")
                || ci_endswith(b, ".com"))
                pool[pool_n++] = rel.assets[a];
        }
        pool_n = arch_prefer(pool, pool_n);
        if (pool == NULL || pool_n == 0) {
            free(pool);
            prov_release_free(&rel);
            failed = 1;
            continue;
        }
        int sel = -1;
        for (int a = 0; a < pool_n; a++)
            if (ms[k].asset_url != NULL && pool[a].url != NULL
                && !strcmp(pool[a].url, ms[k].asset_url)) { sel = a; break; }
        if (sel < 0)
            for (int a = 0; a < pool_n; a++) {
                // a twin's recorded URL or the recorded raw name of the
                // same logical artifact both identify the entry
                char b[512];
                artifact_base_name(pool[a].name, b, sizeof b);
                if (ms[k].asset != NULL && !strcmp(b, ms[k].asset)) {
                    sel = a; break;
                }
            }
        if (sel < 0) sel = 0;
        bool newer;
        if (isself && rel.version != NULL && ms[k].version != NULL)
            // the registered file:// URL cannot judge freshness; the version can
            newer = !ver_eq(rel.version, ms[k].version);
        else
            // identity is (artifact, version): a variant swap (raw URL vs
            // twin URL) at the same version is not "outdated"
            newer = ms[k].asset_url == NULL
                || (strcmp(pool[sel].url, ms[k].asset_url) != 0
                    && !ver_eq(rel.version, ms[k].version));
        if (newer && oc < mc && refv != NULL && cur != NULL
            && latest != NULL && asset != NULL) {
            refv[oc] = strdup(ref);
            cur[oc] = ms[k].version != NULL ? strdup(ms[k].version) : NULL;
            latest[oc] = rel.version != NULL
                ? strdup(rel.version) : NULL;
            asset[oc] = strdup(pool[sel].name);
            lockv[oc] = ms[k].locked;
            if (refv[oc] != NULL && asset[oc] != NULL) oc++;
        }
        free(pool);
        prov_release_free(&rel);
    }

    if (o->json) {
        jbuf b = {0};
        jb_lit(&b, "{\"outdated\":[");
        for (size_t k = 0; k < oc; k++) {
            jb_lit(&b, k ? ",{" : "{");
            size_t c = 0;
            while (refv[k][c] != ':' && refv[k][c] != '\0') c++;
            char *p = strndup(refv[k], c);
            jb_lit(&b, "\"provider\":");
            jb_json_string(&b, p != NULL ? p : "");
            free(p);
            jb_lit(&b, ",\"repo\":");
            jb_json_string(&b, refv[k] + c + 1);
            jb_lit(&b, ",\"current\":");
            json_null_or_string(&b, cur[k]);
            jb_lit(&b, ",\"latest\":");
            json_null_or_string(&b, latest[k]);
            jb_lit(&b, ",\"asset\":");
            json_null_or_string(&b, asset[k]);
            if (lockv[k]) jb_lit(&b, ",\"locked\":true");
            jb_lit(&b, "}");
        }
        jb_lit(&b, "]}");
        if (b.p != NULL) puts(b.p);
        jb_free(&b);
    } else if (oc == 0) {
        if (failed) ui_msg(stdout, UI_ERROR,
            "Could not check every package.");
        else ui_msg(stdout, UI_OK, "Everything is up to date.");
    } else {
        for (size_t k = 0; k < oc; k++) {
            char line[768];
            snprintf(line, sizeof(line), "%s %s -> %s%s", refv[k],
                cur[k] != NULL ? cur[k] : "unknown",
                latest[k] != NULL ? latest[k] : "unknown",
                lockv[k] ? " (locked)" : "");
            if (lockv[k])
                ui_msg(stdout, UI_ITALIC, "%s", line);
            else
                printf("%s\n", line);
        }
        for (size_t k = 0; k < oc; k++)
            if (lockv[k])
                ui_msg(stdout, UI_DIM,
                    "Run 'ape-get unlock %s' to include it in updates.",
                    refv[k]);
        if (failed)
            ui_msg(stderr, UI_ERROR, "Could not check every package.");
    }

    for (size_t k = 0; k < oc; k++) {
        free(refv[k]); free(cur[k]); free(latest[k]); free(asset[k]);
    }
    free(refv); free(cur); free(latest); free(asset); free(lockv);
    providers_free(plist, pn);
    for (size_t k = 0; k < mc; k++) manifest_entry_free(&ms[k]);
    free(ms);
    return failed ? 1 : 0;
}

