// Exercise the machine description without going through a command.
#include "platform.h"

#include <stdbool.h>
#include <stdio.h>
#include <string.h>
#include <sys/utsname.h>

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

int __real_uname(struct utsname *u);

int __wrap_uname(struct utsname *u)
{
    if (!kernel_answers) return -1;
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

int main(int argc, char **argv)
{
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
