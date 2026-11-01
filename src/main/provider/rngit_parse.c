// rngit text side: the opaque asset-URL codec plus the ANSI strip and
// release-view grammar; the CLI runner with its error classes: rngit.c.

#include "provider.h"
#include "path.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// base64url without padding (opaque asset URLs); the decoder also accepts
// the standard alphabet and trailing '='.
static const char B64[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

static char *b64url_encode(const unsigned char *in, size_t n)
{
    char *o = malloc((n + 2) / 3 * 4 + 1);
    if (o == NULL) return NULL;
    size_t w = 0, k;
    for (k = 0; k + 3 <= n; k += 3) {
        unsigned v = ((unsigned)in[k] << 16) | ((unsigned)in[k + 1] << 8)
            | in[k + 2];
        o[w++] = B64[(v >> 18) & 63]; o[w++] = B64[(v >> 12) & 63];
        o[w++] = B64[(v >> 6) & 63]; o[w++] = B64[v & 63];
    }
    if (k < n) {
        unsigned v = (unsigned)in[k] << 16;
        if (k + 1 < n) v |= (unsigned)in[k + 1] << 8;
        o[w++] = B64[(v >> 18) & 63];
        o[w++] = B64[(v >> 12) & 63];
        if (k + 1 < n) o[w++] = B64[(v >> 6) & 63];
    }
    o[w] = '\0';
    return o;
}

static int b64val(int c)
{
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '-' || c == '+') return 62;
    if (c == '_' || c == '/') return 63;
    return -1;
}

static unsigned char *b64url_decode(const char *s, size_t *len_out)
{
    size_t n = strlen(s), w = 0, v = 0, bits = 0;
    unsigned char *o = malloc((n / 4) * 3 + 3);
    if (o == NULL) return NULL;
    bool pad = false;
    for (size_t k = 0; k < n; k++) {
        if (s[k] == '=') { pad = true; continue; }
        if (pad) { free(o); return NULL; }
        int d = b64val((unsigned char)s[k]);
        if (d < 0) { free(o); return NULL; }
        v = (v << 6) | (unsigned)d;
        if ((bits += 6) >= 8) { bits -= 8; o[w++] = (unsigned char)(v >> bits); }
    }
    *len_out = w;
    return o;
}

static char *opaque_url(const char *path, const char *tag, const char *name)
{
    size_t n = strlen(path) + strlen(tag) + strlen(name) + 3;
    unsigned char *raw = malloc(n);
    if (raw == NULL) return NULL;
    size_t w = 0;
    memcpy(raw + w, path, strlen(path) + 1); w += strlen(path) + 1;
    memcpy(raw + w, tag, strlen(tag) + 1); w += strlen(tag) + 1;
    memcpy(raw + w, name, strlen(name) + 1);
    char *b64 = b64url_encode(raw, n);
    free(raw);
    char *url = b64 != NULL ? path_fmt("rngit://%s", b64) : NULL;
    free(b64);
    return url;
}

static bool clean_name(const char *s, const char *extra)
{
    if (*s == '\0') return false;
    for (const char *p = s; *p != '\0'; p++)
        if (iscntrl((unsigned char)*p) || strchr(extra, *p) != NULL)
            return false;
    return true;
}

// Path segment: non-empty, not "." or "..".
static bool seg_ok(const char *s, size_t len)
{
    if (len == 0) return false;
    if (len == 1 && s[0] == '.') return false;
    if (len == 2 && s[0] == '.' && s[1] == '.') return false;
    return true;
}

