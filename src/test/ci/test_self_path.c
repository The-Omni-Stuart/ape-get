// Exercise executable selection with and without /proc, independent of host OS.
// As with the fuzz targets, include the module to reach its private helper.
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static const char *proc_target;
static int proc_error = ENOENT;

static ssize_t fixture_readlink(const char *path, char *buf, size_t cap)
{
    if (strcmp(path, "/proc/self/exe") != 0) abort();
    if (proc_target == NULL) {
        errno = proc_error;
        return -1;
    }
    size_t n = strlen(proc_target);
    if (n > cap) n = cap;
    memcpy(buf, proc_target, n);
    return (ssize_t)n;
}

#define readlink fixture_readlink
#include "pipeline/self.c"
#undef readlink

static int check(const char *label, const char *proc, const char *arg,
                 const char *expected)
{
    proc_target = proc;
    free(self_argv0);
    self_argv0 = NULL;
    self_set_argv0(arg);
    char *got = self_exe_path();
    bool ok = expected == NULL ? got == NULL
        : got != NULL && strcmp(got, expected) == 0;
    if (!ok) fprintf(stderr, "%s: expected %s, got %s\n", label,
                     expected != NULL ? expected : "NULL",
                     got != NULL ? got : "NULL");
    free(got);
    return ok ? 0 : 1;
}

int main(void)
{
    char dir[] = "apeget-self-path-XXXXXX";
    if (mkdtemp(dir) == NULL) return 1;
    char *ape = path_join(dir, "ape-get.com");
    char *loader = path_join(dir, "loader");
    if (ape == NULL || loader == NULL) return 1;

    // Only the APE identification trailer is needed; never execute this file.
    unsigned char image[128] = {0};
    memcpy(image + 80, ".cosmo\n", 7);
    memcpy(image + 106, "PK\x05\x06", 4);
    FILE *f = fopen(ape, "wb");
    if (f == NULL) return 1;
    bool written = fwrite(image, 1, sizeof image, f) == sizeof image;
    if (fclose(f) != 0 || !written) return 1;
    f = fopen(loader, "wb");
    if (f == NULL || fclose(f) != 0) return 1;

    int failures = 0;
    failures += check("Linux APE wins", ape, loader, ape);
    failures += check("Linux loader uses argv", loader, ape, ape);
    failures += check("Linux staged fake", loader, NULL, loader);
    failures += check("missing proc uses argv", NULL, ape, ape);
    failures += check("missing proc and argv", NULL, NULL, NULL);
    failures += check("missing proc and file", NULL, "/missing/ape-get", NULL);
    proc_error = EINVAL;
    failures += check("unsupported proc uses argv", NULL, ape, ape);
    if (setenv("PATH", dir, 1) != 0) failures++;
    else failures += check("bare argv through PATH", NULL, "ape-get.com", ape);

    free(self_argv0);
    self_argv0 = NULL;
    unlink(ape);
    unlink(loader);
    rmdir(dir);
    free(ape);
    free(loader);
    if (failures == 0) puts("8 executable-path cases passed");
    return failures != 0;
}
