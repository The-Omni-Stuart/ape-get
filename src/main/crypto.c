#include "crypto.h"
#include <mbedtls/error.h>
#include <mbedtls/net_sockets.h>
#include <mbedtls/pem.h>
#include <mbedtls/ssl.h>
#include <mbedtls/entropy.h>
#include <mbedtls/ctr_drbg.h>
#include <mbedtls/x509.h>
#include <mbedtls/x509_crt.h>
#include <mbedtls/sha256.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

struct tls_conn {
    mbedtls_ssl_context ssl;
    mbedtls_ssl_config cfg;
    mbedtls_net_context net;
    mbedtls_x509_crt ca;
    mbedtls_entropy_context entropy;
    mbedtls_ctr_drbg_context drbg;
};

// Compact mbedtls_strerror: the vendored table costs ~14 KB/arch and this
// client reaches only a dozen codes; unknown codes fall back to hex.
static void tls_strerror(int rc, char *out, size_t cap)
{
    static const struct { int rc; const char *msg; } t[] = {
        { MBEDTLS_ERR_X509_CERT_VERIFY_FAILED,
            "X509 - certificate verification failed" },
        { MBEDTLS_ERR_X509_INVALID_FORMAT, "X509 - invalid format" },
        { MBEDTLS_ERR_X509_INVALID_ALG, "X509 - invalid algorithm" },
        { MBEDTLS_ERR_X509_INVALID_NAME, "X509 - invalid name" },
        { MBEDTLS_ERR_X509_INVALID_DATE, "X509 - invalid date" },
        { MBEDTLS_ERR_X509_INVALID_SIGNATURE,
            "X509 - invalid signature" },
        { MBEDTLS_ERR_X509_INVALID_EXTENSIONS,
            "X509 - invalid extensions" },
        { MBEDTLS_ERR_X509_UNKNOWN_SIG_ALG,
            "X509 - unknown signature algorithm" },
        { MBEDTLS_ERR_X509_CERT_UNKNOWN_FORMAT,
            "X509 - unknown certificate format" },
        { MBEDTLS_ERR_X509_BAD_INPUT_DATA, "X509 - bad input data" },
        { MBEDTLS_ERR_PEM_INVALID_DATA, "PEM - invalid data" },
        { MBEDTLS_ERR_SSL_HANDSHAKE_FAILURE, "SSL - handshake failed" },
        { MBEDTLS_ERR_SSL_FATAL_ALERT_MESSAGE,
            "SSL - received fatal alert message" },
        { MBEDTLS_ERR_SSL_CA_CHAIN_REQUIRED, "SSL - a CA Chain is required" },
        { MBEDTLS_ERR_SSL_CERTIFICATE_VERIFICATION_WITHOUT_HOSTNAME,
            "SSL - certificate verification without hostname" },
        { MBEDTLS_ERR_SSL_INVALID_MAC, "SSL - invalid MAC" },
        { MBEDTLS_ERR_SSL_ALLOC_FAILED, "SSL - memory allocation failed" },
        { MBEDTLS_ERR_SSL_INTERNAL_ERROR, "SSL - internal error" },
        { MBEDTLS_ERR_SSL_TIMEOUT, "SSL - operation timed out" },
    };
    for (size_t k = 0; k < sizeof(t) / sizeof(t[0]); k++)
        if (t[k].rc == rc) { snprintf(out, cap, "%s", t[k].msg); return; }
    snprintf(out, cap, "tls error -0x%04x", (unsigned)(-(unsigned)rc));
}

static void fail(tls_conn *c, char *errbuf, size_t errcap,
                 const char *what, int rc)
{
    char detail[128] = "";
    if (rc) tls_strerror(rc, detail, sizeof(detail));
    snprintf(errbuf, errcap, "%s%s%s", what, detail[0] ? ": " : "", detail);
    tls_close(c);
}

