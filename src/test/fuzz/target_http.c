// fuzz target: http.c response parsing (status line, header block,
// Content-Length/chunked framing) over stub tls transports — no network.

#include "http.c"
#include "http/internal.h"
#include "http/parse.c"
#include "http/request.c"

#include "fuzz_util.h"

// ---- stub transport (crypto.h's opaque tls_conn) ----

struct tls_conn { int alive; };

static const unsigned char *g_resp;
static size_t g_resp_n, g_resp_pos;

tls_conn *tls_connect(const char *host, int port, const char *ca_file,
                      const char *ca_mem, size_t ca_mem_len, int timeout_ms,
                      tls_err *kind, char *errbuf, size_t errcap)
{
    (void)host; (void)port; (void)ca_file; (void)ca_mem;
    (void)ca_mem_len; (void)timeout_ms; (void)errbuf; (void)errcap;
    static struct tls_conn c = { 1 };
    *kind = TLS_OK;
    return &c;
}

int tls_write_all(tls_conn *c, const void *buf, size_t n)
{
    (void)c; (void)buf; return (int)n;
}

int tls_read(tls_conn *c, void *buf, size_t n)
{
    (void)c;
    size_t rem = g_resp_n - g_resp_pos;
    size_t k = rem < 2048 ? rem : 2048;
    if (k > n) k = n;
    if (k == 0) return 0;                     // clean EOF
    memcpy(buf, g_resp + g_resp_pos, k);
    g_resp_pos += k;
    return (int)k;
}

void tls_close(tls_conn *c) { (void)c; }

// open_conn (http/internal.h): stubbed to use the direct tls_connect above
tls_conn *open_conn(const char *host, int port, const char *ca_file,
                    const char *ca_mem, size_t ca_mem_len, int timeout_ms,
                    tls_err *kind, char *errbuf, size_t errcap)
{
    return tls_connect(host, port, ca_file, ca_mem, ca_mem_len, timeout_ms,
                       kind, errbuf, errcap);
}

// the embedded CA bundle symbol http.c references (never inspected)
const unsigned char apeget_ca_der[1] = { 0 };
const unsigned long apeget_ca_der_len = 0;

// ---- target ----

static void one(const char *s, void *ud)
{
    (void)ud;
    char host[256], path[2048], err[256];
    int port = 0;
    (void)split_url(s, host, sizeof host, &port, path, sizeof path,
                    err, sizeof err);
    const char *vs, *ve;
    (void)header_get(s, "content-length", &vs, &ve);
    (void)header_get(s, "transfer-encoding", &vs, &ve);
    long long v;
    (void)hdr_ll(s, strlen(s), "content-length", &v);
    free(http_url_encode(s));

    // chunked_copy alone: its output can never exceed the input span
    size_t n = strlen(s);
    char *body = malloc(n + 1);
    if (body != NULL) {
        size_t bn = 0;
        if (chunked_copy(s, s + n, &body, &bn)) body[bn] = '\0';
        free(body);
    }
}

void fuzz_target(const unsigned char *data, size_t size)
{
    g_resp = data;
    g_resp_n = size;
    g_resp_pos = 0;
    http_resp r;
    char err[256];
    if (http_get_hdr("https://api.example.test/x", "Authorization",
            NULL, 5000, &r, err, sizeof err))
        http_resp_free(&r);
    fuzz_for_each_segment(data, size, one, NULL);
}
