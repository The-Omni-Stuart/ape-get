// Raw response-block parsing shared by the buffered and streaming
// paths: header lookups, chunked decoding, numeric header probes.

#include "http/internal.h"

#include <stdlib.h>
#include <string.h>
#include <strings.h>

bool header_get(const char *head, const char *name,
                       const char **vs, const char **ve)
{
    size_t nl = strlen(name);
    for (const char *line = strstr(head, "\r\n"); line != NULL;
         line = strstr(line + 2, "\r\n")) {
        line += 2;
        const char *eol = strstr(line, "\r\n");
        if (eol == NULL) break;
        if ((size_t)(eol - line) > nl + 1
            && strncasecmp(line, name, nl) == 0 && line[nl] == ':') {
            const char *v = line + nl + 1;
            while (v < eol && (*v == ' ' || *v == '\t')) v++;
            *vs = v;
            *ve = eol;
            return true;
        }
    }
    return false;
}

// Chunked body decoder over [p, end); appends to *body via caller buffer.
bool chunked_copy(const char *p, const char *end, char **body, size_t *bn)
{
    while (p < end) {
        const char *nl = strstr(p, "\r\n");
        if (nl == NULL || nl > end - 2) return false;
        char sz[24] = "";
        size_t l = (size_t)(nl - p);
        if (l >= sizeof(sz)) return false;
        memcpy(sz, p, l);
        sz[strcspn(sz, ";")] = '\0';
        char *endp = NULL;
        long long n = strtoll(sz, &endp, 16);
        if (endp == sz || n < 0) return false;
        p = nl + 2;
        if (n == 0) return true;
        // bound before pointer math: p + n + 2 can wrap
        if (n > (long long)(end - p) - 2) return false;
        memcpy(*body + *bn, p, (size_t)n);
        *bn += (size_t)n;
        p += n + 2;                      // skip trailing CRLF
    }
    return false;
}

// Exists for the Transfer-Encoding probe: the chunked token may legally be
// spelled "Chunked", so token-in-block searches must ignore case.
const char *mem_find_ci(const char *h, size_t hn, const char *n)
{
    size_t nn = strlen(n);
    for (size_t k = 0; k + nn <= hn; k++)
        if (strncasecmp(h + k, n, nn) == 0) return h + k;
    return NULL;
}

const char *mem_find(const char *h, size_t hn, const char *n,
                           size_t nn)
{
    if (nn == 0 || hn < nn) return NULL;
    for (size_t i = 0; i + nn <= hn; i++)
        if (memcmp(h + i, n, nn) == 0) return h + i;
    return NULL;
}

// Numeric header lookup over the raw header block [raw, raw+hoff).
bool hdr_ll(const char *raw, size_t hoff, const char *name,
                   long long *v)
{
    size_t nl = strlen(name);
    const char *end = raw + hoff;
    for (const char *line = raw; line < end; ) {
        const char *e = mem_find(line, (size_t)(end - line), "\r\n", 2);
        if (e == NULL) break;
        size_t len = (size_t)(e - line);
        if (len > nl + 1 && strncasecmp(line, name, nl) == 0
            && line[nl] == ':') {
            const char *p = line + nl + 1;
            while (p < e && (*p == ' ' || *p == '\t')) p++;
            char num[21];
            size_t n = 0;
            while (p < e && *p >= '0' && *p <= '9' && n < 20) num[n++] = *p++;
            if (n > 0) { num[n] = '\0'; *v = strtoll(num, NULL, 10); return true; }
        }
        line = e + 2;
    }
    return false;
}