// Non-blocking connect with a wall deadline, then back to blocking.
static int tcp_connect_timeout(const char *host, const char *port, int ms,
                               tls_err *kind)
{
    struct addrinfo hints = { .ai_family = AF_UNSPEC,
                              .ai_socktype = SOCK_STREAM }, *res = NULL;
    int ga = getaddrinfo(host, port, &hints, &res);
    if (ga != 0) { *kind = TLS_DNS; return -1; }
    int fd = -1;
    for (struct addrinfo *ai = res; ai != NULL; ai = ai->ai_next) {
        fd = socket(ai->ai_family, ai->ai_socktype | O_NONBLOCK, 0);
        if (fd < 0) continue;
        int rc = connect(fd, ai->ai_addr, ai->ai_addrlen);
        if (rc == 0) { errno = 0; }
        else if (errno == EINPROGRESS) {
            struct pollfd pfd = { .fd = fd, .events = POLLOUT };
            rc = poll(&pfd, 1, ms);
            if (rc == 1) {
                int err = 0;
                socklen_t len = sizeof(err);
                getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &len);
                if (err == 0) rc = 0;
                else { errno = err; rc = -1; }
            } else { errno = ETIMEDOUT; rc = -1; }
        }
        if (rc == 0) {
            int fl = fcntl(fd, F_GETFL, 0);
            fcntl(fd, F_SETFL, fl & ~O_NONBLOCK);
            break;
        }
        close(fd);
        fd = -1;
    }
    freeaddrinfo(res);
    if (fd < 0) *kind = errno == ETIMEDOUT ? TLS_TIMEOUT : TLS_CONNECT;
    return fd;
}

// Parse the DER-concatenated embedded bundle cert by cert (outer SEQUENCE
// + length); certs the trimmed config cannot parse are skipped, as in PEM.
static int add_der_bundle(mbedtls_x509_crt *ca,
                          const unsigned char *p, size_t n)
{
    int ok = 0;
    while (n > 4) {
        const unsigned char *cert = p;
        size_t len, hdr = 2;
        if (*p != 0x30) return ok;
        if (p[1] < 0x80) {
            len = p[1];
        } else {
            int nb = p[1] & 0x7f;
            if (nb < 1 || nb > 2 || (size_t)nb + 2 > n) return ok;
            len = 0;
            for (int i = 0; i < nb; i++) len = (len << 8) | p[2 + i];
            hdr = 2 + (size_t)nb;
        }
        if (hdr + len > n) return ok;
        if (mbedtls_x509_crt_parse_der(ca, cert, hdr + len) == 0) ok++;
        p += hdr + len; n -= hdr + len;
    }
    return ok;
}

