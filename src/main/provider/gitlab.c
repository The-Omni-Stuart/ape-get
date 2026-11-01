#include "provider.h"
#include "http.h"
#include "json.h"
#include "path.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// GitLab search: GET <api>/projects?search=<enc>&per_page=5&simple=true,
// a top-level array, hits from path_with_namespace (missing dropped).
bool provider_gitlab_search(const provider_entry *e, const char *query,
                            int timeout_ms, prov_search *out)
{
    char *enc = http_url_encode(query);
    if (enc == NULL) return false;
    char *url = malloc(strlen(e->api) + strlen(enc) + 64);
    if (url != NULL)
        sprintf(url, "%s/projects?search=%s&per_page=5&simple=true",
                e->api, enc);
    free(enc);
    if (url == NULL) return false;
    bool ok = provider_search_exec(e, url, NULL, true,
                                   "path_with_namespace", true, timeout_ms, out);
    free(url);
    return ok;
}

// ---- GitLab releases ----

// One GitLab release object: tag_name, upcoming_release, assets from
// assets.links[] (name required; direct_asset_url ?: url). No release id.
static bool gitlab_release_from_json(const char *text, prov_release *r,
                                     bool *upcoming)
{
    jobject o = {0};
    if (!jobject_parse(text, &o)) return false;
    *upcoming = false;
    int f = jobject_find(&o, "tag_name");
    if (f >= 0) json_span_string(o.vals[f], o.vends[f], &r->version);
    f = jobject_find(&o, "upcoming_release");
    if (f >= 0) json_span_bool(o.vals[f], o.vends[f], upcoming);

    f = jobject_find(&o, "assets");
    if (f >= 0) {
        char *at = strndup(o.vals[f], (size_t)(o.vends[f] - o.vals[f]));
        jobject ao = {0};
        if (at != NULL && jobject_parse(at, &ao)) {
            int li = jobject_find(&ao, "links");
            if (li >= 0) {
                char *lt = strndup(ao.vals[li],
                    (size_t)(ao.vends[li] - ao.vals[li]));
                jspan *sp = NULL;
                int n = lt != NULL ? json_array_spans(lt, &sp) : -1;
                if (n > 0) r->assets = calloc((size_t)n,
                    sizeof(*r->assets));
                for (int k = 0; lt != NULL && k < n && r->assets != NULL;
                     k++) {
                    char *el = strndup(sp[k].s,
                        (size_t)(sp[k].e - sp[k].s));
                    jobject lo = {0};
                    if (el != NULL && jobject_parse(el, &lo)) {
                        char *nm = NULL, *url = NULL, *direct = NULL;
                        int g = jobject_find(&lo, "name");
                        if (g >= 0)
                            json_span_string(lo.vals[g], lo.vends[g], &nm);
                        g = jobject_find(&lo, "url");
                        if (g >= 0)
                            json_span_string(lo.vals[g], lo.vends[g], &url);
                        g = jobject_find(&lo, "direct_asset_url");
                        if (g >= 0)
                            json_span_string(lo.vals[g], lo.vends[g],
                                &direct);
                        char *dl = direct != NULL && *direct != '\0'
                            ? direct
                            : url != NULL && *url != '\0' ? url : NULL;
                        if (nm != NULL && dl != NULL) {
                            r->assets[r->n].name = nm;
                            r->assets[r->n].url = strdup(dl);
                            if (r->assets[r->n].url == NULL) {
                                free(nm);
                            } else {
                                nm = NULL;
                                dl = NULL;
                                r->n++;
                            }
                        }
                        free(nm);
                        free(url);
                        free(direct);
                    }
                    jobject_free(&lo);
                    free(el);
                }
                free(sp);
                free(lt);
            }
        }
        jobject_free(&ao);
        free(at);
    }
    jobject_free(&o);
    return true;
}

bool provider_gitlab_release(const provider_entry *e, const char *path,
                             const char *tag, bool beta, int timeout_ms,
                             prov_release *out)
{
    (void)beta; // GitLab releases have no channel distinction
    char *enc = http_url_encode(path);
    char *tenc = tag != NULL
        ? http_url_encode(tag) : NULL;
    char *ctx = tag != NULL
        ? path_fmt("%s release %s", path, tag) : strdup(path);
    char *url = enc != NULL && (tag == NULL || tenc != NULL)
        ? (tag != NULL
            ? path_fmt("%s/projects/%s/releases/%s", e->api, enc, tenc)
            : path_fmt("%s/projects/%s/releases?per_page=20", e->api, enc))
        : NULL;
    free(enc);
    free(tenc);
    if (url == NULL) { free(ctx); return false; }

    char *body = NULL;
    prov_reason reason = PROV_OK;
    bool ok = prov_api_json(e, url, timeout_ms, "resource not found",
        ctx != NULL ? ctx : path, &body, &reason,
        out->message, sizeof(out->message));
    free(url);
    free(ctx);
    if (!ok) {
        out->reason = reason;
        return false;
    }

    bool upcoming = false;
    if (tag != NULL) {
        ok = body != NULL && gitlab_release_from_json(body, out, &upcoming);
        free(body);
        if (!ok) goto invalid;
        if (upcoming) {
            prov_release_free(out);
            memset(out, 0, sizeof(*out));
            out->reason = PROV_OTHER;
            snprintf(out->message, sizeof(out->message),
                "Release %s is not published yet", tag);
            return false;
        }
        out->reason = PROV_OK;
        return true;
    }

    jspan *sp = NULL;
    int n = body != NULL ? json_array_spans(body, &sp) : -1;
    ok = false;
    for (int k = 0; k < n; k++) {
        char *el = strndup(sp[k].s, (size_t)(sp[k].e - sp[k].s));
        bool parsed = el != NULL
            && gitlab_release_from_json(el, out, &upcoming);
        free(el);
        if (!parsed) continue;
        if (upcoming) {
            prov_release_free(out);
            memset(out, 0, sizeof(*out));
            continue;
        }
        ok = true;
        break;
    }
    free(sp);
    free(body);
    if (!ok) {
        prov_release_free(out);
        memset(out, 0, sizeof(*out));
        out->reason = PROV_NOT_FOUND;
        snprintf(out->message, sizeof(out->message), "no published release");
        return false;
    }
    out->reason = PROV_OK;
    return true;

invalid:
    out->reason = PROV_OTHER;
    snprintf(out->message, sizeof(out->message), "invalid response");
    return false;
}
