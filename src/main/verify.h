#ifndef APEGET_VERIFY_H
#define APEGET_VERIFY_H

#include <stdbool.h>

// Signature verification: gpg --batch --status-fd=1 --verify, 60 s, in a
// sanitised environment; gpg's default keyring.

typedef enum {
    S_VERIFIED, S_MISSING, S_UNAVAILABLE, S_INVALID, S_ERROR
} sig_status;

typedef struct {
    sig_status status;
    char *asset_name, *mechanism, *signer, *identity, *detail;
} sig_result;

void sig_result_free(sig_result *r);

// Verify the staged sig_file against artifact; asset_name labels the result.
// Always fills a result (caller frees).
sig_status sig_run_gpg(const char *sig_file, const char *artifact,
    const char *asset_name, sig_result *out);

char *sig_message(const sig_result *r);
bool sig_allows_install(const sig_result *r, bool required);

// lowercase status token, as recorded in manifests and printed in messages
const char *sig_status_str(sig_status s);

#endif
