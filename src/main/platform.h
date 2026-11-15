#ifndef APEGET_PLATFORM_H
#define APEGET_PLATFORM_H

// Platform policy; this slice carries Linux roots.

#include <stddef.h>

// Default install root <home>/.local/bin, NULL without $HOME.
char *platform_install_root_default(void);

// What this machine is, e.g. "Linux x86_64": the running kernel plus the
// arch compiled for, arch alone if the kernel cannot be asked. Writes at
// most cap bytes and always terminates.
void platform_describe(char *buf, size_t cap);

#endif
