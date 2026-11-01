#ifndef APEGET_COMPAT_H
#define APEGET_COMPAT_H

// Compatibility layers; the only one is wine — Windows portables on Linux.
// The caller owns the gate-1 intent check; wine is never named before it passes.

// The layer name recorded as the manifest's compat value.
#define COMPAT_WINE "wine"

// A configured wine_path is used verbatim; unusable fails loudly. Unset:
// PATH scan for wine, then wine64. Malloc'd abs path or *err; byte-exact.
char *compat_detect_wine(const char *wine_path, char **err);

// Availability-only probe: a flatpak wine lives in the export dirs, not on
// PATH, and isn't exec-tested here; reporting it keeps doctor honest.
int compat_flatpak_wine(void);

// POSIX single-quote: wrap in '…', escaping internal quotes as '\''. Control
// characters (newline included) are rejected with a malloc'd *err.
char *compat_shell_quote(const char *s, char **err);

// Stages <launcher>.tmp-<txn_id> (#!/bin/sh + marker + exec line), mode
// 0755, `sh -n`-checked; the caller renames at commit. Failure leaves no file.
char *compat_wrapper_stage(const char *launcher, const char *txn_id,
    const char *wine_abs, const char *target_abs, char **err);

// Doctor probe: NULL when the wine a wrapper execs still resolves, else a
// malloc'd detail. No readable wrapper falls back to install-time detection.
char *compat_wrapper_problem(const char *wrapper, const char *wine_path);

#endif
