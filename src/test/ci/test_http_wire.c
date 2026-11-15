// Wire-probe for the http module (N03, N05, N06, N12): URL parsing,
// redirect resolution, auth origin scoping and proxy scheme rejection
// against a stubbed TLS transport, plus a table asserting the buffered
// and streamed paths render the exact same framing verdict for every
// canned response.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

#include "crypto.h"
#include "http.h"
#include "http/internal.h"
#include "provider.h"
#include "provider/internal.h"
#include "state.h"

static int fails;

#define CHECK(cond) do {                                        \
    if (!(cond)) {                                              \
        fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__,  \
            #cond);                                             \
        fails++;                                                \
    }                                                           \
} while (0)

// ---- stub transport ----

static tls_conn *fake_conn;   // only compared as a handle

static const char *feeds[3];  // one response per successive connection
static int feed_n, conn_n;
static size_t resp_off;
static char reqs[3][4608];    // what each connection was asked to send
static int req_n;
static const char *conn_host;
static int conn_port;
static int tcp_connect_calls;

static void set_feeds(const char *f0, const char *f1, const char *f2)
{
    feeds[0] = f0; feeds[1] = f1; feeds[2] = f2;
    feed_n = f2 != NULL ? 3 : (f1 != NULL ? 2 : 1);
    conn_n = 0;
    req_n = 0;
    conn_host = NULL;
    conn_port = 0;
}

tls_conn *__wrap_tls_connect(const char *host, int port,
                             const char *ca_file,
                             const char *ca_mem, size_t ca_mem_len,
                             int timeout_ms, tls_err *kind,
                             char *errbuf, size_t errcap)
{
    (void)ca_file; (void)ca_mem; (void)ca_mem_len; (void)timeout_ms;
    (void)errbuf; (void)errcap;
    if (kind) *kind = TLS_OK;
    resp_off = 0;
    conn_n++;
    conn_host = host;
    conn_port = port;
    return fake_conn;
}

int __wrap_tls_tcp_connect(const char *host, int port, int timeout_ms,
                           tls_err *kind, char *errbuf, size_t errcap)
{
    (void)host; (void)port; (void)timeout_ms; (void)kind;
    (void)errbuf; (void)errcap;
    tcp_connect_calls++;
    return -1;
}

int __wrap_tls_write_all(tls_conn *c, const void *buf, size_t n)
{
    (void)c;
    if (req_n < 3 && n < sizeof reqs[0]) {
        memcpy(reqs[req_n], buf, n);
        reqs[req_n][n] = '\0';
        req_n++;
    }
    return 0;
}

int __wrap_tls_read(tls_conn *c, void *buf, size_t n)
{
    (void)c;
    const char *f = feeds[conn_n - 1 < feed_n ? conn_n - 1 : feed_n - 1];
    size_t left = strlen(f) - resp_off;
    if (left == 0) return 0;
    size_t take = left < n ? left : n;
    memcpy(buf, f + resp_off, take);
    resp_off += take;
    return (int)take;
}

void __wrap_tls_close(tls_conn *c)
{
    (void)c;
}

// ---- URL probes (N05) ----

