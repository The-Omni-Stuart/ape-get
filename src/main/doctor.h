#ifndef APEGET_DOCTOR_H
#define APEGET_DOCTOR_H

#include "cmd.h"
#include "state.h"
#include <stdbool.h>

// doctor command
int doctor_run(const cmd_opts *o);

// saved fields of a recovered provider (all malloc'd, caller frees)
typedef struct { char *type, *url, *api, *token_env; } pv_recovered;

// Recover <name> from its installed manifests (agreeing type/API required),
// confirm and save; rc 0 recovered (fills *out), 1 declined, 2 not recoverable.
int providers_recover_core(const cmd_opts *o, state_ctx *st, const char *name,
    const char *type_ov, const char *api_ov, const char *tok_ov,
    bool tok_given, pv_recovered *out);

#endif
