#ifndef APEGET_PROVIDER_H
#define APEGET_PROVIDER_H

#include <stdbool.h>
#include "http.h"
#include "state.h"

// Shared provider surface: search dispatch plus the release-fetch and
// download routing shared by the gitea/github/gitlab/rngit adapters.

// Failure classes mirroring ProviderException.Reason.
typedef enum {
    PROV_OK = 0,
    PROV_NOT_FOUND,
    PROV_UNAUTHORIZED,
    PROV_RATE_LIMITED,
    PROV_TIMEOUT,
    PROV_UNREACHABLE,
    PROV_UNSUPPORTED,
    PROV_OTHER
} prov_reason;

typedef struct { char *full_name, *description; } prov_repo;

typedef struct {
    prov_repo *items;   // filled only on success
    int n;
    prov_reason reason; // PROV_OK on success
    char message[512];  // report text on failure
} prov_search;

void prov_search_free(prov_search *r);

// HTTP status -> reason + message in search context (action "API",
// not-found "resource not found"); label is the provider type label.
prov_reason prov_classify(const char *label, int status,
                          char *msg, size_t cap);

// Transport failure (errkind is an http_err class) -> message for action
// "request"; host from url, io_message verbatim when unclassifiable.
prov_reason prov_net_error(const char *label, int errkind, const char *url,
                           int timeout_s, const char *io_message,
                           char *msg, size_t cap);

// Token value from the provider's token_env, malloc'd; NULL when the
// variable is unset or empty.
char *provider_token(const provider_entry *e);

// Hits from a JSON array of repo objects; name_key picks the full-name member
// ("full_name", gitlab "path_with_namespace"), filter_null_name drops misses.
bool prov_parse_hits(const char *array_text, const char *name_key,
                     bool filter_null_name, prov_repo **items, int *n);

// One search round-trip: GET url, classify failures, parse the repo array
// (member array_key, or top-level when top_array); false fills out's report.
bool provider_search_exec(const provider_entry *e, const char *url,
                          const char *array_key,
                          bool top_array, const char *name_key,
                          bool filter_null_name, int timeout_ms,
                          prov_search *out);

// Type label as it appears in messages.
const char *provider_label(const provider_entry *e);

// Per-type search (dispatched by the caller; each resolves its own token);
// timeout_ms is the api timeout from config.
bool provider_gitea_search(const provider_entry *e, const char *query,
                           int timeout_ms, prov_search *out);
bool provider_github_search(const provider_entry *e, const char *query,
                            int timeout_ms, prov_search *out);
bool provider_gitlab_search(const provider_entry *e, const char *query,
                            int timeout_ms, prov_search *out);

// rngit has no search: fills out as UNSUPPORTED with its message.
void provider_search_unsupported(prov_search *out);

// ---- install/update side ----

typedef struct { char *name, *url; } prov_asset;

typedef struct {
    char *version;      // tag name; NULL when the record omits it
    char *release_id;   // raw JSON number text or NULL
    prov_asset *assets; // assets with a download URL, in release order
    int n;
    prov_reason reason; // PROV_OK on success
    char message[512];
} prov_release;

void prov_release_free(prov_release *r);

// Parse one GitHub/Gitea release object (span or full document) into r,
// reporting draft/prerelease flags; assets keep release order.
bool prov_release_from_json(const char *text, prov_release *r,
                            bool *draft, bool *prerelease);

// GET url with the type-appropriate token header; classify failures like
// ProviderHttp, per context; *body is malloc'd on success.
bool prov_api_json(const provider_entry *e, const char *url, int timeout_ms,
                   const char *notfound, const char *context, char **body,
                   prov_reason *reason, char *msg, size_t cap);

// Fetch the latest published release (tag == NULL, channel beta keeps
// prereleases) or one tag. Dispatched by provider type.
bool provider_release_fetch(const provider_entry *e, const char *path,
                            const char *tag, bool beta, int timeout_ms,
                            prov_release *out);

bool provider_gitea_release(const provider_entry *e, const char *path,
                            const char *tag, bool beta, int timeout_ms,
                            prov_release *out);
bool provider_github_release(const provider_entry *e, const char *path,
                             const char *tag, bool beta, int timeout_ms,
                             prov_release *out);
bool provider_gitlab_release(const provider_entry *e, const char *path,
                             const char *tag, bool beta, int timeout_ms,
                             prov_release *out);

// rngit: view parses the CLI's release listing; downloads
// arrive via `rngit fetch` into the staging path.
bool provider_rngit_release(const provider_entry *e, const char *path,
                            const char *tag, bool beta, int timeout_ms,
                            prov_release *out);
bool provider_rngit_download(const provider_entry *e, const char *url,
                             const char *name, int timeout_ms,
                             long long max_bytes,
                             const char *dest, char **tmp_path,
                             char *msg, size_t cap);

// Download one asset URL to a staged temp file; *msg carries the failure text.
// dest (exact path) streams, never buffering; NULL buffers into a temp file.
// max_bytes > 0 caps the streamed body (hostile-padding cap, spec I2):
// the HTTP route aborts mid-stream; the rngit route can only gate after
// its fetch subprocess (radio already spent) but never copies the excess.
bool provider_download(const provider_entry *e, const char *url,
                       const char *name, int timeout_ms, long long max_bytes,
                       const char *dest,
                       http_prog cb, void *ud,
                       char **tmp_path, char *msg, size_t cap);

#endif
