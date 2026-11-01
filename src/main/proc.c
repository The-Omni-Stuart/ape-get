#include "proc.h"
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

// Raw syscalls, not libc wrappers: they drag in Cosmo's Win32 process
// emulation (+36 KB, past the 1.5 MB APE gate). Numbers from kernel uapi.
extern char **environ;

#if defined(__x86_64__)
enum {
    NR_DUP2 = 33, NR_NANOSLEEP = 35, NR_SOCKETPAIR = 53,
    NR_CLONE = 56, NR_EXECVE = 59, NR_EXIT = 60, NR_WAIT4 = 61,
    NR_KILL = 62, NR_SETPGID = 109, NR_OPENAT = 257,
};
#elif defined(__aarch64__)
enum {
    NR_DUP3 = 24, NR_EXIT = 93, NR_NANOSLEEP = 101, NR_KILL = 129,
    NR_SETPGID = 154, NR_SOCKETPAIR = 199, NR_CLONE = 220,
    NR_EXECVE = 221, NR_OPENAT = 56, NR_WAIT4 = 260,
};
#else
#error "proc: unsupported architecture"
#endif

static long raw2(long num, long a, long b)
{
#if defined(__x86_64__)
    long r;
    __asm__ volatile("syscall"
        : "=a"(r) : "0"(num), "D"(a), "S"(b)
        : "rcx", "r11", "rdx", "r10", "r8", "memory");
    return r;
#else
    register long x8 __asm__("x8") = num;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    __asm__ volatile("svc #0"
        : "+r"(x0) : "r"(x8), "r"(x1) : "x2", "x3", "x4", "x5", "memory");
    return x0;
#endif
}

static long raw3(long num, long a, long b, long c)
{
#if defined(__x86_64__)
    long r;
    __asm__ volatile("syscall"
        : "=a"(r) : "0"(num), "D"(a), "S"(b), "d"(c)
        : "rcx", "r11", "r10", "r8", "memory");
    return r;
#else
    register long x8 __asm__("x8") = num;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    __asm__ volatile("svc #0"
        : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2) : "x3", "x4", "x5", "memory");
    return x0;
#endif
}

// 4-arg form: x86 places the fourth argument in r10 (not rcx)
static long raw4(long num, long a, long b, long c, long d)
{
#if defined(__x86_64__)
    long r;
    register long r10 __asm__("r10") = d;
    __asm__ volatile("syscall"
        : "=a"(r) : "0"(num), "D"(a), "S"(b), "d"(c), "r"(r10)
        : "rcx", "r11", "r8", "memory");
    return r;
#else
    register long x8 __asm__("x8") = num;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    __asm__ volatile("svc #0"
        : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3)
        : "x4", "x5", "memory");
    return x0;
#endif
}

// fork(): SIGCHLD clone with the kernel-default stack and no tid pointers,
// i.e. exactly sys_fork's own kernel call on both arches.
static long raw_fork(void)
{
#if defined(__x86_64__)
    long r;
    register long di __asm__("rdi") = SIGCHLD;
    register long si __asm__("rsi") = 0;
    register long dx __asm__("rdx") = 0;
    register long r10 __asm__("r10") = 0;
    register long r8 __asm__("r8") = 0;
    __asm__ volatile("syscall"
        : "=a"(r) : "0"((long)NR_CLONE), "r"(di), "r"(si), "r"(dx),
          "r"(r10), "r"(r8)
        : "rcx", "r11", "memory");
    return r;
#else
    register long x8 __asm__("x8") = NR_CLONE;
    register long x0 __asm__("x0") = SIGCHLD;
    register long x1 __asm__("x1") = 0;
    register long x2 __asm__("x2") = 0;
    register long x3 __asm__("x3") = 0;
    register long x4 __asm__("x4") = 0;
    __asm__ volatile("svc #0"
        : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4)
        : "x5", "memory");
    return x0;
#endif
}

// aarch64 has no dup2; dup3 with flags 0 stands in
static void raw_dup(int from, int to)
{
#if defined(__x86_64__)
    raw2(NR_DUP2, from, to);
#else
    raw3(NR_DUP3, from, to, 0);
#endif
}

static void raw_sleep_ms(long ms)
{
    struct timespec ts = { .tv_sec = ms / 1000,
        .tv_nsec = (ms % 1000) * 1000000L };
    long r;
    do r = raw2(NR_NANOSLEEP, (long)&ts, 0);
    while (r == -EINTR);
}

static bool path_has_exe(const char *path_env, const char *name)
{
    if (path_env == NULL || *path_env == '\0') return false;
    char *dup = strdup(path_env);
    if (dup == NULL) return false;
    bool hit = false;
    for (char *tok = strtok(dup, ":"); tok != NULL; tok = strtok(NULL, ":")) {
        char full[4096];
        const char *d = *tok ? tok : ".";
        if (snprintf(full, sizeof(full), "%s/%s", d, name) < (int)sizeof(full)
            && access(full, X_OK) == 0) { hit = true; break; }
    }
    free(dup);
    return hit;
}

bool proc_have_executable(const char *name)
{
    if (strchr(name, '/')) return access(name, X_OK) == 0;
    return path_has_exe(getenv("PATH"), name);
}

char *proc_find_executable(const char *name)
{
    if (strchr(name, '/'))
        return access(name, X_OK) == 0 ? strdup(name) : NULL;
    const char *path_env = getenv("PATH");
    if (path_env == NULL || *path_env == '\0') return NULL;
    char *dup = strdup(path_env);
    if (dup == NULL) return NULL;
    char *hit = NULL;
    for (char *tok = strtok(dup, ":"); tok != NULL && hit == NULL;
         tok = strtok(NULL, ":")) {
        char full[4096];
        const char *d = *tok ? tok : ".";
        if (snprintf(full, sizeof(full), "%s/%s", d, name) < (int)sizeof(full)
            && access(full, X_OK) == 0) hit = strdup(full);
    }
    free(dup);
    return hit;
}

