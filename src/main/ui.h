#ifndef APEGET_UI_H
#define APEGET_UI_H

#include <stdbool.h>
#include <stdio.h>

// Terminal styling and progress: hand-rolled SGR behind a per-stream gate;
// with the gate off (always for piped output) ui_line emits the unstyled bytes.

typedef enum {
    UI_NONE = 0,   // unstyled but gate-safe (use for all normal lines)
    UI_BOLD,       // headers, command names
    UI_DIM,        // secondary detail
    UI_ITALIC,     // hints only
    UI_ERROR,      // red: error
    UI_WARN,       // yellow: warning/attention
    UI_OK,         // green: success/verified
    UI_REF,        // cyan: refs, versions, quoted names
} ui_style;

// Resolve the --colour flag once: never > always > NO_COLOUR (NO_COLOR) or
// TERM=dumb > per-stream isatty. json styles stdout off (it carries JSON).
void ui_setup(const char *colour, bool json);

// on suppresses the live bar even on a TTY (--quiet, --json, --no-progress)
void ui_suppress_progress(bool on);

// style(text) + newline; bare bytes when f's gate is off. UI_ERROR/UI_WARN
// gain their prefix when missing — message bytes in every mode, not styling.
void ui_line(FILE *f, ui_style style, const char *text);

// the resolved gate for f: callers emitting raw SGR (about) honour it too
bool ui_on(FILE *f);

// the gate's verdict were no --colour given: lets main tell whether an
// explicit override actually changes the outcome
bool ui_default_on(FILE *f);

// printf-style ui_line (formats untruncated)
void ui_msg(FILE *f, ui_style style, const char *fmt, ...)
    __attribute__((format(printf, 3, 4)));

// printf-style warning with an italic subject: emits exactly
// "Warning: <subject>: <detail>" — for genuine "<name>: <detail>" subjects
void ui_warn_subject(FILE *f, const char *subject, const char *fmt, ...)
    __attribute__((format(printf, 3, 4)));

// "Continue? [y/N] " prompt: identical bytes in every mode; bold cyan, or
// bold yellow when destructive (uninstall, rollback, purge).
void ui_prompt_continue(FILE *f, bool destructive);

// Composite rows with byte-identical gate-off output: cyan ref + plain rest
// (list rows), bold label + ": value" rest (info fields).
void ui_line_ref(FILE *f, const char *ref, const char *rest);
// ref line with an emphasised tail ((locked) reads as a warning); the tail
// carries its own leading space; gate-off bytes stay identical
void ui_line_ref_tail(FILE *f, const char *ref, const char *rest,
    const char *tail, ui_style tail_style);
void ui_line_kv(FILE *f, const char *label, const char *rest);
// kv with a semantically coloured value (trust levels); gate-off identical
void ui_line_kv_value(FILE *f, const char *label, const char *rest,
    ui_style value);

// Live status line on stderr (CR + erase); no-op when the progress gate is
// off. force skips the ~1 s throttle; ui_progress_done() ends the sequence.
void ui_progress(const char *text, bool force);
void ui_progress_done(void);

#endif