// Decode + re-validate one opaque URL; the checks run in a fixed order:
// tag, then artefact name, then path shape.
bool opaque_decode(const char *url, char **path, char **tag,
                          char **name, char *msg, size_t cap)
{
    *path = NULL; *tag = NULL; *name = NULL;
    size_t len = 0;
    unsigned char *raw = b64url_decode(url + 8, &len);
    char *p1 = raw != NULL ? memchr(raw, 0, len) : NULL;
    char *p2 = NULL, *p3 = NULL;
    if (p1 != NULL && (size_t)(p1 - (char *)raw) + 1 < len)
        p2 = memchr(p1 + 1, 0, len - (size_t)(p1 - (char *)raw) - 1);
    if (p2 != NULL && (size_t)(p2 - (char *)raw) + 1 < len)
        p3 = memchr(p2 + 1, 0, len - (size_t)(p2 - (char *)raw) - 1);
    if (p3 == NULL || p3 + 1 != (char *)raw + len) {
        free(raw);
        snprintf(msg, cap, "rngit asset URL is invalid");
        return false;
    }
    *path = strdup((char *)raw);
    *tag = strdup(p1 + 1);
    *name = strdup(p2 + 1);
    free(raw);
    if (*path == NULL || *tag == NULL || *name == NULL) return false;
    if (*(*tag) == '-' || !clean_name(*tag, "/:")) {
        snprintf(msg, cap, "rngit release tag is invalid: %s", *tag);
        return false;
    }
    if (strcmp(*name, ".") == 0 || strcmp(*name, "..") == 0
        || !clean_name(*name, "/\\:")) {
        snprintf(msg, cap, "rngit asset name is invalid: %s", *name);
        return false;
    }
    const char *slash = strchr(*path, '/');
    size_t l1 = slash != NULL ? (size_t)(slash - *path) : strlen(*path);
    const char *seg2 = slash != NULL ? slash + 1 : "";
    if (slash == NULL || strchr(seg2, '/') != NULL
        || !seg_ok(*path, l1) || !seg_ok(seg2, strlen(seg2))) {
        snprintf(msg, cap, "rngit repositories use group/repo paths, got: %s",
            *path);
        return false;
    }
    return true;
}

// Strip ANSI CSI/escape sequences before parsing view output.
char *strip_ansi(const char *s)
{
    char *o = malloc(strlen(s) + 1);
    if (o == NULL) return NULL;
    char *w = o;
    for (const char *p = s; *p != '\0'; ) {
        if ((unsigned char)*p == 0x1b && p[1] == '[') {
            const char *q = p + 2;
            while (*q != '\0' && (*q < '@' || *q > '~')) q++;
            p = *q != '\0' ? q + 1 : q;
        } else if ((unsigned char)*p == 0x1b && p[1] != '\0') {
            p += 2;
        } else {
            *w++ = *p++;
        }
    }
    *w = '\0';
    return o;
}

// `Release : <tag>` plus the `Artifacts (` bullets to end of output (no
// end marker, so note lines read as assets); "(size)" groups are dropped.
bool parse_view(const char *vis, const char *path, prov_release *out)
{
    char *version = NULL;
    char **names = NULL;
    int cap = 0;
    bool inart = false;
    const char *p = vis;
    while (*p != '\0') {
        const char *e = strchr(p, '\n');
        size_t span = e != NULL ? (size_t)(e - p) : strlen(p);
        const char *a = p, *b = p + span;
        while (a < b && isspace((unsigned char)*a)) a++;
        while (b > a && isspace((unsigned char)*(b - 1))) b--;
        if (!inart && version == NULL && (size_t)(b - a) > 10
            && strncmp(a, "Release : ", 10) == 0) {
            const char *v = a + 10;
            while (v < b && isspace((unsigned char)*v)) v++;
            if (v < b) version = strndup(v, (size_t)(b - v));
        } else if (!inart && (size_t)(b - a) >= 11
            && strncmp(a, "Artifacts (", 11) == 0) {
            inart = true;
        } else if (inart && (size_t)(b - a) >= 2 && a[0] == '-' && a[1] == ' ') {
            const char *s = a + 2;
            while (s < b && isspace((unsigned char)*s)) s++;
            const char *ne = b;
            if (ne > s && ne[-1] == ')')
                for (const char *q = ne - 1; q > s + 1; q--)
                    if (q[-1] == '(' && q[-2] == ' ') { ne = q - 2; break; }
            while (ne > s && isspace((unsigned char)*(ne - 1))) ne--;
            if (ne > s) {
                char **nb = realloc(names, (size_t)(cap + 1) * sizeof *names);
                if (nb == NULL) break;
                names = nb;
                names[cap] = strndup(s, (size_t)(ne - s));
                if (names[cap] != NULL) cap++;
            }
        }
        if (e == NULL) break;
        p = e + 1;
    }
    if (version == NULL) {
        for (int k = 0; k < cap; k++) free(names[k]);
        free(names);
        return false;
    }
    out->version = version;
    out->assets = cap > 0 ? calloc((size_t)cap, sizeof(*out->assets)) : NULL;
    out->n = 0;
    for (int k = 0; names != NULL && k < cap; k++) {
        char *url = opaque_url(path, version, names[k]);
        if (out->assets != NULL && url != NULL) {
            out->assets[out->n].name = names[k];
            out->assets[out->n].url = url;
            out->n++;
        } else {
            free(names[k]);
            free(url);
        }
    }
    free(names);
    return true;
}
