// The streaming download path: one fixed-buffer attempt per URL driven
// by the shared framing machine, and the redirect loop around it; a
// 200 body goes straight to disk.

#include "http.h"
#include "http/internal.h"
#include "crypto.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

struct dl_io {
    tls_conn *c;
    FILE *f;
    int werr;                   // errno of a sink write failure
    long long total;            // declared total for the progress sink
    long long done;
    http_prog cb;
    void *ud;
};

static int dl_read(void *v, char *b, size_t n)
{
    struct dl_io *x = v;
    return tls_read(x->c, b, n);
}

static bool dl_sink(void *v, const char *p, size_t n)
{
    struct dl_io *x = v;
    if (fwrite(p, 1, n, x->f) != n) {
        x->werr = errno;
        return false;
    }
    x->done += (long long)n;
    if (x->cb != NULL) x->cb((size_t)x->done, x->total, x->ud);
    return true;
}

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
        *errkind = kind;
        free(h.location);
        goto fail_conn;
    }
    if (h.location != NULL) {
        snprintf(location, loccap, "%s", h.location);
        free(h.location);
    }
    if (h.status != 200) {              // nothing written; caller decides
        *status = h.status;
        tls_close(c);
        free(buf);
        return true;
    }
    // a lying-enormous declared length refuses before the destination
    // is opened, let alone truncated by the "wb" below
    if (max_bytes > 0 && h.clen > max_bytes) {
        snprintf(errbuf, errcap, "declared body of %lld bytes exceeds the "
            "%lld MiB cap", h.clen, max_bytes >> 20);
        goto fail_conn;
    }
    FILE *f = fopen(dest_path, "wb");
    if (f == NULL) {
        snprintf(errbuf, errcap, "failed writing %s: %s", dest_path,
                 strerror(errno));
        goto fail_conn;
    }
    long long total = !h.chunked && h.clen >= 0 ? h.clen : -1;
    struct dl_io x = { .c = c, .f = f, .total = total, .cb = cb, .ud = ud };
    if (cb != NULL) cb(0, total, ud);

    resp_io io = { dl_read, dl_sink, &x };
    long long written = 0;
    resp_stop st = resp_read_body(&io, buf, &have, HTTP_BUF, &h,
                                  max_bytes, &written);
    if (fclose(f) != 0 && st == RESP_DONE && x.werr == 0) {
        x.werr = errno;               // flush/ENOSPC surfacing at close
        st = RESP_SINKERR;
    }
    tls_close(c);
    free(buf);
    if (st == RESP_DONE) {
        *status = h.status;
        return true;
    }
    if (st == RESP_SINKERR) {
        snprintf(errbuf, errcap, "failed writing %s: %s", dest_path,
                 strerror(x.werr));
    } else if (st == RESP_TOOBIG) {
        snprintf(errbuf, errcap, "body exceeds the %lld MiB download cap "
            "(read %lld of it)", max_bytes >> 20, written);
    } else {
        if (st == RESP_TIMEOUT) *errkind = HTTP_ERR_TIMEOUT;
        char tot[32];
        if (total > 0) snprintf(tot, sizeof tot, "%lld", total);
        else snprintf(tot, sizeof tot, "?");
        snprintf(errbuf, errcap,
                 "download from %s failed after %lld of %s bytes",
                 hostp, written, tot);
    }
    remove(dest_path);                // nothing partial may survive a failure
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
    char *cur = strdup(url);
    if (cur == NULL) {
        snprintf(errbuf, errcap, "out of memory");
        return false;
    }
    bool ok = false;
    for (int hop = 0; hop <= 20; hop++) {
        int st = 0, kind = HTTP_ERR_OTHER;
        char loc[4096] = "";
        if (!http_download_once(cur, value != NULL ? hdr : NULL, timeout_ms,
                                max_bytes, cb, ud, dest_path, &st, &kind,
                                loc, sizeof(loc), errbuf, errcap)) {
            if (errkind != NULL) *errkind = kind;
            break;
        }
        if (status != NULL) *status = st;
        bool redir = st == 301 || st == 302 || st == 303
            || st == 307 || st == 308;
        if (!redir || loc[0] == '\0' || hop == 20) { ok = st == 200; break; }

        char *next = url_join(cur, loc);
        if (next == NULL) { ok = st == 200; break; }  // unresolvable: report
        if (value != NULL && !same_origin(cur, next)) value = NULL;
        free(cur);
        cur = next;
    }
    free(cur);
    return ok;
}
