#include "cmd.h"
#include "doctor.h"
#include "fs.h"
#include "json.h"
#include "manifest.h"
#include "path.h"
#include "pipeline.h"
#include "provider.h"
#include "probe.h"
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

// providers command; stateful, so main.c holds the lock and ensures state
// before calling in

void providers_usage(void)
{
    put_s(stderr, UI_BOLD, "Usage:");
    put_s(stderr, UI_NONE, "  providers list");
    put_s(stderr, UI_NONE, "  providers show <name>");
    put_s(stderr, UI_NONE,
        "  providers add <name> <url> [--type auto|github|gitea|gitlab|rngit]"
        " [--api URL] [--token-env VAR]");
    put_s(stderr, UI_NONE,
        "  providers update <name> [--type ...] [--api URL] [--url URL]"
        " [--token-env VAR] [--force]");
    put_s(stderr, UI_NONE, "  providers remove <name> [--abandon | --purge]");
    put_s(stderr, UI_NONE,
        "  providers recover <name> [--type ...] [--api URL]");
}

bool pv_parse(char **a, int n, int mode, pv_opts *o, char **pos, int *npos,
             const char **err, char *errbuf, size_t errcap)
{
    int np = 0;
    for (int k = 0; k < n; k++) {
        if (strncmp(a[k], "--", 2) != 0) {
            if (np < 8) pos[np++] = a[k];
            continue;
        }
        const char *fl = a[k] + 2;
        bool is_flag =
            (mode == 1 && !strcmp(fl, "force")) ||
            (mode == 2 && (!strcmp(fl, "abandon") || !strcmp(fl, "purge")));
        if (is_flag) {
            if (!strcmp(fl, "force")) o->force = true;
            else if (!strcmp(fl, "abandon")) o->abandon = true;
            else o->purge = true;
            continue;
        }
        const char **dst = NULL;
        if (!strcmp(fl, "type")) dst = &o->type;
        else if (!strcmp(fl, "api")) dst = &o->api;
        else if (!strcmp(fl, "token-env")) { dst = &o->token_env; o->token_env_given = true; }
        else if (!strcmp(fl, "name") && mode == 0) dst = &o->name;
        else if (!strcmp(fl, "url") && mode == 1) dst = &o->url;
        else {
            snprintf(errbuf, errcap, "Unknown option: --%s", fl);
            *err = errbuf;
            return false;
        }
        if (k + 1 >= n) {
            snprintf(errbuf, errcap, "Missing value for --%s", fl);
            *err = errbuf;
            return false;
        }
        *dst = a[++k];
    }
    *npos = np;
    return true;
}

static int cmd_providers_list(const cmd_opts *o, state_ctx *st)
{
    provider_entry *list = NULL;
    int n = providers_load(st, &list);
    manifest_entry *ms = NULL;
    size_t mc = 0;
    if (manifests_scan(st->root, &ms, &mc) != 0) {
        put_fail("Failed to read installed packages: state scan failed");
        providers_free(list, n);
        return 1;
    }
    jbuf out = {0};
    if (o->json) jb_lit(&out, "{\"providers\":[");
    for (int k = 0; k < n; k++) {
        provider_entry *e = &list[k];
        size_t inst = 0;
        for (size_t j = 0; j < mc; j++)
            if (!strcmp(ms[j].provider, e->name)) inst++;
        const char *token_state = NULL;
        if (e->token_env != NULL && *e->token_env != '\0')
            token_state = getenv(e->token_env) == NULL ? "missing" : "set";
        if (o->json) {
            char cnt[32];
            if (k) jb_lit(&out, ",");
            jb_lit(&out, "{\"token_env\":");
            json_null_or_string(&out, e->token_env);
            jb_lit(&out, ",\"token_state\":");
            json_null_or_string(&out, token_state);
            jb_lit(&out, ",\"built_in\":");
            jb_lit(&out, e->built_in ? "true" : "false");
            jb_lit(&out, ",\"name\":");
            jb_json_string(&out, e->name);
            jb_lit(&out, ",\"type\":");
            jb_json_string(&out, e->type);
            jb_lit(&out, ",\"url\":");
            jb_json_string(&out, e->url);
            jb_lit(&out, ",\"api\":");
            jb_json_string(&out, e->api);
            snprintf(cnt, sizeof cnt, ",\"packages\":%zu}", inst);
            jb_lit(&out, cnt);
        } else {
            char rest[2048];
            snprintf(rest, sizeof rest, " (%s, %s) packages=%zu%s",
                e->type, e->api, inst, e->built_in ? " [built-in]" : "");
            if (token_state)
                snprintf(rest + strlen(rest), sizeof rest - strlen(rest),
                    " token-env=%s (%s)", e->token_env, token_state);
            ui_line_ref(stdout, e->name, rest);
        }
    }
    if (o->json) {
        jb_lit(&out, "]}");
        puts(out.p);
    }
    jb_free(&out);
    manifest_list_free(ms, mc);
    providers_free(list, n);
    return 0;
}

