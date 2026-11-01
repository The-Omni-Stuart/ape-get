// Styling and progress rendering. "Error:"/"Warning:" are message bytes, not
// styling: they join before the gate, so gate-off output stays byte-identical.

#include "ui.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static enum { MODE_AUTO, MODE_ALWAYS, MODE_NEVER } g_mode = MODE_AUTO;
static bool g_json, g_suppress, g_resolved;
static int g_tty_out, g_tty_err, g_no_colour, g_dumb;
static time_t g_prog_last;

void ui_setup(const char *colour, bool json)
{
    g_json = json;
    if (colour != NULL && !strcmp(colour, "never")) g_mode = MODE_NEVER;
    else if (colour != NULL && !strcmp(colour, "always")) g_mode = MODE_ALWAYS;
    else g_mode = MODE_AUTO;
    // NO_COLOR is the no-color.org cross-tool spelling, accepted as a
    // fallback after our own NO_COLOUR.
    g_no_colour = getenv("NO_COLOUR") != NULL || getenv("NO_COLOR") != NULL;
    const char *term = getenv("TERM");
    g_dumb = term != NULL && !strcmp(term, "dumb");
    g_tty_out = isatty(fileno(stdout));
    g_tty_err = isatty(fileno(stderr));
    g_resolved = 1;
}

bool ui_on(FILE *f)
{
    if (!g_resolved) ui_setup(NULL, false);
    if (g_mode == MODE_NEVER) return false;
    if (f == stdout && g_json) return false;   // stdout carries JSON
    if (g_mode == MODE_ALWAYS) return true;
    if (g_no_colour || g_dumb) return false;
    return f == stdout ? g_tty_out : g_tty_err;
}

bool ui_default_on(FILE *f)
{
    if (!g_resolved) ui_setup(NULL, false);
    if (f == stdout && g_json) return false;
    if (g_no_colour || g_dumb) return false;
    return f == stdout ? g_tty_out : g_tty_err;
}

void ui_suppress_progress(bool on) { g_suppress = on; }

// true when t begins with the word w (colon included) plus space or EOL;
// *n receives the word length
static bool begins(const char *t, const char *w, size_t *n)
{
    size_t k = strlen(w);
    if (strncmp(t, w, k) != 0 || (t[k] != ' ' && t[k] != '\0')) return false;
    *n = k;
    return true;
}

void ui_line(FILE *f, ui_style style, const char *text)
{
    if (!text) return;
    char *owned = NULL;
    size_t n;
    // prefixes are message text, not styling: they join before the gate, so
    // every mode reads "Error:/Warning: <message>"; idempotent at call sites
    const char *pfxword = style == UI_ERROR ? "Error: "
        : style == UI_WARN ? "Warning: " : NULL;
    if (pfxword != NULL && !begins(text, style == UI_ERROR ? "Error:"
            : "Warning:", &n)) {
        size_t len = strlen(text), plen = strlen(pfxword);
        owned = malloc(len + plen + 1);
        if (owned == NULL) return;
        memcpy(owned, pfxword, plen);
        memcpy(owned + plen, text, len + 1);
        text = owned;
    }
    const char *s = style == UI_NONE || !ui_on(f) ? NULL
        : style == UI_BOLD ? "\033[1m"
        : style == UI_DIM ? "\033[2m"
        : style == UI_ITALIC ? "\033[3m"
        : style == UI_ERROR ? "\033[31m"
        : style == UI_WARN ? "\033[33m"
        : style == UI_OK ? "\033[1;32m"
        : "\033[36m";
    if (s == NULL) {
        // help renders with UI_NONE yet still leads Usage: bold on a TTY
        if (style == UI_NONE && ui_on(f)
            && (begins(text, "Usage:", &n) || begins(text, "Common:", &n)))
            fprintf(f, "\033[1m%.*s\033[0m%s\n", (int)n, text, text + n);
        else
            fprintf(f, "%s\n", text);
        free(owned);
        return;
    }
    size_t pfx = 0;
    if (style == UI_ERROR) {
        fputs("\033[1;31mError:\033[0m", f);
        pfx = 6;
    } else if (style == UI_WARN && begins(text, "Warning:", &pfx)) {
        fputs("\033[1;33mWarning:\033[0m", f);
    } else if (begins(text, "Usage:", &pfx) || begins(text, "Common:", &pfx)) {
        fprintf(f, "\033[1m%.*s\033[0m", (int)pfx, text);
    } else {
        fprintf(f, "%s%s\033[0m\n", s, text);
        free(owned);
        return;
    }
    if (text[pfx] != '\0') fprintf(f, "%s%s\033[0m", s, text + pfx);
    fputc('\n', f);
    free(owned);
}

