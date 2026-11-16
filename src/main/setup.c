// The setup command: turn the portable executable the user downloaded into
// the installed one. What that means on each OS comes from the platform
// module; this file is the order of steps and the words said to the user.

#include "setup.h"

#include "fs.h"
#include "path.h"
#include "platform.h"
#include "prompt.h"
#include "ui.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <unistd.h>

// What PATH will do, in the few words the plan line has room for.
static char *path_sentence(bool want_path, bool refused_by_flag,
    bool registered, const char *dir, const char *path_target)
{
    if (!want_path)
        return strdup(refused_by_flag ? "left alone (--no-path)"
            : "left alone");
    if (path_target == NULL) return strdup("unavailable");
    if (registered) return path_fmt("already registered in %s", path_target);
    return path_fmt("add %s to %s", dir, path_target);
}

// One trimmed line from stdin, NULL when there is none to read.
static char *read_answer(void)
{
    char ln[4096];
    fflush(stderr);
    if (fgets(ln, sizeof ln, stdin) == NULL) return NULL;
    size_t a = 0, z = strlen(ln);
    while (a < z && (ln[a] == ' ' || ln[a] == '\t')) a++;
    while (z > a && (ln[z-1] == '\n' || ln[z-1] == '\r'
        || ln[z-1] == ' ' || ln[z-1] == '\t')) z--;
    ln[z] = '\0';
    return strdup(ln + a);
}

// Where the command goes and whether PATH may be touched, asked of whoever is
// at the terminal. The flags answer both when given, and a run whose stdin is
// a pipe is asked nothing: it cannot reply, and confirm_action is where such
// a run is stopped.
static void ask_layout(const cmd_opts *o, char **dir, bool *want_path)
{
    if (!isatty(fileno(stdin)) || o->assume_yes || o->dry_run) return;
    if (o->prefix == NULL) {
        ui_msg(stderr, UI_NONE, "Installation directory [%s]:", *dir);
        char *asked = read_answer();
        char *moved = asked != NULL && *asked != '\0' ? strdup(asked) : NULL;
        if (moved != NULL) {
            free(*dir);
            *dir = moved;
        }
        free(asked);
    }
    if (*want_path) {
        ui_msg(stderr, UI_NONE, "Add it to PATH? [Y/n]:");
        char *ans = read_answer();
        if (ans != NULL && (!strcasecmp(ans, "n") || !strcasecmp(ans, "no")))
            *want_path = false;
        free(ans);
    }
}

// The systems named in the menu, in the words a person uses for them.
static const char *system_name(platform_os os)
{
    switch (os) {
    case OS_LINUX: return "Linux";
    case OS_MACOS: return "macOS";
    case OS_WINDOWS: return "Windows";
    default: return "an unrecognised system";
    }
}

// Every system setup knows how to lay out, numbered so the answer can be one
// keystroke. Whoever is being shown this has just said the detected system is
// not theirs, so each line says what would change there.
static void list_supported(void)
{
    ui_msg(stderr, UI_NONE, "ape-get setup installs on:");
    ui_msg(stderr, UI_NONE, "1  Linux   - this file as ~/.local/bin/ape-get,"
        " with that directory listed in ~/.profile");
    ui_msg(stderr, UI_NONE, "2  macOS   - this file as ~/.local/bin/ape-get,"
        " with that directory listed in ~/.zshrc");
    ui_msg(stderr, UI_NONE, "3  Windows - this file as"
        " %%LOCALAPPDATA%%\\Programs\\ape-get\\ape-get.com, with that"
        " directory added to the user's Path");
    ui_msg(stderr, UI_NONE, "4  other (manual installation)");
}

