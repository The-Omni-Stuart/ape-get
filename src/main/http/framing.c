// One strict response framing machine shared by both request paths: the
// status/header-block reader and the incremental body decoder. The two
// sinks (memory for the buffered path, fd for the streamed path) differ;
// the framing decisions they enforce are identical.

#include "http/internal.h"
#include "crypto.h"
#include "http.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

static const char *mem_find(const char *h, size_t hn, const char *n,
                            size_t nn)
{
    if (nn == 0 || hn < nn) return NULL;
    for (size_t i = 0; i + nn <= hn; i++)
        if (memcmp(h + i, n, nn) == 0) return h + i;
    return NULL;
}

static bool is_tchar(unsigned char c)
{
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9')
        || strchr("!#$%&'*+-.^_`|~", (char)c) != NULL;
}

// Header line grammar: tchar token, ':', value without control chars.
// A line starting with whitespace is an obs-fold continuation: rejected.
static bool head_line_ok(const char *line, size_t n)
{
    const char *col = memchr(line, ':', n);
    if (col == NULL || col == line) return false;
    for (const char *p = line; p < col; p++)
        if (!is_tchar((unsigned char)*p)) return false;
    for (const char *p = col + 1; p < line + n; p++) {
        unsigned char v = (unsigned char)*p;
        if (v != '\t' && (v < 0x20 || v >= 0x7f)) return false;
    }
    return true;
}

static bool name_is(const char *name, size_t nlen, const char *lower)
{
    return nlen == strlen(lower) && strncasecmp(name, lower, nlen) == 0;
}

static bool status_line(const char *line, size_t n, int *status)
{
    if (n < 12 || memcmp(line, "HTTP/1.", 7) != 0) return false;
    if (line[7] != '0' && line[7] != '1') return false;
    if (line[8] != ' ') return false;
    int v = 0;
    for (int k = 0; k < 3; k++) {
        if (line[9 + k] < '0' || line[9 + k] > '9') return false;
        v = v * 10 + (line[9 + k] - '0');
    }
    if (n > 12 && line[12] != ' ') return false;
    if (v < 100) return false;
    *status = v;
    return true;
}

static void trim_ows(const char **v, size_t *n)
{
    while (*n > 0 && (**v == ' ' || **v == '\t')) { (*v)++; (*n)--; }
    while (*n > 0 && ((*v)[*n - 1] == ' ' || (*v)[*n - 1] == '\t')) (*n)--;
}

// Exact digits (after OWS trimming); rejects signs, spaces and overflow.
static bool cl_parse(const char *v, size_t n, long long *out)
{
    trim_ows(&v, &n);
    if (n == 0 || n > 18) return false;
    long long x = 0;
    for (size_t k = 0; k < n; k++) {
        if (v[k] < '0' || v[k] > '9') return false;
        x = x * 10 + (v[k] - '0');
    }
    *out = x;
    return true;
}

// Transfer-Encoding must be exactly the coding tokens; anything but
// "chunked" is undecodable here, so the whole response is refused.
static bool te_is_chunked(const char *v, size_t n)
{
    const char *end = v + n;
    while (v < end) {
        const char *c = memchr(v, ',', (size_t)(end - v));
        size_t tn = c != NULL ? (size_t)(c - v) : (size_t)(end - v);
        trim_ows(&v, &tn);
        if (tn != 7 || strncasecmp(v, "chunked", 7) != 0) return false;
        if (c == NULL) break;
        v = c + 1;
        if (v == end) return false;             // trailing comma: empty token
    }
    return true;
}

