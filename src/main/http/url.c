// One strict URL/authority parser and redirect resolver, shared by both
// request paths, the origin compares and the provider auth scoping.

#include "http/internal.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

static void uerr(char *errbuf, size_t errcap, const char *msg)
{
    if (errbuf != NULL && errcap > 0) snprintf(errbuf, errcap, "%s", msg);
}

// Control characters, spaces and DEL have no place anywhere in a URL.
static bool clean(const char *s)
{
    for (const unsigned char *p = (const unsigned char *)s; *p; p++)
        if (*p <= ' ' || *p == 0x7f) return false;
    return true;
}

// Absolute https URL -> host (IPv6 brackets stripped), port and request
// target (path+query, fragment dropped). Every failure is explicit: no
// truncation, no silent defaults for junk, no credentials.
bool split_url(const char *url, char *host, size_t hostcap, int *port,
               char *path, size_t pathcap, char *errbuf, size_t errcap)
{
    if (strncasecmp(url, "https://", 8) != 0) {
        uerr(errbuf, errcap, "https required for the request URL");
        return false;
    }
    const char *p = url + 8;
    if (!clean(p)) {
        uerr(errbuf, errcap, "invalid characters in URL");
        return false;
    }
    const char *ae = p;
    while (*ae != '\0' && *ae != '/' && *ae != '?' && *ae != '#') ae++;
    if (memchr(p, '@', (size_t)(ae - p)) != NULL) {
        uerr(errbuf, errcap, "credentials in URL are not supported");
        return false;
    }

    const char *hb = p, *he, *colon = NULL;
    bool bracket = false;
    if (*p == '[') {
        const char *rb = memchr(p, ']', (size_t)(ae - p));
        if (rb == NULL || rb == p + 1
            || (rb + 1 < ae && rb[1] != ':')) {
            uerr(errbuf, errcap, "invalid bracketed IPv6 host");
            return false;
        }
        hb = p + 1; he = rb; bracket = true;
        if (rb + 1 < ae) colon = rb + 1;
    } else {
        colon = memchr(p, ':', (size_t)(ae - p));
        he = colon != NULL ? colon : ae;
    }
    size_t hlen = (size_t)(he - hb);
    if (hlen == 0 || hlen >= hostcap) {
        uerr(errbuf, errcap, "invalid URL host");
        return false;
    }
    memcpy(host, hb, hlen);
    host[hlen] = '\0';
    for (size_t k = 0; k < hlen; k++) {
        char ch = host[k];
        bool ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
            || (ch >= '0' && ch <= '9') || ch == '-' || ch == '.'
            || (bracket && (ch == ':' || ch == '%'));
        if (!ok) {
            uerr(errbuf, errcap, bracket
                ? "invalid bracketed IPv6 host"
                : "invalid URL host (use [ ] for IPv6 literals)");
            return false;
        }
    }
    if (bracket) {   // zone ids reach the socket API as "%eth0", not "%25eth0"
        char *w = host;
        for (const char *q = host; *q != '\0'; ) {
            if (q[0] == '%' && q[1] == '2' && q[2] == '5') {
                *w++ = '%';
                q += 3;
            } else {
                *w++ = *q++;
            }
        }
        *w = '\0';
    }

    int pt = 443;
    if (colon != NULL) {
        const char *q = colon + 1;
        if (q >= ae) {
            uerr(errbuf, errcap, "invalid URL port");
            return false;
        }
        pt = 0;
        for (; q < ae; q++) {
            if (*q < '0' || *q > '9') {
                uerr(errbuf, errcap, "invalid URL port");
                return false;
            }
            if (pt > (65535 - (*q - '0')) / 10) {
                uerr(errbuf, errcap, "URL port out of range");
                return false;
            }
            pt = pt * 10 + (*q - '0');
        }
        if (pt == 0) {
            uerr(errbuf, errcap, "invalid URL port");
            return false;
        }
    }
    *port = pt;

    if (path != NULL) {
        const char *tend = strchr(ae, '#');
        if (tend == NULL) tend = ae + strlen(ae);
        size_t tlen = ae == tend ? 1
            : (size_t)(tend - ae) + (*ae != '/' ? 1 : 0);
        if (tlen >= pathcap) {
            uerr(errbuf, errcap, "URL path too long");
            return false;
        }
        char *w = path;
        if (ae == tend) *w++ = '/';
        else {
            if (*ae != '/') *w++ = '/';
            w = (char *)memcpy(w, ae, (size_t)(tend - ae)) + (tend - ae);
        }
        *w = '\0';
    }
    return true;
}