// How to do setup's job without setup, spelled out for both families of
// system: the one detected has just been denied, so nothing narrows the
// choice, and a recipe for the wrong one is worth more than no recipe.
static void manual_steps(const char *self)
{
    const char *file = self != NULL ? self : "the ape-get file";

    ui_msg(stderr, UI_NONE,
        "Put this file in a directory that is already on your PATH:");
    ui_msg(stderr, UI_NONE, "  %s", file);
    fputs("\n", stderr);
    ui_msg(stderr, UI_NONE, "On Linux or macOS:");
    ui_msg(stderr, UI_NONE, "  mkdir -p \"$HOME/.local/bin\"");
    ui_msg(stderr, UI_NONE, "  cp \"%s\" \"$HOME/.local/bin/ape-get\"", file);
    ui_msg(stderr, UI_NONE, "  chmod 755 \"$HOME/.local/bin/ape-get\"");
    ui_msg(stderr, UI_NONE, "  echo 'export PATH=\"$HOME/.local/bin:$PATH\"'"
        " >> ~/.profile    # or ~/.zshrc on a macOS login shell");
    fputs("\n", stderr);
    ui_msg(stderr, UI_NONE, "On Windows:");
    ui_msg(stderr, UI_NONE, "  mkdir \"%%LOCALAPPDATA%%\\Programs\\ape-get\""
        "");
    ui_msg(stderr, UI_NONE, "  copy \"%s\""
        " \"%%LOCALAPPDATA%%\\Programs\\ape-get\\ape-get.com\"", file);
    ui_msg(stderr, UI_NONE, "  Then add that directory to your user Path:"
        " System, then Edit environment variables for your account, then"
        " Path, then New.");
    ui_msg(stderr, UI_NONE, "  From a command prompt the same is:"
        " reg add HKCU\\Environment /v Path /t REG_EXPAND_SZ"
        " /d \"<your current Path>;%%LOCALAPPDATA%%\\Programs\\ape-get\" /f");
    fputs("\n", stderr);
    ui_msg(stderr, UI_NONE, "In either case open a new terminal and check with:"
        " ape-get about");
}

// The answer is which system to lay out for, so it is not a question that can
// be skipped: 4 hands over to doing it by hand, and an answer outside the list
// stops the run, because installing for a system nobody chose is worse than
// installing nothing.
static void pick_system(const char *self, platform_os *chosen, bool *install)
{
    list_supported();
    ui_msg(stderr, UI_NONE, "Which of these is it? [1-4]:");
    char *ans = read_answer();
    *install = ans != NULL
        && (!strcmp(ans, "1") || !strcmp(ans, "2") || !strcmp(ans, "3"));
    if (ans != NULL && !strcmp(ans, "1")) {
        *chosen = OS_LINUX;
    } else if (ans != NULL && !strcmp(ans, "2")) {
        *chosen = OS_MACOS;
    } else if (ans != NULL && !strcmp(ans, "3")) {
        *chosen = OS_WINDOWS;
    } else if (ans != NULL && !strcmp(ans, "4")) {
        manual_steps(self);
    } else {
        ui_msg(stderr, UI_ERROR, "Not one of those. Nothing has been changed.");
    }
    free(ans);
}

// Which system to install for: the one the kernel reported once its name has
// been confirmed, or the one picked from the list after it has been denied.
// Only a terminal can be asked; a piped run is stopped at the confirmation
// before anything is written, and --yes has declined to be asked.
static void settle_system(const cmd_opts *o, platform_os seen,
    const char *self, platform_os *chosen, bool *install)
{
    char desc[128];
    platform_describe(desc, sizeof desc);
    *chosen = seen;
    *install = true;

    if (seen == OS_UNKNOWN) {
        ui_msg(stderr, UI_ERROR,
            "Could not identify this system: it reports \"%s\".", desc);
        if (!isatty(fileno(stdin)) || o->assume_yes || o->dry_run) {
            list_supported();
            manual_steps(self);   // no terminal to ask, so say everything
            *install = false;
            return;
        }
        pick_system(self, chosen, install);
        return;
    }
    if (!isatty(fileno(stdin)) || o->assume_yes || o->dry_run) return;

    ui_msg(stderr, UI_NONE, "This looks like %s. Is that the system you are"
        " installing on? [Y/n]:", desc);
    char *ans = read_answer();
    bool denied = ans != NULL
        && (!strcasecmp(ans, "n") || !strcasecmp(ans, "no"));
    free(ans);
    if (!denied) return;
    pick_system(self, chosen, install);
}

