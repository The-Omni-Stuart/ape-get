/* The GitHub adapter — the job of the original GithubClient:
   "interacting with the Github API … search for repositories, get the
   latest release assets". */

#include "provider.h"
#include "http.h"
#include "json.h"
#include "path.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* GitHub search (the original's GithubClient.searchRepoByName):
   GET <api>/search/repositories?q=<enc>&per_page=5, hits from
   items[].full_name, token sent as "Bearer <value>". */
bool provider_github_search(const provider_entry *e, const char *query,
                            int timeout_ms, prov_search *out)
{
    char *enc = http_url_encode(query);
    if (enc == NULL) return false;
    char *url = malloc(strlen(e->api) + strlen(enc) + 64);
    if (url != NULL)
        sprintf(url, "%s/search/repositories?q=%s&per_page=5", e->api, enc);
    free(enc);
    if (url == NULL) return false;
    bool ok = provider_search_exec(e, url, "items", false,
                                   "full_name", false, timeout_ms, out);
    free(url);
    return ok;
}

// GitHub releases: /releases/latest (stable), /releases?per_page=20 for
// beta (first non-draft; prereleases allowed), /releases/tags for --version.
bool provider_github_release(const provider_entry *e, const char *path,
                             const char *tag, bool beta, int timeout_ms,
                             prov_release *out)
{
    char *ctx = tag != NULL
        ? path_fmt("%s release %s", path, tag) : strdup(path);
    char *enc = tag != NULL ? http_url_encode(tag) : NULL;
    char *url = tag != NULL
        ? path_fmt("%s/repos/%s/releases/tags/%s", e->api, path, enc)
        : beta ? path_fmt("%s/repos/%s/releases?per_page=20", e->api, path)
               : path_fmt("%s/repos/%s/releases/latest", e->api, path);
    free(enc);
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

    if (!beta || tag != NULL) {
        bool draft = false, pre = false;
        ok = prov_release_from_json(body, out, &draft, &pre);
        free(body);
        if (!ok) goto invalid;
        out->reason = PROV_OK;
        return true;
    }

    jspan *sp = NULL;
    int n = json_array_spans(body, &sp);
    ok = false;
    for (int k = 0; k < n; k++) {
        char *el = strndup(sp[k].s, (size_t)(sp[k].e - sp[k].s));
        bool draft = false, pre = false;
        bool parsed = el != NULL
            && prov_release_from_json(el, out, &draft, &pre);
        free(el);
        if (!parsed) continue;
        if (draft) {
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
