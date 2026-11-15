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

// True when a desktop entry carries ape-get's managed marker: the only
// entries uninstall may remove.
bool desktop_entry_owned(const char *entry);

#endif
