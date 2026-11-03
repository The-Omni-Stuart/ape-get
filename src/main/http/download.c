// The streaming download path: one fixed-buffer attempt per URL and
// the redirect loop around it; a 200 body goes straight to disk.

#include "http.h"
#include "http/internal.h"
#include "crypto.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

// Streaming buffer for http_download (header block + body chunks); a
// header block larger than this is rejected as malformed.
#define DL_BUF (1UL << 16)

// One streaming attempt on one URL. true = response fully handled (200:
// whole body written); *status is the final HTTP status, 0 for failure.
static bool http_download_once(const char *url, const char *hdr_line,
                               int timeout_ms, long long max_bytes,
                               http_prog cb, void *ud,
                               const char *dest_path, int *status,
                               int *errkind, char *location, size_t loccap,
                               char *errbuf, size_t errcap)
{
    *status = 0;
    *errkind = HTTP_ERR_OTHER;
    location[0] = '\0';
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
        *errkind = tk == TLS_TIMEOUT ? HTTP_ERR_TIMEOUT
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

    char *buf = malloc(DL_BUF);
    if (buf == NULL) {
        snprintf(errbuf, errcap, "connection to %s failed", hostp);
        tls_close(c);
        return false;
    }

    size_t rn = 0, hoff = 0;
    for (;;) {                          // header block first, capped
        int r = tls_read(c, buf + rn, DL_BUF - rn);
        if (r == -2) {
            *errkind = HTTP_ERR_TIMEOUT;
            snprintf(errbuf, errcap, "connection to %s failed", hostp);
            goto fail_conn;
        }
        if (r < 0) {
            snprintf(errbuf, errcap, "connection to %s failed", hostp);
            goto fail_conn;
        }
        if (r == 0) {
            if (rn >= 12 && strncmp(buf, "HTTP/", 5) == 0)
                snprintf(errbuf, errcap, "malformed response from %s",
                         hostp);
            else
                snprintf(errbuf, errcap, "connection to %s failed", hostp);
            goto fail_conn;
        }
        rn += (size_t)r;
        const char *hd = mem_find(buf, rn, "\r\n\r\n", 4);
        if (hd != NULL && (size_t)(hd + 4 - buf) < DL_BUF) {
            hoff = (size_t)(hd + 4 - buf);
            break;
        }
        if (rn == DL_BUF) {
            snprintf(errbuf, errcap, "malformed response from %s", hostp);
            goto fail_conn;
        }
    }

    // NUL AFTER the terminating CRLFCRLF: zeroing its first CR (what the
    // buffered path does) hides the last header line from header_get.
    char head_end_saved = buf[hoff];
    buf[hoff] = '\0';
    int status_ = -1;
    // Hand-rolled: sscanf drags in ~12 KB/arch of scanf machinery.
    if (strncmp(buf, "HTTP/1.", 7) == 0 && buf[7] >= '0'
            && buf[7] <= '9' && buf[8] == ' ') {
        const char *p = buf + 9;
        int v = 0, k = 0;
        while (k < 3 && p[k] >= '0' && p[k] <= '9') {
            v = v * 10 + (p[k] - '0');
            k++;
        }
        if (k == 3 && (p[k] == ' ' || p[k] == '\r' || p[k] == '\0'))
            status_ = v;
    }
    if (status_ < 0) {
        snprintf(errbuf, errcap, "malformed status line");
        goto fail_conn;
    }

    const char *vs, *ve;
    bool chunked = false;
    if (header_get(buf, "transfer-encoding", &vs, &ve))
        for (const char *p = vs; p + 6 < ve; p++)
            if (strncasecmp(p, "chunked", 7) == 0) chunked = true;
    if (header_get(buf, "location", &vs, &ve))
        snprintf(location, loccap, "%.*s", (int)(ve - vs), vs);
    long long clen = -1;
    if (!chunked && header_get(buf, "content-length", &vs, &ve)) {
        char num[24];
        size_t l = (size_t)(ve - vs);
        if (l < sizeof(num)) {
            memcpy(num, vs, l);
            num[l] = '\0';
            clen = atoll(num);
        }
    }

    if (status_ != 200) {               // nothing written; caller decides
        *status = status_;
        tls_close(c);
        free(buf);
        return true;
    }
    buf[hoff] = head_end_saved;         // body bytes live again

    FILE *f = fopen(dest_path, "wb");
    if (f == NULL) {
        snprintf(errbuf, errcap, "failed writing %s: %s", dest_path,
                 strerror(errno));
        goto fail_conn;
    }
    long long total = chunked ? -1 : clen;
    // a lying-enormous declared length refuses before the first byte
    // is written, let alone read
    if (max_bytes > 0 && clen > max_bytes) {
        snprintf(errbuf, errcap, "declared body of %lld bytes exceeds the "
            "%lld MiB cap", clen, max_bytes >> 20);
        tls_close(c);
        free(buf);
        return false;
    }
    long long written = 0;
    if (cb != NULL) cb(0, total, ud);

    bool done = false, werr = false, terr = false, toobig = false;
    int ferr = 0;                       // errno of a write failure
    size_t have = rn - hoff;            // unconsumed body bytes
    if (have > 0) memmove(buf, buf + hoff, have);
    #define DL_DROP(k) do {                                             \
        memmove(buf, buf + (k), have - (size_t)(k));                    \
        have -= (size_t)(k);                                            \
    } while (0)

    if (!chunked) {
        for (;;) {
            if (clen >= 0 && written >= clen) { done = true; break; }
            size_t take = have;
            if (clen >= 0 && (long long)take > clen - written)
                take = (size_t)(clen - written);
            if (max_bytes > 0 && written + (long long)take > max_bytes) {
                toobig = true;          // undeclared padding: stop reading
                break;
            }
            if (take > 0) {
                if (fwrite(buf, 1, take, f) != take) {
                    werr = true;
                    ferr = errno;
                    break;
                }
                written += (long long)take;
                DL_DROP(take);
                if (cb != NULL) cb((size_t)written, total, ud);
            }
            // clen met with bytes still buffered: stop at the declared
            // length, like the buffered path truncating at clen
            if (clen >= 0 && written >= clen) { done = true; break; }
            int r = tls_read(c, buf + have, DL_BUF - have);
            if (r > 0) { have += (size_t)r; continue; }
            if (r == -2) *errkind = HTTP_ERR_TIMEOUT;
            if (r != 0 || (clen >= 0 && written < clen)) {
                terr = true;            // stall, error or truncation
                break;
            }
            done = true;                // clean EOF at the end
            break;
        }
    } else {
        int st = 0;                     // 0 size-line, 1 data, 2 CRLF
        long long want = 0;
        for (;;) {
            bool progressed = false;
            if (st == 0) {
                const char *nl = mem_find(buf, have, "\r\n", 2);
                if (nl != NULL) {
                    char sz[24] = "";
                    size_t l = (size_t)(nl - buf);
                    if (l >= sizeof(sz)) { terr = true; break; }
                    memcpy(sz, buf, l);
                    sz[strcspn(sz, ";")] = '\0';
                    char *endp = NULL;
                    want = strtoll(sz, &endp, 16);
                    if (endp == sz || want < 0) { terr = true; break; }
                    DL_DROP(l + 2);
                    if (want == 0) { done = true; break; }
                    st = 1;
                    progressed = true;
                } else if (have == DL_BUF) { terr = true; break; }
            } else if (st == 1) {
                size_t take = (long long)have < want ? have : (size_t)want;
                if (max_bytes > 0 && written + (long long)take > max_bytes) {
                    toobig = true;
                    break;
                }
                if (take > 0) {
                    if (fwrite(buf, 1, take, f) != take) {
                        werr = true;
                        ferr = errno;
                        break;
                    }
                    written += (long long)take;
                    want -= (long long)take;
                    DL_DROP(take);
                    if (cb != NULL) cb((size_t)written, -1, ud);
                    progressed = true;
                }
                if (want == 0) st = 2;
            } else if (have >= 2) {     // trailing CRLF of a chunk
                DL_DROP(2);
                st = 0;
                progressed = true;
            }
            if (progressed) continue;
            if (have == DL_BUF) {       // cannot consume, cannot append
                terr = true;
                break;
            }
            int r = tls_read(c, buf + have, DL_BUF - have);
            if (r > 0) { have += (size_t)r; continue; }
            if (r == -2) *errkind = HTTP_ERR_TIMEOUT;
            terr = true;                // stalled, errored or truncated
            break;
        }
    }
    #undef DL_DROP

    if (fclose(f) != 0 && !werr && !terr) {
        werr = true;                    // flush/ENOSPC surfacing at close
        ferr = errno;
    }
    tls_close(c);
    free(buf);
    if (done && !werr && !terr && !toobig) {
        *status = status_;
        return true;
    }
    if (werr) {
        snprintf(errbuf, errcap, "failed writing %s: %s", dest_path,
                 strerror(ferr));
    } else if (toobig) {
        snprintf(errbuf, errcap, "body exceeds the %lld MiB download cap "
            "(read %lld of it)", max_bytes >> 20, written);
    } else {
        char tot[32];
        if (total > 0) snprintf(tot, sizeof tot, "%lld", total);
        else snprintf(tot, sizeof tot, "?");
        snprintf(errbuf, errcap,
                 "download from %s failed after %lld of %s bytes",
                 hostp, written, tot);
    }
    remove(dest_path);                  // nothing partial may survive a failure
    return false;

