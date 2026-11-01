// Path/file helpers for the pipeline phases.

#include "pipeline/internal.h"

#include <ctype.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>

bool mkdir_p(const char *path)
{
    char *copy = strdup(path);
    if (copy == NULL) return false;
    for (char *p = copy + 1; *p != '\0'; p++) {
        if (*p == '/') {
            *p = '\0';
            if (mkdir(copy, 0777) != 0 && errno != EEXIST) {
                free(copy);
                return false;
            }
            *p = '/';
        }
    }
    bool ok = mkdir(copy, 0777) == 0 || errno == EEXIST;
    free(copy);
    return ok;
}

char *sanitize_version(const char *v)
{
    if (blank(v)) return strdup("unknown");
    const char *p = v;
    size_t l = strlen(p);
    while (l > 0 && isspace((unsigned char)*p)) { p++; l--; }
    while (l > 0 && isspace((unsigned char)p[l - 1])) l--;
    char *out = malloc(l + 1);
    if (out == NULL) return NULL;
    for (size_t i = 0; i < l; i++)
        out[i] = (isalnum((unsigned char)p[i]) || p[i] == '.'
            || p[i] == '_' || p[i] == '+' || p[i] == '-') ? p[i] : '-';
    out[l] = '\0';
    size_t b = 0;
    while (out[b] == '.' || out[b] == '-') b++;
    char *t = strdup(out + b);
    free(out);
    // collapse ".." runs: keep one dot
    char *r = t;
    while (r != NULL && (r = strstr(r, "..")) != NULL) {
        char *w = r + 1, *rd = w + 1;
        while ((*w = *rd) != '\0') { w++; rd++; }
    }
    return t;
}

bool path_is_dir(const char *p)
{
    struct stat sb;
    return lstat(p, &sb) == 0 && S_ISDIR(sb.st_mode);
}

bool path_exists(const char *p)
{
    struct stat sb;
    return lstat(p, &sb) == 0;
}

bool dir_on_path(const char *dir)
{
    const char *path = getenv("PATH");
    if (path == NULL) return false;
    char *p = strdup(path);
    if (p == NULL) return false;
    char *want = strdup(dir);
    size_t wl = strlen(want);
    while (wl > 1 && want[wl - 1] == '/') want[--wl] = '\0';
    bool found = false;
    char *save = NULL;
    for (char *e = strtok_r(p, ":", &save); e != NULL;
         e = strtok_r(NULL, ":", &save)) {
        size_t el = strlen(e);
        while (el > 1 && e[el - 1] == '/') e[--el] = '\0';
        if (!strcmp(e, want)) { found = true; break; }
    }
    free(want);
    free(p);
    return found;
}

bool owned_by_other(const manifest_entry *ms, size_t mc,
                    const char *prov, const char *path,
                    const char *target)
{
    for (size_t k = 0; k < mc; k++) {
        if (ms[k].provider == NULL || ms[k].repo == NULL) continue;
        if (!strcmp(ms[k].provider, prov) && !strcmp(ms[k].repo, path))
            continue;
        const char *owned[2] = { ms[k].path, ms[k].launcher };
        for (int f = 0; f < 2; f++) {
            if (owned[f] == NULL || *owned[f] == '\0') continue;
            if (!strcmp(owned[f], target)) return true;
            size_t tl = strlen(target);
            if (!strncmp(owned[f], target, tl) && owned[f][tl] == '/')
                return true;
        }
    }
    return false;
}
