// Search side of the shared provider surface: labels, failure
// classes, the token header and the one round-trip the per-type searches run.

#include "provider.h"
#include "provider/internal.h"
#include "http.h"
#include "json.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

const char *provider_label(const provider_entry *e)
{
    if (!strcasecmp(e->type, "github")) return "GitHub";
    if (!strcasecmp(e->type, "gitea")) return "Gitea";
    if (!strcasecmp(e->type, "gitlab")) return "GitLab";
    return e->type;
}

void prov_search_free(prov_search *r)
{
    for (int k = 0; k < r->n; k++) {
        free(r->items[k].full_name);
        free(r->items[k].description);
    }
    free(r->items);
    memset(r, 0, sizeof(*r));
}

prov_reason prov_classify(const char *label, int status, char *msg, size_t cap)
{
    switch (status) {
    case 401:
        snprintf(msg, cap, "authentication failed");
        return PROV_UNAUTHORIZED;
    case 403:
        snprintf(msg, cap, "access denied");
        return PROV_UNAUTHORIZED;
    case 404:
        snprintf(msg, cap, "resource not found");
        return PROV_NOT_FOUND;
    case 429:
        snprintf(msg, cap, "rate limit exceeded");
        return PROV_RATE_LIMITED;
    default:
        snprintf(msg, cap, "%s API returned %d for search", label, status);
        return PROV_OTHER;
    }
}

prov_reason prov_net_error(const char *label, int errkind, const char *url,
                           int timeout_s, const char *io_message,
                           char *msg, size_t cap)
{
    char host[256] = "";
    const char *p = strstr(url, "://");
    if (p != NULL) {
        p += 3;
        const char *slash = strchr(p, '/');
        size_t rest = slash ? (size_t)(slash - p) : strlen(p);
        const char *colon = memchr(p, ':', rest);
        size_t n = colon ? (size_t)(colon - p) : rest;
        if (n >= sizeof(host)) n = 0;
        memcpy(host, p, n);
        host[n] = '\0';
    }
    switch (errkind) {
    case HTTP_ERR_TIMEOUT:
        snprintf(msg, cap, "%s request timed out after %ds", label, timeout_s);
        return PROV_TIMEOUT;
    case HTTP_ERR_DNS:
        snprintf(msg, cap, "%s could not resolve %s", label, host);
        return PROV_UNREACHABLE;
    case HTTP_ERR_CONNECT:
        snprintf(msg, cap, "%s could not connect to %s", label, host);
        return PROV_UNREACHABLE;
    default:
        snprintf(msg, cap, "%s",
                 io_message != NULL && *io_message != '\0' ? io_message
                                                           : "unknown error");
        return PROV_OTHER;
    }
}

char *provider_token(const provider_entry *e)
{
    if (e->token_env == NULL || e->token_env[0] == '\0') return NULL;
    const char *v = getenv(e->token_env);
    if (v == NULL || *v == '\0') return NULL;
    // a token is spliced into a request header; CR/LF or any control byte
    // would inject headers, so treat such a value as unset
    for (const unsigned char *p = (const unsigned char *)v; *p; p++)
        if (*p < 0x20 || *p == 0x7f) return NULL;
    return strdup(v);
}

bool prov_parse_hits(const char *array_text, const char *name_key,
                     bool filter_null_name, prov_repo **items, int *n)
{
    jspan *spans = NULL;
    int cnt = json_array_spans(array_text, &spans);
    *items = NULL;
    *n = 0;
    if (cnt < 0) return false;
    prov_repo *list = cnt > 0 ? calloc((size_t)cnt, sizeof(*list)) : NULL;
    for (int k = 0; k < cnt && list != NULL; k++) {
        char *text = strndup(spans[k].s, (size_t)(spans[k].e - spans[k].s));
        jobject eo = {0};
        if (text != NULL && jobject_parse(text, &eo)) {
            char *name = NULL, *desc = NULL;
            int f = jobject_find(&eo, name_key);
            if (f >= 0) json_span_string(eo.vals[f], eo.vends[f], &name);
            f = jobject_find(&eo, "description");
            if (f >= 0) json_span_string(eo.vals[f], eo.vends[f], &desc);
            if (name != NULL || !filter_null_name) {
                list[*n].full_name = name;
                list[*n].description = desc;
                (*n)++;
            } else {
                free(name);
                free(desc);
            }
        }
        jobject_free(&eo);
        free(text);
    }
    free(spans);
    *items = list;
    return true;
}

void provider_search_unsupported(prov_search *out)
{
    memset(out, 0, sizeof(*out));
    out->reason = PROV_UNSUPPORTED;
    snprintf(out->message, sizeof(out->message),
             "repository search is not supported");
}

