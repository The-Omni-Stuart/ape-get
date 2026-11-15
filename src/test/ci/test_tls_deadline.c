/*
 * test_tls_deadline.c — the whole connect+handshake obeys one deadline (N04).
 *
 * Wrapped syscalls simulate a network that never answers: connect() parks
 * in progress and poll() burns the timeout it is given. Probe A runs the
 * multi-address connect and asserts the SUM of poll budgets stays within
 * the deadline (HEAD gives every address the full timeout). Probe B hands
 * tls_connect_fd a socket whose peer never writes: the handshake must die
 * with a timeout error through the deadline-aware bio (HEAD's bio has no
 * bounded poll at all).
 */
#define _POSIX_C_SOURCE 200809L
#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <netinet/in.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include "crypto.h"

static int poll_count;
static int poll_sum_ms;
static int last_poll_ms = -1;

int __wrap_poll(struct pollfd *fds, nfds_t n, int timeout)
{
    struct timespec ts;
    (void)fds; (void)n;
    poll_count++;
    poll_sum_ms += timeout > 0 ? timeout : 0;
    last_poll_ms = timeout;
    if (timeout > 0) {
        /* burn the budget we were given, like an unresponsive peer */
        ts.tv_sec = timeout / 1000;
        ts.tv_nsec = (long)(timeout % 1000) * 1000000L;
        nanosleep(&ts, NULL);
    }
    return 0; /* never readable, never writable */
}

static int addr_count = 2;

int __wrap_getaddrinfo(const char *node, const char *service,
                       const struct addrinfo *hints, struct addrinfo **res)
{
    struct addrinfo *head = NULL;
    (void)hints;
    for (int i = 0; i < addr_count; i++) {
        struct addrinfo *ai = calloc(1, sizeof(*ai));
        struct sockaddr_in *sa = calloc(1, sizeof(*sa));
        if (ai == NULL || sa == NULL)
            return EAI_MEMORY;
        ai->ai_family = AF_INET;
        ai->ai_socktype = SOCK_STREAM;
        ai->ai_addrlen = sizeof(*sa);
        ai->ai_addr = (struct sockaddr *)sa;
        sa->sin_family = AF_INET;
        sa->sin_port = htons(1);
        inet_pton(AF_INET, i == 0 ? "127.0.0.1" : "127.0.0.2", &sa->sin_addr);
        ai->ai_canonname = strdup(node != NULL ? node : "stub");
        ai->ai_next = head;
        head = ai;
    }
    (void)service;
    *res = head;
    return 0;
}

int __wrap_connect(int fd, const struct sockaddr *sa, socklen_t len)
{
    (void)fd; (void)sa; (void)len;
    errno = EINPROGRESS; /* a connect that never completes */
    return -1;
}

static long long now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int probe_connect_budget(void)
{
    char err[256];
    long long t0, spent;
    tls_err kind = TLS_OK;
    int fd, count_before = poll_count;

    poll_sum_ms = 0;
    t0 = now_ms();
    fd = tls_tcp_connect("127.0.0.1", 1, 300, &kind, err, sizeof(err));
    spent = now_ms() - t0;

    if (fd >= 0) {
        fprintf(stderr, "connect must not succeed against a dead port\n");
        return 1;
    }
    if (poll_count - count_before < 1) {
        fprintf(stderr, "connect did not use a bounded poll\n");
        return 1;
    }
    /* the deadline is shared across addresses: total poll budget must not
       exceed it. HEAD bills every address the full timeout: 2 x 300 here. */
    if (poll_sum_ms > 350) {
        fprintf(stderr, "poll budgets sum to %d ms for a 300 ms deadline "
                        "(every address got the full timeout)\n", poll_sum_ms);
        return 1;
    }
    if (spent > 500) {
        fprintf(stderr, "multi-address connect took %lld ms against a 300 ms "
                        "deadline\n", spent);
        return 1;
    }
    if (kind != TLS_TIMEOUT) {
        fprintf(stderr, "dead-port connect reported kind %d, want timeout\n",
                (int)kind);
        return 1;
    }
    printf("connect budget: %d poll(s), %d ms billed, %lld ms spent\n",
           poll_count - count_before, poll_sum_ms, spent);
    return 0;
}

static int probe_handshake_deadline(const char *ca_path)
{
    FILE *f = fopen(ca_path, "rb");
    char err[256];
    tls_err kind = TLS_OK;
    int sv[2];
    long long t0, spent;
    int poll_before = poll_count;

    if (f == NULL) {
        fprintf(stderr, "cannot read %s\n", ca_path);
        return 1;
    }
    fclose(f);
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sv) != 0) {
        perror("socketpair");
        return 1;
    }
    /* leave sv[1] unread: the handshake stalls waiting on the peer */
    close(sv[1]);

    err[0] = '\0';
    t0 = now_ms();
    tls_conn *c = tls_connect_fd(sv[0], "localhost", ca_path, NULL, 0, 250,
                                 &kind, err, sizeof(err));
    spent = now_ms() - t0;
    if (c != NULL) {
        fprintf(stderr, "handshake with a silent peer must not succeed\n");
        tls_close(c);
        return 1;
    }
    if (poll_count == poll_before) {
        fprintf(stderr, "handshake reached no error through a bounded poll "
                        "(no deadline-aware bio): %s\n", err);
        return 1;
    }
    if (last_poll_ms < 200 || last_poll_ms > 250) {
        fprintf(stderr, "bio poll got timeout %d for a 250 ms deadline\n",
                last_poll_ms);
        return 1;
    }
    if (kind != TLS_TIMEOUT || strcmp(err, "tls handshake timed out") != 0) {
        fprintf(stderr, "expected a handshake timeout, got kind %d: %s\n",
                (int)kind, err);
        return 1;
    }
    if (spent >= 1000 || spent < 200) {
        fprintf(stderr, "handshake timeout took %lld ms (want ~250 ms)\n",
                spent);
        return 1;
    }
    printf("handshake deadline: %s in %lld ms\n", err, spent);
    return 0;
}

int main(int argc, char **argv)
{
    int bad = 0;
    if (argc < 2) {
        fprintf(stderr, "usage: test_tls_deadline <ca.pem>\n");
        return 2;
    }
    bad |= probe_connect_budget();
    bad |= probe_handshake_deadline(argv[1]);
    if (bad)
        fprintf(stderr, "FAIL\n");
    else
        printf("PASS\n");
    return bad ? 1 : 0;
}
