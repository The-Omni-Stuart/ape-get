#ifndef APEGET_TXN_H
#define APEGET_TXN_H

#include <stddef.h>
#include <stdbool.h>

// Transaction journal (grm_transaction.json at the state root): prepared
// rolls back, committing/committed roll forward; recovery runs under the lock.

typedef enum {
    TXN_ABSENT,     // no journal file
    TXN_READ,       // parsed, fields filled (owned by the struct)
    TXN_UNREADABLE  // bad JSON or unknown kind/schema/op/phase/field
} txn_load;

typedef struct {
    char *id, *op, *ref, *phase, *created_at;
    char *backup, *host;
    long pid;
    bool history_written;
    // old side (nullable)
    char *old_manifest_path, *old_manifest, *old_generation,
         *old_launcher, *old_binary;
    // new side (nullable)
    char *new_generation, *new_manifest_tmp, *new_manifest,
         *new_launcher_tmp, *new_binary_tmp, *new_asset_sha256;
} txn;

void txn_init(txn *t);
void txn_free(txn *t);

// Parse and validate <root>/grm_transaction.json.
txn_load txn_load_journal(const char *root, txn *t);

// Journal text in canonical member order; the write is atomic (tmp +
// fsync + rename + dir fsync).
char *txn_build(const txn *t);
bool txn_write(const char *root, const char *json_text);

// New transaction id: 16 lowercase hex chars.
void txn_new_id(char out[17]);

// absent: sweep orphan .tmp-<id>. prepared: delete temps and journal. committing/
// committed: roll forward, set *need_history. unreadable or corrupt: block.
bool txn_recover_startup(const char *root, const char *install_root,
    bool quiet, bool json,
    bool *need_history, const char **need_op, const char **need_ref,
    char *op_buf, size_t op_buf_sz, char *ref_buf, size_t ref_buf_sz);

// Doctor issue line for a pending journal (no lock, read-only): "Pending
// transaction: <op> <ref> (...)" or the unreadable notice; NULL if none.
char *txn_doctor_issue(const char *root);

#endif
