// subprocess runner semantics (N02 libc port): stdout+stderr capture,
// exit-class return, missing-program -1, timeout group-kill -2, and a cwd,
// env and output-buffer the child actually honours.
#include "proc.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

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
    out = NULL;

    char dir[] = "apg-proc-cwd-XXXXXX";
    if (mkdtemp(dir) == NULL) return 1;
    const char *pwd_argv[] = { "/bin/sh", "-c", "pwd", NULL };
    rc = proc_run_env(pwd_argv, NULL, dir, 5000, &out);
    if (rc != 0 || out == NULL || strstr(out, dir) == NULL) {
        fprintf(stderr, "child did not run in the requested cwd: rc=%ld out=%s\n",
            rc, out != NULL ? out : "(none)");
        return 1;
    }
    free(out);
    out = NULL;

    if (proc_run_env(pwd_argv, NULL, "/nonexistent/ape-get-cwd", 5000,
        &out) != -1) {
        fprintf(stderr, "missing cwd did not fail with -1\n");
        return 1;
    }

    const char *envline[] = { "/bin/sh", "-c", "echo APEGET_PROBE=$APEGET_PROBE",
        NULL };
    char *child_env[] = { "APEGET_PROBE=scrubbed-and-set", "PATH=/bin:/usr/bin",
        NULL };
    rc = proc_run_env(envline, child_env, NULL, 5000, &out);
    if (rc != 0 || out == NULL || !strstr(out, "APEGET_PROBE=scrubbed-and-set")) {
        fprintf(stderr, "child env not applied: rc=%ld out=%s\n",
            rc, out != NULL ? out : "(none)");
        return 1;
    }
    free(out);
    out = NULL;

    // a caller that wants no output still gets the exit class, and is not
    // handed a null place for the runner to write the buffer into
    const char *quietly[] = { "/bin/sh", "-c", "echo discarded; exit 7", NULL };
    if (proc_run_env(quietly, NULL, NULL, 5000, NULL) != 7) {
        fprintf(stderr, "no-output caller could not get its exit class\n");
        return 1;
    }

    rmdir(dir);
    puts("proc_run capture, exit class, missing program, timeout, cwd, env"
        " and no-output caller passed");
    return 0;
}
