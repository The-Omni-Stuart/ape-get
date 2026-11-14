#include "crypto.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netdb.h>
#include <stdio.h>
#include <sys/socket.h>
#include <unistd.h>

static int resolver_mode;
static int resolver_calls;

int __real_getaddrinfo(const char *, const char *, const struct addrinfo *,
                       struct addrinfo **);

int __wrap_getaddrinfo(const char *host, const char *port,
                       const struct addrinfo *hints, struct addrinfo **result)
{
    resolver_calls++;
    if (resolver_mode == 2) return EAI_NONAME;
    if (resolver_mode == 3) return EAI_AGAIN;
    if (resolver_mode == 1 && hints->ai_family == AF_UNSPEC) return EAI_AGAIN;
    return __real_getaddrinfo(host, port, hints, result);
}

int main(void)
{
    int listener = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in address = {0};
    address.sin_family = AF_INET;
    inet_pton(AF_INET, "127.0.0.1", &address.sin_addr);
    socklen_t length = sizeof(address);
    if (listener < 0 || bind(listener, (struct sockaddr *)&address, length)
        || listen(listener, 1)
        || getsockname(listener, (struct sockaddr *)&address, &length)) {
        perror("loopback listener");
        if (listener >= 0) close(listener);
        return 1;
    }

    for (int retry = 0; retry < 2; retry++) {
        resolver_mode = retry;
        resolver_calls = 0;
        tls_err kind = TLS_OTHER;
        char error[256] = "";
        int client = tls_tcp_connect("127.0.0.1", ntohs(address.sin_port), 1000,
                                     &kind, error, sizeof(error));
        if (client < 0) {
            fprintf(stderr, "loopback connection failed: %s\n", error);
            close(listener);
            return 1;
        }
        int flags = fcntl(client, F_GETFL, 0);
        int peer = accept(listener, NULL, NULL);
        close(client);
        if (peer >= 0) close(peer);
        if (peer < 0 || flags < 0 || (flags & O_NONBLOCK)
            || resolver_calls != retry + 1) {
            fprintf(stderr, "connection, blocking mode, or IPv4 retry failed\n");
            close(listener);
            return 1;
        }
    }
    close(listener);
    for (int mode = 2; mode <= 3; mode++) {
        resolver_mode = mode;
        resolver_calls = 0;
        tls_err kind = TLS_OTHER;
        char error[256] = "";
        int client = tls_tcp_connect("unavailable.invalid", 443, 1000,
                                     &kind, error, sizeof(error));
        if (client >= 0) close(client);
        if (client >= 0 || kind != TLS_DNS || resolver_calls != mode - 1) {
            fprintf(stderr, "permanent/transient DNS failure handling failed\n");
            return 1;
        }
    }
    puts("TCP connections, blocking mode, and IPv4 DNS retry passed");
    return 0;
}
