// Signer-conflict handling shared by the verify phase's "pinned to"
// and "signer changed" branches.

#include "pipeline/internal.h"

#include "path.h"
#include "prompt.h"
#include "ui.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// signer label for conflict messages: "<signer> (<identity>)", signer
// falling back to "unknown signer"; only reached for verified records
char *describe_manifest_sig(const manifest_entry *m)
{
    if (m->sig_status == NULL || strcmp(m->sig_status, "verified") != 0)
        return strdup("unknown signer");
    if (m->sig_identity == NULL)
        return strdup(m->sig_signer != NULL ? m->sig_signer
                                            : "unknown signer");
    return path_fmt("%s (%s)",
        m->sig_signer != NULL ? m->sig_signer : "unknown signer",
        m->sig_identity);
}

// signer-conflict rule for the "pinned to" and "signer changed" branches: deny
// without --allow-signer-change, else confirm and warn; false = caller fails.
bool resolve_pin(const cmd_opts *o, state_ctx *st, const char *op,
    const char *ref, const char *repo, const char *reason,
    const char *deny_hint, const char *question, const char *warning)
{
    if (!o->allow_signer_change) {
        char *d = path_fmt("Unable to %s %s.", op, repo);
        ui_msg(stderr, UI_ERROR, "%s", d != NULL ? d : "unable");
        oplog_append(st, op, ref, "-", d != NULL ? d : "unable");
        free(d);
        d = path_fmt("Reason: %s.", reason != NULL ? reason : "");
        ui_msg(stderr, UI_DIM, "%s", d != NULL ? d : "reason");
        free(d);
        ui_msg(stderr, UI_WARN, "Be careful: this can mean the release was"
            " replaced or tampered with.");
        ui_msg(stderr, UI_ITALIC, "%s", deny_hint != NULL ? deny_hint : "");
        return false;
    }
    if (!confirm_action(o, question != NULL ? question : "", true)) {
        ui_msg(stderr, UI_WARN, "Signer override declined.");
        oplog_append(st, op, ref, "-", "Signer override declined.");
        return false;
    }
    ui_msg(stderr, UI_WARN, "%s", warning != NULL ? warning : "pin");
    return true;
}
