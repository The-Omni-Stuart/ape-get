// setup: turn the file that was just downloaded into the command a shell
// finds. Two things make that true, and setup does both — the copy in the
// install root with the self manifest that lets `update ape-get` keep it
// current, and the one line in the shell's own file that puts that directory
// in front of PATH. The copy goes through the same self-integration path the
// install commands use, so there is one journal and one rule about never
// overwriting a file that is not ours.

#include "main.h"

#include "fs.h"
#include "json.h"
#include "path.h"
#include "pipeline.h"
#include "platform.h"
#include "state.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// Which system to lay out for: the one the kernel reports, unless the flag
// corrects it. A word that names no known system is a mistake in the command
// line, so it is answered as one (rc 2) rather than guessed around.
static int settle_os(const cmd_opts *o, platform_os *out)
{
    if (o->override_platform == NULL) *out = platform_current();
    else if (!platform_os_parse(o->override_platform, out)) {
        put_s(stderr, UI_ERROR, "Invalid --override-platform value: %s"
            " (linux or macos)", o->override_platform);
        return 2;
    }
    if (*out == OS_WINDOWS) {
        // The layout is known but nothing in this build can write it; saying
        // which build does beats implying the flag achieved something.
        put_fail("setup does not install for Windows from this build yet;"
            " use the Windows build's setup.");
        return 1;
    }
    if (*out == OS_UNKNOWN) {
        char desc[128];
        platform_describe(desc, sizeof desc);
        put_s(stderr, UI_ERROR, "Could not identify this system: it reports"
            " \"%s\". If you know which one it is, re-run with"
            " --override-platform <linux|macos>.", desc);
        return 1;
    }
    return 0;
}

// The system in the words a person reads. The architecture is always this
// machine's; only the name can be overridden, and a plan that hides which
// half came from them is indistinguishable from a bug.
static char *system_line(platform_os chosen, platform_os seen)
{
    char desc[128];
    platform_describe(desc, sizeof desc);
    if (chosen == seen) return strdup(desc);
    const char *space = strchr(desc, ' ');
    return path_fmt("%s%s", platform_os_name(chosen),
        space != NULL ? space : "");
}

static const char *state_words(self_state s, bool dry)
{
    switch (s) {
    case SELF_OK: return "already installed";
    case SELF_BLOCKED: return "something else is in the way";
    default: return dry ? "would be written" : "to be written";
    }
}

// What setup will change, said before any of it happens.
static void show_plan(const char *title, const char *sys, const char *target,
    self_state state, const char *dir, const char *path_file, bool listed,
    bool effective, bool dry)
{
    put_s(stdout, UI_BOLD, "%s", title);
    put_s(stdout, UI_NONE, "  System:  %s", sys);
    put_s(stdout, UI_NONE, "  Command: %s (%s)", target,
        state_words(state, dry));
    if (path_file == NULL)
        put_s(stdout, UI_NONE, "  PATH:    no file to write here");
    else if (listed)
        put_s(stdout, UI_NONE, "  PATH:    %s is listed in %s", dir, path_file);
    else if (effective)
        put_s(stdout, UI_NONE, "  PATH:    %s is already on PATH", dir);
    else
        put_s(stdout, UI_NONE, "  PATH:    %s is not in %s (%s)", dir,
            path_file, dry ? "would add the line" : "setup adds the line");
}

static void report_json(const char *sys, const char *target, const char *dir,
    const char *path_file, const char *path_action, bool installed,
    bool dry_run)
{
    jbuf b = {0};
    bool first = true;
    jb_lit(&b, "{");
    jb_kv(&b, "command", "setup", &first);
    jb_kv(&b, "system", sys, &first);
    jb_kv(&b, "target", target, &first);
    jb_kv(&b, "install_root", dir, &first);
    jb_kv(&b, "path_file", path_file, &first);
    jb_kv(&b, "path", path_action, &first);
    jb_lit(&b, ",\"installed\":");
    jb_lit(&b, installed ? "true" : "false");
    if (dry_run) jb_lit(&b, ",\"dry_run\":true");
    jb_lit(&b, "}");
    if (b.p != NULL) put_out(b.p);
    jb_free(&b);
}

