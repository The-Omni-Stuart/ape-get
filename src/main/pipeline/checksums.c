// Checksum-file parsing; pure, so the fuzz target links it without
// the install pipeline's link closure.

#include "pipeline/checksums.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

static bool is_sha256_hex(const char *v)
{
    if (strlen(v) != 64) return false;
    for (int i = 0; i < 64; i++)
        if (!isxdigit((unsigned char)v[i])) return false;
    return true;
}

static char *norm_name(const char *name)
{
    char *s = strdup(name);
    if (s == NULL) return NULL;
    char *p = s;
    while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++;
    size_t l = strlen(p);
    while (l > 0 && (p[l - 1] == ' ' || p[l - 1] == '\t' || p[l - 1] == '\n'
        || p[l - 1] == '\r'))
        l--;
    char *t = strndup(p, l);
    free(s);
    for (char *q = t != NULL ? t : (char *)""; *q != '\0'; q++)
        if (*q == '\\') *q = '/';
    char *slash = t != NULL ? strrchr(t, '/') : NULL;
    if (slash != NULL) {
        char *r = strdup(slash + 1);
        free(t);
        return r;
    }
    return t;
}

// BSD line: SHA256 (<name>) = <64 hex>, case-insensitive, name greedy
static bool bsd_line(const char *trimmed, char **name, char **hash)
{
    const char *p = trimmed;
    if (strncasecmp(p, "SHA256", 6) != 0) return false;
    p += 6;
    while (*p == ' ' || *p == '\t') p++;
    if (*p != '(') return false;
    p++;
    const char *close = strrchr(p, ')');
    if (close == NULL || close == p) return false;
    const char *q = close + 1;
    while (*q == ' ' || *q == '\t') q++;
    if (*q != '=') return false;
    q++;
    while (*q == ' ' || *q == '\t') q++;
    if (!is_sha256_hex(q)) return false;
    if (q[strlen(q)] != '\0') return false;
    *name = strndup(p, (size_t)(close - p));
    *hash = strdup(q);
    return *name != NULL && *hash != NULL;
}

void checksum_lookup(const char *content, const char *filename,
                     bool *has_entries, bool *ambiguous, char **sha)
{
    *has_entries = false;
    *ambiguous = false;
    *sha = NULL;
    char *target = norm_name(filename);
    if (target == NULL) return;
    bool done = false;
    const char *start = content;
    while (!done) {
        const char *nl = strpbrk(start, "\r\n");
        size_t llen = nl != NULL ? (size_t)(nl - start) : strlen(start);
        char *line = strndup(start, llen);
        const char *next = nl != NULL ? nl + 1 : NULL;
        if (line != NULL) {
            char *trimmed = line;
            while (*trimmed == ' ' || *trimmed == '\t') trimmed++;
            size_t tl = strlen(trimmed);
            while (tl > 0 && (trimmed[tl - 1] == ' '
                || trimmed[tl - 1] == '\t'))
                trimmed[--tl] = '\0';
            if (*trimmed != '\0' && *trimmed != '#') {
                char *name = NULL, *hash = NULL;
                if (!bsd_line(trimmed, &name, &hash)) {
                    // sha256sum: <hash> [<space>]*<name>, or hash only
                    char *sp = strspn(trimmed, " \t") + trimmed;
                    size_t first = strcspn(sp, " \t");
                    char *tok = strndup(sp, first);
                    if (tok != NULL && is_sha256_hex(tok)) {
                        if (*(sp + first) == '\0') {
                            name = strdup(target);
                            hash = tok;
                            tok = NULL;
                        } else if (*(sp + first) != '\0') {
                            char *rest = sp + first;
                            while (*rest == ' ' || *rest == '\t') rest++;
                            if (*rest == '*') rest++;
                            size_t rl = strlen(rest);
                            while (rl > 0 && (rest[rl - 1] == ' '
                                || rest[rl - 1] == '\t'))
                                rest[--rl] = '\0';
                            name = strdup(rest);
                            hash = tok;
                            tok = NULL;
                        }
                    }
                    free(tok);
                }
                if (name != NULL && hash != NULL) {
                    *has_entries = true;
                    char *nn = norm_name(name);
                    if (nn != NULL && !strcasecmp(nn, target)) {
                        for (char *h = hash; *h != '\0'; h++)
                            *h = (char)tolower((unsigned char)*h);
                        if (*sha != NULL && strcmp(*sha, hash) != 0) {
                            *ambiguous = true;
                            done = true;
                        } else if (*sha == NULL) {
                            *sha = strdup(hash);
                        }
                    }
                    free(nn);
                }
                free(name);
                free(hash);
            }
            free(line);
        }
        if (next == NULL || *next == '\0') {
            if (next == NULL) done = true;
            else if (*next == '\0') done = true;
        }
        start = next;
        if (start == NULL) done = true;
        if (done && *ambiguous) break;
    }
    free(target);
}
