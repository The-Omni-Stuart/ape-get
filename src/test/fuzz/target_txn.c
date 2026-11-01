// fuzz target: transaction-journal reader; input is fed as grm_transaction.json
// through txn_load_journal, and txn_build output must load back field-equal.

#include "txn.c"
#include "txn/journal.c"
#include "txn/recover.c"

#include "fuzz_util.h"

static const char *journal_dir(void)
{
    static char *dir;
    if (dir == NULL) dir = fuzz_work_subdir("txn");
    return dir;
}

static void put_journal(const char *dir, const char *text, size_t n)
{
    char path[4096];
    snprintf(path, sizeof path, "%s/grm_transaction.json", dir);
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) return;
    ssize_t r = write(fd, text, n);
    (void)r;
    close(fd);
}

static int seq(const char *a, const char *b)
{
    if (a == NULL || b == NULL) return a == b;
    return strcmp(a, b) == 0;
}

static void fields_equal(const txn *a, const txn *b)
{
    FUZZ_ASSERT(seq(a->id, b->id));
    FUZZ_ASSERT(seq(a->op, b->op));
    FUZZ_ASSERT(seq(a->ref, b->ref));
    FUZZ_ASSERT(seq(a->phase, b->phase));
    FUZZ_ASSERT(seq(a->created_at, b->created_at));
    FUZZ_ASSERT(seq(a->backup, b->backup));
    FUZZ_ASSERT(seq(a->host, b->host));
    FUZZ_ASSERT(seq(a->old_manifest_path, b->old_manifest_path));
    FUZZ_ASSERT(seq(a->old_generation, b->old_generation));
    FUZZ_ASSERT(seq(a->old_launcher, b->old_launcher));
    FUZZ_ASSERT(seq(a->old_binary, b->old_binary));
    FUZZ_ASSERT(seq(a->new_generation, b->new_generation));
    FUZZ_ASSERT(seq(a->new_manifest, b->new_manifest));
    FUZZ_ASSERT(seq(a->new_manifest_tmp, b->new_manifest_tmp));
    FUZZ_ASSERT(seq(a->new_launcher_tmp, b->new_launcher_tmp));
    FUZZ_ASSERT(seq(a->new_binary_tmp, b->new_binary_tmp));
    FUZZ_ASSERT(seq(a->new_asset_sha256, b->new_asset_sha256));
    FUZZ_ASSERT(a->history_written == b->history_written);
    FUZZ_ASSERT(a->pid > 0 ? a->pid == b->pid : b->pid == 0);
}

void fuzz_target(const unsigned char *data, size_t size)
{
    const char *dir = journal_dir();
    if (dir == NULL) return;
    put_journal(dir, (const char *)data, size);
    txn t;
    txn_load l = txn_load_journal(dir, &t);
    if (l == TXN_READ) {
        char *text = txn_build(&t);
        if (text != NULL) {
            put_journal(dir, text, strlen(text));
            txn t2;
            txn_load l2 = txn_load_journal(dir, &t2);
            FUZZ_ASSERT(l2 == TXN_READ);
            fields_equal(&t, &t2);
            txn_free(&t2);
            free(text);
        }
    }
    char *issue = txn_doctor_issue(dir);
    free(issue);
    txn_free(&t);
}
