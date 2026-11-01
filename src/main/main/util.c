#include "cmd.h"
#include "doctor.h"
#include "fs.h"
#include "json.h"
#include "manifest.h"
#include "path.h"
#include "pipeline.h"
#include "provider.h"
#include "repo.h"
#include "state.h"
#include "txn.h"
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include "main.h"

void put_out(const char *line) { puts(line); }
void put_err(const char *line) { fputs(line, stderr); fputc('\n', stderr); }

void put_fail(const char *line) { ui_line(stderr, UI_ERROR, line); }

void put_s(FILE *f, ui_style style, const char *fmt, ...)
{
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    ui_line(f, style, buf);
}

void manifest_list_free(manifest_entry *ms, size_t n)
{
    for (size_t k = 0; k < n; k++) manifest_entry_free(&ms[k]);
    free(ms);
}

char *span_dup(const char *s, const char *e)
{
    size_t n = (size_t)(e - s);
    char *q = malloc(n + 1);
    if (q != NULL) {
        memcpy(q, s, n);
        q[n] = '\0';
    }
    return q;
}

char *jstr(const jobject *o, const char *key)
{
    int i = jobject_find(o, key);
    if (i < 0) return NULL;
    char *s = NULL;
    json_span_string(o->vals[i], o->vends[i], &s);
    return s;
}

// emit "key":<string|null> for a member of a parsed object
void jb_jstr(jbuf *b, const jobject *o, const char *key)
{
    char *s = jstr(o, key);
    json_null_or_string(b, s);
    free(s);
}

// thin alias of repo_parse_ref: the grammar there and its exact error
// wording are user-visible behaviour callers rely on
bool parse_ref(const char *arg, char *prov, size_t provcap,
                      char *path, size_t pathcap)
{
    return repo_parse_ref(arg, prov, provcap, path, pathcap);
}

// short-name resolution over the scanned manifests (see main.h)
manifest_entry *resolve_installed(const cmd_opts *o, const char *arg,
    manifest_entry *ms, size_t mc, bool *many)
{
    *many = false;
    size_t idx[64], n = 0;
    for (size_t k = 0; k < mc; k++) {
        if (ms[k].provider != NULL
            && strcmp(ms[k].provider, "self") == 0) continue;
        const char *repo = ms[k].repo != NULL ? ms[k].repo : "";
        const char *tail = strrchr(repo, '/');
        tail = tail != NULL ? tail + 1 : repo;
        const char *binf = ms[k].path != NULL
            ? strrchr(ms[k].path, '/') : NULL;
        bool hit = strcasecmp(tail, arg) == 0
            || strcasecmp(repo, arg) == 0
            || (ms[k].command != NULL
                && strcasecmp(ms[k].command, arg) == 0)
            || (binf != NULL && strcasecmp(binf + 1, arg) == 0);
        if (hit && n < sizeof idx / sizeof idx[0]) idx[n++] = k;
    }
    if (n == 0) return NULL;
    if (n == 1) {
        fprintf(stderr, "Using %s:%s.\n", ms[idx[0]].provider,
            ms[idx[0]].repo);
        return &ms[idx[0]];
    }
    *many = true;
    put_s(stderr, UI_BOLD, "Multiple sources found for '%s':", arg);
    for (size_t i = 0; i < n; i++)
        fprintf(stderr, "  %zu: %s:%s - %s\n", i + 1,
            ms[idx[i]].provider, ms[idx[i]].repo,
            ms[idx[i]].version != NULL ? ms[idx[i]].version : "unknown");
    if (o->json || o->assume_yes || !isatty(fileno(stdin))) return NULL;
    for ( ; ; ) {
        fprintf(stderr, "Choose a package [1-%zu]: ", n);
        fflush(stderr);
        char ln[80];
        if (fgets(ln, sizeof ln, stdin) == NULL) {
            fprintf(stderr, "No package selected.\n");
            return NULL;
        }
        char *endp = NULL;
        long v = strtol(ln, &endp, 10);
        while (endp != NULL && *endp == ' ') endp++;
        if (endp != NULL && v >= 1 && (size_t)v <= n
            && (*endp == '\0' || *endp == '\n' || *endp == '\r'))
            return &ms[idx[(size_t)v - 1]];
        fprintf(stderr, "Invalid choice. Valid options are: 1 - %zu\n", n);
    }
}

void now_instant(char *buf, size_t cap)
{
    time_t t = time(NULL);
    struct tm tmv;
    gmtime_r(&t, &tmv);
    snprintf(buf, cap, "%04d-%02d-%02dT%02d:%02d:%02dZ",
        tmv.tm_year + 1900, tmv.tm_mon + 1, tmv.tm_mday,
        tmv.tm_hour, tmv.tm_min, tmv.tm_sec);
}
