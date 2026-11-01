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
void pipeline_self_after(const cmd_opts *o, state_ctx *st);

// Remembers argv[0] for self-integration's loader fallback; a no-op unless
// the build enables APEGET_SELF.
void self_set_argv0(const char *argv0);

// Release coordinates self-update fetches from; APEGET_SELF_UPSTREAM=
// "provider:repo" overrides them (test hook, mirrors APEGET_NO_SELF).
bool self_upstream(char *prov, size_t psz, char *path, size_t ssz);

#endif
