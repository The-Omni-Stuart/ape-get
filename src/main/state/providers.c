#include "state.h"
#include "fs.h"
#include "json.h"
#include "path.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <ctype.h>

// Provider registry: three built-ins plus config entries; invalid or
// built-in-shadowing config entries warn on stderr and are dropped.

static const provider_entry BUILT_INS[3] = {
    { (char *)"codeberg", (char *)"gitea", (char *)"https://codeberg.org",
      (char *)"https://codeberg.org/api/v1", NULL, true },
    { (char *)"github", (char *)"github", (char *)"https://github.com",
      (char *)"https://api.github.com", NULL, true },
    { (char *)"gitlab", (char *)"gitlab", (char *)"https://gitlab.com",
      (char *)"https://gitlab.com/api/v4", NULL, true },
};

bool provider_valid_name(const char *name)
{
    if (name == NULL || *name == '\0') return false;
    if (!islower((unsigned char)*name) && !isdigit((unsigned char)*name)) return false;
    for (const char *p = name + 1; *p; p++)
        if (!islower((unsigned char)*p) && !isdigit((unsigned char)*p)
            && *p != '.' && *p != '_' && *p != '-') return false;
    return true;
}

// scheme://host with no userinfo; https for web types (any path), rns for
// rngit (host <=64 chars, alnum first, rest alnum or . _ -, path empty or "/")
static bool url_shape(const char *url, const char *scheme, bool rns_style)
{
    size_t sl = strlen(scheme);
    if (strncasecmp(url, scheme, sl) != 0 || strncmp(url + sl, "://", 3) != 0)
        return false;
    const char *host = url + sl + 3;
    const char *slash = strchr(host, '/');
    size_t hl = slash ? (size_t)(slash - host) : strlen(host);
    if (hl == 0) return false;
    if (memchr(host, '@', hl) != NULL) return false;   // userinfo
    const char *path = slash ? slash + 1 : "";
    if (rns_style) {
        if (hl > 64 || !isalnum((unsigned char)host[0])) return false;
        for (size_t k = 1; k < hl; k++)
            if (!isalnum((unsigned char)host[k]) && host[k] != '.' && host[k] != '-'
                && host[k] != '_') return false;
        if (*path != '\0' && !(path[0] == '/' && path[1] == '\0')) return false;
    } else if (memchr(host, ' ', hl) != NULL || memchr(host, '\t', hl) != NULL)
        return false;
    return true;
}

static bool is_https(const char *url)
{
    return url != NULL && url_shape(url, "https", false);
}

static bool is_rns(const char *url)
{
    return url != NULL && url_shape(url, "rns", true);
}

bool provider_url_ok(const char *type, const char *url)
{
    if (url == NULL) return false;
    return strcasecmp(type, "rngit") == 0 ? is_rns(url) : is_https(url);
}

bool provider_token_ok(const char *token_env)
{
    if (token_env == NULL || *token_env == '\0') return true;
    if (!isalpha((unsigned char)*token_env) && *token_env != '_') return false;
    for (const char *p = token_env + 1; *p; p++)
        if (!isalnum((unsigned char)*p) && *p != '_') return false;
    static const char *reserved[] = {
        "PATH", "HOME", "TMPDIR", "LD_PRELOAD", "LD_LIBRARY_PATH", NULL };
    for (int k = 0; reserved[k]; k++)
        if (strcmp(token_env, reserved[k]) == 0) return false;
    return true;
}

static char *strip_slash(const char *url)
{
    size_t n = strlen(url);
    while (n > 0 && url[n - 1] == '/') n--;
    char *r = malloc(n + 1);
    memcpy(r, url, n);
    r[n] = '\0';
    return r;
}

static bool known_type(const char *type)
{
    return type != NULL && (strcasecmp(type, "github") == 0
        || strcasecmp(type, "gitea") == 0 || strcasecmp(type, "gitlab") == 0
        || strcasecmp(type, "rngit") == 0);
}

char *provider_resolved_api(const char *type, const char *url, const char *api)
{
    if (api != NULL && *api != '\0') return strip_slash(api);
    char *base = strip_slash(url);
    if (strcasecmp(type, "rngit") == 0) return base;
    if (strcasecmp(type, "github") == 0) {
        const char *h = strstr(base, "://");
        size_t hl = 0;
        if (h != NULL) {
            h += 3;
            while (h[hl] != '\0' && h[hl] != '/') hl++;
        }
        if (hl == 10 && strncmp(h, "github.com", 10) == 0) {
            free(base);
            return strdup("https://api.github.com");
        }
    }
    const char *suffix = strcasecmp(type, "gitea") == 0 ? "/api/v1"
        : strcasecmp(type, "gitlab") == 0 ? "/api/v4" : "/api/v3";
    char *r = path_fmt("%s%s", base, suffix);
    free(base);
    return r;
}

