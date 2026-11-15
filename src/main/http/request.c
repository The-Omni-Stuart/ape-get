// The buffered GET path: one request per connection, the response read
// whole through the shared framing machine, and the redirect loop that
// drops the header cross-origin.

#include "http.h"
#include "http/internal.h"
#include "crypto.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

#define MAX_RESP (32UL << 20)

struct buf_io {
    tls_conn *c;
    char *p;                    // growable body accumulator
    size_t len, cap, max;
    long long total;            // declared total for the progress sink
    http_prog cb;
    void *ud;
};

static int buf_read(void *v, char *b, size_t n)
{
    struct buf_io *x = v;
    return tls_read(x->c, b, n);
}

static bool buf_sink(void *v, const char *p, size_t n)
{
    struct buf_io *x = v;
    if (x->len + n > x->max) return false;
    if (x->len + n + 1 > x->cap) {
        size_t need = x->len + n + 1;
        size_t want = x->cap * 2;
        if (want < need) want = need;
        if (want > x->max + 1) want = x->max + 1;
        char *nr = realloc(x->p, want);
        if (nr == NULL) return false;   // current contents stay owned
        x->p = nr;
        x->cap = want;
    }
    memcpy(x->p + x->len, p, n);
    x->len += n;
    x->p[x->len] = '\0';
    if (x->cb != NULL) x->cb(x->len, x->total, x->ud);
    return true;
}

static bool http_get_once(const char *url, const char *hdr_line,
                          int timeout_ms, http_prog cb, void *ud,
                          http_resp *out, char *errbuf, size_t errcap)
{
    memset(out, 0, sizeof(*out));
    out->errkind = HTTP_ERR_OTHER;
    char host[256], path[2048];
    int port = 443;
    if (!split_url(url, host, sizeof(host), &port, path, sizeof(path),
                   errbuf, errcap))
        return false;

    const char *ca_file = getenv("APEGET_CA_BUNDLE");
    if (ca_file != NULL && *ca_file == '\0') ca_file = NULL;
    tls_err tk = TLS_OTHER;
    tls_conn *c = open_conn(host, port, ca_file,
                              (const char *)apeget_ca_der, apeget_ca_der_len,
                              timeout_ms, &tk, errbuf, errcap);
    if (c == NULL) {
        out->errkind = tk == TLS_TIMEOUT ? HTTP_ERR_TIMEOUT
            : tk == TLS_DNS ? HTTP_ERR_DNS
            : tk == TLS_CONNECT ? HTTP_ERR_CONNECT : HTTP_ERR_OTHER;
        return false;
    }

    char hostp[300];
    bool ok = url_authority(host, port, hostp, sizeof hostp);
    char *req = ok
        ? malloc(4096 + (hdr_line != NULL ? strlen(hdr_line) : 0)) : NULL;
    if (req == NULL) {
        snprintf(errbuf, errcap, "request failed");
        tls_close(c);
        return false;
    }
    int n = snprintf(req, 4096 + (hdr_line != NULL ? strlen(hdr_line) : 0),
        "GET %s HTTP/1.1\r\n"
        "Host: %s\r\n"
        "User-Agent: ape-get\r\n"
        "Accept: application/json\r\n"
        "Connection: close\r\n"
        "%s"
        "\r\n",
        path, hostp, hdr_line != NULL ? hdr_line : "");
    ok = n > 0 && tls_write_all(c, req, (size_t)n) == 0;
    free(req);
    if (!ok) {
        snprintf(errbuf, errcap, "request failed");
        tls_close(c);
        return false;
    }

    char *buf = malloc(HTTP_BUF);
    if (buf == NULL) {
        snprintf(errbuf, errcap, "connection to %s failed", hostp);
        tls_close(c);
        return false;
    }
    size_t have = 0;
    resp_head h;
    int kind = HTTP_ERR_OTHER;
    if (!resp_head_read(c, hostp, buf, HTTP_BUF, &have, &h, &kind,
                        errbuf, errcap)) {
        out->errkind = kind;
        free(buf);
        tls_close(c);
        return false;
    }
    out->status = h.status;
    out->location = h.location;

    struct buf_io x = { .c = c, .cb = cb, .ud = ud, .max = MAX_RESP,
        .total = !h.chunked && h.clen >= 0 ? h.clen : -1 };
    if (h.clen > (long long)MAX_RESP - 1) {
        snprintf(errbuf, errcap, "response exceeds the %zu MiB cap",
            MAX_RESP >> 20);
        goto fail_body;
    }
    x.cap = h.clen >= 0 ? (size_t)h.clen + 1 : 4096;
    x.p = malloc(x.cap);
    if (x.p == NULL) {
        snprintf(errbuf, errcap, "out of memory");
        goto fail_body;
    }
    x.p[0] = '\0';

    resp_io io = { buf_read, buf_sink, &x };
    long long written = 0;
    resp_stop st = resp_read_body(&io, buf, &have, HTTP_BUF, &h,
                                  MAX_RESP, &written);
    free(buf);
    if (st == RESP_DONE) {
        out->body = x.p;
        out->len = x.len;
        tls_close(c);
        return true;
    }
    free(x.p);
    free(out->location);
    out->location = NULL;
    snprintf(errbuf, errcap,
        st == RESP_MALFORMED ? "malformed response from %s"
                             : "connection to %s failed", hostp);
    out->errkind = st == RESP_TIMEOUT ? HTTP_ERR_TIMEOUT : HTTP_ERR_OTHER;
    tls_close(c);
    return false;

fail_body:
    free(h.location);
    out->location = NULL;
    free(buf);
    tls_close(c);
    return false;
}

// Redirect policy: GET-only, up to 20; the caller's header drops on host
// changes so credentials never follow a redirect to a third-party host.
bool http_get_hdr(const char *url, const char *name, const char *value,
                  int timeout_ms, http_resp *out, char *errbuf, size_t errcap)
{
    return http_get_hdr_prog(url, name, value, timeout_ms, NULL, NULL,
                             out, errbuf, errcap);
}

bool http_get_hdr_prog(const char *url, const char *name, const char *value,
                       int timeout_ms, http_prog cb, void *ud,
                       http_resp *out, char *errbuf, size_t errcap)
{
    char hdr[1300];
    if (value != NULL) snprintf(hdr, sizeof(hdr), "%s: %s\r\n", name, value);
    char *cur = strdup(url);
    if (cur == NULL) {
        snprintf(errbuf, errcap, "out of memory");
        return false;
    }
    for (int hop = 0; hop <= 20; hop++) {
        if (!http_get_once(cur, value != NULL ? hdr : NULL, timeout_ms,
                           cb, ud, out, errbuf, errcap)) {
            free(cur);
            return false;
        }
        bool redir = out->status == 301 || out->status == 302
            || out->status == 303 || out->status == 307 || out->status == 308;
        if (!redir || out->location == NULL || hop == 20) break;

        char *next = url_join(cur, out->location);
        free(out->location);
        out->location = NULL;
        if (next == NULL) break;        // unresolvable Location: report as-is
        if (value != NULL && !same_origin(cur, next)) value = NULL;
        free(cur);
        cur = next;
        free(out->body);
        out->body = NULL;
        out->len = 0;
    }
    free(cur);
    return true;
}