fail_conn:
    tls_close(c);
    free(buf);
    return false;
}

bool http_download(const char *url, const char *name, const char *value,
                   int timeout_ms, long long max_bytes,
                   http_prog cb, void *ud,
                   const char *dest_path, int *status, int *errkind,
                   char *errbuf, size_t errcap)
{
    if (status != NULL) *status = 0;
    if (errkind != NULL) *errkind = HTTP_ERR_OTHER;
    char hdr[1300];
    if (value != NULL) snprintf(hdr, sizeof(hdr), "%s: %s\r\n", name, value);
    char cur[4096];
    snprintf(cur, sizeof(cur), "%s", url);
    for (int hop = 0; hop <= 20; hop++) {
        int st = 0, kind = HTTP_ERR_OTHER;
        char loc[4096] = "";
        if (!http_download_once(cur, value != NULL ? hdr : NULL, timeout_ms,
                                max_bytes, cb, ud, dest_path, &st, &kind,
                                loc, sizeof(loc), errbuf, errcap)) {
            if (errkind != NULL) *errkind = kind;
            return false;
        }
        if (status != NULL) *status = st;
        bool redir = st == 301 || st == 302 || st == 303
            || st == 307 || st == 308;
        if (!redir || loc[0] == '\0' || hop == 20) return st == 200;

        char *next = NULL;
        if (strncasecmp(loc, "https://", 8) == 0)
            next = strdup(loc);
        else if (loc[0] == '/') {
            char host[256], p0[8];
            int port;
            if (split_url(cur, host, sizeof(host), &port, p0, sizeof(p0),
                          errbuf, errcap)) {
                char auth[280];
                if (port == 443) snprintf(auth, sizeof(auth), "%s", host);
                else snprintf(auth, sizeof(auth), "%s:%d", host, port);
                next = malloc(8 + strlen(auth) + strlen(loc) + 1);
                if (next != NULL) sprintf(next, "https://%s%s", auth, loc);
            }
        }
        if (next == NULL) return st == 200; // unresolvable: report as-is
        if (value != NULL && !same_origin(cur, next)) value = NULL;
        snprintf(cur, sizeof(cur), "%s", next);
        free(next);
    }
    return false;
}