static bool entry_valid(const char *name, const char *type, const char *url,
                        const char *api, const char *token_env)
{
    if (name != NULL && *name && !provider_valid_name(name)) return false;
    if (!known_type(type)) return false;
    if (!provider_url_ok(type, url)) return false;
    if (!provider_token_ok(token_env)) return false;
    if (api != NULL && *api != '\0' && !provider_url_ok(type, api)) return false;
    return true;
}

static int cmp_entry(const void *a, const void *b)
{
    const provider_entry *x = a, *y = b;
    return strcmp(x->name, y->name);
}

int providers_load(const state_ctx *st, provider_entry **out)
{
    size_t n = 0;
    char *cfg = fs_read_file(st->config_file, &n);
    provider_entry *list = NULL;
    int count = 0, cap = 0;

    jobject co = {0};
    if (cfg != NULL && jobject_parse(cfg, &co)) {
        int pi = jobject_find(&co, "providers");
        jobject po = {0};
        if (pi >= 0) {
            char *ptext = strndup(co.vals[pi], (size_t)(co.vends[pi] - co.vals[pi]));
            if (ptext != NULL && jobject_parse(ptext, &po)) {
                for (int k = 0; k < po.n; k++) {
                    const char *name = po.keys[k];
                    bool builtin = false;
                    for (int b = 0; b < 3; b++)
                        if (strcmp(BUILT_INS[b].name, name) == 0) builtin = true;
                    if (builtin) {
                        fprintf(stderr,
                            "Warning: ignoring provider config for built-in %s.\n", name);
                        continue;
                    }
                    jobject eo = {0};
                    char *etext = strndup(po.vals[k], (size_t)(po.vends[k] - po.vals[k]));
                    char *type = NULL, *url = NULL, *api = NULL, *tok = NULL;
                    bool ok = etext != NULL && jobject_parse(etext, &eo);
                    if (ok) {
                        char *s;
                        int t = jobject_find(&eo, "type");
                        if (t >= 0 && json_span_string(eo.vals[t], eo.vends[t], &s)) type = s;
                        t = jobject_find(&eo, "url");
                        if (t >= 0 && json_span_string(eo.vals[t], eo.vends[t], &s)) url = s;
                        t = jobject_find(&eo, "api");
                        if (t >= 0 && json_span_string(eo.vals[t], eo.vends[t], &s)) api = s;
                        t = jobject_find(&eo, "token_env");
                        if (t >= 0 && json_span_string(eo.vals[t], eo.vends[t], &s)) tok = s;
                        ok = entry_valid(name, type, url, api, tok);
                    }
                    if (!ok) {
                        fprintf(stderr,
                            "Warning: ignoring invalid provider config for %s.\n", name);
                        free(type); free(url); free(api); free(tok);
                    } else {
                        if (count == cap) {
                            cap = cap ? cap * 2 : 8;
                            list = realloc(list, (size_t)cap * sizeof(*list));
                        }
                        provider_entry e = { strdup(name), type, strip_slash(url),
                            provider_resolved_api(type, url, api), tok, false };
                        list[count++] = e;
                    }
                    free(etext);
                    jobject_free(&eo);
                }
            }
            free(ptext);
        }
        jobject_free(&co);
    }
    free(cfg);

    for (int b = 0; b < 3; b++) {
        const provider_entry *bi = &BUILT_INS[b];
        bool shadowed = false;
        for (int k = 0; k < count; k++)
            if (strcmp(list[k].name, bi->name) == 0) shadowed = true;
        if (shadowed) continue;
        if (count == cap) {
            cap = cap ? cap * 2 : 8;
            list = realloc(list, (size_t)cap * sizeof(*list));
        }
        provider_entry e = *bi;
        e.name = strdup(bi->name);
        e.type = strdup(bi->type);
        e.url = strdup(bi->url);
        e.api = strdup(bi->api);
        e.token_env = NULL;
        e.built_in = true;
        list[count++] = e;
    }
    qsort(list, (size_t)count, sizeof(*list), cmp_entry);
    *out = list;
    return count;
}

void providers_free(provider_entry *list, int n)
{
    for (int k = 0; k < n; k++) {
        free(list[k].name);
        free(list[k].type);
        free(list[k].url);
        free(list[k].api);
        free(list[k].token_env);
    }
    free(list);
}

provider_entry *providers_find(provider_entry *list, int n, const char *name)
{
    for (int k = 0; k < n; k++)
        if (strcasecmp(list[k].name, name) == 0) return &list[k];
    return NULL;
}
