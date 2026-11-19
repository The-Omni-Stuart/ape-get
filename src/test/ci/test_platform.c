// Exercise the machine description without going through a command.
#include "platform.h"

#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/utsname.h>
#include <uchar.h>
#include <unistd.h>

// The expected strings are worked examples, not a re-run of the code: this
// binary runs on a Linux kernel and is compiled for one architecture, so
// both are known before the call.
#if defined(__x86_64__)
#define WANT_ARCH "x86_64"
#define WANT_LINE "Linux x86_64"
#elif defined(__aarch64__)
#define WANT_ARCH "aarch64"
#define WANT_LINE "Linux aarch64"
#else
#define WANT_ARCH NULL
#endif

// uname is the one system boundary here, so it is the one thing faked;
// the link wraps it (see test_tcp_connect and its getaddrinfo).
static bool kernel_answers = true;
static const char *kernel_sysname;   // NULL: hand the call back to the kernel

// Cosmopolitan carries these in libc/runtime/runtime.h rather than the
// <stdlib.h> a host compiler would declare them from.
int setenv(const char *, const char *, int);
int unsetenv(const char *);

int __real_uname(struct utsname *u);

int __wrap_uname(struct utsname *u)
{
    if (!kernel_answers) return -1;
    if (kernel_sysname != NULL) {
        snprintf(u->sysname, sizeof u->sysname, "%s", kernel_sysname);
        return 0;
    }
    return __real_uname(u);
}

static int failures;

static void expect(const char *label, const char *got, const char *want)
{
    if (strcmp(got, want) != 0) {
        fprintf(stderr, "%s: expected \"%s\", got \"%s\"\n", label, want, got);
        failures++;
    }
}

// Which OS a given kernel answer means. The answers are the strings uname is
// documented to return, so the pairing comes from that contract, not from the
// code under test; a host's own kernel is never asked here, so all four
// branches are pinned on every host.
static void expect_os(const char *sysname, platform_os want, const char *label)
{
    kernel_answers = sysname != NULL;
    kernel_sysname = sysname;
    platform_os got = platform_current();
    if (got != want) {
        fprintf(stderr, "%s: expected os %d, got %d\n", label,
            (int)want, (int)got);
        failures++;
    }
}

// Where each OS puts its executables. The expected paths are written out by
// hand from the platform convention being followed, so a wrong join, a wrong
// separator or a wrong default fails here rather than in someone's PATH.
static void expect_root(const char *sysname, const char *want,
    const char *label)
{
    kernel_answers = sysname != NULL;
    kernel_sysname = sysname;
    char *got = platform_install_root_default();
    bool ok = want == NULL ? got == NULL
        : got != NULL && strcmp(got, want) == 0;
    if (!ok) {
        fprintf(stderr, "%s: expected %s, got %s\n", label,
            want != NULL ? want : "NULL", got != NULL ? got : "NULL");
        failures++;
    }
    free(got);
}

// The directory a person named, in the form the system being installed for
// names directories. Every expected string is the Windows spelling written out
// by hand, and the unix answers are the given path back again: they are what
// pins that the repair happens only where a leading "/C" is a drive at all.
static void expect_dir(platform_os os, const char *given, const char *want,
    const char *label)
{
    char *got = platform_install_dir_for(os, given);
    bool ok = want == NULL ? got == NULL
        : got != NULL && strcmp(got, want) == 0;
    if (!ok) {
        fprintf(stderr, "%s: expected %s, got %s\n", label,
            want != NULL ? want : "NULL", got != NULL ? got : "NULL");
        failures++;
    }
    free(got);
}

// Which file this process is running from is the file setup installs, so the
// one system call behind it is worth standing in for: the kernel's answer about
// the system is already faked through uname above, and on Windows the name
// comes from the NT kernel in UTF-16 rather than from a link. Both fakes answer
// only while the case has set them, and the readlink one hands the call back to
// the host otherwise. The expected paths are Windows paths written out by hand:
// the code under test has to do the UTF-16 to UTF-8 work to match them.
static const char16_t *win_answer;      // NULL: the kernel gives no answer
static const char *link_answer;         // NULL: the host's own link answers
static bool link_missing;               // true: there is no link to read