void ui_warn_subject(FILE *f, const char *subject, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(NULL, 0, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    char *body = malloc((size_t)n + 1);
    if (body == NULL) return;
    va_start(ap, fmt);
    vsnprintf(body, (size_t)n + 1, fmt, ap);
    va_end(ap);
    if (!ui_on(f))
        fprintf(f, "Warning: %s: %s\n", subject, body);
    else
        fprintf(f, "\033[1;33mWarning: \033[0m\033[3;33m%s:\033[0m"
            "\033[33m %s\033[0m\n", subject, body);
    free(body);
}

void ui_line_ref_tail(FILE *f, const char *ref, const char *rest,
    const char *tail, ui_style tail_style)
{
    if (!ui_on(f)) {
        fprintf(f, "%s%s%s\n", ref, rest, tail != NULL ? tail : "");
        return;
    }
    const char *s = tail_style == UI_ERROR ? "\033[1;31m"
        : tail_style == UI_OK ? "\033[1;32m"
        : tail_style == UI_WARN ? "\033[1;33m" : "\033[1m";
    fprintf(f, "\033[36m%s\033[0m%s", ref, rest);
    if (tail != NULL) fprintf(f, "%s%s\033[0m", s, tail);
    fputc('\n', f);
}

void ui_line_ref(FILE *f, const char *ref, const char *rest)
{
    ui_line_ref_tail(f, ref, rest, NULL, UI_NONE);
}

// rest carries the ": value" remainder, colon included; the label is bolded
void ui_line_kv(FILE *f, const char *label, const char *rest)
{
    if (!ui_on(f))
        fprintf(f, "%s%s\n", label, rest);
    else
        fprintf(f, "\033[1m%s\033[0m%s\n", label, rest);
}

void ui_line_kv_value(FILE *f, const char *label, const char *rest,
    ui_style value)
{
    if (!ui_on(f)) {
        fprintf(f, "%s%s\n", label, rest);
        return;
    }
    const char *s = value == UI_OK ? "\033[1;32m"
        : value == UI_WARN ? "\033[33m"
        : value == UI_ERROR ? "\033[31m"
        : value == UI_DIM ? "\033[2m" : NULL;
    if (s == NULL)
        fprintf(f, "\033[1m%s\033[0m%s\n", label, rest);
    else
        fprintf(f, "\033[1m%s\033[0m%s%s\033[0m\n", label, s, rest);
}

void ui_prompt_continue(FILE *f, bool destructive)
{
    if (!ui_on(f)) {
        fputs("Continue? [y/N] ", f);
        return;
    }
    fprintf(f, "\033[1;%smContinue? [y/N] \033[0m", destructive ? "33" : "36");
}

void ui_msg(FILE *f, ui_style style, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(NULL, 0, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    char *buf = malloc((size_t)n + 1);
    if (buf == NULL) return;
    va_start(ap, fmt);
    vsnprintf(buf, (size_t)n + 1, fmt, ap);
    va_end(ap);
    ui_line(f, style, buf);
    free(buf);
}

// Progress renders on a TTY only: --colour=always forces styling, not
// the live bar.
static bool prog_on(void)
{
    if (!g_resolved) ui_setup(NULL, false);
    return g_tty_err && !g_suppress && !g_no_colour && !g_dumb
        && g_mode != MODE_NEVER;
}

void ui_progress(const char *text, bool force)
{
    if (!text || !prog_on()) return;
    time_t now = time(NULL);
    if (!force && g_prog_last != 0 && now - g_prog_last < 1) return;
    g_prog_last = now;
    fprintf(stderr, "\r\033[2K%s", text);
    fflush(stderr);
}

void ui_progress_done(void)
{
    if (!prog_on()) return;
    fputc('\n', stderr);
    fflush(stderr);
    g_prog_last = 0;
}
