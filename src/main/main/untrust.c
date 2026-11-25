// untrust: drop a repo's signing-key trust record, and the key itself from
// ape-get's keyring when no remaining record still names it. ~/.gnupg is
// never touched.

#include "main.h"

#include "path.h"
#include "state.h"
#include "verify.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int untrust_run(const cmd_opts *o, state_ctx *st)
{
    const char *arg = o->args[1];
    char prov[64], path[512];
    const char *provp = NULL, *repo = arg;
    if (strchr(arg, ':') != NULL) {
        if (!parse_ref(arg, prov, sizeof prov, path, sizeof path)) {
            put_fail("Expected provider:owner/repo.");
            return 2;
        }
        provp = prov;
        repo = path;
    }
    trust_hit *hits = NULL;
    int n = trust_remove(st, provp, repo, &hits);
    if (n < 0) {
        put_fail("Failed to update trust records.");
        oplog_append(st, "untrust", arg, "-", "Failed to update trust records.");
        return 1;
    }
    if (n == 0) {
        put_s(stderr, UI_WARN, "No trusted signing key for %s.", arg);
        oplog_append(st, "untrust", arg, "-", "No trusted signing key.");
        return 1;
    }
    char *kr = state_keyring_dir(st);
    for (int k = 0; k < n; k++) {
        const char *fpr = hits[k].fingerprint != NULL
            ? hits[k].fingerprint : "unknown";
        printf("Untrusted %s (key %s).\n", hits[k].ref, fpr);
        oplog_append(st, "untrust", hits[k].ref, fpr, NULL);
        if (kr != NULL && hits[k].fingerprint != NULL
            && !trust_fpr_used(st, hits[k].fingerprint)) {
            char *err = NULL;
            if (!sig_keyring_delete(kr, hits[k].fingerprint, &err))
                put_s(stderr, UI_WARN, "Key %s left in the keyring: %s",
                    fpr, err != NULL ? err : "gpg failed");
            free(err);
        }
        free(hits[k].ref);
        free(hits[k].fingerprint);
    }
    free(hits);
    free(kr);
    return 0;
}