static int cmd_providers_show(const cmd_opts *o, state_ctx *st, const char *name)
{
    provider_entry *list = NULL;
    int n = providers_load(st, &list);
    provider_entry *e = providers_find(list, n, name);
    if (e == NULL) {
        put_s(stderr, UI_ERROR, "Provider '%s' is not configured.", name);
        providers_free(list, n);
        return 1;
    }
    manifest_entry *ms = NULL;
    size_t mc = 0;
    if (manifests_scan(st->root, &ms, &mc) != 0) {
        put_fail("Failed to read installed packages: state scan failed");
        providers_free(list, n);
        return 1;
    }
    size_t inst = 0;
    for (size_t j = 0; j < mc; j++)
        if (!strcmp(ms[j].provider, e->name)) inst++;
    if (o->json) {
        jbuf b = {0};
        jb_lit(&b, "{\"token_env\":");
        json_null_or_string(&b, e->token_env);
        jb_lit(&b, ",\"built_in\":");
        jb_lit(&b, e->built_in ? "true" : "false");
        jb_lit(&b, ",\"name\":");
        jb_json_string(&b, e->name);
        jb_lit(&b, ",\"type\":");
        jb_json_string(&b, e->type);
        jb_lit(&b, ",\"url\":");
        jb_json_string(&b, e->url);
        jb_lit(&b, ",\"api\":");
        jb_json_string(&b, e->api);
        jb_lit(&b, ",\"packages\":[");
        bool first = true;
        for (size_t j = 0; j < mc; j++)
            if (!strcmp(ms[j].provider, e->name)) {
                char *ref = path_fmt("%s:%s", e->name, ms[j].repo);
                if (ref == NULL) continue;
                jb_lit(&b, first ? "" : ",");
                first = false;
                jb_json_string(&b, ref);
                free(ref);
            }
        jb_lit(&b, "]}");
        puts(b.p);
        jb_free(&b);
    } else {
        char ln[1100];
        snprintf(ln, sizeof ln, ": %s", e->name);
        ui_line_kv(stdout, "Name", ln);
        snprintf(ln, sizeof ln, ": %s", e->type);
        ui_line_kv(stdout, "Type", ln);
        snprintf(ln, sizeof ln, ": %s", e->url);
        ui_line_kv(stdout, "URL", ln);
        snprintf(ln, sizeof ln, ": %s", e->api);
        ui_line_kv(stdout, "API", ln);
        snprintf(ln, sizeof ln, ": %s",
            (e->token_env == NULL || *e->token_env == '\0')
                ? "none" : e->token_env);
        ui_line_kv(stdout, "Token env", ln);
        snprintf(ln, sizeof ln, ": %s", e->built_in ? "true" : "false");
        ui_line_kv(stdout, "Built-in", ln);
        snprintf(ln, sizeof ln, ": %zu", inst);
        ui_line_kv(stdout, "Packages", ln);
        for (size_t j = 0; j < mc; j++)
            if (!strcmp(ms[j].provider, e->name)) {
                char *ref = path_fmt("%s:%s", e->name, ms[j].repo);
                if (ref == NULL) continue;
                char rest[720];
                snprintf(rest, sizeof rest, " %s",
                    ms[j].version != NULL ? ms[j].version : "unknown");
                ui_line_ref(stdout, ref, rest);
                free(ref);
            }
    }
    manifest_list_free(ms, mc);
    providers_free(list, n);
    return 0;
}

