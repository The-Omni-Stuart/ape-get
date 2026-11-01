#ifndef APEGET_MAIN_H
#define APEGET_MAIN_H

// Internal interface of the main module: main.c dispatches, main/ holds the
// command bodies, main/util.c the shared helpers. Not used outside main/.

#include "cmd.h"
#include "json.h"
#include "manifest.h"
#include "prompt.h"
#include "state.h"
#include "ui.h"
#include <stdbool.h>
#include <stddef.h>
#include <stdio.h>

void put_out(const char *line);
void put_err(const char *line);
// failure line with UI_ERROR semantics (prefix bytes + red); put_err
// stays raw for structured dumps
void put_fail(const char *line);

// printf-style line through ui: styled when the gate is on, byte-identical
// unstyled bytes when off
void put_s(FILE *f, ui_style style, const char *fmt, ...)
    __attribute__((format(printf, 3, 4)));

// Styled help: NULL/global listing, or one command's options; commands
// missing a required argument return run_help("<name>") as their error.
int run_help(const char *cmd);

// thin alias onto the shared repo module (repo.c)
bool parse_ref(const char *arg, char *prov, size_t provcap,
               char *path, size_t pathcap);

// Provider-less argument -> installed ref by repo tail, command or binary
// name (never self): one hit echoes, many set *many, none leave it unset.
manifest_entry *resolve_installed(const cmd_opts *o, const char *arg,
    manifest_entry *ms, size_t mc, bool *many);

void manifest_list_free(manifest_entry *ms, size_t n);

// span duplication, escaped string value of a jobject member, and its
// jbuf printer for "key": "value" pairs (pair comma handled via *first)
char *span_dup(const char *s, const char *e);
char *jstr(const jobject *o, const char *key);
void jb_jstr(jbuf *b, const jobject *o, const char *key);

void now_instant(char *buf, size_t cap);

// providers subcommand plumbing (main/providers*.c). mode: 0 add,
// 1 update, 2 remove, 3 recover (mirrors each command's option sets)
typedef struct {
    const char *type, *api, *token_env, *name, *url;
    bool token_env_given, force, abandon, purge;
} pv_opts;

// per-subcommand option parsing over the already-global-filtered args
bool pv_parse(char **a, int n, int mode, pv_opts *o, char **pos, int *npos,
              const char **err, char *errbuf, size_t errcap);
void providers_usage(void);
const char *type_canon(const char *v);
int cmd_providers_add(const cmd_opts *o, state_ctx *st,
    char **args, int n);
int cmd_providers_remove(const cmd_opts *o, state_ctx *st,
    char **args, int n);

// history-line helpers: used once the target provider is named
#define PVLOG_ERR(st, cmd, ref, ...) do {                                 \
    char _eb[512];                                                        \
    snprintf(_eb, sizeof(_eb), __VA_ARGS__);                              \
    oplog_append((st), (cmd), (ref), "-", _eb);                           \
} while (0)

// command entry points; rc is the process exit code
int providers_run(const cmd_opts *o, state_ctx *st);
int uninstall_run(const cmd_opts *o, state_ctx *st);
bool uninstall_one(const state_ctx *st, manifest_entry *m);
int adopt_run(const cmd_opts *o, state_ctx *st);
int lock_run(const cmd_opts *o, state_ctx *st, bool locking);
int list_run(const cmd_opts *o, state_ctx *st);
int info_run(const cmd_opts *o, state_ctx *st);
int rollback_run(const cmd_opts *o, state_ctx *st);
int cmd_search(const cmd_opts *o, state_ctx *st);
int run_about(void);
int run_completions(const cmd_opts *o);

#endif
