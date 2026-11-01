#include "compat.h"

#include "fs.h"
#include "path.h"
#include "proc.h"

#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

// Both paths in the wrapper are resolved to absolute at install time; a
// failed realpath leaves the absolute join as-is.
static char *to_absolute(const char *p)
{
    char *abs;
    if (p[0] == '/') {
        abs = strdup(p);
    } else {
        char cwd[PATH_MAX];
        if (getcwd(cwd, sizeof cwd) == NULL) return strdup(p);
        abs = path_fmt("%s/%s", cwd, p);
    }
    if (abs == NULL) return NULL;
    char resolved[PATH_MAX];
    if (realpath(abs, resolved) != NULL) {
        free(abs);
        return strdup(resolved);
    }
    return abs;
}

char *compat_detect_wine(const char *wine_path, char **err)
{
    *err = NULL;
    if (wine_path != NULL) {
        if (access(wine_path, X_OK) != 0) {
            *err = path_fmt("Wine not usable at wine_path: %s. Fix the "
                "config, or unset wine_path to fall back to "
                "auto-detection.", wine_path);
            return NULL;
        }
        return to_absolute(wine_path);
    }
    static const char *names[] = { "wine", "wine64", NULL };
    for (int k = 0; names[k] != NULL; k++) {
        char *hit = proc_find_executable(names[k]);
        if (hit != NULL) {
            char *abs = to_absolute(hit);
            free(hit);
            return abs;
        }
    }
    *err = strdup("No wine detected. Install wine and run again, or set "
        "wine_path in the config to your wine binary.");
    return NULL;
}

int compat_flatpak_wine(void)
{
    static const char *names[] = { "wine", "wine64", "org.winehq.Wine",
        NULL };
    char *dirs[2];
    int nd = 0;
    const char *xdg = getenv("XDG_DATA_HOME");
    char *home = path_home();
    if (xdg != NULL && *xdg != '\0')
        dirs[nd++] = path_fmt("%s/flatpak/exports/bin", xdg);
    else if (home != NULL)
        dirs[nd++] = path_fmt("%s/.local/share/flatpak/exports/bin", home);
    dirs[nd++] = strdup("/var/lib/flatpak/exports/bin");
    int hit = 0;
    for (int d = 0; d < nd && !hit; d++)
        for (int k = 0; names[k] != NULL; k++) {
            char *cand = path_fmt("%s/%s", dirs[d], names[k]);
            if (cand != NULL && access(cand, X_OK) == 0) hit = 1;
            free(cand);
        }
    for (int d = 0; d < nd; d++) free(dirs[d]);
    free(home);
    return hit;
}

char *compat_shell_quote(const char *s, char **err)
{
    for (const unsigned char *p = (const unsigned char *)s; *p != '\0';
         p++)
        if (*p < 0x20 || *p == 0x7f) {
            *err = path_fmt("Cannot generate the wine launcher: %s "
                "contains control characters.", s);
            return NULL;
        }
    char *q = malloc(strlen(s) * 4 + 3);
    if (q == NULL) return NULL;
    char *w = q;
    *w++ = '\'';
    for (const char *p = s; *p != '\0'; p++) {
        if (*p == '\'') {
            memcpy(w, "'\\''", 4);
            w += 4;
        } else {
            *w++ = *p;
        }
    }
    *w++ = '\'';
    *w = '\0';
    return q;
}

char *compat_wrapper_stage(const char *launcher, const char *txn_id,
    const char *wine_abs, const char *target_abs, char **err)
{
    char *qw = compat_shell_quote(wine_abs, err);
    if (qw == NULL) return NULL;
    char *qt = compat_shell_quote(target_abs, err);
    if (qt == NULL) { free(qw); return NULL; }
    char *tmp = path_fmt("%s.tmp-%s", launcher, txn_id);
    bool ok = tmp != NULL;
    if (ok) {
        FILE *f = fopen(tmp, "wb");
        if (f == NULL) ok = false;
        else {
            fputs("#!/bin/sh\n", f);
            fputs("# managed by ape-get (compat: wine)\n", f);
            fprintf(f, "exec %s %s \"$@\"\n", qw, qt);
            ok = fclose(f) == 0;
        }
    }
    free(qw);
    free(qt);
    if (ok) ok = chmod(tmp, 0755) == 0;
    if (ok) {
        const char *av[] = { "sh", "-n", tmp, NULL };
        char *out = NULL;
        ok = proc_run(av, 5000, &out) == 0;
        free(out);
    }
    if (!ok) {
        if (tmp != NULL) remove(tmp);
        free(tmp);
        *err = strdup("Generated wine launcher failed the sh -n syntax "
            "check; install aborted.");
        return NULL;
    }
    return tmp;
}

// The single-quoted token after "exec ", with '\'' unescaped. The wrappers
// are our own output, but a truncated or tampered file reads as no wine.
static char *unquote(const char *q)
{
    if (*q != '\'') return NULL;
    const char *p = q + 1;
    char *out = malloc(strlen(p) + 1);
    if (out == NULL) return NULL;
    size_t w = 0;
    for (;;) {
        if (*p == '\0') { free(out); return NULL; }
        if (*p == '\'') {
            if (p[1] == '\\' && p[2] == '\'' && p[3] == '\'') {
                out[w++] = '\'';
                p += 4;
                continue;
            }
            if (p[1] == ' ' || p[1] == '\n' || p[1] == '\0') break;
            free(out);
            return NULL;
        }
        out[w++] = *p++;
    }
    out[w] = '\0';
    return out;
}

static char *wrapper_wine_path(const char *wrapper)
{
    size_t n = 0;
    char *text = fs_read_file(wrapper, &n);
    if (text == NULL) return NULL;
    const char *hit = strstr(text, "exec '");
    char *wine = hit != NULL ? unquote(hit + 5) : NULL;
    free(text);
    return wine;
}

char *compat_wrapper_problem(const char *wrapper, const char *wine_path)
{
    char *wine = wrapper != NULL ? wrapper_wine_path(wrapper) : NULL;
    if (wine != NULL) {
        if (access(wine, X_OK) == 0) { free(wine); return NULL; }
        return wine;
    }
    if (wine_path != NULL)
        return access(wine_path, X_OK) == 0 ? NULL : strdup(wine_path);
    static const char *names[] = { "wine", "wine64", NULL };
    for (int k = 0; names[k] != NULL; k++)
        if (proc_have_executable(names[k])) return NULL;
    return strdup("wine not on PATH");
}
