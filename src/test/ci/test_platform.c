// Exercise the machine description without going through a command.
#include "platform.h"

#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
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
// The Windows branch talks to reg.exe and nothing else, so reg.exe is the
// thing replaced here: a shell shim on PATH answers queries from a file and
// records every argument of every write. The assertions spell out the command
// line the registry should receive, so a duplicated entry, a clobbered
// existing PATH or the wrong value type fails the check.
#define WIN_DIR "C:\\Users\\t\\AppData\\Local\\Programs\\ape-get"
#define QUERY_LISTED "HKEY_CURRENT_USER\\Environment\n" \
    "    Path    REG_EXPAND_SZ    C:\\Windows;" WIN_DIR "\\\n\n"
#define QUERY_OTHER  "HKEY_CURRENT_USER\\Environment\n" \
    "    Path    REG_EXPAND_SZ    C:\\Windows\n\n"

static const char *fake_dir;

static void fake_at(char *buf, size_t cap, const char *name)
{
    snprintf(buf, cap, "%s/%s", fake_dir, name);
}

static void file_write(const char *path, const char *text)
{
    FILE *f = fopen(path, "w");
    if (f == NULL) {
        fprintf(stderr, "cannot write %s\n", path);
        exit(2);
    }
    fputs(text, f);
    fclose(f);
}

static void write_shim(const char *path)
{
    FILE *f = fopen(path, "w");
    if (f == NULL) exit(2);
    fputs("#!/bin/sh\n"
          "case \"$1\" in\n"
          "query)\n"
          "  [ -f \"$FAKE_REG_DIR/value\" ] || exit 1\n"
          "  cat \"$FAKE_REG_DIR/value\"\n"
          "  exit 0 ;;\n"
          "add)\n"
          "  if [ -f \"$FAKE_REG_DIR/fail\" ]; then exit 1; fi\n"
          "  while [ $# -gt 0 ]; do\n"
          "    printf '%s\\n' \"$1\" >> \"$FAKE_REG_DIR/addlog\"\n"
          "    shift\n"
          "  done\n"
          "  exit 0 ;;\n"
          "esac\n"
          "exit 1\n", f);
    fclose(f);
    chmod(path, 0755);
}

// One case's state: what `reg query` answers (NULL: there is no Path value),
// whether writes fail, and an empty log of writes made so far.
static void fake_reg(const char *query_output, bool write_fails)
{
    char p[4096];
    fake_at(p, sizeof p, "value");  remove(p);
    fake_at(p, sizeof p, "fail");   remove(p);
    fake_at(p, sizeof p, "addlog"); remove(p);
    if (query_output != NULL) {
        fake_at(p, sizeof p, "value");
        file_write(p, query_output);
    }
    if (write_fails) {
        fake_at(p, sizeof p, "fail");
        file_write(p, "");
    }
}

// The arguments of every `reg add` the code under test made, one per line.
static int addlog_lines(char lines[][512], int cap)
{
    char p[4096];
    fake_at(p, sizeof p, "addlog");
    FILE *f = fopen(p, "r");
    if (f == NULL) return 0;
    int n = 0;
    while (n < cap && fgets(lines[n], 512, f) != NULL) {
        lines[n][strcspn(lines[n], "\n")] = '\0';
        n++;
    }
    fclose(f);
    return n;
}

static int writes_made(void)
{
    char lines[24][512];
    return addlog_lines(lines, 24);
}

static void expect_written(const char *const *want, int n, const char *label)
{
    char lines[24][512];
    int got = addlog_lines(lines, 24);
    if (got != n) {
        fprintf(stderr, "%s: reg add had %d arguments, expected %d\n",
            label, got, n);
        for (int i = 0; i < got; i++)
            fprintf(stderr, "  %d: %s\n", i, lines[i]);
        failures++;
        return;
    }
    for (int i = 0; i < n; i++)
        if (strcmp(lines[i], want[i]) != 0) {
            fprintf(stderr, "%s: argument %d is \"%s\", expected \"%s\"\n",
                label, i, lines[i], want[i]);
            failures++;
        }
}

