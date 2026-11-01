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
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include "main.h"

// stateful commands run journal recovery under the lock before anything else
// touches state; false blocks the command
static bool recover_gate(const state_ctx *st, const cmd_opts *o)
{
    return state_recover_gate(st, o->quiet, o->json);
}

// state load + proxy arm (config "proxy" drives every later request)
static bool state_open_net(state_ctx *st)
{
    if (!state_open(st)) return false;
    http_set_proxy(st->proxy);
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
        put_s(stdout, UI_NONE, "Usage: ape-get.com %s %s", cmd, h->usage);
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
        // the argument doubles as the usage-error message (unknown command
        // or missing value); a blank one — arity errors — prints the dump alone
        if (o.id == CMD_UPDATE && o.usage_msg != NULL
            && o.usage_msg[0] == '\0')
            return run_help("update"); // bare update: styled help
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
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        return cmd_search(&o, &st);
    }
    case CMD_INSTALL: {
        if (o.nargs < 2) return run_help("install");
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        if (!state_lock(st.root)) return 1;
        if (!recover_gate(&st, &o)) return 1;
        state_ensure(&st, o.quiet, o.json);
        int rv = pipeline_install(&o, &st);
        pipeline_self_after(&o, &st);
        return rv;
    }
    case CMD_UPDATE: {
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        if (!state_lock(st.root)) return 1;
        if (!recover_gate(&st, &o)) return 1;
        state_ensure(&st, o.quiet, o.json);
        int rv = o.all ? pipeline_update_all(&o, &st)
                    : pipeline_update(&o, &st);
        pipeline_self_after(&o, &st);
        return rv;
    }
    case CMD_UNINSTALL: {
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        if (!state_lock(st.root)) return 1;
        if (!recover_gate(&st, &o)) return 1;
        state_ensure(&st, o.quiet, o.json);
        int rv = uninstall_run(&o, &st);
        pipeline_self_after(&o, &st);
        return rv;
    }
    case CMD_ADOPT: {
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        if (!state_lock(st.root)) return 1;
        if (!recover_gate(&st, &o)) return 1;
        state_ensure(&st, o.quiet, o.json);
        int rv = adopt_run(&o, &st);
        pipeline_self_after(&o, &st);
        return rv;
    }
    case CMD_LIST: {
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        if (!state_lock(st.root)) return 1;
        if (!recover_gate(&st, &o)) return 1;
        state_ensure(&st, o.quiet, o.json);
        return list_run(&o, &st);
    }
    case CMD_INFO: {
        // info is not stateful: no lock, no ensure
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        return info_run(&o, &st);
    }
    case CMD_ROLLBACK: {
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        if (!state_lock(st.root)) return 1;
        if (!recover_gate(&st, &o)) return 1;
        state_ensure(&st, o.quiet, o.json);
        int rv = rollback_run(&o, &st);
        pipeline_self_after(&o, &st);
        return rv;
    }
    case CMD_PROVIDERS: {
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        if (!state_lock(st.root)) return 1;
        if (!recover_gate(&st, &o)) return 1;
        state_ensure(&st, o.quiet, o.json);
        int rv = providers_run(&o, &st);
        pipeline_self_after(&o, &st);
        return rv;
    }
    case CMD_LOCK:
    case CMD_UNLOCK: {
        state_ctx st;
        if (!state_open_net(&st)) return 2;
        if (!state_lock(st.root)) return 1;
        if (!recover_gate(&st, &o)) return 1;
        state_ensure(&st, o.quiet, o.json);
        int rv = lock_run(&o, &st, o.id == CMD_LOCK);
        pipeline_self_after(&o, &st);
        return rv;
    }
    default:
        // help [command]: args[1] picks the page (C-only layout)
        return run_help(o.nargs >= 2 ? o.args[1] : NULL);
    }
}
