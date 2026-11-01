#ifndef APEGET_PIPELINE_CHECKSUMS_H
#define APEGET_PIPELINE_CHECKSUMS_H

#include <stdbool.h>

// Checksum-file parsing: pure, so the fuzz target links it without the
// install pipeline's link closure.

// Find a usable sha256 entry: fills has_entries/ambiguous/sha; *sha is
// malloc'd when found (caller frees).
void checksum_lookup(const char *content, const char *filename,
                     bool *has_entries, bool *ambiguous, char **sha);

#endif