static void url_probes(void)
{
    char host[256], path[2048], err[256];
    int port;

    // invalid port spellings never silently become 0 or 443
    CHECK(!split_url("https://host:wat/", host, sizeof host, &port,
                     path, sizeof path, err, sizeof err));
    CHECK(!split_url("https://host:0/", host, sizeof host, &port,
                     path, sizeof path, err, sizeof err));
    CHECK(!split_url("https://host:65536/", host, sizeof host, &port,
                     path, sizeof path, err, sizeof err));
    CHECK(!split_url("https://host:/x", host, sizeof host, &port,
                     path, sizeof path, err, sizeof err));

    // IPv6 literals parse; bracketless ones are rejected, not mangled
    CHECK(split_url("https://[::1]/file", host, sizeof host, &port,
                    path, sizeof path, err, sizeof err));
    CHECK(strcmp(host, "::1") == 0 && port == 443);
    CHECK(strcmp(path, "/file") == 0);
    CHECK(split_url("https://[fe80::1%25eth0]:8443/", host, sizeof host,
                    &port, path, sizeof path, err, sizeof err));
    CHECK(strcmp(host, "fe80::1%eth0") == 0 && port == 8443);
    CHECK(!split_url("https://::1/x", host, sizeof host, &port,
                     path, sizeof path, err, sizeof err));

    // overlong paths are refused, never truncated
    char big[3000];
    strcpy(big, "https://host/");
    memset(big + 13, 'x', sizeof big - 14);
    big[sizeof big - 1] = '\0';
    CHECK(!split_url(big, host, sizeof host, &port,
                     path, sizeof path, err, sizeof err));

    // query-only and fragment-only URLs keep a sane request target
    CHECK(split_url("https://host?q=1", host, sizeof host, &port,
                    path, sizeof path, err, sizeof err));
    CHECK(strcmp(path, "/?q=1") == 0);
    CHECK(split_url("https://host/x#frag", host, sizeof host, &port,
                    path, sizeof path, err, sizeof err));
    CHECK(strcmp(path, "/x") == 0);
    CHECK(split_url("https://host#f", host, sizeof host, &port,
                    path, sizeof path, err, sizeof err));
    CHECK(strcmp(path, "/") == 0);

    // credentials and control characters are rejected
    CHECK(!split_url("https://user@host/x", host, sizeof host, &port,
                     path, sizeof path, err, sizeof err));
    CHECK(!split_url("https://ho\tst/x", host, sizeof host, &port,
                     path, sizeof path, err, sizeof err));

    // plain http and other schemes still name themselves
    CHECK(!split_url("http://host/x", host, sizeof host, &port,
                     path, sizeof path, err, sizeof err));

    // authority rendering: default port omitted, IPv6 bracketed
    char auth[300];
    CHECK(url_authority("::1", 443, auth, sizeof auth)
        && !strcmp(auth, "[::1]"));
    CHECK(url_authority("::1", 8443, auth, sizeof auth)
        && !strcmp(auth, "[::1]:8443"));
    CHECK(url_authority("h.test", 443, auth, sizeof auth)
        && !strcmp(auth, "h.test"));

    // origin compare includes the explicit-default-port normalisation
    CHECK(same_origin("https://h.test/a", "https://h.test:443/b"));
    CHECK(!same_origin("https://h.test/a", "https://h.test:8443/b"));
    CHECK(!same_origin("https://h.test/a", "https://other.test/b"));
}

static void join_probes(void)
{
    char *out;
    out = url_join("https://h.test/a/b?c=1", "/x");
    CHECK(out != NULL && !strcmp(out, "https://h.test/x"));
    free(out);
    out = url_join("https://h.test:8443/a/b", "/x?q");
    CHECK(out != NULL && !strcmp(out, "https://h.test:8443/x?q"));
    free(out);
    out = url_join("https://h.test/a/b", "//other.test/y");
    CHECK(out != NULL && !strcmp(out, "https://other.test/y"));
    free(out);
    out = url_join("https://h.test/a/b", "sub/../z");
    CHECK(out != NULL && !strcmp(out, "https://h.test/a/z"));
    free(out);
    out = url_join("https://h.test/a/b", "z");
    CHECK(out != NULL && !strcmp(out, "https://h.test/a/z"));
    free(out);
    out = url_join("https://h.test/a/b", "../z");
    CHECK(out != NULL && !strcmp(out, "https://h.test/z"));
    free(out);
    out = url_join("https://h.test/a/b", "https://o.test/p#f");
    CHECK(out != NULL && !strcmp(out, "https://o.test/p#f"));
    free(out);
    // foreign schemes and malformed locs stay unresolvable
    CHECK(url_join("https://h.test/a/b", "http://h.test/x") == NULL);
    CHECK(url_join("https://h.test/a/b", "//") == NULL);
    CHECK(url_join("https://h.test/a/b", "//u@h/x") == NULL);
    CHECK(url_join("https://h.test/a/b", "https://h:99999/x") == NULL);
}

// ---- N06: explicit :443 keeps the same auth origin ----

static void auth_probes(void)
{
    setenv("APEGET_WIRE_TOKEN", "sekrit", 1);
    provider_entry e = {0};
    e.type = "gitea";
    e.api = "https://api.example.test";
    e.token_env = "APEGET_WIRE_TOKEN";
    const char *name = NULL;
    char authv[512];

    CHECK(prov_auth(&e, "https://api.example.test/file", &name,
                    authv, sizeof authv));
    CHECK(!strcmp(name, "Authorization") && !strcmp(authv, "token sekrit"));
    // the very same origin, spelled with the default port
    CHECK(prov_auth(&e, "https://api.example.test:443/file", &name,
                    authv, sizeof authv));
    CHECK(!strcmp(authv, "token sekrit"));
    // host case must not split the origin
    CHECK(prov_auth(&e, "https://API.example.TEST/f", &name,
                    authv, sizeof authv));
    // a different port and a foreign host must not carry the token
    CHECK(!prov_auth(&e, "https://api.example.test:8443/f", &name,
                     authv, sizeof authv));
    CHECK(!prov_auth(&e, "https://evil.example/f", &name,
                     authv, sizeof authv));
}

