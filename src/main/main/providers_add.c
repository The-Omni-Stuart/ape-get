// providers add: validate a new provider entry (name, URL, type — probing
// for auto) and write it into the config

#include "cmd.h"
#include "fs.h"
#include "json.h"
#include "path.h"
#include "probe.h"
#include "provider.h"
#include "state.h"
#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include "main.h"

static const char *host_of(const char *url, char *buf, size_t cap)
{
    const char *p = strstr(url, "://");
    if (p == NULL) return NULL;
    p += 3;
    const char *at = strchr(p, '@');
    const char *slash = strchr(p, '/');
    if (at != NULL && (slash == NULL || at < slash)) return NULL;   // userinfo
    size_t n = slash ? (size_t)(slash - p) : strlen(p);
    if (n == 0 || n >= cap) return NULL;
    memcpy(buf, p, n);
    buf[n] = '\0';
    return buf;
}

static char *derive_name(const char *url)
{
    char host[256];
    if (host_of(url, host, sizeof(host)) == NULL) return NULL;
    for (char *p = host; *p; p++) *p = (char)tolower((unsigned char)*p);
    if (!strcmp(host, "github.com")) return strdup("github");
    if (!strcmp(host, "codeberg.org")) return strdup("codeberg");
    if (!strcmp(host, "gitlab.com")) return strdup("gitlab");
    const int rngit = strncasecmp(url, "rns://", 6) == 0;
    char alias[264];
    size_t w = 0;
    for (const char *p = host; *p && w < sizeof(alias) - 1; p++)
        alias[w++] = (isalnum((unsigned char)*p) || *p == '.' || *p == '-' || *p == '_')
            ? *p : '-';
    alias[w] = '\0';
    size_t b = 0, e2 = strlen(alias);
    while (b < e2 && (alias[b] == '.' || alias[b] == '_' || alias[b] == '-')) b++;
    while (e2 > b && (alias[e2 - 1] == '.' || alias[e2 - 1] == '_' || alias[e2 - 1] == '-')) e2--;
    alias[e2 > b ? e2 : b] = '\0';
    const char *body = alias + (e2 > b ? b : 0);
    if (rngit) {
        int hash = strlen(body) >= 32;
        for (const char *p = body; *p && hash; p++)
            if (!isxdigit((unsigned char)*p)) hash = 0;
        if (hash) {
            char cut[9];
            memcpy(cut, body, 8);
            cut[8] = '\0';
            return path_fmt("rns-%s", cut);
        }
        return path_fmt("rns-%s", body);
    }
    if (*body == '\0') return NULL;
    return strdup(body);
}

