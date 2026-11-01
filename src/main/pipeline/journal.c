// Install/update journal phase rewrites.

#include "pipeline/internal.h"

#include "txn.h"

#include <stdlib.h>
#include <string.h>

// rewrite the journal for a phase transition, re-stating the same fields;
// prev (updates) fills the old side
bool journal_phase_write(const state_ctx *st, const char *op,
    const char *ref, const char *id, const char *created,
    const char *phase, const char *vdir, const char *mfile,
    const char *mtmp, const char *staged, const char *sha,
    const char *backup, bool history, const manifest_entry *prev)
{
    txn t;
    txn_init(&t);
    t.id = strdup(id);
    t.op = strdup(op);
    t.ref = strdup(ref);
    t.phase = strdup(phase);
    t.created_at = strdup(created);
    if (vdir != NULL) t.new_generation = strdup(vdir);
    if (mfile != NULL) t.new_manifest = strdup(mfile);
    if (mtmp != NULL) t.new_manifest_tmp = strdup(mtmp);
    if (staged != NULL) t.new_binary_tmp = strdup(staged);
    if (sha != NULL && *sha != '\0') t.new_asset_sha256 = strdup(sha);
    if (backup != NULL) t.backup = strdup(backup);
    t.history_written = history;
    if (prev != NULL) {
        if (prev->file != NULL)
            t.old_manifest_path = strdup(prev->file);
        if (prev->launcher != NULL)
            t.old_launcher = strdup(prev->launcher);
        if (prev->path != NULL)
            t.old_binary = strdup(prev->path);
    }
    char *j = txn_build(&t);
    bool ok = j != NULL && txn_write(st->root, j);
    free(j);
    txn_free(&t);
    return ok;
}
