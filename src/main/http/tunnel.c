// Proxy tunnels: configuration, SOCKS5/CONNECT handshakes, and the
// tunnel-or-direct TLS connector every request rides.

#include "http.h"
#include "http/internal.h"
#include "crypto.h"

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

// ---- proxy tunnel ---- every TLS connection rides it; origins are
// https-only, so CONNECT-or-SOCKS5 covers the tool's traffic.

static char *g_proxy;

void http_set_proxy(const char *url)
{
    free(g_proxy);
    g_proxy = (url != NULL && *url != '\0') ? strdup(url) : NULL;
}

static bool proxy_write_all(int fd, const void *buf, size_t n)
{
    const unsigned char *p = buf;
    while (n > 0) {
        ssize_t w = send(fd, p, n, 0);
        if (w <= 0) { if (errno == EINTR) continue; return false; }
        p += (size_t)w; n -= (size_t)w;
    }
    return true;
}

// Read until `end` appears or cap fills; false on EOF/timeout.
static bool proxy_read_until(int fd, const char *end, char *buf, size_t cap,
                             int ms)
{
    struct timeval tv = { .tv_sec = ms / 1000 > 0 ? ms / 1000 : 1,
                          .tv_usec = 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    size_t n = 0, el = strlen(end);
    buf[0] = '\0';
    for (;;) {
        ssize_t r = recv(fd, buf + n, cap - n - 1, 0);
        if (r <= 0) return false;
        n += (size_t)r;
        buf[n] = '\0';
        if (n >= el && strstr(buf, end) != NULL) return true;
        if (n + 1 >= cap) return false;
    }
}

static bool proxy_read_exact(int fd, unsigned char *buf, size_t n)
{
    size_t got = 0;
    while (got < n) {
        ssize_t r = recv(fd, buf + got, n - got, 0);
        if (r <= 0) return false;
        got += (size_t)r;
    }
    return true;
}

// Open a tunnelled socket to host:port through proxy; -1 sets errbuf.
static int proxy_via(const char *proxy, const char *host, int port, int ms,
                     char *errbuf, size_t errcap)
{
    bool socks;
    char ph[300];
    int pp;
    if (!strcasecmp(proxy, "tor")) {
        socks = true;
        snprintf(ph, sizeof ph, "127.0.0.1");
        pp = 9050;
    } else {
        const char *rest;
        if (!strncasecmp(proxy, "socks5h://", 10)) { socks = true; rest = proxy + 10; }
        else if (!strncasecmp(proxy, "socks5://", 9)) { socks = true; rest = proxy + 9; }
        else if (!strncasecmp(proxy, "https://", 8)) { socks = false; rest = proxy + 8; }
        else if (!strncasecmp(proxy, "http://", 7)) { socks = false; rest = proxy + 7; }
        else {
            snprintf(errbuf, errcap, "Unsupported proxy scheme: %s", proxy);
            return -1;
        }
        char spec[300];
        snprintf(spec, sizeof spec, "%.*s", (int)strcspn(rest, "/"), rest);
        char *col = strrchr(spec, ':');
        if (col != NULL && strspn(col + 1, "0123456789") == strlen(col + 1)
            && col[1] != '\0') {
            *col = '\0';
            pp = atoi(col + 1);
        } else {
            pp = socks ? 1080 : 80;
        }
        snprintf(ph, sizeof ph, "%s", spec);
    }

    tls_err tk = TLS_CONNECT;
    char perr[160];
    int fd = tls_tcp_connect(ph, pp, ms, &tk, perr, sizeof perr);
    if (fd < 0) {
        snprintf(errbuf, errcap, "proxy connection to %s:%d failed", ph, pp);
        return -1;
    }
    struct timeval tv = { .tv_sec = ms / 1000 > 0 ? ms / 1000 : 1,
                          .tv_usec = 0 };
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    if (socks) {
        unsigned char greet[3] = { 5, 1, 0 }, reply[2] = { 0, 0 };
        if (!proxy_write_all(fd, greet, 3) || !proxy_read_exact(fd, reply, 2)
            || reply[0] != 5 || reply[1] != 0)
            goto sockfail;
        unsigned char req[264], rsp[32];
        size_t hl = strlen(host);
        struct in_addr a4;
        struct in6_addr a6;
        if (inet_pton(AF_INET, host, &a4) == 1) {
            unsigned char r[10] = { 5, 1, 0, 1 };
            memcpy(r + 4, &a4, 4);
            r[8] = (unsigned char)(port >> 8); r[9] = (unsigned char)port;
            if (!proxy_write_all(fd, r, 10)) goto sockfail;
            if (!proxy_read_exact(fd, rsp, 10)) goto sockfail;
        } else if (inet_pton(AF_INET6, host, &a6) == 1) {
            unsigned char r[22] = { 5, 1, 0, 4 };
            memcpy(r + 4, &a6, 16);
            r[20] = (unsigned char)(port >> 8); r[21] = (unsigned char)port;
            if (!proxy_write_all(fd, r, 22)) goto sockfail;
            if (!proxy_read_exact(fd, rsp, 22)) goto sockfail;
        } else {
            if (hl > 255) goto sockfail;
            size_t n = 0;
            req[n++] = 5; req[n++] = 1; req[n++] = 0; req[n++] = 3;
            req[n++] = (unsigned char)hl;
            memcpy(req + n, host, hl); n += hl;
            req[n++] = (unsigned char)(port >> 8); req[n++] = (unsigned char)port;
            if (!proxy_write_all(fd, req, n)) goto sockfail;
            if (!proxy_read_exact(fd, rsp, 4)) goto sockfail;
            // Reply = VER REP RSV ATYP + BND.ADDR + BND.PORT: IPv4 4+2,
            // IPv6 16+2, domain LEN+2; never leave bytes for TLS to read.
            unsigned char atyp = rsp[3], len = 0;
            size_t extra;
            if (atyp == 1) extra = 6;
            else if (atyp == 4) extra = 18;
            else if (atyp == 3) {
                if (!proxy_read_exact(fd, &len, 1)) goto sockfail;
                extra = (size_t)len + 2;
            } else goto sockfail;
            if (extra > sizeof req) goto sockfail;
            if (!proxy_read_exact(fd, req, extra)) goto sockfail;
        }
        if (rsp[0] != 5 || rsp[1] != 0) goto sockfail;
        return fd;
    sockfail:
        close(fd);
        snprintf(errbuf, errcap, "SOCKS5 handshake with %s:%d failed", ph, pp);
        return -1;
    }

    char req[512], head[2048];
    snprintf(req, sizeof req,
        "CONNECT %s:%d HTTP/1.1\r\nHost: %s:%d\r\n\r\n", host, port,
        host, port);
    if (!proxy_write_all(fd, req, strlen(req))
        || !proxy_read_until(fd, "\r\n\r\n", head, sizeof head, ms)
        || strncmp(head, "HTTP/", 5) != 0 || strncmp(head + 9, "200", 3) != 0) {
        close(fd);
        snprintf(errbuf, errcap, "proxy CONNECT to %s:%d failed", host, port);
        return -1;
    }
    return fd;
}

// tls_connect over the tunnel when a proxy is set, direct otherwise.
tls_conn *open_conn(const char *host, int port, const char *ca_file,
    const char *ca_mem, size_t ca_mem_len, int timeout_ms, tls_err *kind,
    char *errbuf, size_t errcap)
{
    if (g_proxy == NULL)
        return tls_connect(host, port, ca_file, ca_mem, ca_mem_len,
            timeout_ms, kind, errbuf, errcap);
    int fd = proxy_via(g_proxy, host, port, timeout_ms, errbuf, errcap);
    if (fd < 0) {
        *kind = TLS_CONNECT;
        return NULL;
    }
    return tls_connect_fd(fd, host, ca_file, ca_mem, ca_mem_len,
        timeout_ms, kind, errbuf, errcap);
}

