#ifndef APEGET_MANIFEST_H
#define APEGET_MANIFEST_H

#include <stdbool.h>
#include <stddef.h>

#include "json.h"

// Installed-release manifests: scan, validity checks and directory
// naming all follow the state format the Java wrote.

typedef struct {
    char *file;       // grm_manifest.json path
    char *provider;   // provider name
    char *repo;       // owner/repo
    char *version;    // NULL when absent
    char *path;       // install.path
    char *kind;       // install.kind, NULL when absent
    char *launcher;   // install.uninstall.path, NULL when absent
    char *desktop;    // install.desktop_entry, NULL when absent
    char *command;    // install.command, NULL when absent
    char *asset;      // asset.name, NULL when absent
    char *asset_url;  // asset.url, NULL when absent (update identity)
    char *installed;  // installed_at, always present on valid records
    char *updated;    // updated_at, NULL when absent
    char *sig_status;   // signature.status, NULL when absent
    char *sig_identity; // signature.identity, NULL when absent
    char *sig_signer;   // signature.signer, NULL when absent
    char *compat;       // layer name ("wine"), NULL when absent
    bool locked;        // locked member
} manifest_entry;

void manifest_entry_free(manifest_entry *e);

// Valid manifests under <root>/applications sorted by repo then provider;
// foreign kinds reported to stderr, corrupt ones quarantined; -1 on failure.
int manifests_scan(const char *root, manifest_entry **out, size_t *count);

// Path helpers; NULL when repo cannot produce a directory name.
char *manifest_provider_dir(const char *provider);
char *manifest_app_dir(const char *root, const char *provider,
                       const char *repo);
char *manifest_path(const char *root, const char *provider,
                    const char *repo);
char *manifest_install_name(const char *provider, const char *repo);

// Read/rewrite the locked member (omitted when false). Rewrites preserve
// member order and raw value text.
bool manifest_get_locked(const char *file, bool *locked);
bool manifest_set_locked(const char *file, bool locked);

// Validity of an already parsed manifest.
bool manifest_json_valid(const jobject *root);

// Remove the whole app state directory (providers remove --purge).
bool manifest_remove_app_dir(const char *root, const char *provider,
                             const char *repo);

#endif
