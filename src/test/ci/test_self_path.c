// Exercise executable selection with and without /proc, independent of host OS.
// As with the fuzz targets, include the module to reach its private helper.
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
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

static int cases = 0;

static int check(const char *label, const char *proc, const char *arg,
                 const char *expected)
{
    cases++;
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

static int write_bytes(const char *path, const unsigned char *p, size_t n)
{
    FILE *f = fopen(path, "wb");
    if (f == NULL) return 1;
    bool ok = n == 0 || fwrite(p, 1, n, f) == n;
    return fclose(f) != 0 || !ok ? 1 : 0;
}

// "PK\x05\x06" + one comment-length byte + ".cosmo\n": the order real
// Cosmopolitan builds use. The fixture used to put .cosmo before the zip
// header, a shape no shipped binary has.
#define APE_MARK "PK\x05\x06\x00.cosmo\n"

// The check the old probe failed: our own build, bytes and all. Present only
// where the cross-build has run, so it reports what it did rather than lie.
static int check_real(const char *com)
{
    struct stat sb;
    if (com == NULL) return 0;
    if (stat(com, &sb) != 0) {
        printf("skip: %s not built here\n", com);
        return 0;
    }
    int fd = open(com, O_RDONLY);
    if (fd < 0) return 1;
    bool ok = self_tail_ok(fd) == true;
    close(fd);
    if (!ok) fprintf(stderr, "real build %s (%lld B) is not recognised\n",
        com, (long long)sb.st_size);
    printf("%s: %s identifies %s\n", ok ? "ok" : "FAIL",
        com, ok ? "as ours" : "NOT as ours");
    return ok ? 0 : 1;
}

int main(int argc, char **argv)
{
    char dir[] = "apeget-self-path-XXXXXX";
    if (mkdtemp(dir) == NULL) return 1;
    char *ape = path_join(dir, "ape-get.com");
    char *loader = path_join(dir, "loader");
    char *fat = path_join(dir, "fat.com");
    char *ziponly = path_join(dir, "zip");
    char *wordonly = path_join(dir, "cosmo");
    if (ape == NULL || loader == NULL || fat == NULL || ziponly == NULL
        || wordonly == NULL) return 1;

    // Only the APE identification trailer is needed; never execute this file.
    unsigned char image[128] = {0};
    memcpy(image + 80, APE_MARK, sizeof APE_MARK - 1);
    if (write_bytes(ape, image, sizeof image)) return 1;
    if (write_bytes(loader, (const unsigned char *)"", 0)) return 1;

    // A fat build's marker, then ~240 KB of the string table that real builds
    // append after the zip: nothing at the end, so only a scan finds it.
    size_t flen = 262144 + 1024;
    unsigned char *big = calloc(flen, 1);
    if (big == NULL) return 1;
    memcpy(big + flen - 262144, APE_MARK, sizeof APE_MARK - 1);
    int rc = write_bytes(fat, big, flen);
    free(big);
    if (rc) return 1;
    if (write_bytes(ziponly, (const unsigned char *)"PK\x05\x06"
        "\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00"
        "\x00\x00", 22)) return 1;
    if (write_bytes(wordonly, (const unsigned char *)".cosmo\n", 7))
        return 1;

    int failures = 0;
    failures += check("Linux APE wins", ape, loader, ape);
    failures += check("Linux loader uses argv", loader, ape, ape);
    failures += check("Linux staged fake", loader, NULL, loader);
    failures += check("missing proc uses argv", NULL, ape, ape);
    failures += check("missing proc and argv", NULL, NULL, NULL);
    failures += check("missing proc and file", NULL, "/missing/ape-get", NULL);
    failures += check("marker far from EOF still ours", fat, loader, fat);
    failures += check("a plain zip is not ours", ziponly, loader, loader);
    failures += check("the word alone is not ours", wordonly, loader, loader);
    proc_error = EINVAL;
    failures += check("unsupported proc uses argv", NULL, ape, ape);
    if (setenv("PATH", dir, 1) != 0) failures++;
    else failures += check("bare argv through PATH", NULL, "ape-get.com", ape);
    // the branch the issue says bites: no /proc, a bare argv[0], so the PATH
    // scan and this probe are the only way to find our own file
    proc_error = ENOENT;
    for (int i = 1; i < argc; i++) {
        struct stat sb;
        failures += check_real(argv[i]);      // prints a skip when absent
        if (stat(argv[i], &sb) != 0) continue;
        cases += 2;
        char *alias = path_join(dir, "real-build");
        FILE *w = alias != NULL ? fopen(alias, "wb") : NULL;
        bool copied = false;
        if (w != NULL) {
            FILE *r = fopen(argv[i], "rb");
            size_t left = (size_t)sb.st_size;
            copied = r != NULL;
            while (copied && left > 0) {
                char buf[65536];
                size_t n = fread(buf, 1, left < sizeof buf ? left : sizeof buf, r);
                if (n == 0 || fwrite(buf, 1, n, w) != n) { copied = false; break; }
                left -= n;
            }
            if (r != NULL) fclose(r);
            fclose(w);
        }
        if (!copied) { fprintf(stderr, "could not stage %s\n", argv[i]);
            free(alias); failures++; continue; }
        free(self_argv0);
        self_argv0 = NULL;
        self_set_argv0("real-build");
        char *got = self_exe_path();
        bool ok = got != NULL && strcmp(got, alias) == 0;
        printf("%s: %s found through PATH with no /proc\n",
            ok ? "ok" : "FAIL", argv[i]);
        if (!ok) {
            char *cand = path_fmt("%s/real-build", dir);
            fprintf(stderr, "%s: bare argv[0] without /proc found %s (probe on "
                "%s said %s)\n", argv[i], got != NULL ? got : "NULL",
                cand != NULL ? cand : "?",
                self_file_is_apeget(alias) ? "ours" : "not ours");
            free(cand);
        }
        free(got);
        unlink(alias);
        free(alias);
        if (!ok) failures++;
    }

    free(self_argv0);
    self_argv0 = NULL;
    unlink(ape);
    unlink(loader);
    unlink(fat);
    unlink(ziponly);
    unlink(wordonly);
    rmdir(dir);
    free(ape);
    free(loader);
    free(fat);
    free(ziponly);
    free(wordonly);
    if (failures == 0) printf("%d executable-path cases passed\n", cases);
    return failures != 0;
}
