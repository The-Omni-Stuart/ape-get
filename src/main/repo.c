// RepoRef parsing: the provider:path grammar shared by commands and the
// install pipeline.

#include "repo.h"

#include "ui.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// Parse diagnostics carry the Error: prefix + red like every other message;
// ui_msg formats the text unchanged.
#define PARSE_FAIL(...) ui_msg(stderr, UI_ERROR, __VA_ARGS__)

// provider-name grammar, also the SelfId name grammar
static bool name_ok(const char *s)
{
    if (*s == '\0' || !isalnum((unsigned char)*s)) return false;
    for (const char *q = s + 1; *q != '\0'; q++) {
        char c = *q;
        if (!isalnum((unsigned char)c) && c != '.' && c != '_'
            && c != '-') return false;
    }
    return true;
}

static bool parse_core(const char *arg, char *prov, size_t provcap,
                       char *path, size_t pathcap)
{
    const char *colon = strchr(arg, ':');
    const char *slash = strchr(arg, '/');
    if (colon == NULL || (slash != NULL && slash < colon)) {
        PARSE_FAIL("Provider is required; use provider:owner/repo, "
            "for example github:owner/repo");
        return false;
    }
    size_t plen = (size_t)(colon - arg);
    if (plen == 0 || plen + 1 > provcap) {
        PARSE_FAIL("Invalid provider name: %.*s", (int)plen, arg);
        return false;
    }
    for (size_t k = 0; k < plen; k++)
        prov[k] = (char)tolower((unsigned char)arg[k]);
    prov[plen] = '\0';
    // [a-z0-9][a-z0-9._-]*, checked after lower-casing
    if (!isalnum((unsigned char)prov[0])) {
        PARSE_FAIL("Invalid provider name: %s", prov);
        return false;
    }
    for (const char *q = prov + 1; *q != '\0'; q++) {
        char c = *q;
        if (!isalnum((unsigned char)c) && c != '.' && c != '_'
            && c != '-') {
            PARSE_FAIL("Invalid provider name: %s", prov);
            return false;
        }
    }

    const char *p = colon + 1;
    bool blank_path = *p == '\0';
    for (const char *s = p; *s != '\0'; s++)
        if (!isspace((unsigned char)*s)) { blank_path = false; break; }
    if (blank_path) {
        PARSE_FAIL("Repository path is required");
        return false;
    }
    if (p[0] == '/' || p[strlen(p) - 1] == '/'
        || strstr(p, "//") != NULL || strchr(p, '\\') != NULL
        || strchr(p, ':') != NULL) {
        PARSE_FAIL("Invalid repository path: %s", p);
        return false;
    }
    size_t nseg = 1;
    for (const char *s = p; *s != '\0'; s++)
        if (*s == '/') nseg++;
    if (nseg < 2) {
        // `self:<name>` (SelfId) is a bare name under the reserved self
        // provider; everything else demands owner/repo
        bool selfid = strcmp(prov, "self") == 0
            && strchr(p, '/') == NULL && name_ok(p);
        if (!selfid) {
            PARSE_FAIL("Expected provider:owner/repo: %s", p);
            return false;
        }
    }
    for (const char *s = p; *s != '\0';) {
        const char *slash2 = strchr(s, '/');
        size_t len = slash2 != NULL ? (size_t)(slash2 - s) : strlen(s);
        bool blank_seg = len == 0;
        for (size_t k = 0; k < len; k++)
            if (!isspace((unsigned char)s[k])) blank_seg = false;
        if (blank_seg || (len == 1 && s[0] == '.')
            || (len == 2 && s[0] == '.' && s[1] == '.')) {
            PARSE_FAIL("Invalid repository path: %s", p);
            return false;
        }
        if (slash2 == NULL) break;
        s = slash2 + 1;
    }
    snprintf(path, pathcap, "%s", p);
    return true;
}

bool repo_parse_ref(const char *arg, char *prov, size_t provcap,
                    char *path, size_t pathcap)
{
    return parse_core(arg, prov, provcap, path, pathcap);
}