// The system named in the plan. After the menu that is not necessarily the one
// the kernel reported: the architecture is shared, the name is not, and a
// substitution nobody was told about is indistinguishable from a bug.
static void platform_line(platform_os chosen, platform_os seen,
    char *buf, size_t cap)
{
    char seen_line[64], arch[32];
    const char *space;

    platform_describe(seen_line, sizeof seen_line);
    space = strchr(seen_line, ' ');
    snprintf(arch, sizeof arch, "%s", space != NULL ? space : "");
    if (chosen == seen) {
        snprintf(buf, cap, "%s", seen_line);
        return;
    }
    // The precisions are the bounds snprintf is being given to work with: the
    // pieces are all short, but only spelled out can that be shown.
    snprintf(buf, cap, "%.20s%.31s - chosen by you; this system reports %.63s",
        system_name(chosen), arch, seen_line);
}

// Byte-for-byte: is the file already in place this same executable? A size
// check comes first so a foreign file is rejected without reading it all.
static bool files_match(const char *a, const char *b)
{
    struct stat sa, sb;
    if (stat(a, &sa) != 0 || stat(b, &sb) != 0) return false;
    if (!S_ISREG(sa.st_mode) || !S_ISREG(sb.st_mode)) return false;
    if (sa.st_size != sb.st_size) return false;
    FILE *fa = fopen(a, "rb"), *fb = fopen(b, "rb");
    if (fa == NULL || fb == NULL) {
        if (fa != NULL) fclose(fa);
        if (fb != NULL) fclose(fb);
        return false;
    }
    bool ok = true;
    char ba[8192], bb[8192];
    size_t na;
    while (ok && (na = fread(ba, 1, sizeof ba, fa)) > 0) {
        size_t nb = fread(bb, 1, sizeof bb, fb);
        ok = nb == na && memcmp(ba, bb, na) == 0;
    }
    fclose(fa);
    fclose(fb);
    return ok;
}

void setup_portable_notice(const cmd_opts *o)
{
    if (o->json || o->quiet) return;
    if (o->id == CMD_SETUP || o->id == CMD_COMPLETIONS) return;
    if (!isatty(fileno(stderr))) return;
    if (platform_self_is_installed()) return;
    ui_msg(stderr, UI_NONE, "ape-get is running in portable mode.");
    ui_msg(stderr, UI_NONE, "Run 'ape-get setup' to install it.");
}

