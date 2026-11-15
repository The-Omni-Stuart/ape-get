#include "platform.h"
#include "path.h"
#include <stdio.h>
#include <stdlib.h>
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