// The scratch directory the fake registry is built in, taken down file by file
// on the way out: asking a shell to do it would go through the interpreter
// this binary embeds, whose rm does not take those flags.
static char g_scratch[576];

static void remove_scratch(void)
{
    static const char *const leaves[] = { "reg", "value", "fail", "addlog" };
    char path[640];

    if (g_scratch[0] == '\0')
        return;
    for (size_t i = 0; i < sizeof leaves / sizeof *leaves; i++) {
        snprintf(path, sizeof path, "%s/%s", g_scratch, leaves[i]);
        remove(path);
    }
    if (rmdir(g_scratch) != 0)
        fprintf(stderr, "could not remove %s\n", g_scratch);
}

int main(int argc, char **argv)
{
    if (argc > 1 && !strcmp(argv[1], "windows-path")) {
        char dir[512];
        const char *base = getenv("TMPDIR");
        snprintf(dir, sizeof dir, "%s/apeget-reg-XXXXXX",
            base != NULL && *base != '\0' ? base : "/tmp");
        if (mkdtemp(dir) == NULL) return 2;
        fake_dir = dir;
        snprintf(g_scratch, sizeof g_scratch, "%s", fake_dir);
        atexit(remove_scratch);
        char p[4096], pathv[8192];
        setenv("FAKE_REG_DIR", fake_dir, 1);
        snprintf(pathv, sizeof pathv, "%s:%s", fake_dir, getenv("PATH"));
        setenv("PATH", pathv, 1);
        fake_at(p, sizeof p, "reg");
        write_shim(p);

        // Already on the user's PATH, spelled with a trailing separator:
        // nothing is written, because a second entry adds nothing.
        fake_reg(QUERY_LISTED, false);
        if (!platform_path_registered(OS_WINDOWS, WIN_DIR)) {
            fprintf(stderr, "listed directory not recognised\n");
            failures++;
        }
        if (writes_made() != 0) {
            fprintf(stderr, "a registered directory was written again\n");
            failures++;
        }

        // Not on it yet: one write that keeps the user's own entry in front
        // of ours and keeps the type, so %VAR% entries stay expandable.
        static const char *const keep[] = {
            "add", "HKCU\\Environment", "/v", "Path", "/t", "REG_EXPAND_SZ",
            "/d", "C:\\Windows;" WIN_DIR, "/f",
        };
        char err[256] = "";
        fake_reg(QUERY_OTHER, false);
        if (platform_path_registered(OS_WINDOWS, WIN_DIR)) {
            fprintf(stderr, "unlisted directory thought registered\n");
            failures++;
        }
        if (!platform_path_register(OS_WINDOWS, WIN_DIR, err, sizeof err)) {
            fprintf(stderr, "register failed: %s\n", err);
            failures++;
        } else {
            expect_written(keep, 9, "existing PATH kept");
        }

        // No Path value at all: the directory alone, so nothing the machine
        // holds is copied into a value that belongs to the user.
        static const char *const alone[] = {
            "add", "HKCU\\Environment", "/v", "Path", "/t", "REG_EXPAND_SZ",
            "/d", WIN_DIR, "/f",
        };
        fake_reg(NULL, false);
        if (!platform_path_register(OS_WINDOWS, WIN_DIR, err, sizeof err)) {
            fprintf(stderr, "register with no existing value failed: %s\n",
                err);
            failures++;
        } else {
            expect_written(alone, 9, "no existing value");
        }

        // A registry that says no must not be reported as a success.
        fake_reg(QUERY_OTHER, true);
        if (platform_path_register(OS_WINDOWS, WIN_DIR, err, sizeof err)) {
            fprintf(stderr, "a rejected write was reported as success\n");
            failures++;
        }

        if (failures == 0) puts("4 PATH cases passed");
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
