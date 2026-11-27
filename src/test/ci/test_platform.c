// Exercise the machine description, the system it implies and the PATH line,
// without going through a command.
#include "platform.h"

#include "fs.h"

#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/utsname.h>
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

// uname is the one system boundary for the description and for which system we
// are, so it is the one thing faked; the link wraps it (see test_tcp_connect
// and its getaddrinfo).
static bool kernel_answers = true;
static const char *fake_sysname;   // answers with this instead of the kernel's

int __real_uname(struct utsname *u);

int __wrap_uname(struct utsname *u)
{
    if (!kernel_answers) return -1;
    if (fake_sysname != NULL) {
        snprintf(u->sysname, sizeof u->sysname, "%s", fake_sysname);
        return 0;
    }
    return __real_uname(u);
}

static int failures;
static int cases;   // counted, not declared: the PATH file checks differ by
                    // who is running, so a written-out total would lie

static void expect(const char *label, const char *got, const char *want)
{
    cases++;
    if (strcmp(got, want) != 0) {
        fprintf(stderr, "%s: expected \"%s\", got \"%s\"\n", label, want, got);
        failures++;
    }
}

static void expect_os(const char *label, platform_os got, platform_os want)
{
    cases++;
    if (got != want) {
        fprintf(stderr, "%s: expected %s, got %s\n", label,
            platform_os_name(want), platform_os_name(got));
        failures++;
    }
}

static void expect_bool(const char *label, bool got, bool want)
{
    cases++;
    if (got != want) {
        fprintf(stderr, "%s: expected %s\n", label, want ? "true" : "false");
        failures++;
    }
}

static char *read_all(const char *file)
{
    char *text = fs_read_file(file, NULL);
    return text != NULL ? text : strdup("");
}

// The system behind a kernel's self-reported name, and the flag's spellings.
static void detect_cases(void)
{
    fake_sysname = "Linux";
    expect_os("linux kernel", platform_current(), OS_LINUX);
    fake_sysname = "Darwin";
    expect_os("darwin kernel", platform_current(), OS_MACOS);
    fake_sysname = "FreeBSD";
    expect_os("a system with no layout here", platform_current(), OS_UNKNOWN);
    fake_sysname = NULL;
    kernel_answers = false;
    expect_os("kernel refusing", platform_current(), OS_UNKNOWN);
    kernel_answers = true;

    platform_os got;
    expect_bool("linux parses", platform_os_parse("linux", &got), true);
    expect_os("linux value", got, OS_LINUX);
    expect_bool("any case parses", platform_os_parse("WINDOWS", &got), true);
    expect_os("windows value", got, OS_WINDOWS);
    expect_bool("macos parses", platform_os_parse("macos", &got), true);
    expect_bool("a near miss does not parse",
        platform_os_parse("win32", &got), false);
    expect_bool("nothing to parse", platform_os_parse(NULL, &got), false);
    expect("name read back", platform_os_name(OS_MACOS), "macOS");
}

