// config: print the policy profile and its effective values, or write the
// profile word (atomic rewrite through the config machinery, oplog entry).
// The four policy keys stay hand-editable in grm_config.json.

#include "main.h"

#include "fs.h"
#include "state.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int config_run(const cmd_opts *o, state_ctx *st)
{
    int extra = o->nargs - 1;
    if (extra == 1 && strcmp(o->args[1], "set") != 0) {
        put_s(stderr, UI_ERROR, "Unknown config subcommand: %s", o->args[1]);
        return 2;
    }
    if (extra == 2 && !strcmp(o->args[1], "set")) {
        const char *p = o->args[2];
        if (strcmp(p, "standard") != 0 && strcmp(p, "hardened") != 0) {
            put_s(stderr, UI_ERROR,
                "Unknown policy profile: %s (standard or hardened)", p);
            return 2;
        }
        char *text = state_config_set_profile(st, p);
        if (text == NULL || !fs_write_file(st->config_file, text)) {
            free(text);
            put_fail("Failed to update config.");
            oplog_append(st, "config", "-", "-", "Failed to update config.");
            return 1;
        }
        free(text);
        oplog_append(st, "config", "-", p, NULL);
        if (strcmp(p, "hardened") == 0)
            printf("Policy: hardened. Override per run with --strict,"
                " --require-signature, --allow-signer-change,"
                " --no-desktop.\n");
        else
            printf("Policy: standard. Tighten per run with --strict,"
                " --require-signature, --allow-signer-change.\n");
        return 0;
    }
    if (extra != 0) {
        put_s(stderr, UI_ERROR, "config set needs standard or hardened.");
        return 2;
    }
    printf("Policy: %s\n", st->policy_profile == 1 ? "hardened" : "standard");
    printf("  require_signature: %s\n",
        st->require_signature ? "true" : "false");
    printf("  checksum_strict: %s\n",
        st->strict_verification ? "true" : "false");
    printf("  signer_change: %s\n",
        st->signer_change == 1 ? "refuse" : "confirm");
    printf("  desktop: %s\n",
        st->desktop_policy == 1 ? "always"
        : st->desktop_policy == 2 ? "never" : "auto");
    return 0;
}