bool proc_dir_on_path(const char *dir)
{
    const char *path_env = getenv("PATH");
    if (path_env == NULL) return false;
    char *dup = strdup(path_env);
    if (dup == NULL) return false;
    bool hit = false;
    for (char *tok = strtok(dup, ":"); tok != NULL; tok = strtok(NULL, ":")) {
        size_t a = strlen(tok);
        while (a > 1 && tok[a - 1] == '/') tok[--a] = '\0';
        if (strcmp(tok, dir) == 0) { hit = true; break; }
    }
    free(dup);
    return hit;
}

long proc_run(const char *const argv[], int timeout_ms, char **out)
{
    return proc_run_env(argv, NULL, timeout_ms, out);
}

long proc_run_env(const char *const argv[], char *const env[],
    int timeout_ms, char **out)
{
    *out = NULL;
    // Resolve argv[0] before forking; the PATH search mirrors execvp so the
    // raw execve below resolves the same program.
    char *exe = NULL;
    if (strchr(argv[0], '/') != NULL) exe = strdup(argv[0]);
    else {
        char *dup = getenv("PATH") != NULL ? strdup(getenv("PATH")) : NULL;
        for (char *tok = dup != NULL ? strtok(dup, ":") : NULL;
             tok != NULL && exe == NULL; tok = strtok(NULL, ":")) {
            char cand[4096];
            if (snprintf(cand, sizeof cand, "%s/%s",
                    *tok != '\0' ? tok : ".", argv[0]) < (int)sizeof cand
                && access(cand, X_OK) == 0) exe = strdup(cand);
        }
        free(dup);
    }
    if (exe == NULL) return -1;

    int fds[2];
    if (raw4(NR_SOCKETPAIR, AF_UNIX, SOCK_STREAM, 0, (long)fds) != 0)
        { free(exe); return -1; }
    long pid = raw_fork();
    if (pid < 0) { close(fds[0]); close(fds[1]); free(exe); return -1; }
    if (pid == 0) {
        raw2(NR_SETPGID, 0, 0);
        raw_dup(fds[1], STDOUT_FILENO);
        raw_dup(fds[1], STDERR_FILENO);
        close(fds[0]);
        close(fds[1]);
        int dn = (int)raw3(NR_OPENAT, -100, // AT_FDCWD
            (long)"/dev/null", O_RDONLY);
        if (dn >= 0) { raw_dup(dn, STDIN_FILENO); close(dn); }
        raw3(NR_EXECVE, (long)exe, (long)argv,
            (long)(env != NULL ? env : environ));
        raw2(NR_EXIT, 127, 0);
    }
    free(exe);
    raw2(NR_SETPGID, pid, 0); // either side may win; both are legal
    close(fds[1]);
    fcntl(fds[0], F_SETFL, O_NONBLOCK);

    const size_t out_cap = 8u << 20;
    size_t cap = 4096, n = 0;
    char *buf = malloc(cap);
    if (buf == NULL) {
        close(fds[0]);
        raw2(NR_KILL, pid, SIGKILL);
        raw4(NR_WAIT4, pid, 0, 0, 0);
        return -1;
    }
    bool eof = false, killed = false, reaped = false;
    struct timespec t0, now;
    clock_gettime(CLOCK_MONOTONIC, &t0);
    int status = 0;
    long ms = 0;

    for (;;) {
        char chunk[8192];
        while (!eof) {
            ssize_t r = read(fds[0], chunk, sizeof chunk);
            if (r > 0) {
                if ((size_t)r + n + 1 > out_cap) r = (ssize_t)(out_cap - 1 - n);
                if (r > 0 && n + (size_t)r + 1 > cap) {
                    size_t want = (n + (size_t)r + 1) * 2;
                    if (want > out_cap) want = out_cap;
                    char *nb = realloc(buf, want);
                    if (nb != NULL) { buf = nb; cap = want; }
                }
                if (r > 0 && n + (size_t)r + 1 <= cap) {
                    memcpy(buf + n, chunk, (size_t)r);
                    n += (size_t)r;
                }
                // Past the cap this only drains; the deadline is checked per
                // chunk, so a busy producer cannot starve the timeout.
                clock_gettime(CLOCK_MONOTONIC, &now);
                ms = (now.tv_sec - t0.tv_sec) * 1000L
                    + (now.tv_nsec - t0.tv_nsec) / 1000000L;
                if (ms > timeout_ms) break;
                continue;
            }
            if (r < 0 && errno == EINTR) continue;
            if (r == 0) eof = true;
            else if (r < 0 && errno != EAGAIN && errno != EWOULDBLOCK) eof = true;
            break;
        }
        if (!reaped
            && raw4(NR_WAIT4, pid, (long)&status, 1, 0) == pid) // WNOHANG
            reaped = true;
        if (reaped && (eof || killed)) break;
        clock_gettime(CLOCK_MONOTONIC, &now);
        ms = (now.tv_sec - t0.tv_sec) * 1000L
            + (now.tv_nsec - t0.tv_nsec) / 1000000L;
        if (!killed && ms > timeout_ms) {
            raw2(NR_KILL, -pid, SIGKILL); // whole process group
            raw2(NR_KILL, pid, SIGKILL);
            killed = true;
        }
        raw_sleep_ms(20);
    }
    close(fds[0]);
    if (!killed && (status & 0x7f) != 0) { free(buf); return -1; }
    buf[n] = '\0';
    *out = buf;
    return killed ? -2 : (long)((status >> 8) & 0xff);
}
