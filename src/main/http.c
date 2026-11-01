#include "http.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// API GETs (small JSON responses buffered whole); downloads stream
// through the separate http_download path.

char *http_url_encode(const char *s)
{
    size_t n = strlen(s);
    char *r = malloc(n * 3 + 1), *w = r;
    if (r == NULL) return NULL;
    for (const unsigned char *p = (const unsigned char *)s; *p; p++) {
        if (isalnum(*p) || strchr("-._~", (char)*p) != NULL) *w++ = (char)*p;
        else w += sprintf(w, "%%%02X", *p);
    }
    *w = '\0';
    return r;
}

bool http_get(const char *url, const char *bearer, int timeout_ms,
              http_resp *out, char *errbuf, size_t errcap)
{
    return http_get_hdr(url, "Authorization", bearer, timeout_ms, out,
        errbuf, errcap);
}

void http_resp_free(http_resp *r)
{
    free(r->body);
    free(r->location);
    memset(r, 0, sizeof(*r));
}
