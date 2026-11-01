// Asset architecture preference: rank release assets against this machine.

#include "pipeline/internal.h"

#include <ctype.h>
#include <stdlib.h>
#include <string.h>

static const char *ARCH_TAGS[][2] = {
    {"universal2", "universal"}, {"all-arch", NULL},
    {"aarch64", "arm64"}, {"armv8", NULL}, {"arm-64", "arm_64"},
    {"x86-64", "x86_64"}, {"amd64", "x64"}, {"win64", NULL},
    {"armv6l", "armv7l"}, {"armv6", "armv7"}, {"armhf", NULL},
    {"arm-32", "arm_32"},
    {"x86", "i386"}, {"i486", "i586"}, {"i686", "win32"}, {"ia32", NULL},
    {NULL, NULL}
};
// tag groups in preference order: [0,2) universal, [2,5) ARM_64, [5,8) X86_64, [8,12) ARM_32, [12,16) X86_32

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
    const int ranges[][3] = { {0, 2, 1}, {2, 5, 2}, {5, 8, 3},
        {8, 12, 4}, {12, 16, 5} };
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
