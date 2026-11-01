// fuzz target: gpg [GNUPG:] status-line parser of the signature verify path
// (sig_run_gpg spawns gpg and is deliberately not reached); sig_message too.

#include "verify.c"

#include "fuzz_util.h"

static void one(const char *s, void *ud)
{
    (void)ud;
    free(status_value(s, "VALIDSIG"));
    free(status_value(s, "GOODSIG"));
    free(status_signer(s));
    free(failure_detail(s));

    sig_result r;
    memset(&r, 0, sizeof r);
    // s may be empty; only s[0] is guaranteed readable (NUL sentinel)
    unsigned char c0 = (unsigned char)s[0];
    unsigned char c1 = c0 != 0 ? (unsigned char)s[1] : 0;
    r.status = (sig_status)(c0 % 5);
    r.asset_name = strdup(s);
    r.mechanism = strdup("gpg");
    r.signer = strdup(s);
    r.identity = strdup(s);
    r.detail = strdup(s);
    char *m = sig_message(&r);
    free(m);
    (void)sig_status_str(r.status);
    (void)sig_allows_install(&r, c1 % 2);
    sig_result_free(&r);
}

void fuzz_target(const unsigned char *data, size_t size)
{
    // status lines are C-string scans; segments cover bytes past NULs
    (void)size;
    fuzz_for_each_segment(data, size, one, NULL);
}
