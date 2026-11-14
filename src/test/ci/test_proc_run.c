// subprocess runner semantics (N02 libc port): stdout+stderr capture,
// exit-class return, missing-program -1, and timeout group-kill -2.
#include "proc.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static long long now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

int main(void)
{
    char *out = NULL;
    const char *run[] = { "/bin/sh", "-c",
        "echo to-stdout; echo to-stderr >&2; exit 3", NULL };
    long rc = proc_run(run, 5000, &out);
    if (rc != 3 || out == NULL || !strstr(out, "to-stdout")
        || !strstr(out, "to-stderr")) {
        fprintf(stderr, "capture or exit class failed: rc=%ld\n", rc);
        return 1;
    }
    free(out);
    out = NULL;
    const char *missing[] = { "/nonexistent/ape-get-probe", NULL };
    if (proc_run(missing, 5000, &out) != -1) {
        fprintf(stderr, "missing program did not fail with -1\n");
        return 1;
    }
    const char *sleepy[] = { "/bin/sh", "-c", "echo hi; sleep 30", NULL };
    long long t0 = now_ms();
    rc = proc_run(sleepy, 300, &out);
    long long dt = now_ms() - t0;
    if (rc != -2 || dt > 5000) {
        fprintf(stderr, "timeout kill failed: rc=%ld dt=%lldms\n", rc, dt);
        return 1;
    }
    free(out); // the partial capture before the deadline
    puts("proc_run capture, exit class, missing program and timeout passed");
    return 0;
}
