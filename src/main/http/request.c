// The buffered GET path: one request per connection, the response
// drained whole, and the redirect loop that drops the header cross-origin.

#include "http.h"
#include "http/internal.h"
#include "crypto.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

#define MAX_RESP (32UL << 20)

bool split_url(const char *url, char *host, size_t hostcap, int *port,
                      char *path, size_t pathcap, char *errbuf, size_t errcap)
{
    if (strncasecmp(url, "https://", 8) != 0) {
        snprintf(errbuf, errcap, "https required for %s", url);
        return false;
    }
    const char *p = url + 8, *slash = strchr(p, '/');
    const char *hostend = slash != NULL ? slash : p + strlen(p);
    const char *colon = memchr(p, ':', (size_t)(hostend - p));
    *port = 443;
    size_t hlen = colon != NULL ? (size_t)(colon - p) : (size_t)(hostend - p);
    if (hlen == 0 || hlen >= hostcap) {
        snprintf(errbuf, errcap, "invalid URL host");
        return false;
    }
    memcpy(host, p, hlen);
    host[hlen] = '\0';
    if (colon != NULL) *port = atoi(colon + 1);
    if (slash == NULL) snprintf(path, pathcap, "/");
    else snprintf(path, pathcap, "%s", slash);
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
    if (port == 443) snprintf(hostp, sizeof(hostp), "%s", host);
    else snprintf(hostp, sizeof(hostp), "%s:%d", host, port);
    char *req = malloc(4096 + (hdr_line != NULL ? strlen(hdr_line) : 0));
    int n = snprintf(req, 4096 + (hdr_line != NULL ? strlen(hdr_line) : 0),
        "GET %s HTTP/1.1\r\n"
        "Host: %s\r\n"
        "User-Agent: ape-get\r\n"
        "Accept: application/json\r\n"
        "Connection: close\r\n"
        "%s"
        "\r\n",
        path, hostp, hdr_line != NULL ? hdr_line : "");
    bool ok = n > 0 && tls_write_all(c, req, (size_t)n) == 0;
    free(req);
    if (!ok) {
        snprintf(errbuf, errcap, "request failed");
        tls_close(c);
        return false;
    }

    // Drain the whole response: server closes because we sent Connection: close.
    size_t cap = 1 << 15, rn = 0;
    size_t hoff = 0;
    long long prog_total = -1;
    char *raw = malloc(cap);
    ok = raw != NULL;
    while (ok) {
        if (rn + 4096 > cap) {
            if (cap >= MAX_RESP) { ok = false; break; }
            char *nr = realloc(raw, cap * 2);
            if (nr == NULL) { ok = false; break; }
            raw = nr;
            cap *= 2;
        }
        int r = tls_read(c, raw + rn, cap - rn);
        if (r == -2) { out->errkind = HTTP_ERR_TIMEOUT; ok = false; break; }
        if (r < 0) { ok = false; break; }
        if (r == 0) break;
        rn += (size_t)r;
        if (cb != NULL) {
            if (hoff == 0) {
                const char *hd = mem_find(raw, rn, "\r\n\r\n", 4);
                if (hd != NULL) {
                    hoff = (size_t)(hd + 4 - raw);
                    if (mem_find_ci(raw, hoff, "hunked") == NULL) {
                        long long cl = -1;
                        if (hdr_ll(raw, hoff, "Content-Length", &cl))
                            prog_total = cl;
                    }
                }
            }
            if (hoff != 0) cb(rn > hoff ? rn - hoff : 0, prog_total, ud);
        }
    }
    if (!ok || rn < 12 || strncmp(raw, "HTTP/", 5) != 0) {
        free(raw);
        snprintf(errbuf, errcap, "connection to %s failed", hostp);
        tls_close(c);
        return false;
    }

    raw[rn] = '\0';
    char *head_end = strstr(raw, "\r\n\r\n");
    if (head_end == NULL) {
        free(raw);
        snprintf(errbuf, errcap, "malformed response from %s", hostp);
        tls_close(c);
        return false;
    }
    // NUL inside the final CRLF pair (body starts at +4): zeroing head_end
    // itself erases the last header line's terminator from header_get.
    head_end[3] = '\0';
    // Hand-rolled: sscanf drags in ~12 KB/arch of scanf machinery.
    int status = -1;
    if (strncmp(raw, "HTTP/1.", 7) == 0 && raw[7] >= '0'
            && raw[7] <= '9' && raw[8] == ' ') {
        const char *p = raw + 9;
        int v = 0, n = 0;
        while (n < 3 && p[n] >= '0' && p[n] <= '9') {
            v = v * 10 + (p[n] - '0');
            n++;
        }
        if (n == 3 && (p[n] == ' ' || p[n] == '\r' || p[n] == '\0'))
            status = v;
    }
    if (status < 0) {
        free(raw);
        snprintf(errbuf, errcap, "malformed status line");
        tls_close(c);
        return false;
    }
    out->status = status;

    const char *vs, *ve;
    bool chunked = false;
    long long clen = -1;
    if (header_get(raw, "transfer-encoding", &vs, &ve))
        for (const char *p = vs; p + 6 < ve; p++)
            if (strncasecmp(p, "chunked", 7) == 0) chunked = true;
    if (header_get(raw, "location", &vs, &ve))
        out->location = strndup(vs, (size_t)(ve - vs));
    if (!chunked && header_get(raw, "content-length", &vs, &ve)) {
        char num[24];
        size_t l = (size_t)(ve - vs);
        if (l < sizeof(num)) {
            memcpy(num, vs, l);
            num[l] = '\0';
            clen = atoll(num);
        }
    }

    const char *body = head_end + 4;
    size_t avail = rn - (size_t)(body - raw);
    size_t take = chunked ? rn - (size_t)(body - raw)   // upper bound
        : clen >= 0 && (unsigned long long)clen < avail ? (size_t)clen
        : avail;
    char *out_body = malloc(take + 1);
    if (out_body == NULL) {
        free(raw);
        snprintf(errbuf, errcap, "out of memory");
        tls_close(c);
        return false;
    }
    if (chunked) {
        size_t bn = 0;
        if (!chunked_copy(body, raw + rn, &out_body, &bn)) {
            free(out_body);
            free(raw);
            snprintf(errbuf, errcap, "malformed chunked body");
            tls_close(c);
            return false;
        }
        out_body[bn] = '\0';
        out->body = out_body;
        out->len = bn;
    } else {
        memcpy(out_body, body, take);
        out_body[take] = '\0';
        out->body = out_body;
        out->len = take;
    }
    tls_close(c);
    return true;
}