static void print_provider_result(const char *token_env, bool built_in,
    const char *name, const char *type, const char *url, const char *api)
{
    jbuf b = {0};
    jb_lit(&b, "{\"provider\":{\"token_env\":");
    json_null_or_string(&b, token_env);
    jb_lit(&b, ",\"built_in\":");
    jb_lit(&b, built_in ? "true" : "false");
    jb_lit(&b, ",\"name\":");
    jb_json_string(&b, name);
    jb_lit(&b, ",\"type\":");
    jb_json_string(&b, type);
    jb_lit(&b, ",\"url\":");
    jb_json_string(&b, url);
    jb_lit(&b, ",\"api\":");
    jb_json_string(&b, api);
    jb_lit(&b, "}}");
    puts(b.p);
    jb_free(&b);
}

const char *type_canon(const char *v)
{
    if (!strcasecmp(v, "github")) return "github";
    if (!strcasecmp(v, "gitea")) return "gitea";
    if (!strcasecmp(v, "gitlab")) return "gitlab";
    if (!strcasecmp(v, "rngit")) return "rngit";
    return NULL;
}

static char *strip_slash_dup(const char *url)
{
    size_t n = strlen(url);
    while (n > 0 && url[n - 1] == '/') n--;
    char *r = malloc(n + 1);
    memcpy(r, url, n);
    r[n] = '\0';
    return r;
}

