#ifndef APEGET_HTTP_INTERNAL_H
#define APEGET_HTTP_INTERNAL_H

// http-module internals: the tunnel-or-direct TLS connector, the CA
// bundle, the URL split and the response-block probes. Public: http.h.

#include <stdbool.h>
#include <stddef.h>

#include "crypto.h"

extern const unsigned char apeget_ca_der[];
extern const unsigned long apeget_ca_der_len;

tls_conn *open_conn(const char *host, int port, const char *ca_file,
    const char *ca_mem, size_t ca_mem_len, int timeout_ms, tls_err *kind,
    char *errbuf, size_t errcap);

// http/parse.c: response-block parsing shared by the two request paths
bool header_get(const char *head, const char *name,
    const char **vs, const char **ve);
bool chunked_copy(const char *p, const char *end, char **body, size_t *bn);
const char *mem_find_ci(const char *h, size_t hn, const char *n);
const char *mem_find(const char *h, size_t hn, const char *n, size_t nn);
bool hdr_ll(const char *raw, size_t hoff, const char *name, long long *v);

// http/request.c: https-only URL split; host[:port] origin compare
bool split_url(const char *url, char *host, size_t hostcap, int *port,
    char *path, size_t pathcap, char *errbuf, size_t errcap);
bool same_origin(const char *a, const char *b);

#endif
