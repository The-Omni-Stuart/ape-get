// Install/update routing: type dispatch for release fetch and asset
// download, plus the release JSON shape the gitea and github adapters share.

#include "provider.h"
#include "provider/internal.h"
#include "http.h"
#include "json.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <unistd.h>

// ---- install/update side ----

void prov_release_free(prov_release *r)
{
    for (int k = 0; k < r->n; k++) {
        free(r->assets[k].name);
        free(r->assets[k].url);
    }
    free(r->assets);
    free(r->version);
    free(r->release_id);
    memset(r, 0, sizeof(*r));
}

bool prov_api_json(const provider_entry *e, const char *url, int timeout_ms,
                   const char *notfound, const char *context, char **body,
                   prov_reason *reason, char *msg, size_t cap)
{
    const char *label = provider_label(e);
    const char *hdr = "Authorization";
    char authv[1152] = "";
    bool have_auth = prov_auth(e, NULL, &hdr, authv, sizeof(authv));
    http_resp r;
    char errbuf[256] = "";
    bool ok = http_get_hdr(url, hdr, have_auth ? authv : NULL, timeout_ms,
                           &r, errbuf, sizeof(errbuf));
    if (!ok) {
        if (reason != NULL)
            *reason = prov_net_error(label, r.errkind, url,
                timeout_ms / 1000, errbuf, msg, cap);
        return false;
    }
    if (r.status != 200) {
        switch (r.status) {
        case 401:
            snprintf(msg, cap, "authentication failed");
            if (reason != NULL) *reason = PROV_UNAUTHORIZED;
            break;
        case 403:
            snprintf(msg, cap, "access denied");
            if (reason != NULL) *reason = PROV_UNAUTHORIZED;
            break;
        case 404:
            snprintf(msg, cap, "%s", notfound);
            if (reason != NULL) *reason = PROV_NOT_FOUND;
            break;
        case 429:
            snprintf(msg, cap, "rate limit exceeded");
            if (reason != NULL) *reason = PROV_RATE_LIMITED;
            break;
        default:
            snprintf(msg, cap, "%s API returned %d for %s", label,
                r.status, context);
            if (reason != NULL) *reason = PROV_OTHER;
            break;
        }
        http_resp_free(&r);
        return false;
    }
    *body = r.body != NULL ? r.body : strdup("");
    r.body = NULL;
    http_resp_free(&r);
    if (reason != NULL) *reason = PROV_OK;
    return true;
}

bool provider_release_fetch(const provider_entry *e, const char *path,
                            const char *tag, bool beta, int timeout_ms,
                            prov_release *out)
{
    memset(out, 0, sizeof(*out));
    if (!strcasecmp(e->type, "gitea"))
        return provider_gitea_release(e, path, tag, beta, timeout_ms, out);
    if (!strcasecmp(e->type, "github"))
        return provider_github_release(e, path, tag, beta, timeout_ms, out);
    if (!strcasecmp(e->type, "gitlab"))
        return provider_gitlab_release(e, path, tag, beta, timeout_ms, out);
    if (!strcasecmp(e->type, "rngit"))
        return provider_rngit_release(e, path, tag, beta, timeout_ms, out);
    out->reason = PROV_UNSUPPORTED;
    snprintf(out->message, sizeof(out->message),
        "release fetching is not supported for this provider type");
    return false;
}

// Non-200 asset download message, shared by the streamed and buffered routes.
static void dl_status_msg(const char *label, int status, const char *name,
                          char *msg, size_t cap)
{
    switch (status) {
    case 401: snprintf(msg, cap, "authentication failed"); break;
    case 403: snprintf(msg, cap, "access denied"); break;
    case 404: snprintf(msg, cap, "asset not found"); break;
    case 429: snprintf(msg, cap, "rate limit exceeded"); break;
    default:
        snprintf(msg, cap, "%s download returned %d for %s", label,
            status, name);
        break;
    }
}