// The NT kernel's reply exactly as the kernel gives it: the name written and
// its length, the whole buffer quoted back when the name did not fit, and zero
// when it has nothing to say. Passing that back unfinished, rather than
// trimming the name down, is the platform module's decision - so the kernel it
// is deciding about has to be answered with here.
uint32_t __wrap_platform_win_module_name(char16_t *wide, uint32_t cap)
{
    if (win_answer == NULL) return 0;
    uint32_t n = 0;
    while (win_answer[n] != 0) n++;
    if (n >= cap) {
        for (uint32_t i = 0; i + 1 < cap; i++) wide[i] = win_answer[i];
        wide[cap - 1] = 0;
        return cap;
    }
    for (uint32_t i = 0; i <= n; i++) wide[i] = win_answer[i];
    return n;
}

ssize_t __real_readlink(const char *, char *, size_t);

ssize_t __wrap_readlink(const char *path, char *buf, size_t size)
{
    if (link_missing) return -1;
    if (link_answer == NULL) return __real_readlink(path, buf, size);
    size_t n = strlen(link_answer);
    if (n > size - 1) n = size - 1;
    memcpy(buf, link_answer, n);
    return (ssize_t)n;
}

static void expect_self(const char *label, const char *want)
{
    char *got = platform_self_exe();
    bool ok = want == NULL ? got == NULL
        : got != NULL && strcmp(got, want) == 0;
    if (!ok) {
        fprintf(stderr, "%s: expected %s, got %s\n", label,
            want != NULL ? want : "NULL", got != NULL ? got : "NULL");
        failures++;
    }
    free(got);
    win_answer = NULL;
    link_answer = NULL;
    link_missing = false;
}

// --- PATH registration on Windows -------------------------------------
// The Windows branch talks to the registry, which no Linux host has, so the
// two calls that reach it are stood in for here, in the form the registry
// speaks: UTF-16 code units. Reading the stored value back, working out
// whether the directory is already in it, joining ours on the end and writing
// the result are all the code under test, which is why the seam sits at the
// registry rather than at the decision. The expected values are written out in
// UTF-16 as well, so a conversion that mangles what it carries fails here.
#define WIN_DIR "C:\\Users\\t\\AppData\\Local\\Programs\\ape-get"
#define WIN_DIR16 u"C:\\Users\\t\\AppData\\Local\\Programs\\ape-get"

// A Path as people actually leave theirs: an expandable entry that has to come
// back out with its percent signs intact, since the value type is what makes
// them mean something.
#define WIN_OTHER16 u"C:\\Windows;%USERPROFILE%\\bin"

static const char16_t *reg_stored;      // NULL: the registry holds no Path
static bool reg_refuses;                // true: the registry says no
static char16_t *reg_written;           // NULL: nothing has been written

char16_t *__wrap_platform_win_reg_read(void)
{
    if (reg_stored == NULL) return NULL;
    size_t n = 0;
    while (reg_stored[n] != 0) n++;
    char16_t *copy = malloc((n + 1) * sizeof *copy);
    if (copy == NULL) return NULL;
    memcpy(copy, reg_stored, (n + 1) * sizeof *copy);
    return copy;
}

bool __wrap_platform_win_reg_write(const char16_t *wide)
{
    if (reg_refuses) return false;
    free(reg_written);
    reg_written = NULL;
    size_t n = 0;
    while (wide[n] != 0) n++;
    reg_written = malloc((n + 1) * sizeof *reg_written);
    if (reg_written == NULL) return false;
    memcpy(reg_written, wide, (n + 1) * sizeof *reg_written);
    return true;
}

// What the registry holds, and whether it will accept a change. Called before
// every case, so no case inherits another's registry.
static void fake_registry(const char16_t *stored, bool refuses)
{
    reg_stored = stored;
    reg_refuses = refuses;
    free(reg_written);
    reg_written = NULL;
}

static bool wide_same(const char16_t *a, const char16_t *b)
{
    for (size_t i = 0; ; i++) {
        if (a[i] != b[i]) return false;
        if (a[i] == 0) return true;
    }
}

// Code units in hexadecimal: a terminal holding UTF-8 cannot show UTF-16, and
// a mismatch this far into the registry is worth being able to read.
static void print_wide(const char *tag, const char16_t *w)
{
    fprintf(stderr, "    %s:", tag);
    for (size_t i = 0; i < 96 && w[i] != 0; i++) fprintf(stderr, " %04x", w[i]);
    fprintf(stderr, "\n");
}