// True when both absolute https URLs share host[:port]. An unparseable b
// counts as foreign, so headers get dropped.
bool same_origin(const char *a, const char *b)
{
    char ha[256], hb[256], pa[8], pb[8], e[64];
    int porta = 443, portb = 443;
    if (!split_url(a, ha, sizeof(ha), &porta, pa, sizeof(pa), e, sizeof(e)))
        return false;
    if (!split_url(b, hb, sizeof(hb), &portb, pb, sizeof(pb), e, sizeof(e)))
        return false;
    return porta == portb && strcasecmp(ha, hb) == 0;
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
    char cur[4096];
    snprintf(cur, sizeof(cur), "%s", url);
    for (int hop = 0; hop <= 20; hop++) {
        if (!http_get_once(cur, value != NULL ? hdr : NULL, timeout_ms,
                           cb, ud, out, errbuf, errcap))
            return false;
        bool redir = out->status == 301 || out->status == 302
            || out->status == 303 || out->status == 307 || out->status == 308;
        if (!redir || out->location == NULL || hop == 20) return true;

        char *next = NULL;
        if (strncasecmp(out->location, "https://", 8) == 0)
            next = strdup(out->location);
        else if (out->location[0] == '/') {
            char host[256], p0[8];
            int port;
            if (split_url(cur, host, sizeof(host), &port, p0, sizeof(p0),
                          errbuf, errcap)) {
                char auth[280];
                if (port == 443) snprintf(auth, sizeof(auth), "%s", host);
                else snprintf(auth, sizeof(auth), "%s:%d", host, port);
                next = malloc(8 + strlen(auth) + strlen(out->location) + 1);
                if (next != NULL)
                    sprintf(next, "https://%s%s", auth, out->location);
            }
        }
        free(out->location);
        out->location = NULL;
        if (next == NULL) return true;    // unresolvable Location: report as-is
        if (value != NULL && !same_origin(cur, next)) value = NULL;
        snprintf(cur, sizeof(cur), "%s", next);
        free(next);
        free(out->body);
        out->body = NULL;
        out->len = 0;
    }
    return true;
}
