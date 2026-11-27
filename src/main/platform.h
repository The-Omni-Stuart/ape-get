#ifndef APEGET_PLATFORM_H
#define APEGET_PLATFORM_H

// Platform policy; this slice carries the systems setup installs on.

#include <stdbool.h>
#include <stddef.h>

// The systems setup knows how to lay out. Anything else is unknown: saying so
// beats guessing at a layout nobody asked for.
typedef enum { OS_UNKNOWN = 0, OS_LINUX, OS_MACOS, OS_WINDOWS } platform_os;

// Default install root <home>/.local/bin, NULL without $HOME.
char *platform_install_root_default(void);

// What this machine is, e.g. "Linux x86_64": the running kernel plus the
// arch compiled for, arch alone if the kernel cannot be asked. Writes at
// most cap bytes and always terminates.
void platform_describe(char *buf, size_t cap);

// The system the running kernel reports; OS_UNKNOWN when the kernel cannot be
// asked or names a system setup has no layout for.
platform_os platform_current(void);

// linux, macos or windows in any case, to a platform_os; false when the word
// names none of them.
bool platform_os_parse(const char *name, platform_os *out);

// The word a person reads for a system, and the one os_parse takes back.
const char *platform_os_name(platform_os os);

// The file that puts a directory in front of PATH for shells started later:
// ~/.profile on Linux, ~/.zshrc on macOS. NULL for a system with no such file
// written here, or without $HOME.
char *platform_path_target(platform_os os);

// Whether dir is already one of this process's PATH entries. The shell that
// ran the command can already find the command, so nothing needs writing.
bool platform_path_effective(const char *dir);

// Whether dir is already in that file exactly as register would write it.
bool platform_path_registered(platform_os os, const char *dir);

// Put dir in front of PATH for later shells, replacing setup's own earlier
// line and leaving every other line where it is. False, with the reason in
// err, when nothing was written.
bool platform_path_register(platform_os os, const char *dir,
    char *err, size_t cap);

#endif