bool resp_parse_head(const char *block, size_t n, resp_head *h,
                     char *errbuf, size_t errcap)
{
    memset(h, 0, sizeof(*h));
    h->clen = -1;
    if (n < 4 || memcmp(block + n - 4, "\r\n\r\n", 4) != 0) {
        if (errbuf != NULL && errcap > 0)
            snprintf(errbuf, errcap, "malformed status line");
        return false;
    }
    const char *nl = mem_find(block, n, "\r\n", 2);
    if (nl == NULL || !status_line(block, (size_t)(nl - block), &h->status)) {
        if (errbuf != NULL && errcap > 0)
            snprintf(errbuf, errcap, "malformed status line");
        return false;
    }
    const char *p = nl + 2, *stop = block + n - 2;
    const char *cl_v = NULL;
    size_t cl_n = 0;
    bool te_seen = false;
    while (p < block + n) {
        nl = mem_find(p, (size_t)(block + n - p), "\r\n", 2);
        if (nl == NULL) goto bad;
        size_t ln = (size_t)(nl - p);
        if (ln == 0) { p = nl + 2; break; }
        if (!head_line_ok(p, ln)) {
            if (errbuf != NULL && errcap > 0)
                snprintf(errbuf, errcap, "malformed header");
            goto bad;
        }
        size_t nlen = (size_t)((const char *)memchr(p, ':', ln) - p);
        const char *v = p + nlen + 1;
        size_t vn = ln - nlen - 1;
        if (name_is(p, nlen, "content-length")) {
            if (cl_v != NULL
                && (vn != cl_n || memcmp(cl_v, v, cl_n) != 0))
                goto bad;                           // conflicting CL
            cl_v = v;
            cl_n = vn;
        } else if (name_is(p, nlen, "transfer-encoding")) {
            if (te_seen) goto bad;                  // no stacked TEs
            te_seen = true;
            if (!te_is_chunked(v, vn)) {
                if (errbuf != NULL && errcap > 0)
                    snprintf(errbuf, errcap, "undecodable transfer-coding");
                goto bad;
            }
            h->chunked = true;
        } else if (name_is(p, nlen, "location") && h->location == NULL) {
            trim_ows(&v, &vn);
            h->location = strndup(v, vn);
            if (h->location == NULL) goto bad;
        }
        p = nl + 2;
    }
    if (p != stop + 2) goto bad;
    if (cl_v != NULL) {
        if (!cl_parse(cl_v, cl_n, &h->clen)) goto bad;
        if (h->chunked) goto bad;                   // conflicting framing
    }
    h->bodyless = h->status < 200 || h->status == 204 || h->status == 304;
    return true;
bad:
    free(h->location);
    memset(h, 0, sizeof(*h));
    h->clen = -1;
    return false;
}

// Read the header block, parse it strictly and slide the already-read
// body prefix to the front of buf (*have). errbuf messages are the two
// fixed transport texts; *kind classifies for the provider layer.
bool resp_head_read(tls_conn *c, const char *hostp, char *buf, size_t cap,
                    size_t *have, resp_head *h, int *kind,
                    char *errbuf, size_t errcap)
{
    size_t rn = 0;
    for (;;) {
        int r = tls_read(c, buf + rn, cap - rn);
        if (r == -2) {
            *kind = HTTP_ERR_TIMEOUT;
            snprintf(errbuf, errcap, "connection to %s failed", hostp);
            return false;
        }
        if (r < 0) {
            *kind = HTTP_ERR_OTHER;
            snprintf(errbuf, errcap, "connection to %s failed", hostp);
            return false;
        }
        if (r == 0) {
            *kind = HTTP_ERR_OTHER;
            snprintf(errbuf, errcap, "malformed response from %s", hostp);
            return false;
        }
        rn += (size_t)r;
        const char *hd = mem_find(buf, rn, "\r\n\r\n", 4);
        if (hd != NULL) {
            size_t hoff = (size_t)(hd + 4 - buf);
            if (!resp_parse_head(buf, hoff, h, errbuf, errcap)) {
                *kind = HTTP_ERR_OTHER;
                snprintf(errbuf, errcap, "malformed response from %s",
                         hostp);
                return false;
            }
            *have = rn - hoff;
            if (*have > 0) memmove(buf, buf + hoff, *have);
            return true;
        }
        if (rn == cap) {
            *kind = HTTP_ERR_OTHER;
            snprintf(errbuf, errcap, "malformed response from %s", hostp);
            return false;
        }
    }
}

// Strict hex chunk size, optional ";chunk-ext" ignored.
static bool chunk_size(const char *p, size_t n, long long *out)
{
    size_t i = 0;
    while (i < n && p[i] != ';') i++;
    if (i == 0 || i > 15) return false;
    long long v = 0;
    for (size_t k = 0; k < i; k++) {
        char ch = p[k];
        int d = ch >= '0' && ch <= '9' ? ch - '0'
            : ch >= 'a' && ch <= 'f' ? ch - 'a' + 10
            : ch >= 'A' && ch <= 'F' ? ch - 'A' + 10 : -1;
        if (d < 0) return false;
        v = (v << 4) | d;
    }
    *out = v;
    return true;
}

