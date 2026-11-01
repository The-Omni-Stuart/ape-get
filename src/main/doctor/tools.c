// Availability probes behind the doctor tool lines: localhost listeners,
// rnsd liveness, Reticulum config — cheap, spawn-free checks.

#include "doctor/internal.h"

#include "path.h"

#include <arpa/inet.h>
#include <dirent.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

// localhost TCP probe, 150 ms deadline: true if something listens
bool tcp_probe_local(int port)
{
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return false;
    struct sockaddr_in sa = {0};
    sa.sin_family = AF_INET;
    sa.sin_port = htons((unsigned short)port);
    inet_pton(AF_INET, "127.0.0.1", &sa.sin_addr);
    struct timeval tv = { .tv_sec = 0, .tv_usec = 150 * 1000 };
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    bool ok = connect(fd, (struct sockaddr *)&sa, sizeof sa) == 0;
    close(fd);
    return ok;
}

// rnsd alive: scan /proc for an rnsd comm or cmdline token (script execs keep
// the script basename, interpreter launches show python). One pass, no spawn.
bool rnsd_running(void)
{
    DIR *d = opendir("/proc");
    if (d == NULL) return false;
    struct dirent *e;
    bool found = false;
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] < '1' || e->d_name[0] > '9') continue;
        char p[64];
        snprintf(p, sizeof p, "/proc/%.16s/comm", e->d_name);
        FILE *f = fopen(p, "r");
        if (f != NULL) {
            char ln[32];
            if (fgets(ln, sizeof ln, f) != NULL && !strncmp(ln, "rnsd", 4))
                found = true;
            fclose(f);
        }
        if (found) break;
        snprintf(p, sizeof p, "/proc/%.16s/cmdline", e->d_name);
        f = fopen(p, "r");
        if (f == NULL) continue;
        char buf[512];
        size_t n = fread(buf, 1, sizeof buf - 1, f);
        fclose(f);
        buf[n] = '\0';
        for (size_t i = 0; i < n; ) {
            const char *tok = buf + i;
            const char *base = strrchr(tok, '/');
            if (!strcmp(base != NULL ? base + 1 : tok, "rnsd")) {
                found = true;
                break;
            }
            i += strlen(tok) + 1;
        }
        if (found) break;
    }
    closedir(d);
    return found;
}

// RNS configured but idle: ~/.reticulum/config exists
bool rns_configured(void)
{
    char *h = path_home();
    if (h == NULL) return false;
    char *cfg = path_fmt("%s/.reticulum/config", h);
    bool ok = cfg != NULL && access(cfg, R_OK) == 0;
    free(cfg);
    free(h);
    return ok;
}

