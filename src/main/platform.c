#include "platform.h"
#include "path.h"
#include "platform/internal.h"
#include <ctype.h>
#include <libc/str/utf16.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <sys/utsname.h>
#include <unistd.h>

const char *platform_command_name(platform_os os)
{
    return os == OS_WINDOWS ? "ape-get.com" : "ape-get";
}

// PATH entries are separated by ';' on Windows and ':' elsewhere, and only
// the comparison the OS uses is right: case-insensitive on Windows.
static bool self_dir_on_path(const char *dir)
{
    const char *env = getenv("PATH");
    if (env == NULL) return false;
    bool win = platform_current() == OS_WINDOWS;
    char sep = win ? ';' : ':';
    size_t dl = strlen(dir);
    while (dl > 0 && (dir[dl - 1] == '/' || dir[dl - 1] == '\\')) dl--;
    for (const char *p = env; ; ) {
        const char *e = strchr(p, sep);
        size_t len = e != NULL ? (size_t)(e - p) : strlen(p);
        while (len > 0 && (p[len - 1] == '/' || p[len - 1] == '\\')) len--;
        if (len == dl && (win ? !strncasecmp(p, dir, len)
                              : !strncmp(p, dir, len))) return true;
        if (e == NULL) break;
        p = e + 1;
    }
    return false;
}

// Two ways this file can be an installed one rather than a download: the
// directory it was run from is already on PATH, wherever that turned out to
// be, or it is the very file the platform's default root points at. Both are
// a scan and a stat, so the check stays cheap enough to run on every command.
bool platform_self_is_installed(void)
{
    char *self = platform_self_exe();
    if (self == NULL) return true;      // cannot tell: stay quiet

    size_t cut = 0;
    for (size_t i = 0; self[i] != '\0'; i++)
        if (self[i] == '/' || self[i] == '\\') cut = i;
    if (cut > 0) {
        char saved = self[cut];
        self[cut] = '\0';
        bool on = self_dir_on_path(self);
        self[cut] = saved;
        if (on) { free(self); return true; }
    }

    bool same = false;
    char *dir = platform_install_root_default();
    if (dir != NULL) {
        char *want = path_join(dir, platform_command_name(platform_current()));
        struct stat a, b;
        same = want != NULL && stat(want, &a) == 0 && stat(self, &b) == 0
            && a.st_dev == b.st_dev && a.st_ino == b.st_ino;
        free(want);
        free(dir);
    }
    free(self);
    return same;
}

// The UTF-16 the NT kernel answers in, written out as the UTF-8 every other
// path in this program is held in. No length is refused here: the kernel has
// already refused to answer with a name that did not fit.
static char *utf8_of_utf16(const char16_t *wide)
{
    size_t units = 0;
    while (wide[units] != 0) units++;
    char *out = malloc(units * 3 + 1);
    if (out == NULL) return NULL;
    size_t w = 0;
    for (size_t r = 0; wide[r] != 0; r++) {
        unsigned u = wide[r];
        // Two code units name one character beyond the Basic Multilingual
        // Plane, and the pair has to be joined before it can be written.
        if (IsHighSurrogate(u) && IsLowSurrogate(wide[r + 1])) {
            u = MergeUtf16(u, wide[r + 1]);
            r++;
        }
        // Three bytes is the most one code unit ever costs: the joined pair is
        // two units and writes four.
        if (u < 0x80) {
            out[w++] = (char)u;
        } else if (u < 0x800) {
            out[w++] = (char)(0xc0 | (u >> 6));
            out[w++] = (char)(0x80 | (u & 0x3f));
        } else if (u < 0x10000) {
            out[w++] = (char)(0xe0 | (u >> 12));
            out[w++] = (char)(0x80 | ((u >> 6) & 0x3f));
            out[w++] = (char)(0x80 | (u & 0x3f));
        } else {
            out[w++] = (char)(0xf0 | (u >> 18));
            out[w++] = (char)(0x80 | ((u >> 12) & 0x3f));
            out[w++] = (char)(0x80 | ((u >> 6) & 0x3f));
            out[w++] = (char)(0x80 | (u & 0x3f));
        }
    }
    out[w] = '\0';
    return out;
}

