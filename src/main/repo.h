#ifndef APEGET_REPO_H
#define APEGET_REPO_H

#include <stdbool.h>
#include <stddef.h>

// RepoRef parsing: provider grammar [a-z0-9][a-z0-9._-]* lower-cased, and
// an owner/repo-style path with at least two segments.

// Parses provider:owner/repo. Complaints print to stderr; on false the
// caller exits 1.
bool repo_parse_ref(const char *arg, char *prov, size_t provcap,
                    char *path, size_t pathcap);

#endif
