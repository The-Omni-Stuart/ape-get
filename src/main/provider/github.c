/* The GitHub adapter — the job of the original GithubClient:
   "interacting with the Github API … get the latest release assets". */

#include "provider.h"
#include "provider/internal.h"
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

// GitHub releases: /releases/latest for stable (the server-side latest
// is published and non-prerelease), the shared paginated listing for
// beta, /releases/tags for --version — which must name a published
// release, so drafts are rejected like every other provider.
bool provider_github_release(const provider_entry *e, const char *path,
                             const char *tag, bool beta, int timeout_ms,
                             prov_release *out)
{
    char *ctx = tag != NULL
        ? path_fmt("%s release %s", path, tag) : strdup(path);
    char *enc = tag != NULL ? http_url_encode(tag) : NULL;
    char *url = tag != NULL
        ? path_fmt("%s/repos/%s/releases/tags/%s", e->api, path, enc)
        : beta ? path_fmt("%s/repos/%s/releases?per_page=%d",
                          e->api, path, PROV_PAGE_SIZE)
               : path_fmt("%s/repos/%s/releases/latest", e->api, path);
    free(enc);
    if (url == NULL) { free(ctx); return false; }

    if (tag == NULL && beta) {
        bool ok = prov_release_pages(e, url, ctx != NULL ? ctx : path,
                                     true, timeout_ms, out,
                                     prov_parse_gh_flags);
        free(url);
        free(ctx);
        return ok;
    }

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

    bool draft = false, pre = false;
    ok = prov_release_from_json(body, out, &draft, &pre);
    free(body);
    if (!ok) goto invalid;
    if (tag != NULL && draft) {
        prov_release_free(out);
        memset(out, 0, sizeof(*out));
        out->reason = PROV_OTHER;
        snprintf(out->message, sizeof(out->message),
            "Release %s is a draft", tag);
        return false;
    }
    out->reason = PROV_OK;
    return true;

invalid:
    out->reason = PROV_OTHER;
    snprintf(out->message, sizeof(out->message), "invalid response");
    return false;
}