char *platform_self_exe(void)
{
    const char *hook = getenv("APEGET_SELF_EXE");
    if (hook != NULL && *hook != '\0') return strdup(hook);
    if (platform_current() == OS_WINDOWS) {
        char16_t wide[2048];
        uint32_t n = platform_win_module_name(wide,
            sizeof wide / sizeof wide[0]);
        // Zero is a kernel with nothing to say, and the buffer's own size back
        // is the kernel admitting it wrote only the start of the name: half a
        // path names a different file than the one being installed.
        if (n == 0 || n >= sizeof wide / sizeof wide[0]) return NULL;
        return utf8_of_utf16(wide);
    }
    // the loader-emulated link names our own file on the unixes; Windows has
    // no such link and is asked a different way above
    char buf[8192];
    ssize_t n = readlink("/proc/self/exe", buf, sizeof buf - 1);
    if (n <= 0) return NULL;
    buf[n] = '\0';
    return strdup(buf);
}

// The three names the polyglot's libc reports for the kernel it is running
// under: "Windows" under the APE loader, "Darwin" on XNU, "Linux" on Linux.
platform_os platform_current(void)
{
    struct utsname u;
    if (uname(&u) != 0) return OS_UNKNOWN;
    if (!strcmp(u.sysname, "Linux")) return OS_LINUX;
    if (!strcmp(u.sysname, "Darwin")) return OS_MACOS;
    if (!strcmp(u.sysname, "Windows")) return OS_WINDOWS;
    return OS_UNKNOWN;
}

// Two spellings of one directory reach setup on Windows: "/C/Users", which is
// how the runtime reports a drive path held in an environment variable, and
// "C:/Users", which is what a person types or pastes. Both name a drive, so
// both are answered the way Windows itself shows them, and the leading "/" of
// the first is the drive rather than a root: leaving it in place names a
// directory called C under the root of whatever drive the command runs on.
char *platform_install_dir_for(platform_os os, const char *dir)
{
    if (dir == NULL || *dir == '\0') return NULL;
    if (os != OS_WINDOWS) return strdup(dir);

    size_t letter;                   // where the drive letter sits in dir
    if (dir[0] == '/' && isalpha((unsigned char)dir[1]) && dir[2] == '/')
        letter = 1;
    else if (isalpha((unsigned char)dir[0]) && dir[1] == ':'
        && (dir[2] == '/' || dir[2] == '\\'))
        letter = 0;
    else
        return strdup(dir);          // nothing names a drive: nothing to repair

    size_t n = strlen(dir);
    while (n > 2 && (dir[n - 1] == '/' || dir[n - 1] == '\\')) n--;
    char *out = malloc(n + 1);
    if (out == NULL) return NULL;
    out[0] = dir[letter];
    out[1] = ':';
    for (size_t r = 2; r < n; r++)
        out[r] = dir[r] == '/' ? '\\' : dir[r];
    out[n] = '\0';
    return out;
}

char *platform_install_root_default_for(platform_os os)
{
    if (os == OS_WINDOWS) {
        // %LOCALAPPDATA% is per-user and needs no elevation; the path is
        // spelled with backslashes because that is what a PATH entry shows
        const char *l = getenv("LOCALAPPDATA");
        if (l == NULL || *l == '\0') return NULL;
        char *root = platform_install_dir_for(OS_WINDOWS, l);
        if (root == NULL) return NULL;
        char *r = path_fmt("%s\\Programs\\ape-get", root);
        free(root);
        return r;
    }
    char *h = path_home();
    if (h == NULL) return NULL;
    char *r = path_join(h, ".local/bin");
    free(h);
    return r;
}

char *platform_install_root_default(void)
{
    return platform_install_root_default_for(platform_current());
}

char *platform_path_target(platform_os os)
{
    if (os == OS_WINDOWS) return strdup("the user environment");
    char *h = path_home();
    if (h == NULL) return NULL;
    char *r = path_join(h, os == OS_MACOS ? ".zshrc" : ".profile");
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
