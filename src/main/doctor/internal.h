#ifndef APEGET_DOCTOR_INTERNAL_H
#define APEGET_DOCTOR_INTERNAL_H

// Private to the doctor module; nothing outside doctor/ includes this.

#include <stdbool.h>

#include "state.h"
#include "ui.h"

bool tcp_probe_local(int port);
bool rnsd_running(void);
bool rns_configured(void);

typedef struct { char **v; int n, cap; } strv;

// Tool availability lines, between the header fields and the diagnosis: no
// Error:/Warning: word, colour alone carries the level.
typedef struct { const char *label, *value; ui_style style; } tool_line;

// doctor.c: the list every report and heal path accumulates into
void sv_add(strv *l, char *s);

// doctor/recover.c: deduplicated add while the list stays small
void sv_add_uniq(strv *l, const char *s);

// doctor/report.c: the whole report in one call, human lines or --json
void report_lines(const state_ctx *st, strv *issues, strv *warnings,
    bool gpg_ok, long long free_bytes, bool json, bool healthy,
    strv *healed, strv *declined, const tool_line *tools, int ntools);
void heal_add(strv *l, const char *action, const char *ref,
    const char *detail);

// doctor/scan.c: compat manifest walk, warnings only, never quarantines
void compat_walk(const char *dir, const char *wine_cfg, strv *w);

#endif
