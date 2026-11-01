// providers remove: refuse while packages are installed unless --purge or
// --abandon, then drop the entry from the config

#include "cmd.h"
#include "fs.h"
#include "json.h"
#include "manifest.h"
#include "prompt.h"
#include "provider.h"
#include "state.h"
#include "ui.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "main.h"

int cmd_providers_remove(const cmd_opts *o, state_ctx *st,
                                char **args, int n)
{
    pv_opts pv = {0};
    char *pos[8];
    int np = 0;
    const char *err = NULL;
    char errbuf[256];
    if (!pv_parse(args, n, 2, &pv, pos, &np, &err, errbuf, sizeof(errbuf))) {
        put_fail(err);
        return 1;
    }
    if (np != 1) {
        providers_usage();
        return 1;
    }
    const char *name = pos[0];
    char refbuf[300];
    snprintf(refbuf, sizeof(refbuf), "%s:-", name);
    if (pv.abandon && pv.purge) {
        put_fail("Use only one of --abandon or --purge.");
        PVLOG_ERR(st, "providers remove", refbuf,
            "Use only one of --abandon or --purge.");
        return 1;
    }
    provider_entry *list = NULL;
    int ln = providers_load(st, &list);
    provider_entry *ex = providers_find(list, ln, name);
    if (ex == NULL) {
        put_s(stderr, UI_ERROR, "Provider '%s' is not configured.", name);
        PVLOG_ERR(st, "providers remove", refbuf,
            "Provider '%s' is not configured.", name);
        providers_free(list, ln);
        return 1;
    }
    static const char *bi[] = { "codeberg", "github", "gitlab", NULL };
    for (int k = 0; bi[k]; k++)
        if (!strcmp(name, bi[k])) {
            put_s(stderr, UI_ERROR,
                "Provider '%s' is built-in and cannot be removed.", name);
            PVLOG_ERR(st, "providers remove", refbuf,
                "Provider '%s' is built-in and cannot be removed.", name);
            providers_free(list, ln);
            return 1;
        }
    manifest_entry *ms = NULL;
    size_t mc = 0;
    if (manifests_scan(st->root, &ms, &mc) != 0) {
        put_fail("Failed to read installed packages: state scan failed");
        PVLOG_ERR(st, "providers remove", refbuf,
            "Failed to read installed packages: state scan failed");
        providers_free(list, ln);
        return 1;
    }
    size_t inst = 0;
    for (size_t k = 0; k < mc; k++)
        if (!strcmp(ms[k].provider, name)) inst++;
    if (inst > 0) {
        put_s(stderr, UI_NONE, "Provider %s (%s, %s) has %zu installed package(s):",
                name, ex->type, ex->api, inst);
        for (size_t k = 0; k < mc; k++)
            if (!strcmp(ms[k].provider, name)) {
                char ref[660];
                snprintf(ref, sizeof ref, "  %s:%s", name, ms[k].repo);
                char rest[80];
                snprintf(rest, sizeof rest, " %s",
                        ms[k].version != NULL ? ms[k].version : "unknown");
                ui_line_ref(stderr, ref, rest);
            }
        const char *declined =
            "Refusing to remove the provider while packages are installed.";
        if (!pv.purge && !pv.abandon) {
            put_fail(declined);
            put_s(stderr, UI_ITALIC,
                "  --purge     uninstall them, delete their state, then remove it");
            put_s(stderr, UI_ITALIC,
                "  --abandon   remove only the provider config; packages remain");
            PVLOG_ERR(st, "providers remove", refbuf, declined);
            manifest_list_free(ms, mc);
            providers_free(list, ln);
            return 1;
        }
        char q[600];
        bool declined_now = false;
        if (pv.purge) {
            snprintf(q, sizeof(q),
                "Uninstall %zu package(s) and remove provider %s (%s, %s)?",
                inst, name, ex->type, ex->api);
            declined_now = !confirm_action(o, q, true);
        } else {
            snprintf(q, sizeof(q),
                "Remove provider %s and leave %zu package(s) orphaned?",
                name, inst);
            declined_now = !confirm_action(o, q, true);
        }
        if (declined_now) {
            put_fail("Provider removal declined.");
            PVLOG_ERR(st, "providers remove", refbuf,
                "Provider removal declined.");
            manifest_list_free(ms, mc);
            providers_free(list, ln);
            return 1;
        }
        if (pv.purge) {
            // purge removes binary + app state dir only (no hook or
            // desktop parity yet); each uninstall re-scans the manifests
            for (size_t k = 0; k < mc; k++) {
                if (strcmp(ms[k].provider, name) != 0) continue;
                if (!uninstall_one(st, &ms[k])) {
                    char msg[300];
                    snprintf(msg, sizeof(msg),
                        "Stopped; provider %s was not removed.", name);
                    put_fail(msg);
                    PVLOG_ERR(st, "providers remove", refbuf, msg);
                    manifest_list_free(ms, mc);
                    providers_free(list, ln);
                    return 1;
                }
            }
            state_rebuild_index(st);
        }
    }
    manifest_list_free(ms, mc);

    char *cfg = state_config_edit(st, NULL, NULL, NULL, NULL, NULL, name);
    int rc = 0;
    if (cfg == NULL || !fs_write_file(st->config_file, cfg)) {
        put_s(stderr, UI_ERROR, "Failed to save providers");
        rc = 1;
    } else if (o->json) {
        jbuf b = {0};
        jb_lit(&b, "{\"removed\":");
        jb_json_string(&b, name);
        jb_lit(&b, "}");
        puts(b.p);
        jb_free(&b);
    } else {
        put_s(stderr, UI_OK, "Removed provider %s.", name);
    }
    oplog_append(st, "providers remove", refbuf, "-",
                 rc == 1 ? "Failed to save providers" : NULL);
    free(cfg);
    providers_free(list, ln);
    return rc;
}
