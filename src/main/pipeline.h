#ifndef APEGET_PIPELINE_H
#define APEGET_PIPELINE_H

#include "cmd.h"
#include "state.h"

// install command. The caller has locked and ensured state.
int pipeline_install(const cmd_opts *o, state_ctx *st);

// update one ref / update --all. State locked + ensured by the caller.
int pipeline_update(const cmd_opts *o, state_ctx *st);
int pipeline_update_all(const cmd_opts *o, state_ctx *st);

// list --outdated; read-only, no op log.
int pipeline_outdated(const cmd_opts *o, state_ctx *st);

// First run copies the binary into the install root under the journal, plus
// self manifest; never alters the caller's result. Absent: APEGET_SELF=OFF.
// Locking: mutation commands hold the state lock already; a query command
// reaching this takes it for the registration writes, or skips them.
void pipeline_self_after(const cmd_opts *o, state_ctx *st);

// True when a desktop entry carries ape-get's managed marker: the only
// entries uninstall may remove.
bool desktop_entry_owned(const char *entry);

// setup's reading of the installed copy, by comparing it with the running
// executable: SELF_OK is this very file, SELF_BLOCKED is anything else there.
// The two are not told apart, because telling them apart reliably needs a
// marker our fat builds do not carry (see issues-internal#3), and setup
// refuses both the same way. target gets the path whenever it can be named,
// whatever the answer. self_enabled covers both switches that can turn the
// mechanism off — the build option and the run's environment — because a
// caller deciding what to promise needs the answer for this run.
typedef enum { SELF_NONE = 0, SELF_OK, SELF_BLOCKED } self_state;

bool self_enabled(void);
self_state self_install_status(const state_ctx *st, char **target);

// Remembers argv[0] for self-integration's loader fallback; a no-op unless
// the build enables APEGET_SELF.
void self_set_argv0(const char *argv0);

// Release coordinates self-update fetches from; APEGET_SELF_UPSTREAM=
// "provider:repo" overrides them (test hook, mirrors APEGET_NO_SELF).
bool self_upstream(char *prov, size_t psz, char *path, size_t ssz);

#endif