// ---- N12: one framing verdict, two sinks ----

struct resp_case {
    const char *name;
    const char *resp;
    int want_parse;          // 1 = framing accepted by both paths
    int want_status;
    const char *want_body;   // compared on both sinks when 200
};

static const struct resp_case RESP_CASES[] = {
    { "clean-cl",
      "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}", 1, 200, "{}" },
    { "truncated-cl",                       // N12 repro: EOF < declared
      "HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n{}", 0, 0, NULL },
    { "no-cl-eof-body",
      "HTTP/1.1 200 OK\r\n\r\nabc", 1, 200, "abc" },
    { "good-chunked-trailers",
      "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
      "5\r\nhello\r\n6;ext=x\r\nworld!\r\n0\r\nX-T: 1\r\n\r\n",
      1, 200, "helloworld!" },
    { "chunk-crlf-junk",                    // N12 repro: bad chunk CRLF
      "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
      "1\r\nAxx0\r\n\r\n", 0, 0, NULL },
    { "chunk-size-junk",
      "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
      "Z\r\nx\r\n0\r\n\r\n", 0, 0, NULL },
    { "chunk-missing-trailer-terminator",
      "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
      "2\r\nhi\r\n0\r\n", 0, 0, NULL },
    { "te-substring",                       // "chunked" inside a junk token
      "HTTP/1.1 200 OK\r\nTransfer-Encoding: xchunked\r\n\r\n"
      "2\r\nhi\r\n0\r\n\r\n", 0, 0, NULL },
    { "te-gzip",
      "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\n\r\nbody", 0, 0, NULL },
    { "te-plus-cl",                         // framing conflict: smuggling
      "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n"
      "Transfer-Encoding: chunked\r\n\r\n2\r\nhi\r\n0\r\n\r\n", 0, 0, NULL },
    { "dup-cl-conflict",
      "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\nhi",
      0, 0, NULL },
    { "dup-cl-same",
      "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\nhi",
      1, 200, "hi" },
    { "obs-fold",                           // continuation line
      "HTTP/1.1 200 OK\r\nX: 1\r\n\tfolded\r\nContent-Length: 2\r\n\r\n{}",
      0, 0, NULL },
    { "bad-status-line",
      "HTTP/9 200 OK\r\nContent-Length: 2\r\n\r\n{}", 0, 0, NULL },
    { "status-204",
      "HTTP/1.1 204 No Content\r\nContent-Length: 5\r\n\r\n", 1, 204, NULL },
    { "lowercase-headers",
      "HTTP/1.1 200 OK\r\ncontent-length: 2\r\n\r\n{}", 1, 200, "{}" },
};

static char dl_dest[] = "/tmp/ape-get-wire-dest";

static void framing_probes(void)
{
    for (size_t k = 0; k < sizeof RESP_CASES / sizeof RESP_CASES[0]; k++) {
        const struct resp_case *c = &RESP_CASES[k];

        set_feeds(c->resp, NULL, NULL);
        http_resp r = {0};
        char err[256] = "";
        bool bok = http_get_hdr("https://host.test/x", NULL, NULL, 5000,
                                &r, err, sizeof err);
        char *bbody = bok && r.body ? strdup(r.body) : NULL;
        int bstatus = bok ? r.status : 0;
        http_resp_free(&r);

        set_feeds(c->resp, NULL, NULL);
        int sstatus = 0, kind = 0;
        bool sok = http_download("https://host.test/x", NULL, NULL, 5000,
                                 0, NULL, NULL, dl_dest, &sstatus, &kind,
                                 err, sizeof err);
        char sbody[512] = "";
        size_t sn = 0;
        FILE *f = sok && c->want_status == 200 ? fopen(dl_dest, "rb") : NULL;
        if (f != NULL) { sn = fread(sbody, 1, sizeof sbody - 1, f); fclose(f); }
        sbody[sn] = '\0';
        remove(dl_dest);

        // the two paths must AGREE: same parse verdict, same status
        CHECK(bok == (sstatus != 0));
        CHECK(bstatus == sstatus);
        CHECK(bok == (c->want_parse == 1));
        if (c->want_parse && c->want_status == 200) {
            CHECK(bbody != NULL && !strcmp(bbody, c->want_body));
            CHECK(sok && !strcmp(sbody, c->want_body));
        }
        free(bbody);
        if (fails) {
            fprintf(stderr, "  (case %s)\n", c->name);
            return;
        }
    }
}

