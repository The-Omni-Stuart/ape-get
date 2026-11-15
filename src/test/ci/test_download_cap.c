// Declared-size cap probe for http_download: the transport is stubbed at
// the TLS seam, so a response that merely DECLARES more than max_bytes
// must be refused with the destination untouched and no leaked FILE*
// (the http.h false => absent-or-untouched promise).
#include <dirent.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "crypto.h"
#include "http.h"

static int fails;

#define CHECK(cond) do {                                        \
    if (!(cond)) {                                              \
        fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__,  \
            #cond);                                             \
        fails++;                                                \
    }                                                           \
} while (0)

// the whole canned response: declares 100 body bytes, delivers 5
static const char RESP[] =
    "HTTP/1.1 200 OK\r\n"
    "Content-Length: 100\r\n"
    "\r\n"
    "12345";
static size_t resp_off;

static int conn_stub;   // any non-NULL handle works for this seam

tls_conn *__wrap_open_conn(const char *host, int port, const char *ca_file,
    const char *ca_mem, size_t ca_mem_len, int timeout_ms, tls_err *kind,
    char *errbuf, size_t errcap)
{
    (void)host; (void)port; (void)ca_file; (void)ca_mem;
    (void)ca_mem_len; (void)timeout_ms; (void)errbuf; (void)errcap;
    if (kind != NULL) *kind = TLS_OK;
    resp_off = 0;
    return (tls_conn *)&conn_stub;
}

int __wrap_tls_write_all(tls_conn *c, const void *buf, size_t n)
{
    (void)c; (void)buf; (void)n;
    return 0;
}

int __wrap_tls_read(tls_conn *c, void *buf, size_t n)
{
    (void)c;
    if (resp_off >= sizeof RESP - 1) return 0;
    size_t take = sizeof RESP - 1 - resp_off;
    if (take > n) take = n;
    memcpy(buf, RESP + resp_off, take);
    resp_off += take;
    return (int)take;
}

void __wrap_tls_close(tls_conn *c)
{
    (void)c;
}

static int count_fds(void)
{
    DIR *d = opendir("/proc/self/fd");
    if (d == NULL) return -1;
    int n = 0;
    struct dirent *de;
    while ((de = readdir(d)) != NULL) n++;
    closedir(d);
    return n;
}

int main(void)
{
    const char dest[] = "/tmp/ape-get-cap-probe-dest";
    const char keep[] = "PRE-EXISTING CONTENT THAT MUST SURVIVE";
    int fd0 = count_fds();
    CHECK(fd0 >= 0);

    int fd = open(dest, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    CHECK(fd >= 0);
    CHECK(write(fd, keep, sizeof keep) == (ssize_t)sizeof keep);
    close(fd);

    int status = 0, errkind = 0;
    char errbuf[256] = "";
    bool ok = http_download("https://example.invalid/big.bin", NULL, NULL,
                            5000, 8, NULL, NULL, dest, &status, &errkind,
                            errbuf, sizeof errbuf);
    CHECK(!ok);
    CHECK(status == 0);
    CHECK(strstr(errbuf, "exceeds") != NULL);

    // the pre-existing destination must be byte-identical, not truncated
    char back[64] = "";
    int rfd = open(dest, O_RDONLY);
    CHECK(rfd >= 0);
    ssize_t got = read(rfd, back, sizeof back - 1);
    close(rfd);
    CHECK(got == (ssize_t)sizeof keep);
    CHECK(!memcmp(back, keep, sizeof keep));

    // and the refused attempt must not leak a descriptor
    int fd1 = count_fds();
    CHECK(fd1 >= 0 && fd1 <= fd0);

    remove(dest);
    if (fails) {
        fprintf(stderr, "download_cap: %d failed\n", fails);
        return 1;
    }
    printf("download_cap: all checks passed\n");
    return 0;
}
