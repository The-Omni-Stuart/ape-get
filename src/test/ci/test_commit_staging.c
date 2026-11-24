// Run the real install phase/serializer with one manifest-write fault. The
// Python caller creates and validates every filesystem path in a fresh fixture.
#include "pipeline/internal.h"
#include "crypto.h"
#include "fs.h"
#include "path.h"
#include "ui.h"

#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static const char *fixture, *manifest_stage;
static bool fail_write;
static unsigned writes, journals, errors;

static void confined(const char *p)
{
    assert(p != NULL && strstr(p, "/../") == NULL);
    char *ancestor = strdup(p);
    assert(ancestor != NULL);
    while (access(ancestor, F_OK) != 0) {
        char *slash = strrchr(ancestor, '/');
        assert(slash != NULL && slash != ancestor);
        *slash = '\0';
    }
    assert(!strcmp(ancestor, fixture) || path_under(ancestor, fixture));
    free(ancestor);
}

static bool manifest_write(const char *path, const char *text)
{
    confined(path);
    assert(!strcmp(path, manifest_stage));
    assert(text != NULL && strstr(text, "\"kind\":\"grm.manifest\"") != NULL);
    // fs_write_file's own staged sibling is also confined before its real call.
    char *sibling = path_fmt("%s.tmp-%d", path, (int)getpid());
    confined(sibling);
    free(sibling);
    writes++;
    if (fail_write) { errno = ENOSPC; return false; }
    return fs_write_file(path, text);
}

// Unrelated UI/state/provider integration is inert; unsupported paths abort.
void ui_msg(FILE *f, ui_style style, const char *fmt, ...)
{ (void)f; (void)fmt; if (style == UI_ERROR) errors++; }
void oplog_append(const state_ctx *st, const char *op, const char *ref,
    const char *change, const char *detail)
{ (void)st; (void)op; (void)ref; (void)change; (void)detail; }
bool backup_create(const state_ctx *s, const manifest_entry *p,
    int n, char **b, char **pp, char *e, size_t cap)
{ (void)s; (void)p; (void)n; (void)b; (void)pp; (void)e; (void)cap; abort(); }
void backup_prune_trimmed(const char *a, const char *pp)
{ (void)a; (void)pp; abort(); }
char *manifest_backups_array(const char *p) { (void)p; abort(); }
char *assimilate_asset(const char *p, const char *b, char **e)
{ (void)p; (void)b; (void)e; abort(); }
char *manifest_app_dir(const char *r, const char *p, const char *n)
{ (void)r; (void)p; (void)n; abort(); }
char *manifest_path(const char *r, const char *p, const char *n)
{ (void)r; (void)p; (void)n; abort(); }
char *compat_wrapper_stage(const char *l, const char *i, const char *w,
    const char *t, char **e)
{ (void)l; (void)i; (void)w; (void)t; (void)e; abort(); }
char *desktop_entry_path(const state_ctx *st, const char *command)
{ (void)st; assert(command == NULL); return NULL; }
const char *desktop_recorded(const cmd_opts *o, const char *e, bool d)
{ (void)o; (void)d; assert(e == NULL); return NULL; }
bool desktop_entry_owned(const char *e) { (void)e; abort(); }
char *desktop_icon_copy(const char *r, const char *a)
{ (void)r; (void)a; abort(); }
void desktop_entry_write(const char *e, const char *c, const char *l,
    const char *r, const char *i, bool t)
{ (void)e; (void)c; (void)l; (void)r; (void)i; (void)t; abort(); }
const char *sig_status_str(sig_status s) { (void)s; abort(); }
void txn_new_id(char out[17]) { (void)out; abort(); }
bool state_rebuild_index(const state_ctx *st) { (void)st; abort(); }
bool journal_phase_write(const state_ctx *st, const char *op, const char *ref,
    const char *id, const char *created, const char *phase, const char *vdir,
    const char *mfile, const char *mtmp, const char *staged, const char *sha,
    const char *backup, bool history, const manifest_entry *prev)
{
    (void)op; (void)ref; (void)id; (void)created; (void)backup;
    (void)history; (void)prev;
    confined(st->root); confined(st->install_root); confined(vdir);
    confined(mfile); confined(mtmp); confined(staged);
    assert(!strcmp(phase, "committing") && sha != NULL && strlen(sha) == 64);
    journals++;
    return true;
}

#ifndef APEGET_COMMIT_SOURCE
#define APEGET_COMMIT_SOURCE "pipeline/commit.c"
#endif
#define fs_write_file manifest_write
#include APEGET_COMMIT_SOURCE
#undef fs_write_file

int main(int argc, char **argv)
{
    if (argc != 3) return 2;
    fixture = argv[1];
    fail_write = !strcmp(argv[2], "write-fail");
    bool hash_fail = !strcmp(argv[2], "hash-fail");
    assert(fail_write || hash_fail || !strcmp(argv[2], "control"));
    assert(getenv("APEGET_TEST_CRASH") == NULL);
    char *root = path_join(fixture, "state");
    char *install = path_join(fixture, "install");
    char *app = path_join(root, "applications/fixture/owner/tool");
    char *vdir = path_join(app, "versions/v2");
    char *target = path_join(vdir, "tool.com");
    char *stage = path_fmt("%s.tmp-0123456789abcdef", target);
    char *mfile = path_join(app, "grm_manifest.json");
    char *mtmp = path_fmt("%s.tmp-0123456789abcdef", mfile);
    char *current = path_join(app, "current");
    char *journal = path_join(root, "grm_transaction.json");
    const char *paths[] = {root, install, app, vdir, target, stage, mfile,
                           mtmp, current, journal};
    for (size_t i = 0; i < sizeof(paths) / sizeof(*paths); i++) confined(paths[i]);
    // Both old and new relative current-link targets resolve in this fixture.
    char *old_version = path_join(app, "versions/v1");
    confined(old_version);
    free(old_version);
    manifest_stage = mtmp;
    cmd_opts opts = {.quiet = true};
    state_ctx st = {.root = root, .install_root = install};
    provider_entry provider = {.type = "fixture", .api = "https://fixture.invalid"};
    prov_asset asset = {.name = "tool.com", .url = "https://fixture.invalid/tool.com"};
    pipe_ctx pc = {.o = &opts, .st = &st, .pe = &provider, .asset = &asset,
        .app_dir = app, .tmp = stage, .j_vdir = vdir, .j_mfile = mfile,
        .j_on = true, .kind = K_APE, .op = "install", .ref = "fixture:owner/tool"};
    strcpy(pc.j_id, "0123456789abcdef");
    strcpy(pc.prov, "fixture"); strcpy(pc.path, "owner/tool");
    strcpy(pc.base, "tool.com"); pc.rel.version = "v2";
    pc.vr.status = V_MISSING;
    assert(phase_install(&pc) == PIPE_CLEANUP);
    bool failed = fail_write || hash_fail;
    assert(pc.failed == failed && pc.j_committed == !failed);
    assert(writes == (hash_fail ? 0u : 1u));
    assert(journals == (failed ? 0u : 1u));
    assert((errors > 0) == failed);
    assert(pc.mtmp != NULL && !strcmp(pc.mtmp, mtmp));
    printf("%s: ordinary %s, writes=%u journals=%u\n", argv[2],
        failed ? "failure" : "success", writes, journals);
    free(pc.target); free(pc.mtmp); free(pc.detail);
    free(root); free(install); free(app); free(vdir); free(target);
    free(stage); free(mfile); free(mtmp); free(current); free(journal);
    return 0;
}
