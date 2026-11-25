/* Entry point. The original ReleaseManager described this role as the
   "central coordinator for parsing commands, accessing GitHub APIs, and
   manipulating local release data"; dispatch lives here, the install
   pipeline in pipeline/. */

#include "cmd.h"
#include "doctor.h"
#include "fs.h"
#include "http.h"
#include "json.h"
#include "manifest.h"
#include "path.h"
#include "pipeline.h"
#include "provider.h"
#include "repo.h"
#include "state.h"
#include "txn.h"
#include "ui.h"
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include "main.h"

// stateful commands run journal recovery under the lock before anything else
// touches state; false blocks the command, and the blocked attempt joins
// the operation history like every other refused command
static bool recover_gate(const state_ctx *st, const cmd_opts *o,
    const char *cmd)
{
    if (state_recover_gate(st, o->quiet, o->json)) return true;
    oplog_append(st, cmd, "-", "-",
        "Blocked by pending transaction recovery; manual recovery "
        "required.");
    return false;
}

// state load + proxy arm (config "proxy" drives every later request)
static bool state_open_net(state_ctx *st)
{
    if (!state_open(st)) return false;
    http_set_proxy(st->proxy);
    return true;
}

// R05: query commands surface broken state and pending recovery instead
// of repairing or recovering them; stdout (JSON included) stays clean.
static bool lock_repair(state_ctx *st)
{
    if (!state_lock(st->root)) return false;
    state_repair_corrupt(st);
    return true;
}

// the bare help page: commands plus global flags; command pages are built by
// run_help. Usage errors get this on stderr too.
static void global_help(FILE *f)
{
    const char *const *l;
    put_s(f, UI_NONE, "Usage: ape-get.com [command] [options]");
    put_s(f, UI_NONE, "%s", "");
    put_s(f, UI_BOLD, "Commands:");
    for (l = cmd_help_commands(); *l != NULL; l++)
        put_s(f, UI_NONE, "%s", *l);
    put_s(f, UI_NONE, "%s", "");
    put_s(f, UI_BOLD, "Global options:");
    for (l = cmd_global_options(); *l != NULL; l++)
        put_s(f, UI_NONE, "%s", *l);
    fputs("\n", f);
    put_s(f, UI_ITALIC,
        "Run 'ape-get help <command>' for a command's options.");
}

int run_help(const char *cmd)
{
    if (cmd == NULL) { global_help(stdout); return 0; }
    const cmd_help *h = cmd_lookup(cmd);
    if (h != NULL) {
        const char *const *l;
        // aliases print the canonical command's page: upgrade --help is
        // byte-identical to update --help, as the usage-error path already does
        put_s(stdout, UI_NONE, "Usage: ape-get.com %s %s",
            cmd_usage_name(h->id), h->usage);
        if (h->options[0] != NULL) {
            put_out("");
            put_s(stdout, UI_BOLD, "Options:");
            for (l = h->options; *l != NULL; l++)
                put_s(stdout, UI_NONE, "%s", *l);
        }
        put_out("");
        put_s(stdout, UI_DIM,
            "Common: --json --quiet --yes --colour <auto|always|never>");
        return 0;
    }
    put_s(stderr, UI_ERROR, "Unknown command: %s", cmd);
    global_help(stderr);
    return 2;
}

