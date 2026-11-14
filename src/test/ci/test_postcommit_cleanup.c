// Run the real cleanup tail (pipe_ctx_free, via run.c) with a committed
// transaction and real staged files: it must keep recovery's inputs and
// release only memory. The Python caller builds/validates every path.
#include "pipeline/run.c"

#include "pipeline/internal.h"
#include "state.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// run.c's externs; none of these run when only pipe_ctx_free is called.
char dl_line_buf[320];
void dl_progress(size_t done, long long total, void *ud)
{ (void)done; (void)total; (void)ud; }
void ui_msg(FILE *f, ui_style style, const char *fmt, ...)
{ (void)f; (void)style; (void)fmt; }
void ui_progress_done(void) {}
void oplog_append(const state_ctx *st, const char *op, const char *ref,
    const char *change, const char *detail)
{ (void)st; (void)op; (void)ref; (void)change; (void)detail; }
bool provider_download(const provider_entry *e, const char *url,
    const char *name, int timeout_ms, long long max_bytes, const char *dest,
    http_prog cb, void *ud, char **tmp_path, char *msg, size_t cap)
{ (void)e; (void)url; (void)name; (void)timeout_ms; (void)max_bytes;
  (void)dest; (void)cb; (void)ud; (void)tmp_path; (void)msg; (void)cap;
  return false; }
int xz_decode_file(const char *in, const char *out)
{ (void)in; (void)out; abort(); }
int xdelta_apply_fd(int fd, const char *patch, const char *out)
{ (void)fd; (void)patch; (void)out; abort(); }
int phase_preflight(pipe_ctx *pc) { (void)pc; abort(); }
int phase_prepare(pipe_ctx *pc) { (void)pc; abort(); }
int phase_checksum(pipe_ctx *pc) { (void)pc; abort(); }
int phase_verify(pipe_ctx *pc) { (void)pc; abort(); }
int phase_install(pipe_ctx *pc) { (void)pc; abort(); }
void phase_record(pipe_ctx *pc) { (void)pc; abort(); }
void sig_result_free(sig_result *r) { (void)r; }
void prov_release_free(prov_release *r) { (void)r; }
void providers_free(provider_entry *list, int n) { (void)list; (void)n; }
void manifest_entry_free(manifest_entry *e) { (void)e; }

static bool exists(const char *p)
{
    FILE *f = fopen(p, "rb");
    if (f == NULL) return false;
    fclose(f);
    return true;
}

// Prints "mtmp journal" as 1/0 so the driver can assert either shape.
int main(int argc, char **argv)
{
    if (argc != 3) return 2;
    char *root = argv[1];
    bool committed = !strcmp(argv[2], "committed");
    if (!committed && strcmp(argv[2], "uncommitted") != 0) return 2;
    char *mtmp = path_join(root, "grm_manifest.json.tmp-0123456789abcdef");
    char *journal = path_join(root, "grm_transaction.json");
    // pipe_ctx_free frees the pointers it owns; assert on independent copies
    char *mtmp_after = mtmp != NULL ? strdup(mtmp) : NULL;
    if (mtmp == NULL || journal == NULL || mtmp_after == NULL) return 2;
    state_ctx st = {0};
    st.root = root;
    pipe_ctx pc;
    memset(&pc, 0, sizeof(pc));
    pc.st = &st;
    pc.delta_fd = -1;
    pc.j_on = true;
    pc.j_committed = committed;
    pc.mtmp = mtmp;
    pipe_ctx_free(&pc);
    printf("%s: mtmp=%d journal=%d\n", argv[2], exists(mtmp_after),
        exists(journal));
    free(journal);
    free(mtmp_after);
    return 0;
}