int cmd_providers_add(const cmd_opts *o, state_ctx *st,
                             char **args, int n)
{
    pv_opts pv = {0};
    char *pos[8];
    int np = 0;
    const char *err = NULL;
    char errbuf[256];
    if (!pv_parse(args, n, 0, &pv, pos, &np, &err, errbuf, sizeof(errbuf))) {
        put_fail(err);
        return 1;
    }
    if (np < 1 || np > 2) {
        providers_usage();
        return 1;
    }
    const char *url_arg = np == 2 ? pos[1] : pos[0];
    char *name = NULL;
    if (np == 2) name = strdup(pos[0]);
    else if (pv.name != NULL && *pv.name != '\0') name = strdup(pv.name);
    else if ((name = derive_name(url_arg)) == NULL) {
        put_s(stderr, UI_ERROR, "Invalid provider URL: %s", url_arg);
        return 1;
    }
    if (!provider_valid_name(name)) {
        put_s(stderr, UI_ERROR, "Invalid provider name: %s", name);
        free(name);
        return 1;
    }
    // from here the target provider is named, so outcomes are written to
    // the operation history
    char refbuf[300];
    snprintf(refbuf, sizeof(refbuf), "%s:-", name);
    static const char *bi[] = { "codeberg", "github", "gitlab", NULL };
    for (int k = 0; bi[k]; k++)
        if (!strcmp(name, bi[k])) {
            put_s(stderr, UI_ERROR, "Provider '%s' is built-in; choose another name.", name);
            PVLOG_ERR(st, "providers add", refbuf,
                "Provider '%s' is built-in; choose another name.", name);
            free(name);
            return 1;
        }
    provider_entry *list = NULL;
    int ln = providers_load(st, &list);
    if (providers_find(list, ln, name) != NULL) {
        put_s(stderr, UI_ERROR, "Provider '%s' already exists; use providers update.", name);
        PVLOG_ERR(st, "providers add", refbuf,
            "Provider '%s' already exists; use providers update.", name);
        providers_free(list, ln);
        free(name);
        return 1;
    }
    char *url = strdup(url_arg);
    size_t ul = strlen(url);
    while (ul > 0 && url[ul - 1] == '/') url[--ul] = '\0';

    const char *typev = pv.type != NULL ? pv.type : "auto";
    const char *ltype = NULL;
    bool is_auto = !strcasecmp(typev, "auto");
    if (is_auto) {
        // rns:// and the three known hosts need no probing
        char host[128];
        if (strncmp(url, "rns://", 6) == 0) ltype = "rngit";
        else if (host_of(url, host, sizeof host) != NULL) {
            if (!strcasecmp(host, "github.com")) ltype = "github";
            else if (!strcasecmp(host, "codeberg.org")) ltype = "gitea";
            else if (!strcasecmp(host, "gitlab.com")) ltype = "gitlab";
        }
        if (ltype == NULL) {
            probe_hit hits[3];
            int nh = probe_detect(url, hits, 3);
            char msg[800], *mv = msg;
            if (nh == 0) {
                snprintf(msg, sizeof msg, "Could not detect the provider "
                    "type at %s. Pass --type github|gitea|gitlab|rngit, "
                    "or --api if the API is on another host.", url);
            } else if (nh == 1) {
                ltype = hits[0].type;
            } else {
                size_t cap = strlen(url) + 3 * 540 + 128;
                if ((mv = malloc(cap)) != NULL) {
                    size_t o = (size_t)snprintf(mv, cap,
                        "Multiple provider APIs responded at %s:", url);
                    for (int k = 0; k < nh; k++)
                        o += (size_t)snprintf(mv + o, cap - o,
                            " %s (%s)", hits[k].type, hits[k].api);
                    snprintf(mv + o, cap - o,
                        " - pass --type github|gitea|gitlab|rngit");
                } else {
                    snprintf(msg, sizeof msg, "Multiple provider APIs "
                        "responded at %s: - pass --type "
                        "github|gitea|gitlab|rngit", url);
                    mv = msg;
                }
            }
            if (ltype == NULL) {
                put_s(stderr, UI_ERROR, "%s", mv);
                PVLOG_ERR(st, "providers add", refbuf, "%.511s", mv);
                if (mv != msg) free(mv);
                free(url);
                providers_free(list, ln);
                free(name);
                return 1;
            }
            for (int k = 0; k < nh; k++) free(hits[k].api);
        }
    }
    if (ltype == NULL && (ltype = type_canon(typev)) == NULL) {
        put_s(stderr, UI_ERROR, "Unknown provider type: %s", typev);
        PVLOG_ERR(st, "providers add", refbuf,
            "Unknown provider type: %s", typev);
        free(url);
        providers_free(list, ln);
        free(name);
        return 1;
    }
    bool api_given = pv.api != NULL && *pv.api != '\0';
    if (!provider_url_ok(ltype, url) || !provider_token_ok(pv.token_env)
        || (api_given && !provider_url_ok(ltype, pv.api))) {
        put_fail("Invalid provider config; check the URL (https:// or rns://),"
                " API base, and token variable.");
        PVLOG_ERR(st, "providers add", refbuf,
            "Invalid provider config; check the URL (https:// or rns://),"
            " API base, and token variable.");
        free(url);
        providers_free(list, ln);
        free(name);
        return 1;
    }
    char *api = provider_resolved_api(ltype, url, api_given ? pv.api : NULL);
    if (is_auto && ltype != NULL) {
        if (!strcmp(ltype, "rngit"))
            put_s(stderr, UI_REF, "Detected rngit at %s.", url);
        else
            put_s(stderr, UI_REF, "Detected %s at %s (API %s).", ltype, url, api);
    }

    char *cfg = state_config_edit(st, name, ltype, url, api, pv.token_env, NULL);
    int rc = 0;
    if (cfg == NULL || !fs_write_file(st->config_file, cfg)) {
        put_s(stderr, UI_ERROR, "Failed to save providers");
        rc = 1;
    } else if (o->json) {
        jbuf b = {0};
        jb_lit(&b, "{\"provider\":{\"token_env\":");
        json_null_or_string(&b, pv.token_env);
        jb_lit(&b, ",\"built_in\":false,\"name\":");
        jb_json_string(&b, name);
        jb_lit(&b, ",\"type\":");
        jb_json_string(&b, ltype);
        jb_lit(&b, ",\"url\":");
        jb_json_string(&b, url);
        jb_lit(&b, ",\"api\":");
        jb_json_string(&b, api);
        jb_lit(&b, "}}");
        puts(b.p);
        jb_free(&b);
    } else {
        put_s(stderr, UI_OK, "Added provider %s (%s, %s).", name, ltype, api);
    }
    oplog_append(st, "providers add", refbuf, "-", rc == 1 ? "Failed to save providers" : NULL);
    free(cfg);
    free(api);
    free(url);
    providers_free(list, ln);
    free(name);
    return rc;
}