int main(int argc, char **argv)
{
    // a disconnected peer must surface as EPIPE from the write paths, not as a fatal signal
    signal(SIGPIPE, SIG_IGN);
    cmd_opts o;
    self_set_argv0(argc > 0 ? argv[0] : NULL);
    cmd_parse(argv + 1, argc - 1, &o);
    ui_setup(o.colour, o.json);
    ui_suppress_progress(o.quiet || o.json || o.no_progress);

    // an --colour override that changes the outcome says so once, plain:
    // a flag whose effect is invisible should at least be acknowledged
    if (o.colour != NULL && !o.json && !o.quiet) {
        bool auto_on = ui_default_on(stderr);
        bool eff = !strcmp(o.colour, "never") ? false
                 : !strcmp(o.colour, "always") ? true : auto_on;
        if (eff != auto_on)
            ui_line(stderr, UI_NONE,
                eff ? "Colour forced on (--colour always)."
                    : "Colour disabled (--colour never).");
    }

    if (o.help) // C-only flag: --help / <command> --help
        return run_help(o.nargs >= 1 ? o.args[0] : NULL);

    if (o.usage_error) {
        // the argument doubles as the usage-error message (unknown command,
        // unknown option or missing value); a blank one — missing or extra
        // positional argument — prints the command's own help, but a
        // request that was never performed must not exit 0
        if (o.usage_msg != NULL && o.usage_msg[0] == '\0') {
            run_help(cmd_usage_name(o.id));
            return 2;
        }
        const char *arg = NULL;
        if (o.usage_msg != NULL) {
            arg = o.usage_msg;
        } else if (o.nargs > 0) {
            static char joined[512];
            joined[0] = '\0';
            for (int k = 0; k < o.nargs; k++) {
                if (k) strncat(joined, " ", sizeof(joined) - strlen(joined) - 1);
                strncat(joined, o.args[k], sizeof(joined) - strlen(joined) - 1);
            }
            arg = joined;
        }
        if (arg != NULL && arg[0] != '\0')
            put_s(stderr, UI_ERROR, "Unknown command: %s", arg);
        global_help(stderr);
        return 2;
    }

    switch (o.id) {
    case CMD_ABOUT:
        return run_about();
    case CMD_COMPLETIONS:
        return run_completions(&o);
    case CMD_DOCTOR:
        return doctor_run(&o);
    case CMD_SEARCH: {
        // search is a query over the providers, not the local state
        state_set_query();
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        state_report_query(&st, o.quiet);
        return cmd_search(&o, &st);
    }
    case CMD_INSTALL: {
        if (o.nargs < 2) return run_help("install");
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        if (!lock_repair(&st)) return 1;
        if (!recover_gate(&st, &o, "install")) return 1;
        state_ensure(&st, o.quiet, o.json);
        int rv = pipeline_install(&o, &st);
        if (rv == 0) pipeline_self_after(&o, &st);
        return rv;
    }
    case CMD_UPDATE: {
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        if (!lock_repair(&st)) return 1;
        if (!recover_gate(&st, &o, "update")) return 1;
        state_ensure(&st, o.quiet, o.json);
        int rv = o.all ? pipeline_update_all(&o, &st)
                    : pipeline_update(&o, &st);
        if (rv == 0) pipeline_self_after(&o, &st);
        return rv;
    }
    case CMD_UNINSTALL: {
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        if (!lock_repair(&st)) return 1;
        if (!recover_gate(&st, &o, "uninstall")) return 1;
        state_ensure(&st, o.quiet, o.json);
        int rv = uninstall_run(&o, &st);
        if (rv == 0) pipeline_self_after(&o, &st);
        return rv;
    }
    case CMD_UNTRUST: {
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        if (!lock_repair(&st)) return 1;
        if (!recover_gate(&st, &o, "untrust")) return 1;
        state_ensure(&st, o.quiet, o.json);
        return untrust_run(&o, &st);
    }
    case CMD_CONFIG: {
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        if (!lock_repair(&st)) return 1;
        if (!recover_gate(&st, &o, "config")) return 1;
        state_ensure(&st, o.quiet, o.json);
        return config_run(&o, &st);
    }
    case CMD_ADOPT: {
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        if (!lock_repair(&st)) return 1;
        if (!recover_gate(&st, &o, "adopt")) return 1;
        state_ensure(&st, o.quiet, o.json);
        int rv = adopt_run(&o, &st);
        if (rv == 0) pipeline_self_after(&o, &st);
        return rv;
    }
    case CMD_LIST: {
        // query: no lock, no repair, no recovery, no bootstrap; the
        // documented first-run registration still runs and serialises
        // on the lock for its own writes
        state_set_query();
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        state_report_query(&st, o.quiet);
        int rv = list_run(&o, &st);
        if (rv == 0) pipeline_self_after(&o, &st);
        return rv;
    }
    case CMD_INFO: {
        // info is a query: no lock, no ensure
        state_set_query();
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        state_report_query(&st, o.quiet);
        return info_run(&o, &st);
    }
    case CMD_ROLLBACK: {
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        if (!lock_repair(&st)) return 1;
        if (!recover_gate(&st, &o, "rollback")) return 1;
        state_ensure(&st, o.quiet, o.json);
        int rv = rollback_run(&o, &st);
        if (rv == 0) pipeline_self_after(&o, &st);
        return rv;
    }
    case CMD_PROVIDERS: {
        // providers list/show are queries; the rest mutate the registry
        bool query = o.nargs >= 2
            && (!strcmp(o.args[1], "list") || !strcmp(o.args[1], "show"));
        if (query) state_set_query();
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        if (query) {
            state_report_query(&st, o.quiet);
            return providers_run(&o, &st);
        }
        if (!lock_repair(&st)) return 1;
        if (!recover_gate(&st, &o, "providers")) return 1;
        state_ensure(&st, o.quiet, o.json);
        int rv = providers_run(&o, &st);
        if (rv == 0) pipeline_self_after(&o, &st);
        return rv;
    }
    case CMD_LOCK:
    case CMD_UNLOCK: {
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        if (!lock_repair(&st)) return 1;
        if (!recover_gate(&st, &o,
                o.id == CMD_LOCK ? "lock" : "unlock")) return 1;
        state_ensure(&st, o.quiet, o.json);
        int rv = lock_run(&o, &st, o.id == CMD_LOCK);
        if (rv == 0) pipeline_self_after(&o, &st);
        return rv;
    }
    default:
        // help [command]: args[1] picks the page (C-only layout)
        return run_help(o.nargs >= 2 ? o.args[1] : NULL);
    }
}
