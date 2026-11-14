#include "proc.h"
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

extern char **environ;

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
    // resolve before forking so a missing program fails with -1, not a 127 from the child
    char *exe = proc_find_executable(argv[0]);
    if (exe == NULL) return -1;

    int fds[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, fds) != 0)
        { free(exe); return -1; }
    pid_t pid = fork();
    if (pid < 0) { close(fds[0]); close(fds[1]); free(exe); return -1; }
    if (pid == 0) {
        signal(SIGPIPE, SIG_DFL); // the ignore is ours, not the child's
        setpgid(0, 0);
        dup2(fds[1], STDOUT_FILENO);
        dup2(fds[1], STDERR_FILENO);
        close(fds[0]);
        close(fds[1]);
        int dn = open("/dev/null", O_RDONLY);
        if (dn >= 0) { dup2(dn, STDIN_FILENO); close(dn); }
        execve(exe, (char *const *)argv, env != NULL ? env : environ);
        _exit(127);
    }
    free(exe);
    setpgid(pid, 0); // either side may win; both are legal
    close(fds[1]);
    fcntl(fds[0], F_SETFL, O_NONBLOCK);

    const size_t out_cap = 8u << 20;
    size_t cap = 4096, n = 0;
    char *buf = malloc(cap);
    if (buf == NULL) {
        close(fds[0]);
        kill(pid, SIGKILL);
        waitpid(pid, NULL, 0);
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
        if (!reaped && waitpid(pid, &status, WNOHANG) == pid)
            reaped = true;
        if (reaped && (eof || killed)) break;
        clock_gettime(CLOCK_MONOTONIC, &now);
        ms = (now.tv_sec - t0.tv_sec) * 1000L
            + (now.tv_nsec - t0.tv_nsec) / 1000000L;
        if (!killed && ms > timeout_ms) {
            kill(-pid, SIGKILL); // whole process group
            kill(pid, SIGKILL);
            killed = true;
        }
        poll(NULL, 0, 20); // nfds 0: portable sleep, poll is already linked
    }
    close(fds[0]);
    if (!killed && WIFSIGNALED(status)) { free(buf); return -1; }
    buf[n] = '\0';
    *out = buf;
    return killed ? -2 : (long)WEXITSTATUS(status);
}