// host[:port] as it goes into the Host header and redirect bases; the
// default 443 is omitted and IPv6 literals are re-bracketed.
bool url_authority(const char *host, int port, char *out, size_t cap)
{
    int n;
    if (strchr(host, ':') != NULL)
        n = port == 443 ? snprintf(out, cap, "[%s]", host)
                        : snprintf(out, cap, "[%s]:%d", host, port);
    else
        n = port == 443 ? snprintf(out, cap, "%s", host)
                        : snprintf(out, cap, "%s:%d", host, port);
    return n > 0 && (size_t)n < cap;
}

// RFC 3986 §5.2.4 dot-segment removal over an absolute-path buffer.
static void remove_dots(char *p)
{
    char *r = p, *w = p, *end = p + strlen(p);
    while (r < end) {
        if (end - r >= 3 && memcmp(r, "/./", 3) == 0) r += 2;
        else if (end - r == 2 && memcmp(r, "/.", 2) == 0) {
            if (w > p && *(w - 1) != '/') *w++ = '/';
            break;
        }
        else if (end - r >= 4 && memcmp(r, "/../", 4) == 0) {
            if (w > p + 1) while (w > p + 1 && *(w - 1) != '/') w--;
            else w = p + 1;
            r += 4;
        }
        else if (end - r == 3 && memcmp(r, "/..", 3) == 0) {
            if (w > p + 1) while (w > p + 1 && *(w - 1) != '/') w--;
            else w = p + 1;
            break;
        }
        else *w++ = *r++;
    }
    *w = '\0';
}

// Resolve a Location against a base absolute https URL: malloc'd new
// absolute URL, or NULL when unresolvable (bad scheme, bad syntax,
// oversize). Absolute locs are validated but passed through verbatim.
char *url_join(const char *base, const char *loc)
{
    char bh[256], bt[2048], e[96], auth[300];
    int bp;
    if (strncasecmp(loc, "https://", 8) == 0) {
        if (!split_url(loc, bh, sizeof bh, &bp, NULL, 0, e, sizeof e))
            return NULL;
        return strdup(loc);
    }
    if (!clean(loc)) return NULL;
    // a scheme, https or not, is absolute: anything that is not https://
    // is unresolvable, and a colon in the first segment is not a path
    for (const char *q = loc; *q != '\0' && *q != '/'; q++)
        if (*q == ':') return NULL;
    if (!split_url(base, bh, sizeof bh, &bp, bt, sizeof bt, e, sizeof e))
        return NULL;
    if (!url_authority(bh, bp, auth, sizeof auth)) return NULL;

    char merged[4200];
    if (loc[0] == '/' && loc[1] == '/') {
        if (loc[2] == '\0' || loc[2] == '/') return NULL;
        if (snprintf(merged, sizeof merged, "https:%s", loc)
                >= (int)sizeof merged) return NULL;
    } else if (loc[0] == '/') {
        if (snprintf(merged, sizeof merged, "https://%s%s", auth, loc)
                >= (int)sizeof merged) return NULL;
    } else {
        size_t dl = strlen(bt);
        while (dl > 0 && bt[dl - 1] != '/') dl--;   // directory incl '/'
        if (dl + strlen(loc) + 1 >= sizeof merged) return NULL;
        memcpy(merged, bt, dl);
        memcpy(merged + dl, loc, strlen(loc) + 1);
        remove_dots(merged);
        if (merged[0] == '\0') { merged[0] = '/'; merged[1] = '\0'; }
        char target[4200];
        if (snprintf(target, sizeof target, "https://%s%s", auth, merged)
                >= (int)sizeof target) return NULL;
        return split_url(target, bh, sizeof bh, &bp, bt, sizeof bt,
                         e, sizeof e) ? strdup(target) : NULL;
    }
    return split_url(merged, bh, sizeof bh, &bp, bt, sizeof bt,
                     e, sizeof e) ? strdup(merged) : NULL;
}

// True when both absolute https URLs share host[:port]; explicit :443
// normalises onto the default port, an unparseable b counts as foreign.
bool same_origin(const char *a, const char *b)
{
    char ha[256], hb[256], e[64];
    int pa, pb;
    if (!split_url(a, ha, sizeof ha, &pa, NULL, 0, e, sizeof e))
        return false;
    if (!split_url(b, hb, sizeof hb, &pb, NULL, 0, e, sizeof e))
        return false;
    return pa == pb && strcasecmp(ha, hb) == 0;
}
