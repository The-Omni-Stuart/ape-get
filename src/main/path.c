#include "path.h"
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

char *path_home(void)
{
    const char *h = getenv("HOME");
    if (h == NULL || *h == '\0') return NULL;
    return strdup(h);
}

// Either side failing to resolve means "not inside"; the suffix test on
// resolved paths makes a dir prefix itself count as outside.
bool path_under(const char *file, const char *dir)
{
    char *rf = realpath(file, NULL), *rd = realpath(dir, NULL);
    if (rf == NULL || rd == NULL) {
        free(rf);
        free(rd);
        return false;
    }
    size_t n = strlen(rd);
    bool inside = strncmp(rf, rd, n) == 0 && rf[n] == '/';
    free(rf);
    free(rd);
    return inside;
}

char *path_norm_dup(const char *in)
{
    if (in == NULL || in[0] != '/') return NULL;
    char *out = malloc(strlen(in) + 2);
    if (out == NULL) return NULL;
    size_t n = 0;
    out[n++] = '/';
    const char *p = in + 1;
    while (*p != '\0') {
        while (*p == '/') p++;
        const char *e = p;
        while (*e != '\0' && *e != '/') e++;
        size_t len = (size_t)(e - p);
        if (len == 0 || (len == 1 && p[0] == '.')) {
            if (*e == '\0') break;
            p = e + 1;
            continue;
        }
        if (len == 2 && p[0] == '.' && p[1] == '.') {
            while (n > 1) { if (out[--n] == '/') break; }
            if (*e == '\0') break;
            p = e + 1;
            continue;
        }
        if (out[n - 1] != '/') out[n++] = '/';
        memcpy(out + n, p, len);
        n += len;
        if (*e == '\0') break;
        p = e + 1;
    }
    while (n > 1 && out[n - 1] == '/') n--;
    out[n] = '\0';
    return out;
}

char *path_data_home(void)
{
    const char *x = getenv("XDG_DATA_HOME");
    if (x != NULL && *x == '/') return strdup(x);
    char *h = path_home();
    if (h == NULL) return NULL;
    char *r = path_join(h, ".local/share");
    free(h);
    return r;
}

char *path_state_root(void)
{
    char *d = path_data_home();
    if (d == NULL) return NULL;
    char *r = path_join(d, "github-release-manager");
    free(d);
    return r;
}

char *path_join(const char *a, const char *b)
{
    size_t na = strlen(a), nb = strlen(b);
    int slash = na > 0 && a[na - 1] != '/';
    char *r = malloc(na + nb + 2);
    if (r == NULL) return NULL;
    memcpy(r, a, na);
    size_t n = na;
    if (slash) r[n++] = '/';
    memcpy(r + n, b, nb + 1);
    return r;
}

char *path_fmt(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    char *r = NULL;
    if (vasprintf(&r, fmt, ap) < 0) r = NULL;
    va_end(ap);
    return r;
}