// ---- request-line rendering and redirect policy over the stub ----

static void request_probes(void)
{
    http_resp r = {0};
    char err[256] = "";

    // fragment never enters the request target; query survives
    set_feeds("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}", NULL, NULL);
    CHECK(http_get_hdr("https://host.test/a/b?q=1#frag", NULL, NULL, 5000,
                       &r, err, sizeof err));
    CHECK(!strncmp(reqs[0], "GET /a/b?q=1 HTTP/1.1\r\n",
                   strlen("GET /a/b?q=1 HTTP/1.1\r\n")));
    CHECK(strstr(reqs[0], "#") == NULL);
    http_resp_free(&r);

    // IPv6 literal: connect target unbracketed, Host header bracketed
    set_feeds("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}", NULL, NULL);
    CHECK(http_get_hdr("https://[::1]:8443/x", NULL, NULL, 5000, &r,
                       err, sizeof err));
    CHECK(conn_host != NULL && !strcmp(conn_host, "::1"));
    CHECK(conn_port == 8443);
    CHECK(strstr(reqs[0], "Host: [::1]:8443\r\n") != NULL);
    http_resp_free(&r);

    // relative Location resolves against the base and keeps same-origin
    // auth; the fragment in the next Location is dropped again
    set_feeds("HTTP/1.1 302 Found\r\nLocation: sub/../x#f\r\n"
              "Content-Length: 0\r\n\r\n",
              "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}", NULL);
    CHECK(http_get_hdr("https://h.test/a/b", "Authorization", "token tok",
                       5000, &r, err, sizeof err));
    CHECK(r.status == 200);
    CHECK(!strncmp(reqs[1], "GET /a/x HTTP/1.1\r\n",
                   strlen("GET /a/x HTTP/1.1\r\n")));
    CHECK(strstr(reqs[1], "Authorization: token tok") != NULL);
    http_resp_free(&r);

    // cross-origin absolute Location drops the header
    set_feeds("HTTP/1.1 302 Found\r\nLocation: https://other.test/x\r\n"
              "Content-Length: 0\r\n\r\n",
              "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}", NULL);
    CHECK(http_get_hdr("https://h.test/a", "Authorization", "token tok",
                       5000, &r, err, sizeof err));
    CHECK(r.status == 200);
    CHECK(strstr(reqs[0], "Authorization: token tok") != NULL);
    CHECK(strstr(reqs[1], "Authorization") == NULL);
    http_resp_free(&r);

    // network-path Location carries its own authority (RFC 3986 §4.2);
    // the base only supplies the scheme
    set_feeds("HTTP/1.1 302 Found\r\nLocation: //h.test:8443/y\r\n"
              "Content-Length: 0\r\n\r\n",
              "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}", NULL);
    CHECK(http_get_hdr("https://h.test/a", NULL, NULL, 5000, &r, err,
                       sizeof err));
    CHECK(!strncmp(reqs[1], "GET /y HTTP/1.1\r\n",
                   strlen("GET /y HTTP/1.1\r\n")));
    CHECK(strstr(reqs[1], "Host: h.test:8443") != NULL);
    http_resp_free(&r);

    // foreign-scheme Location is reported as-is, never followed
    set_feeds("HTTP/1.1 302 Found\r\nLocation: ftp://h.test/x\r\n"
              "Content-Length: 0\r\n\r\n", NULL, NULL);
    CHECK(http_get_hdr("https://h.test/a", NULL, NULL, 5000, &r, err,
                       sizeof err));
    CHECK(r.status == 302 && req_n == 1);
    http_resp_free(&r);
}

// ---- N03: https:// proxy URLs are refused, not spoken to ----

static void proxy_probes(void)
{
    http_resp r = {0};
    char err[512] = "";
    http_set_proxy("https://proxy.test:8080");
    int before = tcp_connect_calls;
    CHECK(!http_get_hdr("https://host.test/x", NULL, NULL, 5000, &r, err,
                       sizeof err));
    CHECK(tcp_connect_calls == before);   // refused before touching TCP
    CHECK(strstr(err, "https:// proxy URLs are not supported") != NULL);
    http_set_proxy(NULL);
}

int main(void)
{
    fake_conn = (tls_conn *)&fails;   // any non-NULL handle
    url_probes();
    join_probes();
    auth_probes();
    framing_probes();
    request_probes();
    proxy_probes();
    if (fails) {
        fprintf(stderr, "http_wire: %d failed\n", fails);
        return 1;
    }
    printf("http_wire: all checks passed\n");
    return 0;
}
