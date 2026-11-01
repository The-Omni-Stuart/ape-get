#ifndef APEGET_STATE_INTERNAL_H
#define APEGET_STATE_INTERNAL_H

// Installed-manifest helpers shared between the module files: the naming
// rules gate validity, the value readers feed the scan. Public: manifest.h.

#include <stdbool.h>

#include "json.h"

// state/manifest.c: raw member span as a fresh string; repo usable as a
// state directory name
char *dup_span(const char *b, const char *e);
bool repo_dir_ok(const char *provider, const char *repo);

// state/manifest_validate.c: string member (NULL when absent, *bad when
// the type is wrong); record validity over the kept fields
bool str_member(const jobject *o, const char *key, char **out, bool *bad);
bool manifest_is_valid(const jobject *root, char **path);

#endif
