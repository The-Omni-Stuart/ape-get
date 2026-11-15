#ifndef APEGET_STATE_H
#define APEGET_STATE_H

#include <stdbool.h>

#include "manifest.h"

// State root: paths, config validity + install_root, index validity,
// manifest count.

typedef struct {
    char *root, *config_file, *index_file, *install_root;
    bool config_present;    // file exists (parse failures are silent)
    bool config_created;    // state_ensure bootstrapped the defaults
    bool config_corrupt;    // present but unreadable/invalid (never touched)
    bool index_corrupt;     // present but not a valid grm.index v1
    bool index_ok;          // valid grm.index v1 snapshot
    bool search_all;        // config search_all_providers (null/absent = on)
    int timeout_seconds;    // config network_timeout_seconds (<=0 = 30)
    int backup_count;       // config backup_count (absent = 0)
    char *update_channel;   // config update_channel raw text or NULL
    bool strict_verification;  // config strict_verification
    bool require_signature;    // config require_signature
    bool experimental_compat;  // config experimental_compat
    char *wine_path;        // config wine_path override, NULL when unset
    char *proxy;            // config proxy URL, NULL when unset
    int manifests;          // grm_manifest.json under applications/ (no backups)
} state_ctx;

bool state_open(state_ctx *out);   // false only when no state root can be built

// Query commands (list/info/providers-query/search/doctor diagnosis) never
// repair: state_open only reports what is broken, manifests_scan leaves
// corrupt records in place, and no bootstrap or recovery runs. Call
// state_set_query() before the first state call; mutation commands never do.
void state_set_query(void);
bool state_is_query(void);

// Quarantine-and-replace corrupt config/index; mutation commands run this
// only after the state lock is held (S08: repair never precedes the lock).
void state_repair_corrupt(state_ctx *st);

// Query-path notice for corrupt config/index (stderr, never a change).
void state_report_query(const state_ctx *st, bool quiet);

// Acquire state lock (try-lock; message and false on failure). The
// descriptor intentionally stays open until process exit.
bool state_lock(const char *root);

// True when this process already holds the state lock (self-integration
// under a query command takes it, mutation commands already have it).
bool state_lock_held(void);

// Run pending-transaction recovery under the lock (stateful commands
// and doctor --heal). False blocks the command.
bool state_recover_gate(const state_ctx *st, bool quiet, bool json);

// Stateful-command bootstrap: create roots, rebuild the index when
// absent/corrupt; bare doctor calls neither this nor the lock.
void state_ensure(state_ctx *st, bool quiet, bool json);

// Manifest scan for its warnings only (bootstrap, index rebuilds,
// uninstall/purge paths).
void state_scan_manifests_quiet(const char *root);

// Rebuild the index from the current manifests, quietly.
bool state_rebuild_index(const state_ctx *st);

// Append one history line to <root>/grm_updates.log; error_message NULL
// records success, else its first line ends the entry.
void oplog_append(const state_ctx *st, const char *command, const char *ref,
                  const char *detail, const char *error_message);

// Rewrite the config record applying one provider delta (normalised field
// order); add replaces-or-appends, remove drops by exact key; NULL on fail.
char *state_config_edit(const state_ctx *st, const char *add_name,
    const char *type, const char *url, const char *api, const char *token_env,
    const char *remove_name);

// --- provider registry ---

typedef struct {
    char *name, *type, *url, *api;      // api is the resolved API base
    char *token_env;
    bool built_in;
} provider_entry;

// Built-ins + config entries, warnings to stderr, sorted by name.
// Returns count (list owned by caller via providers_free).
int providers_load(const state_ctx *st, provider_entry **out);
void providers_free(provider_entry *list, int n);
provider_entry *providers_find(provider_entry *list, int n, const char *name);

// Name/URL/token validation.
bool provider_valid_name(const char *name);
bool provider_url_ok(const char *type, const char *url);      // https or rns
bool provider_token_ok(const char *token_env);
char *provider_resolved_api(const char *type, const char *url, const char *api);

// --- backups (state/backup.c) ---

// Copy prev->path + manifest copy into appDir/backups/<millis>-<uuid>/
// and merge the record list, deleting nothing: the records dropped
// beyond `keep` are returned (prune_paths) for the post-activation pass.
bool backup_create(const state_ctx *st, const manifest_entry *prev,
                   int keep, char **backups_json, char **prune_paths,
                   char *err, size_t errcap);

// Delete generations trimmed from a new backups array. Only directories
// resolving directly under appDir/backups are removed; anything else a
// corrupt or crafted record names is left in place.
void backup_prune_trimmed(const char *app_dir, const char *prune_paths);

// Raw text of a manifest file's "backups" array member, "[]" absent.
char *manifest_backups_array(const char *file);

#endif
