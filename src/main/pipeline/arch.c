// Asset architecture preference: rank release assets against this machine.

#include "pipeline/internal.h"

#include <ctype.h>
#include <stdlib.h>
#include <string.h>

static const char *ARCH_TAGS[][2] = {
    {"universal2", "universal"}, {"all-arch", NULL}, {"fat", NULL},
    {"aarch64", "arm64"}, {"armv8", NULL}, {"arm-64", "arm_64"},
    {"x86-64", "x86_64"}, {"amd64", "x64"}, {"win64", NULL},
    {"armv6l", "armv7l"}, {"armv6", "armv7"}, {"armhf", NULL},
    {"arm-32", "arm_32"},
    {"x86", "i386"}, {"i486", "i586"}, {"i686", "win32"}, {"ia32", NULL},
    {NULL, NULL}
};
// tag groups in preference order: [0,3) universal, [3,6) ARM_64, [6,9) X86_64, [9,13) ARM_32, [13,17) X86_32

static bool tag_hit(const char *low, const char *tag)
{
    size_t tl = strlen(tag);
    for (const char *p = low; (p = strstr(p, tag)) != NULL; p++) {
        char before = p > low ? p[-1] : '\0';
        char after = p[tl];
        if (isalnum((unsigned char)before)
            || isalnum((unsigned char)after))
            continue;
        return true;
    }
    return false;
}

// detect: 0 none, 1 universal, 2 arm64, 3 x86_64, 4 arm32, 5 x86_32; first matching group wins
static int arch_detect(const char *name)
{
    char *low = strdup(name);
    if (low == NULL) return 0;
    for (char *p = low; *p != '\0'; p++)
        *p = (char)tolower((unsigned char)*p);
    const int ranges[][3] = { {0, 3, 1}, {3, 6, 2}, {6, 9, 3},
        {9, 13, 4}, {13, 17, 5} };
    int res = 0;
    for (int g = 0; g < 5 && res == 0; g++)
        for (int i = ranges[g][0]; i < ranges[g][1] && res == 0; i++)
            for (int v = 0; v < 2; v++)
                if (ARCH_TAGS[i][v] != NULL
                    && tag_hit(low, ARCH_TAGS[i][v])) {
                    res = ranges[g][2];
                    break;
                }
    free(low);
    return res;
}

static int arch_current(void)
{
#if defined(__x86_64__)
    return 3;
#elif defined(__aarch64__)
    return 2;
#elif defined(__arm__)
    return 4;
#elif defined(__i386__) || defined(__X86_32)
    return 5;
#else
    return 0;
#endif
}

// public: the arch group of an asset name (0 none, 1 universal, ... 3 x86_64)
int arch_line_of(const char *name) { return arch_detect(name); }
int arch_line_current(void) { return arch_current(); }

// strip one trailing "-<arch alias>" (or "_<alias>") from a name that has
// already lost its artifact extension; true when a variant token was cut
bool arch_strip_variant(char *name)
{
    size_t l = strlen(name);
    char *low = strdup(name);
    if (low == NULL) return false;
    for (char *p = low; *p != '\0'; p++)
        *p = (char)tolower((unsigned char)*p);
    bool hit = false;
    for (int i = 0; ARCH_TAGS[i][0] != NULL && !hit; i++)
        for (int v = 0; v < 2 && !hit; v++) {
            const char *t = ARCH_TAGS[i][v];
            if (t == NULL) continue;
            size_t tl = strlen(t);
            if (l > tl + 1
                && !strcmp(low + l - tl, t)
                && (name[l - tl - 1] == '-' || name[l - tl - 1] == '_')) {
                name[l - tl - 1] = '\0';
                hit = true;
            }
        }
    free(low);
    return hit;
}

// keep untagged/universal/current-arch when any asset is tagged;
// fall back to the whole pool when nothing would remain
int arch_prefer(prov_asset *assets, int n)
{
    if (n < 2) return n;
    bool tagged = false;
    for (int k = 0; k < n && !tagged; k++)
        tagged = arch_detect(assets[k].name) != 0;
    if (!tagged) return n;
    int cur = arch_current();
    int out = 0;
    for (int k = 0; k < n; k++) {
        int a = arch_detect(assets[k].name);
        if (a == 0 || a == 1 || a == cur) {
            if (out != k) assets[out] = assets[k];
            out++;
        }
    }
    return out == 0 ? n : out;
}
