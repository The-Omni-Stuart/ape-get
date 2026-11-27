#include "platform.h"
#include "path.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/utsname.h>

char *platform_install_root_default(void)
{
    char *h = path_home();
    if (h == NULL) return NULL;
    char *r = path_join(h, ".local/bin");
    free(h);
    return r;
}

// Cosmopolitan binaries outlive their compile-time OS assumption: ask the
// kernel actually running (uname is implemented even under the APE loader)
void platform_describe(char *buf, size_t cap)
{
    const char *arch =
#if defined(__x86_64__)
        "x86_64"
#elif defined(__aarch64__)
        "aarch64"
#else
        "unknown-arch"
#endif
        ;
    struct utsname u;
    if (uname(&u) == 0) snprintf(buf, cap, "%s %s", u.sysname, arch);
    else snprintf(buf, cap, "%s", arch);
}

// sysname is the whole of the decision: it is what the running kernel calls
// itself, so it is also what a person typing --override-platform is correcting.
platform_os platform_current(void)
{
    struct utsname u;
    if (uname(&u) != 0) return OS_UNKNOWN;
    if (!strcmp(u.sysname, "Linux")) return OS_LINUX;
    if (!strcmp(u.sysname, "Darwin")) return OS_MACOS;
    return OS_UNKNOWN;
}

bool platform_os_parse(const char *name, platform_os *out)
{
    if (name == NULL || out == NULL) return false;
    if (!strcasecmp(name, "linux")) *out = OS_LINUX;
    else if (!strcasecmp(name, "macos")) *out = OS_MACOS;
    else if (!strcasecmp(name, "windows")) *out = OS_WINDOWS;
    else return false;
    return true;
}

const char *platform_os_name(platform_os os)
{
    switch (os) {
    case OS_LINUX: return "Linux";
    case OS_MACOS: return "macOS";
    case OS_WINDOWS: return "Windows";
    default: return "an unknown system";
    }
}
