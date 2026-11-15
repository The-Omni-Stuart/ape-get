#ifndef APEGET_PROVIDER_INTERNAL_H
#define APEGET_PROVIDER_INTERNAL_H

// provider-module internals: the token header builder and the rngit text
// parsers, shared across module files. Public surface: provider.h.

#include <stdbool.h>
#include <stddef.h>

#include "provider.h"
#include "state.h"

// provider/search.c: header name + value carrying the provider's token,
// scoped to the provider's own origin
bool prov_auth(const provider_entry *e, const char *download_url,
               const char **name, char *authv, size_t cap);

// provider/rngit_parse.c: opaque asset URLs and the ANSI-stripped release
// view grammar; the CLI runner provider/rngit.c parses and classifies
bool opaque_decode(const char *url, char **path, char **tag, char **name,
                   char *msg, size_t cap);
char *strip_ansi(const char *s);
bool parse_view(const char *vis, const char *path, prov_release *out);

// provider/routing.c: one publication policy over normalized release
// flags: drafts and scheduled (upcoming) releases are never installable;
// prereleases are eligible only on the beta channel.
bool prov_release_published(bool draft, bool upcoming, bool pre, bool beta);

// Page a release listing (url_base + "&page=N") through that policy,
// stopping at the first match, a short page, or after PROV_PAGE_BUDGET
// full pages — where the message reports the search stopped early.
bool prov_release_pages(const provider_entry *e, const char *url_base,
    const char *ctx, bool beta, int timeout_ms, prov_release *out,
    bool (*parse)(const char *el, prov_release *r, bool *draft,
                  bool *upcoming, bool *pre));

// Listing shape shared by the adapters: page size and fetch budget
#define PROV_PAGE_SIZE 20
#define PROV_PAGE_BUDGET 5

// Parse callbacks for prov_release_pages: the GitHub/Gitea object shape
// and the GitLab one (each fills the normalized draft/upcoming/pre flags)
bool prov_parse_gh_flags(const char *el, prov_release *r, bool *draft,
                         bool *upcoming, bool *pre);
bool prov_parse_gitlab_release(const char *el, prov_release *r, bool *draft,
                               bool *upcoming, bool *pre);

#endif