static int cmd_providers_update(const cmd_opts *o, state_ctx *st,
                                char **args, int n)
{
    pv_opts pv = {0};
    char *pos[8];
    int np = 0;
    const char *err = NULL;
    char errbuf[256];
    if (!pv_parse(args, n, 1, &pv, pos, &np, &err, errbuf, sizeof(errbuf))) {
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
    provider_entry *list = NULL;
    int ln = providers_load(st, &list);
    provider_entry *ex = providers_find(list, ln, name);
    if (ex == NULL) {
        put_s(stderr, UI_ERROR, "Provider '%s' is not configured.", name);
        PVLOG_ERR(st, "providers update", refbuf,
            "Provider '%s' is not configured.", name);
        providers_free(list, ln);
        return 1;
    }
    static const char *bi[] = { "codeberg", "github", "gitlab", NULL };
    for (int k = 0; bi[k]; k++)
        if (!strcmp(name, bi[k])) {
            put_s(stderr, UI_ERROR,
                "Provider '%s' is built-in and cannot be updated.", name);
            PVLOG_ERR(st, "providers update", refbuf,
                "Provider '%s' is built-in and cannot be updated.", name);
            providers_free(list, ln);
            return 1;
        }
    const char *typev = pv.type != NULL ? pv.type : ex->type;
    const char *ltype = type_canon(typev);
    if (ltype == NULL) {
        put_s(stderr, UI_ERROR, "Unknown provider type: %s", typev);
        PVLOG_ERR(st, "providers update", refbuf,
            "Unknown provider type: %s", typev);
        providers_free(list, ln);
        return 1;
    }
    const char *url = pv.url != NULL ? pv.url : ex->url;
    const char *apiv = pv.api;
    if (apiv == NULL && !strcmp(ltype, ex->type) && !strcmp(url, ex->url))
        apiv = ex->api;      // unchanged endpoint keeps its custom API
    const char *tok = pv.token_env_given ? pv.token_env : ex->token_env;

    if (!provider_url_ok(ltype, url) || !provider_token_ok(tok)
        || (apiv != NULL && *apiv != '\0' && !provider_url_ok(ltype, apiv))) {
        put_fail("Invalid provider config; check the URL (https:// or rns://),"
                " API base, and token variable.");
        PVLOG_ERR(st, "providers update", refbuf,
            "Invalid provider config; check the URL (https:// or rns://),"
            " API base, and token variable.");
        providers_free(list, ln);
        return 1;
    }
    char *urlc = strip_slash_dup(url);
    char *api = provider_resolved_api(ltype, urlc, apiv);

    // endpoint-change confirmation is manifest-gated: unreachable until
    // the manifest module counts packages for this provider

    char *cfg = state_config_edit(st, name, ltype, urlc, api, tok, NULL);
    int rc = 0;
    if (cfg == NULL || !fs_write_file(st->config_file, cfg)) {
        put_s(stderr, UI_ERROR, "Failed to save providers");
        rc = 1;
    } else if (o->json) {
        print_provider_result(tok != NULL && *tok != '\0' ? tok : NULL, false,
            ex->name, ltype, urlc, api);
    } else {
        put_s(stderr, UI_OK, "Updated provider %s.", name);
    }
    oplog_append(st, "providers update", refbuf, "-",
                 rc == 1 ? "Failed to save providers" : NULL);
    free(cfg);
    free(api);
    free(urlc);
    providers_free(list, ln);
    return rc;
}

static int cmd_providers_recover(const cmd_opts *o, state_ctx *st,
                                 char **args, int n)
{
    pv_opts pv = {0};
    char *pos[8];
    int np = 0;
    const char *err = NULL;
    char errbuf[256];
    if (!pv_parse(args, n, 3, &pv, pos, &np, &err, errbuf, sizeof(errbuf))) {
        put_fail(err);
        return 1;
    }
    if (np != 1) {
        providers_usage();
        return 1;
    }
    const char *name = pos[0];
    provider_entry *list = NULL;
    int ln = providers_load(st, &list);
    if (providers_find(list, ln, name) != NULL) {
        put_s(stderr, UI_ERROR, "Provider '%s' is already configured.", name);
        providers_free(list, ln);
        return 1;
    }
    providers_free(list, ln);
    pv_recovered rec = {0};
    int rc = providers_recover_core(o, st, name, pv.type, pv.api,
        pv.token_env, pv.token_env_given, &rec);
    if (rc == 0 && o->json)
        print_provider_result(rec.token_env, false, name, rec.type,
            rec.url, rec.api);
    free(rec.type);
    free(rec.url);
    free(rec.api);
    free(rec.token_env);
    return rc == 0 ? 0 : 1;
}

int providers_run(const cmd_opts *o, state_ctx *st)
{
    // subcommand args = everything after "providers" (o->args[0])
    char **a = o->args + 1;
    int n = o->nargs - 1;
    if (n == 0) return cmd_providers_list(o, st);
    char sub[16];
    size_t sl = strlen(a[0]);
    if (sl >= sizeof(sub)) sl = sizeof(sub) - 1;
    for (size_t k = 0; k < sl; k++) sub[k] = (char)tolower((unsigned char)a[0][k]);
    sub[sl] = '\0';
    if (!strcmp(sub, "list"))
        return n == 1 ? cmd_providers_list(o, st) : (providers_usage(), 1);
    if (!strcmp(sub, "show")) {
        if (n != 2) { providers_usage(); return 1; }
        return cmd_providers_show(o, st, a[1]);
    }
    if (!strcmp(sub, "add")) return cmd_providers_add(o, st, a + 1, n - 1);
    if (!strcmp(sub, "update")) return cmd_providers_update(o, st, a + 1, n - 1);
    if (!strcmp(sub, "remove")) return cmd_providers_remove(o, st, a + 1, n - 1);
    if (!strcmp(sub, "recover")) return cmd_providers_recover(o, st, a + 1, n - 1);
    providers_usage();
    return 1;
}