int setup_run(const cmd_opts *o, state_ctx *st)
{
    platform_os seen = platform_current(), os;
    char *target = NULL, *path_file = NULL, *sys = NULL, *q = NULL;
    const char *dir = NULL, *path_action = "none";
    self_state state = SELF_NONE;
    int out = 0;
    bool added = false, plain = false, copied = false;

    int rv = settle_os(o, &os);
    if (rv != 0) return rv;
    if (!self_enabled()) {
        put_fail("Setup installs ape-get itself, and this run has"
            " self-integration turned off (the APEGET_SELF build option, or"
            " APEGET_NO_SELF=1). Nothing was changed.");
        return 1;
    }

    state = self_install_status(st, &target);
    if (target == NULL) {
        put_fail("Cannot work out where to install ape-get; is $HOME set?");
        out = 1;
        goto done;
    }
    // st->install_root is where the copy really goes, config override and all,
    // so it is the directory PATH must name — not the platform default
    dir = st->install_root;
    path_file = platform_path_target(os);
    bool listed = platform_path_registered(os, dir);
    bool effective = !listed && platform_path_effective(dir);
    bool path_done = listed || effective;   // true: no line for setup to write
    sys = system_line(os, seen);
    plain = !o->json && !o->quiet;
    copied = state == SELF_NONE;   // exactly what self-integration will copy
    path_action = listed ? "already" : effective ? "effective" : "none";

    if (state == SELF_OK && path_done) {
        // A re-run has nothing left to do, and saying so plainly is the point:
        // setup is also the command to reach for when a PATH line goes missing.
        if (plain) {
            put_s(stdout, UI_NONE, "Already installed at %s.", target);
            if (listed)
                put_s(stdout, UI_NONE, "%s already lists %s.", path_file, dir);
            else
                put_s(stdout, UI_NONE, "%s is already on PATH.", dir);
        }
        goto done;
    }

    if (plain)
        show_plan(o->dry_run ? "ape-get setup --dry-run" : "ape-get setup",
            sys, target, state, dir, path_file, listed, effective, o->dry_run);

    if (state == SELF_BLOCKED) {
        // Nothing here can fix this without replacing a file that is not this
        // one, and ape-get never does that. Nor is PATH worth touching: a line
        // for that directory would point the shell at somebody else's command.
        put_s(stderr, UI_ERROR, "%s already exists and is not this ape-get."
            " ape-get never overwrites it: move it aside and run setup again.",
            target);
        out = 1;
        goto done;
    }

    if (o->dry_run) {
        if (plain) put_s(stdout, UI_DIM, "Nothing was written (--dry-run).");
        if (!path_done) path_action = "planned";
        goto done;
    }

    // --json changes what is printed, never whether the run is confirmed: a
    // scripted setup must say -y, and a piped one is stopped here with the
    // hint on stderr, where stdout stays parseable.
    q = path_done ? path_fmt("Install ape-get at %s?", target)
        : path_fmt("Install ape-get at %s and list %s in %s?",
            target, dir, path_file != NULL ? path_file : "your PATH");
    if (!confirm_action(o, q != NULL ? q : "Install ape-get?", false)) {
        put_fail("Setup declined. Nothing has been changed.");
        out = 1;
        goto done;
    }

    // self-integration has its own closing note, written for a run that came
    // from an install command: it tells the user to fix PATH, which setup has
    // just planned to fix. So it runs quiet and setup says the same thing in
    // the order the user needs. Warnings still come through unsilenced.
    cmd_opts quieter = *o;
    quieter.quiet = 1;
    pipeline_self_after(&quieter, st);

    state = self_install_status(st, NULL);
    if (state != SELF_OK) {
        if (state == SELF_BLOCKED)
            put_s(stderr, UI_ERROR, "%s appeared during the run and is not"
                " this ape-get; nothing was changed.", target);
        else
            put_s(stderr, UI_ERROR, "ape-get could not be installed; the"
                " reason is above. If a transaction is pending, run"
                " ape-get doctor --heal.");
        out = 1;
        goto done;
    }

    if (!path_done) {
        char err[256] = "";
        if (!platform_path_register(os, dir, err, sizeof err)) {
            // The command is installed and works by full path, so this is
            // reported as a failure with the remedy, not a silent half-doing.
            put_s(stderr, UI_ERROR, "%s", err[0] != '\0' ? err
                : "Could not update PATH.");
            put_s(stderr, UI_WARN, "ape-get is installed at %s; add %s to your"
                " PATH to use it by name.", target, dir);
            out = 1;
            goto done;
        }
        added = true;
        path_action = "added";
    }

    if (plain) {
        put_s(stdout, UI_NONE, "ape-get installed at %s.", target);
        if (added)
            put_s(stdout, UI_NONE, "Added %s to PATH in %s.", dir, path_file);
        // the download case is what setup exists for, so it is worth saying
        // once, and only when this run is what put the copy there
        if (copied)
            put_s(stdout, UI_DIM, "If you ran this from a download, you can"
                " safely delete that file.");
        // a shell that already has the directory needs no new one; one that
        // is waiting on a freshly written file does
        if (!effective)
            put_s(stdout, UI_NONE, "Open a new terminal, then check with:"
                " ape-get about.");
    }

done:
    if (out == 0 && o->json)
        report_json(sys, target, dir, path_file, path_action,
            state == SELF_OK, o->dry_run);
    free(q);
    free(path_file);
    free(sys);
    free(target);
    return out;
}
