// Fuzz driver: the pinned host clang ships no libFuzzer runtime, so replay +
// mutation is self-contained; each input runs forked under alarm, LSan, VmHWM.

#include "fuzz_util.h"

#include <dirent.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

// target entry point (target_*.c)
void fuzz_target(const unsigned char *data, size_t size);

// defined by LSan; weak so the driver still links without the runtime
__attribute__((weak)) int __lsan_do_recoverable_leak_check(void);

#define FUZZ_MAX_INPUT (1u << 20)
#define LEAK_EXIT 23
#define RSS_EXIT 67

typedef struct { unsigned char *p; size_t n; } buf;

static unsigned long long g_rng;
static void splitmix_seed(unsigned long long s) { g_rng = s; }
static unsigned long long splitmix64(void)
{
    g_rng += 0x9e3779b97f4a7c15ULL;
    unsigned long long z = g_rng;
    z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9ULL;
    z = (z ^ (z >> 27)) * 0x94d049bb133111ebULL;
    return z ^ (z >> 31);
}
static size_t pick(size_t n) { return n == 0 ? 0 : (size_t)(splitmix64() % n); }

// ---- corpus and dict ----

static buf *g_seeds;
static size_t g_nseeds, g_caseeds;
static void add_seed(const unsigned char *p, size_t n)
{
    if (g_nseeds == g_caseeds) {
        g_caseeds = g_caseeds ? g_caseeds * 2 : 16;
        g_seeds = realloc(g_seeds, g_caseeds * sizeof *g_seeds);
        if (g_seeds == NULL) abort();
    }
    g_seeds[g_nseeds].p = malloc(n ? n : 1);
    g_seeds[g_nseeds].n = n;
    if (g_seeds[g_nseeds].p == NULL) abort();
    memcpy(g_seeds[g_nseeds].p, p, n);
    g_nseeds++;
}

static int cmp_name(const void *a, const void *b)
{
    return strcmp(*(const char *const *)a, *(const char *const *)b);
}

static void load_corpus(const char *dir)
{
    DIR *d = opendir(dir);
    if (d == NULL) { fprintf(stderr, "corpus dir missing: %s\n", dir); exit(2); }
    char *names[512];
    size_t nn = 0;
    struct dirent *e;
    while ((e = readdir(d)) != NULL && nn < sizeof names / sizeof *names) {
        if (e->d_name[0] == '.' || strncmp(e->d_name, "dict", 4) == 0)
            continue;                       // dicts live beside the seeds
        char path[4096];
        snprintf(path, sizeof path, "%s/%s", dir, e->d_name);
        struct stat sb;
        if (stat(path, &sb) != 0 || !S_ISREG(sb.st_mode)) continue;
        names[nn] = strdup(e->d_name);
        if (names[nn] == NULL) abort();
        nn++;
    }
    qsort(names, nn, sizeof *names, cmp_name);
    for (size_t k = 0; k < nn; k++) {
        char path[4096];
        snprintf(path, sizeof path, "%s/%s", dir, names[k]);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        struct stat sb;
        size_t cap = 65536;
        if (fstat(fd, &sb) == 0 && sb.st_size > 0)
            cap = (size_t)sb.st_size < FUZZ_MAX_INPUT ? (size_t)sb.st_size
                                                 : FUZZ_MAX_INPUT;
        unsigned char *p = malloc(cap);
        ssize_t r = read(fd, p, cap);
        close(fd);
        if (r >= 0) add_seed(p, (size_t)r);
        free(p);
        free(names[k]);
    }
    closedir(d);
    if (g_nseeds == 0) { fprintf(stderr, "corpus empty: %s\n", dir); exit(2); }
}

static char *g_dict[128];
static size_t g_dictn[128];
static size_t g_ndict;
static unsigned char g_fallback[] = { 0x00, 0x0a, 0x0d, 0x1b, 0x22, 0x27,
    0x2c, 0x2e, 0x2f, 0x3a, 0x5c, 0x7b, 0x7d, 0x25, 0x5b, 0x5d, 0xff,
    '.', '/', 'h', 'e', 'x', '0', '-', '\\', '\r', '\n' };