static void expect_written(const char16_t *want, const char *label)
{
    if (reg_written == NULL) {
        fprintf(stderr, "%s: nothing was written\n", label);
        failures++;
    } else if (!wide_same(reg_written, want)) {
        fprintf(stderr, "%s: wrote the wrong value\n", label);
        print_wide("wanted", want);
        print_wide("got   ", reg_written);
        failures++;
    }
}

int main(int argc, char **argv)
{
    if (argc > 1 && !strcmp(argv[1], "windows-path")) {
        char err[256] = "";

        // The registry is asked only of a system that has one, so the case
        // says it is on Windows as well as asking for the Windows branch: the
        // two are different questions, and the last case below is about the
        // difference.
        kernel_answers = true;
        kernel_sysname = "Windows";

        // Already on the user's Path, spelled with a trailing separator:
        // nothing is written, because a second entry adds nothing.
        static const char16_t listed[] = u"C:\\Windows;" WIN_DIR16 u"\\";
        fake_registry(listed, false);
        if (!platform_path_registered(OS_WINDOWS, WIN_DIR)) {
            fprintf(stderr, "listed directory not recognised\n");
            failures++;
        }
        if (reg_written != NULL) {
            fprintf(stderr, "a registered directory was written again\n");
            failures++;
        }

        // Not on it yet: one write that keeps the user's own entries in front
        // of ours, percent signs and all, so they go on expanding.
        fake_registry(WIN_OTHER16, false);
        if (platform_path_registered(OS_WINDOWS, WIN_DIR)) {
            fprintf(stderr, "unlisted directory thought registered\n");
            failures++;
        }
        if (!platform_path_register(OS_WINDOWS, WIN_DIR, err, sizeof err)) {
            fprintf(stderr, "register failed: %s\n", err);
            failures++;
        } else {
            expect_written(WIN_OTHER16 u";" WIN_DIR16, "existing Path kept");
        }

        // A stored value ending in a separator has an empty entry on the end
        // that is nobody's directory: joining ours to it must not leave two
        // separators side by side, which is how a Path grows forever.
        static const char16_t trailing[] = u"C:\\Windows;";
        fake_registry(trailing, false);
        if (!platform_path_register(OS_WINDOWS, WIN_DIR, err, sizeof err)) {
            fprintf(stderr, "register over a trailing separator failed: %s\n",
                err);
            failures++;
        } else {
            expect_written(u"C:\\Windows;" WIN_DIR16, "empty entry not kept");
        }

        // No Path value at all: the directory alone, so nothing the machine
        // holds is copied into a value that belongs to the user.
        fake_registry(NULL, false);
        if (!platform_path_register(OS_WINDOWS, WIN_DIR, err, sizeof err)) {
            fprintf(stderr, "register with no existing value failed: %s\n",
                err);
            failures++;
        } else {
            expect_written(WIN_DIR16, "no existing value");
        }

        // A directory named with a character beyond the Basic Multilingual
        // Plane: four UTF-8 bytes coming in, and one character named by two
        // code units going out. Split them wrong and the registry is left
        // holding a name that is not the directory that was installed. The
        // expected pair is written by the compiler rather than by hand.
        static const char16_t want_moar[] =
            u"C:\\Windows;C:\\Users\\t\\\U0001F600\\ape-get";
        fake_registry(u"C:\\Windows", false);
        if (!platform_path_register(OS_WINDOWS,
            "C:\\Users\\t\\\xF0\x9F\x98\x80\\ape-get", err, sizeof err)) {
            fprintf(stderr, "register for a wide name failed: %s\n", err);
            failures++;
        } else {
            expect_written(want_moar, "name beyond the basic plane");
        }

        // The same journey for a character inside the plane, where one
        // character costs two bytes and exactly one code unit: the common case
        // for a name in almost any language but English.
        static const char16_t want_acc[] =
            u"C:\\Windows;C:\\caf\u00E9\\bin";
        fake_registry(u"C:\\Windows", false);
        if (!platform_path_register(OS_WINDOWS, "C:\\caf\xC3\xA9\\bin",
            err, sizeof err)) {
            fprintf(stderr, "register for a name outside ASCII failed: %s\n",
                err);
            failures++;
        } else {
            expect_written(want_acc, "name outside ASCII");
        }

        // A registry that says no must not be reported as a success.
        fake_registry(WIN_OTHER16, true);
        if (platform_path_register(OS_WINDOWS, WIN_DIR, err, sizeof err)) {
            fprintf(stderr, "a rejected write was reported as success\n");
            failures++;
        }

        // A Windows install asked for on a system that has no registry at all,
        // which is what setup's menu makes possible on any other system: the
        // directory is laid out as Windows would have it, but the value is part
        // of the machine running this. Nothing is written, and the reason says
        // the registry is not there rather than that it refused - which is the
        // answer this used to give, until the registry was reached without
        // first asking whether the system had one to reach.
        kernel_sysname = "Linux";
        fake_registry(WIN_OTHER16, false);
        err[0] = '\0';
        if (platform_path_registered(OS_WINDOWS, WIN_DIR)) {
            fprintf(stderr, "absent registry claimed a directory was in it\n");
            failures++;
        }
        if (platform_path_register(OS_WINDOWS, WIN_DIR, err, sizeof err)) {
            fprintf(stderr, "absent registry was written to\n");
            failures++;
        } else if (strstr(err, "refused") != NULL) {
            fprintf(stderr, "the blame was put on a registry that refused: \"%s\"\n",
                err);
            failures++;
        }
        if (reg_written != NULL) {
            fprintf(stderr, "the registry was written from a system without one\n");
            failures++;
        }

        free(reg_written);
        if (failures == 0) puts("8 PATH cases passed");
        return failures != 0;
    }

    if (argc > 1 && !strcmp(argv[1], "install-root")) {
        setenv("HOME", "/home/tester", 1);
        setenv("LOCALAPPDATA", "C:\\Users\\tester\\AppData\\Local", 1);
        expect_root("Linux", "/home/tester/.local/bin", "Linux default");
        expect_root("Darwin", "/home/tester/.local/bin", "macOS default");
        expect_root("Windows",
            "C:\\Users\\tester\\AppData\\Local\\Programs\\ape-get",
            "Windows default");
        // a trailing separator on the variable must not double up
        setenv("LOCALAPPDATA", "C:\\Users\\tester\\AppData\\Local\\", 1);
        expect_root("Windows",
            "C:\\Users\\tester\\AppData\\Local\\Programs\\ape-get",
            "Windows default, trailing separator");
        // The Windows runtime hands a drive path over as /C/Users/..., which
        // is neither the form a Path entry takes nor the form setup accepts as
        // absolute, so a default built on it is refused by setup itself.
        setenv("LOCALAPPDATA", "/C/Users/tester/AppData/Local", 1);
        expect_root("Windows",
            "C:\\Users\\tester\\AppData\\Local\\Programs\\ape-get",
            "Windows default, drive reported as /C/");
        unsetenv("LOCALAPPDATA");
        expect_root("Windows", NULL, "Windows without LOCALAPPDATA");
        unsetenv("HOME");
        expect_root("Linux", NULL, "Linux without HOME");
        if (failures == 0) puts("7 install-root cases passed");
        return failures != 0;
    }

    if (argc > 1 && !strcmp(argv[1], "install-dir")) {
        expect_dir(OS_WINDOWS, "/C/Users/tester/AppData/Local",
            "C:\\Users\\tester\\AppData\\Local", "Windows, drive reported as /C/");
        expect_dir(OS_WINDOWS, "/c/Users/tester", "c:\\Users\\tester",
            "Windows, lower-case drive keeps its spelling");
        expect_dir(OS_WINDOWS, "C:/Users/tester", "C:\\Users\\tester",
            "Windows, forward slashes");
        expect_dir(OS_WINDOWS, "C:\\Users\\tester\\", "C:\\Users\\tester",
            "Windows, trailing separator");
        expect_dir(OS_WINDOWS, "C:\\Users\\tester", "C:\\Users\\tester",
            "Windows, spelled already");
        expect_dir(OS_WINDOWS, "~/tester", "~/tester",
            "Windows, no drive to repair");
        expect_dir(OS_WINDOWS, NULL, NULL, "Windows, nothing named");
        expect_dir(OS_LINUX, "/C/Users/tester", "/C/Users/tester",
            "Linux, where /C is a directory like any other");
        expect_dir(OS_MACOS, "C:/Users/tester", "C:/Users/tester",
            "macOS, a Windows path is an ordinary name");
        if (failures == 0) puts("9 install-dir cases passed");
        return failures != 0;
    }

    if (argc > 1 && !strcmp(argv[1], "self-exe")) {
        static char16_t too_long[4096];
        unsetenv("APEGET_SELF_EXE");
        kernel_answers = true;
        kernel_sysname = "Windows";
        win_answer = u"C:\\Users\\tester\\Downloads\\ape-get.com";
        expect_self("Windows module name",
            "C:\\Users\\tester\\Downloads\\ape-get.com");
        // The kernel writes what fits and quotes the buffer size back. Half a
        // name is a different file than the one setup is installing, so it is
        // reported as no answer rather than installed from.
        for (size_t i = 0; i < 4095; i++) too_long[i] = 'x';
        too_long[4095] = '\0';
        win_answer = too_long;
        expect_self("Windows module name longer than the buffer", NULL);
        win_answer = NULL;
        expect_self("Windows kernel with nothing to say", NULL);
        // A character outside the Basic Multilingual Plane is named by a pair
        // of code units, U+1F600 here as D83D DE00. Read one at a time the two
        // are not characters at all, so the answer has to be joined before it
        // is written out: the UTF-8 of U+1F600 is F0 9F 98 80.
        static const char16_t moar[] = {
            'C', ':', '\\', 'U', 's', 'e', 'r', 's', '\\',
            0xD83D, 0xDE00, '\\', 'a', 'p', 'e', '-', 'g', 'e', 't', 0
        };
        win_answer = moar;
        expect_self("Windows module name beyond the basic plane",
            "C:\\Users\\\xF0\x9F\x98\x80\\ape-get");
        // The link is still the answer where the link exists, and its absence
        // is still no answer: the Windows question above is asked instead of
        // that one, not in place of it anywhere else.
        kernel_sysname = "Linux";
        link_answer = "/home/tester/Downloads/ape-get";
        expect_self("Linux link", "/home/tester/Downloads/ape-get");
        link_missing = true;
        expect_self("Linux link not there", NULL);
        // A name given over the process's own wins on any system: that is how
        // the scenario suite runs without spending a megabyte per case.
        setenv("APEGET_SELF_EXE", "/tmp/fixture/ape-get", 1);
        link_answer = "/home/tester/Downloads/ape-get";
        expect_self("the named file wins over the link", "/tmp/fixture/ape-get");
        kernel_sysname = "Windows";
        win_answer = u"C:\\Users\\tester\\ape-get.com";
        expect_self("the named file wins over the kernel", "/tmp/fixture/ape-get");
        unsetenv("APEGET_SELF_EXE");
        if (failures == 0) puts("8 self-exe cases passed");
        return failures != 0;
    }

    if (argc > 1 && !strcmp(argv[1], "os-detection")) {
        expect_os("Windows", OS_WINDOWS, "Windows NT kernel");
        expect_os("Darwin", OS_MACOS, "XNU kernel");
        expect_os("Linux", OS_LINUX, "Linux kernel");
        expect_os("FreeBSD", OS_UNKNOWN, "unsupported kernel");
        expect_os(NULL, OS_UNKNOWN, "kernel refusing");
        if (failures == 0) puts("5 detection cases passed");
        return failures != 0;
    }

    if (WANT_ARCH == NULL) return 2;
    char buf[256];

    if (argc > 1 && !strcmp(argv[1], "no-kernel")) {
        kernel_answers = false;
        platform_describe(buf, sizeof buf);
        expect("kernel refusing", buf, WANT_ARCH);
        if (failures == 0) puts("1 description case passed");
        return failures != 0;
    }

    platform_describe(buf, sizeof buf);
    expect("kernel and arch", buf, WANT_LINE);

    // Eight bytes hold seven characters plus the terminator, so the line
    // lands truncated and the byte past the limit is untouched.
    char padded[9];
    memset(padded, 'x', sizeof padded);
    platform_describe(padded, 8);
    expect("cap truncates", padded, "Linux x");
    if (padded[8] != 'x') {
        fprintf(stderr, "cap overrun: wrote past the limit\n");
        failures++;
    }

    if (failures == 0) puts("3 description cases passed");
    return failures != 0;
}
