#ifndef APEGET_HTTP_H
#define APEGET_HTTP_H

#include <stdbool.h>
#include <stddef.h>

// HTTPS client: GET with bearer token, Content-Length/chunked bodies,
// capped size, https only. Trust: $APEGET_CA_BUNDLE, else embedded bundle.

// Mirror crypto.h tls_err; the provider layer turns these into
// network-error messages.
typedef enum { HTTP_ERR_NONE = 0, HTTP_ERR_TIMEOUT, HTTP_ERR_DNS,
               HTTP_ERR_CONNECT, HTTP_ERR_OTHER } http_err;

// Process-wide proxy: "http[s]://host[:port]" CONNECTs, "socks5[h]://"
// SOCKS5s, "tor" means socks5 127.0.0.1:9050; NULL or empty goes direct.
void http_set_proxy(const char *url);

typedef struct {
    int status;              // 0 on transport failure (see errbuf)
    char *body;              // malloc'd, NUL-terminated; may be empty
    size_t len;
    char *location;          // Location header or NULL
    int errkind;             // http_err, valid when http_get returned false
} http_resp;

// timeout_ms covers connect+handshake and each read stall; false is a
// transport failure (errbuf), HTTP errors return with their status.
bool http_get(const char *url, const char *auth, int timeout_ms,
              http_resp *out, char *errbuf, size_t errcap);

// A named header instead of Authorization (GitLab uses "PRIVATE-TOKEN");
// NULL value = none. auth above is the full header value ("Bearer xyz").
bool http_get_hdr(const char *url, const char *name, const char *value,
                  int timeout_ms, http_resp *out, char *errbuf, size_t errcap);

// Body-progress sink (asset downloads): called from the read loop with
// cumulative body bytes and Content-Length (-1 when unknown).
typedef void (*http_prog)(size_t done, long long total, void *ud);

// http_get_hdr with a progress callback (NULL = none).
bool http_get_hdr_prog(const char *url, const char *name, const char *value,
                       int timeout_ms, http_prog cb, void *ud,
                       http_resp *out, char *errbuf, size_t errcap);

// Streaming GET to dest_path (asset downloads): fixed buffer to disk.
// max_bytes > 0 aborts the stream the moment the body outgrows it (the
// hostile-padding cap of spec I2: refused before airtime and disk are
// spent); 0 = unlimited. true = full 200 body; false leaves the file
// absent or untouched.
bool http_download(const char *url, const char *name, const char *value,
                   int timeout_ms, long long max_bytes,
                   http_prog cb, void *ud,
                   const char *dest_path, int *status, int *errkind,
                   char *errbuf, size_t errcap);

void http_resp_free(http_resp *r);

// percent-encode for query components (malloc'd)
char *http_url_encode(const char *s);

#endif