static size_t unesc(const char *s, unsigned char *out, size_t cap)
{
    size_t w = 0;
    for (const char *p = s; *p != '\0' && w < cap; p++) {
        if (*p != '\\') { out[w++] = (unsigned char)*p; continue; }
        switch (*++p) {
        case 'n': out[w++] = '\n'; break;
        case 'r': out[w++] = '\r'; break;
        case 't': out[w++] = '\t'; break;
        case '\\': out[w++] = '\\'; break;
        case 'x': {
            unsigned v = 0;
            for (int k = 0; k < 2 && p[1] != '\0'; k++, p++) {
                char c = p[1];
                int dv = (c >= '0' && c <= '9') ? c - '0'
                    : (c >= 'a' && c <= 'f') ? c - 'a' + 10
                    : (c >= 'A' && c <= 'F') ? c - 'A' + 10 : -1;
                if (dv < 0) break;
                v = v * 16 + (unsigned)dv;
            }
            out[w++] = (unsigned char)v;
            break;
        }
        default: p--; out[w++] = '\\'; break;
        }
    }
    return w;
}

static void load_dict(const char *dir)
{
    char path[4096];
    snprintf(path, sizeof path, "%s/dict.txt", dir);
    FILE *f = fopen(path, "rb");
    if (f == NULL) return;                 // dictionaries are optional
    char line[512];
    while (fgets(line, sizeof line, f) != NULL && g_ndict < 128) {
        size_t l = strlen(line);
        while (l > 0 && (line[l - 1] == '\n' || line[l - 1] == '\r'))
            line[--l] = '\0';
        if (l == 0) continue;
        unsigned char tmp[256];
        size_t n = unesc(line, tmp, sizeof tmp);
        if (n == 0) continue;
        g_dict[g_ndict] = malloc(n);
        if (g_dict[g_ndict] == NULL) abort();
        memcpy(g_dict[g_ndict], tmp, n);
        g_dictn[g_ndict] = n;
        g_ndict++;
    }
    fclose(f);
}

// ---- mutation ----

static void insert_bytes(unsigned char *p, size_t *n, size_t at,
                         const unsigned char *tok, size_t tn)
{
    if (*n + tn > FUZZ_MAX_INPUT) return;
    memmove(p + at + tn, p + at, *n - at);
    memcpy(p + at, tok, tn);
    *n += tn;
}

static buf mutate(const buf *base)
{
    buf out;
    out.n = base->n < FUZZ_MAX_INPUT - 256 ? base->n : FUZZ_MAX_INPUT - 256;
    out.p = malloc(out.n + 256);
    if (out.p == NULL) abort();
    memcpy(out.p, base->p, out.n);
    switch (pick(9)) {
    case 0: if (out.n > 0) out.p[pick(out.n)] ^= (unsigned char)splitmix64(); break;
    case 1: { if (out.n > 0)
                  out.p[pick(out.n)] = g_fallback[pick(sizeof g_fallback)];
              break; }
    case 2: if (out.n > 0) { size_t at = pick(out.n), len = 1 + pick(32);
                for (size_t k = 0; k < len && at + k < out.n; k++)
                    out.p[at + k] ^= 0xff; } break;
    case 3: insert_bytes(out.p, &out.n, out.n ? pick(out.n) : 0,
                (unsigned char *)"\x27", 1); break;
    case 4: // truncate
        if (out.n > 0) out.n = pick(out.n); break;
    case 5: // delete a span
        if (out.n > 2) { size_t at = pick(out.n - 1), len = 1 + pick(out.n - 1 - at);
            if (len > out.n - at) len = out.n - at;
            memmove(out.p + at, out.p + at + len, out.n - at - len);
            out.n -= len; } break;
    case 6: // duplicate a span (copy first: the splice may shift it)
        if (out.n > 0) { unsigned char tmp[48]; size_t at = pick(out.n);
            size_t len = 1 + pick(47);
            if (len > out.n - at) len = out.n - at;
            memcpy(tmp, out.p + at, len);
            insert_bytes(out.p, &out.n, out.n, tmp, len); } break;
    case 7: { // splice a dict token (or fallback byte when no dict)
        if (g_ndict > 0) { size_t i = pick(g_ndict);
            insert_bytes(out.p, &out.n, out.n ? pick(out.n) : 0,
                (const unsigned char *)g_dict[i], g_dictn[i]); }
        else
            insert_bytes(out.p, &out.n, out.n ? pick(out.n) : 0,
                g_fallback, 1); } break;
    default: // append a token
        if (g_ndict > 0) { size_t i = pick(g_ndict);
            insert_bytes(out.p, &out.n, out.n, (const unsigned char *)g_dict[i],
                g_dictn[i]); }
        else {
            insert_bytes(out.p, &out.n, out.n,
                g_fallback, 1 + pick(sizeof g_fallback - 1));
        }
        break;
    }
    return out;
}

