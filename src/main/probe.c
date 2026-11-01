// Provider detection: a base URL is whichever of the three supported APIs
// answers its version-style endpoint with any 200.

#include "probe.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "http.h"

#define PROBE_TIMEOUT_MS 5000

static const struct {
    const char *type, *suffix, *api_suffix;
} ENDPOINTS[] = {
    { "gitea", "/api/v1/version", "/api/v1" },
    { "gitlab", "/api/v4/metadata", "/api/v4" },
    { "github", "/api/v3/rate_limit", "/api/v3" },
};

int probe_detect(const char *base, probe_hit *hits, int max)
{
    int n = 0;
    for (size_t i = 0; i < sizeof ENDPOINTS / sizeof ENDPOINTS[0] && n < max; i++) {
        http_resp r = {0};
        char url[512], err[128];
        if (snprintf(url, sizeof url, "%s%s", base, ENDPOINTS[i].suffix)
            >= (int)sizeof url)
            continue;
        if (!http_get(url, NULL, PROBE_TIMEOUT_MS, &r, err, sizeof err))
            continue;
        int ok = r.status == 200;
        http_resp_free(&r);
        if (!ok) continue;
        char *api = malloc(strlen(base) + strlen(ENDPOINTS[i].api_suffix) + 1);
        if (api == NULL) return n;
        strcpy(api, base);
        strcat(api, ENDPOINTS[i].api_suffix);
        hits[n].type = ENDPOINTS[i].type;
        hits[n].api = api;
        n++;
    }
    return n;
}
