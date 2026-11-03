#ifndef APEGET_CRYPTO_H
#define APEGET_CRYPTO_H

#include <stddef.h>
#include <stdbool.h>

// TLS client (the only mbed TLS consumer): TLS 1.2, verified against a
// CA file or the embedded bundle — chosen by the caller in http.

typedef struct tls_conn tls_conn;

// Failure classes for network-error messages.
typedef enum { TLS_OK = 0, TLS_TIMEOUT, TLS_DNS, TLS_CONNECT, TLS_OTHER } tls_err;

// Resolve, connect and handshake on port under one combined deadline;
// failure returns NULL with the message in errbuf and the class in *kind.
tls_conn *tls_connect(const char *host, int port, const char *ca_file,
                      const char *ca_mem, size_t ca_mem_len,
                      int timeout_ms, tls_err *kind,
                      char *errbuf, size_t errcap);

// The two halves, for proxying: tls_tcp_connect returns a connected blocking
// fd; tls_connect_fd handshakes over it, owning the fd (closed on failure).
int tls_tcp_connect(const char *host, int port, int timeout_ms,
                    tls_err *kind, char *errbuf, size_t errcap);
tls_conn *tls_connect_fd(int fd, const char *host, const char *ca_file,
                         const char *ca_mem, size_t ca_mem_len,
                         int timeout_ms, tls_err *kind,
                         char *errbuf, size_t errcap);

// Full-buffer write; 0 ok, -1 error.
int tls_write_all(tls_conn *c, const void *buf, size_t n);

// 1..n bytes read, 0 at clean EOF, -2 when the read stalled past the
// timeout, -1 on any other error.
int tls_read(tls_conn *c, void *buf, size_t n);

void tls_close(tls_conn *c);

// SHA-256 of a whole file, lowercase hex into out[65] (asset
// verification). False when the file cannot be read.
bool crypto_sha256_file(const char *path, char out[65]);
// hash an already-open fd from its current offset to EOF; used as the
// single-read gate for delta sources (open-once, no reopen race)
bool crypto_sha256_fd(int fd, char out[65]);

#endif
