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

#endif
