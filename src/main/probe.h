#ifndef APEGET_PROBE_H
#define APEGET_PROBE_H

#include <stddef.h>

// Provider detection: one pass over the three version endpoints of a
// base URL.

typedef struct {
    const char *type;   // "gitea" | "gitlab" | "github", the fixed probe order
    char *api;          // malloc'd candidate API base, e.g. <base>/api/v1
} probe_hit;

// Probe gitea, gitlab and github version endpoints in turn, 5 s each; any
// 200 fills the next hit (silent misses). Caller frees each hit.api.
int probe_detect(const char *base, probe_hit *hits, int max);

#endif