resp_stop resp_read_body(resp_io *io, char *buf, size_t *have, size_t cap,
                         const resp_head *h, long long max_bytes,
                         long long *written)
{
    size_t have_n = *have;
    long long done = 0;
    *written = 0;
    #define BODY_DROP(k) do {                                           \
        memmove(buf, buf + (size_t)(k), have_n - (size_t)(k));          \
        have_n -= (size_t)(k);                                          \
    } while (0)

    if (h->bodyless) { *have = 0; return RESP_DONE; }

    if (!h->chunked) {
        long long clen = h->clen;
        for (;;) {
            if (clen >= 0 && done >= clen) break;
            size_t take = have_n;
            if (clen >= 0 && (long long)take > clen - done)
                take = (size_t)(clen - done);
            if (max_bytes > 0 && done + (long long)take > max_bytes) {
                *written = done;
                return RESP_TOOBIG;
            }
            if (take > 0) {
                if (!io->sink(io->ud, buf, take)) {
                    *written = done;
                    return RESP_SINKERR;
                }
                done += (long long)take;
                BODY_DROP(take);
            }
            if (clen >= 0 && done >= clen) break;
            int r = io->read(io->ud, buf + have_n, cap - have_n);
            if (r > 0) { have_n += (size_t)r; continue; }
            if (r == -2) { *written = done; return RESP_TIMEOUT; }
            if (r == 0 && clen < 0) break;      // clean EOF: complete
            *written = done;
            return RESP_TRUNC;                  // early EOF or error
        }
        *written = done;
        *have = 0;
        return RESP_DONE;
    }

    int st = 0;                                 // 0 size, 1 data, 2 CRLF, 3 trailer
    long long want = 0;
    for (;;) {
        bool progressed = false;
        if (st == 0 || st == 3) {
            const char *nl = mem_find(buf, have_n, "\r\n", 2);
            if (nl != NULL) {
                size_t ln = (size_t)(nl - buf);
                if (st == 0) {
                    if (!chunk_size(buf, ln, &want)) return RESP_MALFORMED;
                    BODY_DROP(ln + 2);
                    if (want == 0) st = 3;
                    else st = 1;
                } else {
                    if (ln == 0) { *written = done; *have = 0; return RESP_DONE; }
                    if (!head_line_ok(buf, ln)) return RESP_MALFORMED;
                    BODY_DROP(ln + 2);
                }
                progressed = true;
            } else if (have_n >= cap) return RESP_MALFORMED;
        } else if (st == 1) {
            size_t take = (long long)have_n < want ? have_n : (size_t)want;
            if (max_bytes > 0 && done + (long long)take > max_bytes) {
                *written = done;
                return RESP_TOOBIG;
            }
            if (take > 0) {
                if (!io->sink(io->ud, buf, take)) {
                    *written = done;
                    return RESP_SINKERR;
                }
                done += (long long)take;
                want -= (long long)take;
                BODY_DROP(take);
                progressed = true;
            }
            if (want == 0) st = 2;
        } else if (have_n >= 1) {               // chunk must end in CRLF
            if (buf[0] != '\r') return RESP_MALFORMED;
            if (have_n < 2) {
                int r = io->read(io->ud, buf + have_n, cap - have_n);
                if (r > 0) { have_n += (size_t)r; continue; }
                if (r == -2) { *written = done; return RESP_TIMEOUT; }
                return RESP_TRUNC;
            }
            if (buf[1] != '\n') return RESP_MALFORMED;
            BODY_DROP(2);
            st = 0;
            progressed = true;
        }
        if (progressed) continue;
        if (have_n >= cap) return RESP_MALFORMED;
        int r = io->read(io->ud, buf + have_n, cap - have_n);
        if (r > 0) { have_n += (size_t)r; continue; }
        if (r == -2) { *written = done; return RESP_TIMEOUT; }
        return RESP_TRUNC;                      // stalled, errored or truncated
    }
    #undef BODY_DROP
}
