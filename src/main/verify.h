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

// As sig_run_gpg with an explicit gpg home: NULL keeps gpg's default
// keyring (~/.gnupg), a directory selects ape-get's own trust keyring.
sig_status sig_run_gpg_home(const char *home, const char *sig_file,
    const char *artifact, const char *asset_name, sig_result *out);

// Inspect a key asset without writing any keyring (show-only import into
// the given home): primary fingerprint + uid. True on success.
bool sig_key_fingerprint(const char *home, const char *key_file,
    char **fpr, char **uid, char **err);

// Import a key file into the home keyring / remove a public key from it.
bool sig_keyring_import(const char *home, const char *key_file, char **err);
bool sig_keyring_delete(const char *home, const char *fpr, char **err);

// Primary fingerprint of the key holding signing_fpr in the home keyring
// (NULL = default keyring). False when the key is absent there.
bool sig_key_primary(const char *home, const char *signing_fpr,
    char **primary, char **err);

char *sig_message(const sig_result *r);
bool sig_allows_install(const sig_result *r, bool required);

// lowercase status token, as recorded in manifests and printed in messages
const char *sig_status_str(sig_status s);

#endif