// Normalised authority for same-origin token scoping: lowercase host plus
// a non-443 port, so a bearer never rides to a foreign host or port.
static bool url_host(const char *u, char *out, size_t cap)
{
    const char *p = u != NULL ? strstr(u, "://") : NULL;
    if (p == NULL) return false;
    p += 3;
    size_t k = 0;
    while (p[k] != '\0' && p[k] != '/' && p[k] != ':' && p[k] != '?'
        && k + 1 < cap) {
        out[k] = (char)(p[k] >= 'A' && p[k] <= 'Z' ? p[k] + 32 : p[k]);
        k++;
    }
    if (p[k] != '\0' && k + 1 >= cap) return false;
    out[k] = '\0';
    if (k == 0) return false;
    if (p[k] == ':') {                    // non-443 port joins the key
        const char *q = p + k + 1;
        if (*q < '0' || *q > '9') return false;
        int port = 0;
        while (*q >= '0' && *q <= '9') port = port * 10 + (*q++ - '0');
        if ((*q != '\0' && *q != '/' && *q != '?') || port == 443) return false;
        int n = snprintf(out + k, cap - k, ":%d", port);
        if (n < 0 || (size_t)n >= cap - k) return false;
    }
    return true;
}

// Token header per type: gitea and github send Authorization with a prefix,
// gitlab PRIVATE-TOKEN; false when no token or a foreign download_url.
bool prov_auth(const provider_entry *e, const char *download_url,
                      const char **name, char *authv, size_t cap)
{
    const char *hdr = NULL, *pfx = NULL;
    if (!strcasecmp(e->type, "gitea")) { hdr = "Authorization"; pfx = "token "; }
    else if (!strcasecmp(e->type, "github")) { hdr = "Authorization"; pfx = "Bearer "; }
    else if (!strcasecmp(e->type, "gitlab")) { hdr = "PRIVATE-TOKEN"; pfx = ""; }
    else return false;
    if (download_url != NULL) {
        char h[256], a[256];
        if (!url_host(download_url, h, sizeof(h))) return false;
        bool same = (e->api != NULL && url_host(e->api, a, sizeof(a))
            && !strcmp(h, a))
            || (e->url != NULL && url_host(e->url, a, sizeof(a))
                && !strcmp(h, a));
        if (!same) return false;
    }
    char *tok = provider_token(e);
    if (tok == NULL) return false;
    snprintf(authv, cap, "%s%s", pfx, tok);
    free(tok);
    *name = hdr;
    return true;
}

bool provider_search_exec(const provider_entry *e, const char *url,
                          const char *array_key,
                          bool top_array, const char *name_key,
                          bool filter_null_name, int timeout_ms,
                          prov_search *out)
{
    memset(out, 0, sizeof(*out));
    const char *label = provider_label(e);

    const char *hdr = "Authorization";
    char authv[1152] = "";
    bool have_auth = prov_auth(e, NULL, &hdr, authv, sizeof(authv));
    http_resp r;
    char errbuf[256] = "";
    bool ok = http_get_hdr(url, hdr, have_auth ? authv : NULL, timeout_ms,
                           &r, errbuf, sizeof(errbuf));
    if (!ok) {
        out->reason = prov_net_error(label, r.errkind, url,
                                     timeout_ms / 1000, errbuf,
                                     out->message, sizeof(out->message));
        return false;
    }
    if (r.status != 200) {
        out->reason = prov_classify(label, r.status,
                                    out->message, sizeof(out->message));
        http_resp_free(&r);
        return false;
    }

    // parse failures surface as a plain "invalid response" OTHER, never
    // as parser internals
    char *array_text = NULL;
    bool empty = false;
    if (top_array) {
        array_text = r.body != NULL ? strdup(r.body) : NULL;
        empty = r.body == NULL;
    } else {
        jobject top = {0};
        bool parsed = r.body != NULL && jobject_parse(r.body, &top);
        int f = parsed ? jobject_find(&top, array_key) : -1;
        if (parsed && f >= 0) {
            size_t len = (size_t)(top.vends[f] - top.vals[f]);
            empty = len == 4 && memcmp(top.vals[f], "null", 4) == 0;
            if (!empty) array_text = strndup(top.vals[f], len);
        } else if (parsed) {
            empty = true;                 // member absent -> empty list
        } else {
            out->reason = PROV_OTHER;
            snprintf(out->message, sizeof(out->message), "invalid response");
        }
        jobject_free(&top);
        if (out->reason != PROV_OK && out->message[0] != '\0') {
            http_resp_free(&r);
            return false;
        }
    }
    http_resp_free(&r);

    bool ok_parse = true;
    if (!empty && array_text != NULL)
        ok_parse = prov_parse_hits(array_text, name_key, filter_null_name,
                                   &out->items, &out->n);
    free(array_text);
    if (!ok_parse) {
        out->reason = PROV_OTHER;
        snprintf(out->message, sizeof(out->message), "invalid response");
        return false;
    }
    return true;
}