// ---- run one input in a child ----

static double now_s(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec * 1e-9;
}

static size_t vm_hwm_bytes(void)
{
    FILE *f = fopen("/proc/self/status", "rb");
    if (f == NULL) return 0;
    char line[128];
    size_t kb = 0;
    while (fgets(line, sizeof line, f) != NULL)
        if (strncmp(line, "VmHWM:", 6) == 0) { sscanf(line + 6, "%zu", &kb); break; }
    fclose(f);
    return kb * 1024;
}

// returns NULL when the child died cleanly, else a short reason
static const char *child_status(int status, char *why, size_t cap)
{
    if (WIFEXITED(status)) {
        if (WEXITSTATUS(status) == 0) return NULL;
        if (WEXITSTATUS(status) == LEAK_EXIT) return "leak detected";
        if (WEXITSTATUS(status) == RSS_EXIT)
            return "unbounded allocation (VmHWM over cap)";
        if (WEXITSTATUS(status) == 1)
            return "sanitizer report (see asan/ubsan log_path files)";
        snprintf(why, cap, "unexpected exit %d", WEXITSTATUS(status));
        return why;
    }
    if (WIFSIGNALED(status)) {
        if (WTERMSIG(status) == SIGALRM) return "hang (per-input alarm fired)";
        snprintf(why, cap, "%s", strsignal(WTERMSIG(status)));
        return why;
    }
    return "child vanished";
}

static unsigned long long g_seq;
static const char *g_workdir;
static int g_alarm = 5;
static size_t g_rss_cap = 512u << 20;

static void dump_input(const unsigned char *p, size_t n)
{
    FILE *f = stdout;
    size_t show = n < 256 ? n : 256;
    fprintf(f, "  input[%zu] = \"", n);
    for (size_t k = 0; k < show; k++) {
        unsigned char c = p[k];
        if (c >= 0x20 && c < 0x7f && c != '"' && c != '\\') fputc((int)c, f);
        else fprintf(f, "\\x%02x", c);
    }
    fprintf(f, "%s\"\n", n > show ? " ..." : "");
    fflush(f);
}

static void save_repro(const unsigned char *p, size_t n)
{
    if (g_workdir == NULL) return;
    char path[4096];
    snprintf(path, sizeof path, "%s/repro-%06llu.bin", g_workdir, g_seq);
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) return;
    ssize_t r = write(fd, p, n);
    (void)r;
    close(fd);
    printf("  reproducer: %s\n", path);
}

