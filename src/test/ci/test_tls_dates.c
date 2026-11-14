// Handshake against a local TLS server through the production client path.
// Prints the outcome; exit 0 iff the handshake succeeded.
#include "crypto.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv)
{
    if (argc < 3) {
        fprintf(stderr, "usage: test_tls_dates PORT CA_PEM [HOST]\n");
        return 2;
    }
    int port = atoi(argv[1]);
    const char *host = argc > 3 ? argv[3] : "127.0.0.1";
    tls_err kind = TLS_OK;
    char errbuf[512] = {0};
    tls_conn *c = tls_connect(host, port, argv[2], NULL, 0, 15000,
        &kind, errbuf, sizeof errbuf);
    if (c == NULL) {
        printf("handshake-rejected kind=%d msg=%s\n", (int)kind, errbuf);
        return 1;
    }
    printf("handshake-ok\n");
    tls_close(c);
    return 0;
}
