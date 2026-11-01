#include "main.h"
#include "ui.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// `ape-get about` (C-only): wordmark, facts and repository; piped output
// is bare UTF-8, colour only while ui's gate is on.

#ifndef APEGET_VERSION
#define APEGET_VERSION "0.0.0-dev"
#endif

#include <sys/utsname.h>

// Cosmopolitan binaries outlive their compile-time OS assumption: ask the
// kernel actually running (uname is implemented even under the APE loader)
static void platform_line(char *buf, size_t cap)
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

static const char *const ART[] = {
    "                                                                █████   ",
    "                                                               ░░███    ",
    "     ██████   ████████   ██████              ███████  ██████  ███████  ",
    "    ░░░░░███ ░░███░░███ ███░░███ ██████████ ███░░███ ███░░███░░░███░   ",
    "     ███████  ░███ ░███░███████ ░░░░░░░░░░ ░███ ░███░███████   ░███    ",
    "    ███░░███  ░███ ░███░███░░░             ░███ ░███░███░░░    ░███ ███",
    "   ░░████████ ░███████ ░░██████            ░░███████░░██████   ░░█████ ",
    "    ░░░░░░░░  ░███░░░   ░░░░░░              ░░░░░███ ░░░░░░     ░░░░░  ",
    "              ░███                          ███ ░███                   ",
    "              █████                        ░░██████                     ",
    "             ░░░░░                          ░░░░░░                      ",
};
#define ART_N (sizeof ART / sizeof ART[0])

typedef enum { RAMP_NONE, RAMP_CYAN, RAMP_256, RAMP_TRUE } ramp_mode;

static ramp_mode ramp_pick(void)
{
    const char *cc = getenv("COLORTERM");
    if (cc != NULL && (strstr(cc, "truecolor") != NULL
            || strstr(cc, "24bit") != NULL))
        return RAMP_TRUE;
    const char *t = getenv("TERM");
    if (t != NULL && strstr(t, "256") != NULL) return RAMP_256;
    return RAMP_CYAN;
}

// cyan (85,255,255) -> green (120,255,120) along t; 256 approximates the
// same move through the xterm cube (blue channel 5 -> 0)
static void ramp_true(double t, int *r, int *g, int *b)
{
    *r = (int)(85 + 35 * t);
    *g = 255;
    *b = (int)(255 - 135 * t);
}

static void put_run(FILE *f, ramp_mode m, double t, const char *s, size_t n)
{
    if (m == RAMP_NONE) { fwrite(s, 1, n, f); return; }
    if (m == RAMP_CYAN) fputs("\033[36m", f);
    else if (m == RAMP_256) {
        int blue = (int)(5.0 - 5.0 * t + 0.5);
        fprintf(f, "\033[38;5;%dm", 16 + 30 + blue);
    } else {
        int r, g, b;
        ramp_true(t, &r, &g, &b);
        fprintf(f, "\033[38;2;%d;%d;%dm", r, g, b);
    }
    fwrite(s, 1, n, f);
    if (m != RAMP_NONE) fputs("\033[0m", f);
}

int run_about(void)
{
    FILE *f = stdout;
    ramp_mode m = ui_on(f) ? ramp_pick() : RAMP_NONE;
    size_t width = 0;
    for (size_t i = 0; i < ART_N; i++) {
        size_t n = strlen(ART[i]);
        if (n > width) width = n;
    }
    for (size_t i = 0; i < ART_N; i++) {
        if (m == RAMP_NONE) { printf("%s\n", ART[i]); continue; }
        size_t len = strlen(ART[i]), p = 0;
        while (p < len) {
            if (ART[i][p] == ' ') { fputc(' ', f); p++; continue; }
            size_t start = p;
            while (p < len && ART[i][p] != ' ') p++;
            double tcol = width > 1 ? (double)start / (double)(width - 1) : 0;
            double trow = ART_N > 1 ? (double)i / (double)(ART_N - 1) : 0;
            put_run(f, m, 0.8 * tcol + 0.2 * trow, ART[i] + start, p - start);
        }
        fputc('\n', f);
    }
    fputs("\n", f);
    put_s(f, UI_BOLD,
        "The Actually Portable Package Manager ~ Ver. %s"
        " ~ (c) The-Omni-Stuart", APEGET_VERSION);
    char plat[256];
    platform_line(plat, sizeof plat);
    put_s(f, UI_DIM, "  running on %s (APE)", plat);
    fputs("\n", f);
    put_s(f, UI_NONE,
        "If you experience any issues whatsoever, would like to contribute,"
        " or read the source, please find us at the repository linked below:");
    fputs("\n", f);
    if (ui_on(f))
        // OSC 8 hyperlink (control, not styling): unsupporting terminals
        // ignore the wrapper and print the plain URL
        fputs("\033]8;;https://github.com/The-Omni-Stuart/ape-get\033\\"
            "https://github.com/The-Omni-Stuart/ape-get"
            "\033]8;;\033\\\n", f);
    else
        printf("https://github.com/The-Omni-Stuart/ape-get\n");
    fputs("\n", f);
    put_s(f, UI_NONE,
        "Proudly free software under the GPLv3."
        " Made with <3 in SCO & CZ.");
    fputs("\n", f);
    return 0;
}