// false = the child failed; caller stops
static int run_input(const unsigned char *p, size_t n, const char *name,
                     int silence_stderr)
{
    unsigned char *copy = malloc(n + 1);
    if (copy == NULL) abort();
    memcpy(copy, p, n);
    copy[n] = 0;
    fflush(stdout);
    g_seq++;
    pid_t pid = fork();
    if (pid < 0) { perror("fork"); exit(2); }
    if (pid == 0) {
        if (silence_stderr) {
            int devnull = open("/dev/null", O_WRONLY);
            if (devnull >= 0) { dup2(devnull, 2); close(devnull); }
        }
        // exclude everything inherited before this point from leak checks
        if (__lsan_do_recoverable_leak_check != NULL)
            (void)__lsan_do_recoverable_leak_check();
        alarm((unsigned)g_alarm);
        fuzz_target(copy, n);
        if (vm_hwm_bytes() > g_rss_cap) {
            printf("  VmHWM exceeded %zu MiB for input #%llu\n",
                   g_rss_cap >> 20, g_seq);
            _Exit(RSS_EXIT);
        }
        exit(0);                              // exit runs LeakSanitizer
    }
    int status = 0;
    if (waitpid(pid, &status, 0) < 0) { perror("waitpid"); exit(2); }
    free(copy);
    char why[128];
    const char *bad = child_status(status, why, sizeof why);
    if (bad != NULL) {
        printf("  input #%llu (%s): %s\n", g_seq, name, bad);
        dump_input(p, n);
        save_repro(p, n);
        return 0;
    }
    return 1;
}

// ---- main ----

int main(int argc, char **argv)
{
    const char *name = strrchr(argv[0], '/');
    name = name != NULL ? name + 1 : argv[0];
    if (strncmp(name, "fuzz_", 5) == 0) name += 5;
    const char *corpus = NULL;
    double seconds = 10.0;
    unsigned long long seed = 0x5eed;
    char workpath[4096] = "";

    for (int k = 1; k < argc; k++) {
        if (strcmp(argv[k], "--seconds") == 0 && k + 1 < argc)
            seconds = atof(argv[++k]);
        else if (strcmp(argv[k], "--seed") == 0 && k + 1 < argc)
            seed = strtoull(argv[++k], NULL, 0);
        else if (strcmp(argv[k], "--alarm") == 0 && k + 1 < argc)
            g_alarm = atoi(argv[++k]);
        else if (strcmp(argv[k], "--rss-cap") == 0 && k + 1 < argc)
            g_rss_cap = strtoull(argv[++k], NULL, 0) << 20;
        else if (strcmp(argv[k], "--workdir") == 0 && k + 1 < argc) {
            k++;
            mkdir(argv[k], 0777);
            snprintf(workpath, sizeof workpath, "%s", argv[k]);
        } else if (corpus == NULL) corpus = argv[k];
        else { fprintf(stderr, "usage: %s [corpus] [--seconds N] [--seed N] "
                "[--alarm N] [--rss-cap MiB] [--workdir D]\n", argv[0]);
               return 2; }
    }
    char cpath[4096];
    if (corpus == NULL) {
        snprintf(cpath, sizeof cpath, "src/test/c/fuzz/corpus/%s", name);
        corpus = cpath;
    }
    setenv("APEGET_FUZZ_WORK", workpath[0] != '\0' ? workpath : "/tmp", 1);
    g_workdir = workpath[0] != '\0' ? workpath : NULL;
    // parsers print usage noise to stderr; sanitizer reports are kept in
    // the log_path file when run.sh configured one
    const char *ao = getenv("ASAN_OPTIONS");
    int silence_stderr = ao != NULL && strstr(ao, "log_path") != NULL;

    load_corpus(corpus);
    load_dict(corpus);
    splitmix_seed(seed);
    setvbuf(stdout, NULL, _IOLBF, 0);

    for (size_t k = 0; k < g_nseeds; k++)
        if (!run_input(g_seeds[k].p, g_seeds[k].n, "replay", silence_stderr))
            _Exit(1);
    size_t replayed = g_nseeds;

    size_t mutated = 0;
    double t0 = now_s();
    while (now_s() - t0 < seconds) {
        buf m = mutate(&g_seeds[pick(g_nseeds)]);
        int ok = run_input(m.p, m.n, "mutation", silence_stderr);
        free(m.p);
        if (!ok) _Exit(1);
        mutated++;
    }
    printf("PASS %s: replay=%zu mutations=%zu seeds=%s\n", name, replayed,
           mutated, corpus);
    fflush(stdout);
    _Exit(0);   // _Exit: the driver's own retained corpus is not a leak
}