tls_conn *tls_connect_fd(int fd, const char *host, const char *ca_file,
                         const char *ca_mem, size_t ca_mem_len,
                         int timeout_ms, tls_err *kind,
                         char *errbuf, size_t errcap)
{
    tls_conn *c = calloc(1, sizeof(*c));
    *kind = TLS_OTHER;
    if (c == NULL) { snprintf(errbuf, errcap, "out of memory"); return NULL; }
    mbedtls_ssl_init(&c->ssl);
    mbedtls_ssl_config_init(&c->cfg);
    mbedtls_net_init(&c->net);
    c->net.fd = fd; // owned from here; every failure path closes it
    mbedtls_x509_crt_init(&c->ca);
    mbedtls_entropy_init(&c->entropy);
    mbedtls_ctr_drbg_init(&c->drbg);
    *errbuf = '\0';

    int rc = mbedtls_ctr_drbg_seed(&c->drbg, mbedtls_entropy_func,
                                   &c->entropy, NULL, 0);
    if (rc) { fail(c, errbuf, errcap, "rng seed failed", rc); return NULL; }

    if (ca_file != NULL) {
        // parse_file is compiled out; read the PEM ourselves
        FILE *f = fopen(ca_file, "rb");
        if (f == NULL) { fail(c, errbuf, errcap, "ca bundle unreadable", 0); return NULL; }
        fseek(f, 0, SEEK_END);
        long n = ftell(f);
        rewind(f);
        char *pem = n >= 0 ? malloc((size_t)n + 1) : NULL;
        size_t got = pem != NULL ? fread(pem, 1, (size_t)n, f) : 0;
        fclose(f);
        if (pem == NULL || got != (size_t)n) {
            free(pem);
            fail(c, errbuf, errcap, "ca bundle unreadable", 0);
            return NULL;
        }
        pem[got] = '\0'; // parse consumes the terminator
        rc = mbedtls_x509_crt_parse(&c->ca, (const unsigned char *)pem, got + 1);
        free(pem);
    } else {
        // embedded bundle: DER-concatenated (toolchain/embed_ca.py)
        if (add_der_bundle(&c->ca,
                (const unsigned char *)ca_mem, ca_mem_len) == 0)
            rc = -1;
    }
    // A positive rc counts certs skipped: expected, the trimmed config
    // (toolchain/mbedtls_apeget.h) cannot parse every Mozilla root.
    if (rc > 0) rc = 0;
    if (rc) { fail(c, errbuf, errcap, "ca bundle unreadable", rc); return NULL; }

    rc = mbedtls_ssl_config_defaults(&c->cfg, MBEDTLS_SSL_IS_CLIENT,
                                     MBEDTLS_SSL_TRANSPORT_STREAM,
                                     MBEDTLS_SSL_PRESET_DEFAULT);
    if (rc) { fail(c, errbuf, errcap, "ssl config failed", rc); return NULL; }
    mbedtls_ssl_conf_authmode(&c->cfg, MBEDTLS_SSL_VERIFY_REQUIRED);
    mbedtls_ssl_conf_ca_chain(&c->cfg, &c->ca, NULL);
    mbedtls_ssl_conf_rng(&c->cfg, mbedtls_ctr_drbg_random, &c->drbg);

    rc = mbedtls_ssl_setup(&c->ssl, &c->cfg);
    if (rc) { fail(c, errbuf, errcap, "ssl setup failed", rc); return NULL; }
    rc = mbedtls_ssl_set_hostname(&c->ssl, host);
    if (rc) { fail(c, errbuf, errcap, "sni failed", rc); return NULL; }
    mbedtls_ssl_set_bio(&c->ssl, &c->net, mbedtls_net_send, mbedtls_net_recv,
                        NULL);

    // Stall bound for handshake and reads alike: WANT_READ only happens
    // when SO_RCVTIMEO fires on the blocking socket.
    struct timeval tv = { .tv_sec = timeout_ms / 1000, .tv_usec = 0 };
    setsockopt(c->net.fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    while ((rc = mbedtls_ssl_handshake(&c->ssl)) == MBEDTLS_ERR_SSL_WANT_WRITE)
        ;
    if (rc == MBEDTLS_ERR_SSL_WANT_READ) {
        *kind = TLS_TIMEOUT;
        fail(c, errbuf, errcap, "tls handshake timed out", 0);
        return NULL;
    }
    if (rc != 0) { fail(c, errbuf, errcap, "tls handshake failed", rc); return NULL; }

    if (mbedtls_ssl_get_verify_result(&c->ssl) != 0) {
        fail(c, errbuf, errcap, "certificate verification failed", 0);
        return NULL;
    }
    return c;
}

int tls_tcp_connect(const char *host, int port, int timeout_ms,
                    tls_err *kind, char *errbuf, size_t errcap)
{
    char portbuf[8];
    snprintf(portbuf, sizeof(portbuf), "%d", port);
    int fd = tcp_connect_timeout(host, portbuf, timeout_ms, kind);
    if (fd < 0) {
        snprintf(errbuf, errcap, "connect failed: %s", strerror(errno));
        return -1;
    }
    return fd;
}

tls_conn *tls_connect(const char *host, int port, const char *ca_file,
                      const char *ca_mem, size_t ca_mem_len,
                      int timeout_ms, tls_err *kind,
                      char *errbuf, size_t errcap)
{
    int fd = tls_tcp_connect(host, port, timeout_ms, kind, errbuf, errcap);
    if (fd < 0) return NULL;
    return tls_connect_fd(fd, host, ca_file, ca_mem, ca_mem_len,
                          timeout_ms, kind, errbuf, errcap);
}

int tls_write_all(tls_conn *c, const void *buf, size_t n)
{
    const unsigned char *p = buf;
    size_t left = n;
    while (left > 0) {
        int rc = mbedtls_ssl_write(&c->ssl, p, left);
        if (rc == MBEDTLS_ERR_SSL_WANT_WRITE) continue;
        if (rc <= 0) return -1;
        p += rc;
        left -= (size_t)rc;
    }
    return 0;
}

int tls_read(tls_conn *c, void *buf, size_t n)
{
    for (;;) {
        int rc = mbedtls_ssl_read(&c->ssl, buf, n);
        if (rc == MBEDTLS_ERR_SSL_WANT_WRITE) continue;
        if (rc == MBEDTLS_ERR_SSL_WANT_READ) return -2;   // stalled
        if (rc == MBEDTLS_ERR_SSL_PEER_CLOSE_NOTIFY || rc == 0) return 0;
        if (rc < 0) return -1;
        return rc;
    }
}

void tls_close(tls_conn *c)
{
    if (c == NULL) return;
    if (c->net.fd != -1) {
        mbedtls_ssl_close_notify(&c->ssl);
        mbedtls_net_free(&c->net);
    }
    mbedtls_ssl_free(&c->ssl);
    mbedtls_ssl_config_free(&c->cfg);
    mbedtls_x509_crt_free(&c->ca);
    mbedtls_ctr_drbg_free(&c->drbg);
    mbedtls_entropy_free(&c->entropy);
    free(c);
}

bool crypto_sha256_fd(int fd, char out[65])
{
    unsigned char md[32];
    mbedtls_sha256_context ctx;
    mbedtls_sha256_init(&ctx);
    if (mbedtls_sha256_starts(&ctx, 0) != 0) {
        mbedtls_sha256_free(&ctx);
        return false;
    }
    unsigned char buf[8192];
    ssize_t n;
    while ((n = read(fd, buf, sizeof buf)) > 0)
        if (mbedtls_sha256_update(&ctx, buf, (size_t)n) != 0) {
            mbedtls_sha256_free(&ctx);
            return false;
        }
    bool bad = n < 0;
    int rc = mbedtls_sha256_finish(&ctx, md);
    mbedtls_sha256_free(&ctx);
    if (bad || rc != 0) return false;
    for (int i = 0; i < 32; i++)
        sprintf(out + i * 2, "%02x", md[i]);
    return true;
}

bool crypto_sha256_file(const char *path, char out[65])
{
    unsigned char md[32];
    FILE *f = fopen(path, "rb");
    if (f == NULL) return false;
    // streamed so an AppImage-sized download never doubles RAM
    mbedtls_sha256_context ctx;
    mbedtls_sha256_init(&ctx);
    if (mbedtls_sha256_starts(&ctx, 0) != 0) {
        mbedtls_sha256_free(&ctx);
        fclose(f);
        return false;
    }
    unsigned char buf[8192];
    size_t n;
    while ((n = fread(buf, 1, sizeof buf, f)) > 0)
        if (mbedtls_sha256_update(&ctx, buf, n) != 0) {
            mbedtls_sha256_free(&ctx);
            fclose(f);
            return false;
        }
    bool bad = ferror(f) != 0;
    fclose(f);
    int rc = mbedtls_sha256_finish(&ctx, md);
    mbedtls_sha256_free(&ctx);
    if (bad || rc != 0) return false;
    for (int i = 0; i < 32; i++)
        sprintf(out + i * 2, "%02x", md[i]);
    return true;
}
