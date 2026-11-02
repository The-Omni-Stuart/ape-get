// Command name derivation from asset names.

#include "pipeline/internal.h"

#include <ctype.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

static const char *CN_SUFFIXES[] = {
    "linux", "windows", "win", "win32", "win64", "macos", "mac", "darwin",
    "osx", "freebsd", "openbsd", "netbsd", "x86", "x86_64", "x86-64",
    "amd64", "x64", "i386", "i686", "arm", "arm64", "aarch64", "armv7",
    "armv8", "universal", "universal2", "fat", "static", "musl", "gnu",
    "portable", "standalone", "setup", "installer", "install", "cli", "gui",
    NULL
};

// pattern: VERSION token = v?\d+(\.\d+)*
static bool cn_is_version(const char *tok)
{
    const char *p = tok;
    if (*p == 'v') p++;
    if (!isdigit((unsigned char)*p)) return false;
    while (*p != '\0') {
        if (*p == '.') {
            p++;
            if (!isdigit((unsigned char)*p)) return false;
            continue;
        }
        if (!isdigit((unsigned char)*p)) return false;
        p++;
    }
    return true;
}

static bool cn_is_suffix(const char *tok)
{
    if (cn_is_version(tok)) return true;
    for (int k = 0; CN_SUFFIXES[k] != NULL; k++)
        if (!strcasecmp(tok, CN_SUFFIXES[k])) return true;
    return false;
}

bool cn_valid(const char *v)
{
    if (blank(v) || !strcmp(v, ".") || !strcmp(v, "..")) return false;
    if (!isalnum((unsigned char)v[0])) return false;
    for (const char *p = v + 1; *p != '\0'; p++) {
        char c = *p;
        if (!isalnum((unsigned char)c) && c != '.' && c != '_' && c != '+'
            && c != '-')
            return false;
    }
    return true;
}

// strip known extension, token-fold on [._-]+, stop at first version/suffix token, join with '-'
static char *cn_from_asset_name(const char *fileName)
{
    if (blank(fileName)) return NULL;
    char *stem = strdup(fileName);
    if (stem == NULL) return NULL;
    char *dot = strrchr(stem, '.');
    if (dot != NULL && dot > stem) {
        const char *exts[] = {"appimage", "ape", "com", "exe", "msi", "dmg",
            "bin", "xz", "xdelta", NULL};
        for (int k = 0; exts[k] != NULL; k++)
            if (!strcasecmp(dot + 1, exts[k])) { *dot = '\0'; break; }
    }
    jbuf name = {0};
    char *save = NULL;
    for (char *tok = strtok_r(stem, "._-", &save); tok != NULL;
         tok = strtok_r(NULL, "._-", &save)) {
        if (cn_is_suffix(tok)) break;
        if (name.n != 0) jb_lit(&name, "-");
        for (char *p = tok; *p != '\0'; p++) {
            char low[2] = { (char)tolower((unsigned char)*p), 0 };
            jb_lit(&name, low);
        }
    }
    free(stem);
    if (name.p == NULL || name.n == 0) {
        free(name.p);
        return NULL;
    }
    return name.p;
}

// fold from the asset name, else fall back to the repo name; cn_valid applies
char *cn_from_asset(const char *repo_path, const char *asset_name)
{
    char *d = cn_from_asset_name(asset_name);
    if (blank(d)) {
        free(d);
        const char *slash =
            repo_path != NULL ? strrchr(repo_path, '/') : NULL;
        const char *repo =
            slash != NULL ? slash + 1 : (repo_path != NULL ? repo_path : "");
        d = *repo != '\0' ? strdup(repo) : strdup("app");
    }
    for (char *p = d != NULL ? d : (char *)""; *p != '\0'; p++)
        *p = (char)tolower((unsigned char)*p);
    return d;
}