int setup_run(const cmd_opts *o)
{
    // A path typed after the command reads like a prefix and is not one;
    // taking it quietly would install somewhere nobody asked for. The command
    // word itself is the only positional setup recognizes (--setup has none).
    int first = o->nargs > 0 && !strcmp(o->args[0], "setup") ? 1 : 0;
    if (o->nargs - first > 0) {
        ui_msg(stderr, UI_ERROR,
            "setup takes no arguments; use --prefix to choose a directory.");
        return 2;
    }

    platform_os seen = platform_current();
    char *self = platform_self_exe();
    platform_os os;
    bool install;
    settle_system(o, seen, self, &os, &install);
    if (!install) {
        free(self);
        return 1;
    }

    char *dir = o->prefix != NULL ? strdup(o->prefix)
                                  : platform_install_root_default_for(os);
    if (dir == NULL) {
        ui_msg(stderr, UI_ERROR,
            "Cannot work out where to install ape-get; pass --prefix.");
        return 1;
    }
    bool want_path = !o->no_path;
    ask_layout(o, &dir, &want_path);

    // Only an absolute directory can be installed to: a relative one would be
    // found against whatever directory the command happened to run from, and a
    // PATH entry has to name the same place however the next shell starts.
    bool absolute = os == OS_WINDOWS
        ? isalpha((unsigned char)dir[0]) && dir[1] == ':'
        : dir[0] == '/';
    if (!absolute) {
        ui_msg(stderr, UI_ERROR,
            "Install directory must be an absolute path, not \"%s\".", dir);
        return 2;
    }

    char *command = path_join(dir, platform_command_name(os));
    char *path_target = want_path ? platform_path_target(os) : NULL;
    bool registered = want_path && platform_path_registered(os, dir);
    char *path_line = path_sentence(want_path, o->no_path != 0, registered,
        dir, path_target);
    if (command == NULL || path_line == NULL) {
        ui_msg(stderr, UI_ERROR, "Cannot name the command to install.");
        return 1;
    }

    if (o->dry_run) {
        char desc[256];
        platform_line(os, seen, desc, sizeof desc);
        free(self);
        printf("Dry run: would install ape-get.\n");
        printf("  Platform: %s\n", desc);
        printf("  Directory: %s\n", dir);
        printf("  Command: %s\n", command);
        printf("  PATH: %s\n", path_line);
        puts("No changes were made.");
        return 0;
    }

    if (self == NULL) {
        ui_msg(stderr, UI_ERROR,
            "Cannot work out which file is being installed.");
        return 1;
    }

    // The plan is what the user is being asked to approve, so it belongs on
    // the record before the question rather than after the answer.
    char desc[256];
    platform_line(os, seen, desc, sizeof desc);
    ui_msg(stderr, UI_NONE, "Platform: %s", desc);
    ui_msg(stderr, UI_NONE, "Install directory: %s", dir);
    ui_msg(stderr, UI_NONE, "Command: %s", command);
    ui_msg(stderr, UI_NONE, "PATH: %s", path_line);

    // Whatever already answers to that name is either an older ape-get or a
    // stranger, and only the stranger earns a warning before it is replaced.
    bool present = fs_is_regular(command);
    bool same = present && files_match(self, command);
    if (present && !same)
        ui_msg(stderr, UI_WARN,
            "Warning: %s already exists and is not this executable.",
            command);

    const char *tail = want_path ? " and add it to PATH" : "";
    char *question = present && !same
        ? path_fmt("Replace the existing file at %s%s?", command, tail)
        : path_fmt("Install ape-get at %s%s?", command, tail);
    bool go = confirm_action(o, question != NULL ? question : "", false);
    free(question);
    if (!go) return 1;

    // The same bytes already in place are an install, not a failure to make
    // one; rewriting them would only say so in the past tense.
    if (same) {
        ui_msg(stderr, UI_NONE, "%s is already installed.", command);
    } else {
        char *staged = path_fmt("%s.tmp-setup", command);
        if (!fs_mkdir_p(dir) || staged == NULL || !fs_copy_file(self, staged)
            || chmod(staged, 0755) != 0 || !fs_rename(staged, command)) {
            if (staged != NULL) remove(staged);
            ui_msg(stderr, UI_ERROR, "Could not install %s.", command);
            return 1;
        }
        ui_msg(stderr, UI_OK, "Installed %s.", command);
    }
    free(self);
    self = NULL;

    if (want_path) {
        if (registered) {
            ui_msg(stderr, UI_NONE, "%s is already registered in PATH.", dir);
        } else {
            char err[256];
            if (!platform_path_register(os, dir, err, sizeof err)) {
                ui_msg(stderr, UI_ERROR, "Could not add %s to PATH: %s",
                    dir, err);
                return 1;
            }
            ui_msg(stderr, UI_OK, "Added %s to PATH in %s.", dir,
                path_target);
            ui_msg(stderr, UI_NONE,
                "Open a new terminal, then run 'ape-get'.");
        }
    }
    return 0;
}