bool provider_download(const provider_entry *e, const char *url,
                       const char *name, int timeout_ms, long long max_bytes,
                       const char *dest, http_prog cb, void *ud,
                       char **tmp_path, char *msg, size_t cap)
{
    if (!strcasecmp(e->type, "rngit"))
        return provider_rngit_download(e, url, name, timeout_ms, max_bytes,
                                       dest, tmp_path, msg, cap);
    const char *label = provider_label(e);
    const char *hdr = "Authorization";
    char authv[1152] = "";
    bool have_auth = prov_auth(e, url, &hdr, authv, sizeof(authv));
    char tmp[600];
    if (dest != NULL) {
        if (snprintf(tmp, sizeof(tmp), "%s", dest)
                >= (int)sizeof(tmp)) {
            snprintf(msg, cap, "staging path too long");
            return false;
        }
    } else {
        // mkstemp: PID-derived names are guessable on shared machines and
        // a planted symlink would be truncated by our fopen("wb")
        snprintf(tmp, sizeof(tmp), "/tmp/ape-get-dl-XXXXXX");
        int tfd = mkstemp(tmp);
        if (tfd == -1) {
            snprintf(msg, cap, "cannot write %s", tmp);
            return false;
        }
        close(tfd);
    }
    char errbuf[256] = "";
    if (dest != NULL) {
        // staged assets stream straight to the path: they routinely outgrow
        // the 32 MB buffer limit the JSON path keeps
        int status = 0, kind = HTTP_ERR_OTHER;
        if (!http_download(url, hdr, have_auth ? authv : NULL,
                           timeout_ms, max_bytes, cb, ud, tmp, &status,
                           &kind, errbuf, sizeof(errbuf))) {
            if (status != 0)
                dl_status_msg(label, status, name, msg, cap);
            else
                prov_net_error(label, kind, url, timeout_ms / 1000,
                    errbuf, msg, cap);
            return false;
        }
        struct stat sb;
        if (stat(tmp, &sb) != 0 || sb.st_size == 0) {
            remove(tmp);
            snprintf(msg, cap, "Downloaded file is empty: %s", name);
            return false;
        }
        *tmp_path = strdup(tmp);
        return true;
    }
    // side files (checksums, signatures) stay on the buffered route
    http_resp r;
    bool ok = http_get_hdr_prog(url, hdr, have_auth ? authv : NULL,
                                timeout_ms, cb, ud, &r, errbuf,
                                sizeof(errbuf));
    if (!ok) {
        prov_net_error(label, r.errkind, url, timeout_ms / 1000, errbuf,
            msg, cap);
        return false;
    }
    if (r.status != 200) {
        dl_status_msg(label, r.status, name, msg, cap);
        http_resp_free(&r);
        return false;
    }
    if (r.body == NULL || r.len == 0) {
        snprintf(msg, cap, "Downloaded file is empty: %s", name);
        http_resp_free(&r);
        return false;
    }
    FILE *f = fopen(tmp, "wb");
    if (f == NULL) {
        snprintf(msg, cap, "cannot write %s", tmp);
        http_resp_free(&r);
        return false;
    }
    // a partial write here feeds truncated bytes straight to checksum
    // or signature verification, so it must fail the download
    bool wr = fwrite(r.body, 1, r.len, f) == r.len;
    if (fclose(f) != 0) wr = false;
    if (!wr) {
        remove(tmp);
        snprintf(msg, cap, "cannot write %s", tmp);
        http_resp_free(&r);
        return false;
    }
    http_resp_free(&r);
    *tmp_path = strdup(tmp);
    return true;
}

// Parse one release object span into r (version/release_id/draft/
// prerelease/assets); shared by the gitea and github adapters.
bool prov_release_from_json(const char *text, prov_release *r,
                            bool *draft, bool *prerelease)
{
    jobject o = {0};
    if (!jobject_parse(text, &o)) return false;
    int f;
    r->version = NULL;
    f = jobject_find(&o, "tag_name");
    if (f >= 0) json_span_string(o.vals[f], o.vends[f], &r->version);
    f = jobject_find(&o, "id");
    if (f >= 0 && !(o.vends[f] - o.vals[f] == 4
        && !memcmp(o.vals[f], "null", 4)))
        r->release_id = strndup(o.vals[f], (size_t)(o.vends[f] - o.vals[f]));
    bool b = false;
    *draft = false;
    *prerelease = false;
    f = jobject_find(&o, "draft");
    if (f >= 0) json_span_bool(o.vals[f], o.vends[f], draft);
    f = jobject_find(&o, "prerelease");
    if (f >= 0) json_span_bool(o.vals[f], o.vends[f], prerelease);
    (void)b;

    f = jobject_find(&o, "assets");
    r->assets = NULL;
    r->n = 0;
    if (f >= 0) {
        char *arr = strndup(o.vals[f], (size_t)(o.vends[f] - o.vals[f]));
        jspan *sp = NULL;
        int n = arr != NULL ? json_array_spans(arr, &sp) : -1;
        if (n > 0) r->assets = calloc((size_t)n, sizeof(*r->assets));
        for (int k = 0; arr != NULL && k < n && r->assets != NULL; k++) {
            char *el = strndup(sp[k].s, (size_t)(sp[k].e - sp[k].s));
            jobject ao = {0};
            if (el != NULL && jobject_parse(el, &ao)) {
                char *nm = NULL, *url = NULL;
                int g = jobject_find(&ao, "name");
                if (g >= 0) json_span_string(ao.vals[g], ao.vends[g], &nm);
                g = jobject_find(&ao, "browser_download_url");
                if (g >= 0) json_span_string(ao.vals[g], ao.vends[g], &url);
                if (nm != NULL && url != NULL && *url != '\0') {
                    r->assets[r->n].name = nm;
                    r->assets[r->n].url = url;
                    r->n++;
                } else {
                    free(nm);
                    free(url);
                }
            }
            jobject_free(&ao);
            free(el);
        }
        free(sp);
        free(arr);
    }
    jobject_free(&o);
    return true;
}