// The one line written, and everything else in the file left where it was.
static void path_cases(void)
{
    char home[] = "/tmp/apg-platform-XXXXXX";
    if (mkdtemp(home) == NULL) {
        fprintf(stderr, "cannot make a home to write into\n");
        failures++;
        return;
    }
    setenv("HOME", home, 1);
    char *file = platform_path_target(OS_LINUX);
    char err[256];
    if (file == NULL || strstr(file, "/.profile") == NULL) {
        fprintf(stderr, "Linux PATH target is not <home>/.profile\n");
        failures++;
        return;
    }
    char *mac = platform_path_target(OS_MACOS);
    expect("macOS target is .zshrc",
        mac != NULL && strstr(mac, "/.zshrc") != NULL ? "yes" : "no", "yes");
    free(mac);
    expect("no PATH file named for Windows",
        platform_path_target(OS_WINDOWS) == NULL ? "yes" : "no", "yes");

    // The line appears once and is exactly what was composed.
    expect_bool("nothing registered yet",
        platform_path_registered(OS_LINUX, "/x/bin"), false);
    if (!platform_path_register(OS_LINUX, "/x/bin", err, sizeof err)) {
        fprintf(stderr, "register failed: %s\n", err);
        failures++;
    }
    char *text = read_all(file);
    expect("the line written", text,
        "export PATH=\"/x/bin:$PATH\"  # ape-get\n");
    free(text);
    expect_bool("now registered",
        platform_path_registered(OS_LINUX, "/x/bin"), true);
    expect_bool("a different dir is not registered",
        platform_path_registered(OS_LINUX, "/other/bin"), false);

    // A second run does not stack lines.
    if (!platform_path_register(OS_LINUX, "/x/bin", err, sizeof err)) {
        fprintf(stderr, "re-register failed: %s\n", err);
        failures++;
    }
    text = read_all(file);
    expect("registering twice writes once", text,
        "export PATH=\"/x/bin:$PATH\"  # ape-get\n");
    free(text);

    // The user's own lines keep their order; setup's stale line is replaced.
    fs_write_file(file, "first\nexport PATH=\"/old:$PATH\"  # ape-get\nsecond\n");
    if (!platform_path_register(OS_LINUX, "/y/bin", err, sizeof err)) {
        fprintf(stderr, "register over user lines failed: %s\n", err);
        failures++;
    }
    text = read_all(file);
    expect("user lines kept, own line replaced", text,
        "first\nsecond\nexport PATH=\"/y/bin:$PATH\"  # ape-get\n");
    free(text);

    // A line that merely looks like ours is the user's and stays.
    fs_write_file(file, "export PATH=\"/keep:$PATH\" # ape-get-ish\n");
    platform_path_register(OS_LINUX, "/z/bin", err, sizeof err);
    text = read_all(file);
    expect("a look-alike line survives", text,
        "export PATH=\"/keep:$PATH\" # ape-get-ish\n"
        "export PATH=\"/z/bin:$PATH\"  # ape-get\n");
    free(text);

    // A directory the environment already lists needs no line written for it.
    // Entries are compared whole, so a look-alike prefix is not a hit.
    char *saved = getenv("PATH") != NULL ? strdup(getenv("PATH")) : NULL;
    setenv("PATH", "/usr/bin:/x/bin:/bin", 1);
    expect_bool("a middle entry is effective",
        platform_path_effective("/x/bin"), true);
    expect_bool("the first entry counts",
        platform_path_effective("/usr/bin"), true);
    expect_bool("the last entry counts", platform_path_effective("/bin"), true);
    expect_bool("a prefix of an entry is not effective",
        platform_path_effective("/x"), false);
    expect_bool("an extension of an entry is not effective",
        platform_path_effective("/x/bin2"), false);
    setenv("PATH", "", 1);
    expect_bool("an empty PATH holds nothing",
        platform_path_effective("/x/bin"), false);
    if (saved != NULL) { setenv("PATH", saved, 1); free(saved); }
    else unsetenv("PATH");

    // A file present but unreadable is never clobbered. Root reads anything,
    // so that case is only meaningful for someone who cannot.
    if (geteuid() != 0) {
        fs_write_file(file, "mine\n");
        chmod(file, 0000);
        expect_bool("an unreadable file is refused",
            platform_path_register(OS_LINUX, "/w/bin", err, sizeof err), false);
        chmod(file, 0644);
        text = read_all(file);
        expect("and left exactly as it was", text, "mine\n");
        free(text);
    }

    // Windows has no PATH file written from here: say so, write nothing.
    expect_bool("windows is refused",
        platform_path_register(OS_WINDOWS, "/x/bin", err, sizeof err), false);
    expect("and the reason names the system",
        strstr(err, "Windows") != NULL ? "yes" : "no", "yes");

    unlink(file);
    free(file);
    rmdir(home);
}

int main(int argc, char **argv)
{
    if (WANT_ARCH == NULL) return 2;
    char buf[256];

    if (argc > 1 && !strcmp(argv[1], "no-kernel")) {
        kernel_answers = false;
        platform_describe(buf, sizeof buf);
        expect("kernel refusing", buf, WANT_ARCH);
        if (failures == 0) printf("%d description case passed\n", cases);
        return failures != 0;
    }
    if (argc > 1 && !strcmp(argv[1], "detect")) {
        detect_cases();
        if (failures == 0) printf("%d detection cases passed\n", cases);
        return failures != 0;
    }
    if (argc > 1 && !strcmp(argv[1], "path")) {
        path_cases();
        if (failures == 0) printf("%d PATH cases passed\n", cases);
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
    cases++;
    if (padded[8] != 'x') {
        fprintf(stderr, "cap overrun: wrote past the limit\n");
        failures++;
    }

    if (failures == 0) printf("%d description cases passed\n", cases);
    return failures != 0;
}
