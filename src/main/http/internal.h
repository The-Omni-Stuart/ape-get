#ifndef APEGET_HTTP_INTERNAL_H
#define APEGET_HTTP_INTERNAL_H

// http-module internals: the tunnel-or-direct TLS connector, the CA
// bundle, the strict URL split/resolver and the shared response
// framing. Public: http.h.

#include <stdbool.h>
#include <stddef.h>

#include "crypto.h"

// one scratch buffer size: caps the header block independently of any
// body cap, and serves as the body framing window
#define HTTP_BUF (1UL << 16)

extern const unsigned char apeget_ca_der[];
extern const unsigned long apeget_ca_der_len;

tls_conn *open_conn(const char *host, int port, const char *ca_file,
    const char *ca_mem, size_t ca_mem_len, int timeout_ms, tls_err *kind,
    char *errbuf, size_t errcap);

// http/url.c: strict absolute-https split, host[:port] authority,
// RFC-semantics Location resolution, https-only host[:port] compare
bool split_url(const char *url, char *host, size_t hostcap, int *port,
    char *path, size_t pathcap, char *errbuf, size_t errcap);
bool url_authority(const char *host, int port, char *out, size_t cap);
char *url_join(const char *base, const char *loc);
bool same_origin(const char *a, const char *b);

// http/framing.c: one strict framing machine; the sinks below decide
// where the body goes, the framing verdict is shared
typedef struct {
    int status;
    long long clen;      // Content-Length, -1 when absent
    bool chunked;
    bool bodyless;       // 1xx/204/304: no body regardless of headers
    char *location;      // malloc'd Location or NULL (caller frees)
} resp_head;

bool resp_parse_head(const char *block, size_t n, resp_head *h,
    char *errbuf, size_t errcap);

// read + parse headers over tls; on success *have = already-read body
// bytes slid to the front of buf (cap must be HTTP_BUF or larger)
bool resp_head_read(tls_conn *c, const char *hostp, char *buf, size_t cap,
    size_t *have, resp_head *h, int *kind, char *errbuf, size_t errcap);

typedef struct {
    // reader: >0 bytes, 0 EOF, -2 timeout, -1 error (tls_read shape)
    int (*read)(void *ud, char *buf, size_t cap);
    // sink: body bytes in order; false stops with RESP_SINKERR
    bool (*sink)(void *ud, const char *p, size_t n);
    void *ud;
} resp_io;

typedef enum {
    RESP_DONE = 0, RESP_TRUNC, RESP_MALFORMED, RESP_TIMEOUT,
    RESP_TOOBIG, RESP_SINKERR
} resp_stop;

// frame the body: buf[0..*have) holds already-read body bytes; *written
// receives the bytes accepted by the sink
resp_stop resp_read_body(resp_io *io, char *buf, size_t *have, size_t cap,
    const resp_head *h, long long max_bytes, long long *written);

#endif
