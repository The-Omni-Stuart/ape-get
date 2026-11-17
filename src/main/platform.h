#ifndef APEGET_PLATFORM_H
#define APEGET_PLATFORM_H

// Platform policy: what OS is running, where its executables go, and how a
// directory gets onto PATH.

#include <stdbool.h>
#include <stddef.h>

// The OS the process is actually running on. One fat APE serves all three,
// so this is never a compile-time question.
typedef enum { OS_UNKNOWN = 0, OS_LINUX, OS_MACOS, OS_WINDOWS } platform_os;

// Asked of the running kernel; OS_UNKNOWN when it cannot be named.
platform_os platform_current(void);

// The name the installed command answers to. Windows resolves a command only
// through one of its executable extensions, so the download keeps its suffix.
const char *platform_command_name(platform_os os);

// Is this process running from an installed ape-get rather than a downloaded
// one? Anywhere on PATH counts, as does the file the default install root
// points at. When it cannot be told, true: silence beats nagging.
bool platform_self_is_installed(void);

// Where PATH registration for this OS is written, phrased for a person: the
// shell init file a login shell reads, the user environment on Windows.
// NULL when it cannot be named.
char *platform_path_target(platform_os os);

// True when setup has already registered `dir` for future shells.
bool platform_path_registered(platform_os os, const char *dir);

// Register `dir` for future shells, leaving everything else in the file
// alone and rewriting setup's own entry rather than adding a second. False
// with a one-line reason in `err` when it could not be written.
bool platform_path_register(platform_os os, const char *dir,
    char *err, size_t cap);

// The executable this process is running from: the file setup installs.
// APEGET_SELF_EXE overrides it for the scenario suite, which must not spend
// a megabyte per case; production never sets it.
char *platform_self_exe(void);

// Default install root for a named system: <home>/.local/bin on the unixes,
// %LOCALAPPDATA%\Programs\ape-get on Windows. NULL when the pieces the name
// is built from are missing. setup uses this rather than the current system's
// when the user has said which system it is installing for.
char *platform_install_root_default_for(platform_os os);

// The directory `dir` in the form the system being installed for names a
// directory. On Windows a drive path arrives as "/C/Users" from the runtime or
// "C:/Users" from a keyboard and both are answered as "C:\\Users", because that
// is the form a Path entry shows and the form setup accepts as absolute; a
// directory without a drive in front is left exactly as it was named. The
// unixes spell a path the way it was given, so it comes back unchanged. NULL
// when there is nothing to name.
char *platform_install_dir_for(platform_os os, const char *dir);

// The same for the system actually running.
char *platform_install_root_default(void);

// What this machine is, e.g. "Linux x86_64": the running kernel plus the
// arch compiled for, arch alone if the kernel cannot be asked. Writes at
// most cap bytes and always terminates.
void platform_describe(char *buf, size_t cap);

#endif
