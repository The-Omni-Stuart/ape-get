#include "cmd.h"
#include <stddef.h>
#include <string.h>

/* The original CmdParser's grammar: exact-match flags, "--opt value"
   only (no '=' form); anything unrecognised flows into positionals
   . C-only flags join the same list. */

#define VALV(o, f)                                     \
    do {                                               \
        if (++i >= argc) {                             \
            out->usage_error = 1;                      \
            out->usage_msg = "Missing value for " f;   \
            return;                                    \
        }                                              \
        o = argv[i];                                   \
    } while (0)

// Help data, rendered by main.c; the cmd module never styles — the deps
// graph keeps it ui-free at level 0.

static const char *const HELP_COMMANDS[] = {
    "  search [name] - search for a release by name",
    "  install [name] - install provider:owner/repo,"
    " or a bare owner/repo across providers",
    "  uninstall [name] - uninstall the release by name",
    "  update [name] - update one release, or use --all",
    "  list - list all installed releases",
    "  info [name] - show details for an installed release",
    "  providers - list, add, update, remove, or recover provider instances",
    "  rollback [name] - restore the latest backup",
    "  doctor - diagnose state and orphaned installs",
    "  adopt [name] - adopt an existing Linux install",
    "  lock [name] - keep a release at its installed version",
    "  unlock [name] - let a locked release update again",
    "  about - about ape-get: version, licence, source",
    "  completions <bash|zsh> - print a shell completion script",
    "  help - display this help message",
    NULL
};

static const char *const GLOBAL_OPTIONS[] = {
    "  --help - show help for the command",
    "  --json - machine-readable output",
    "  --quiet - suppress progress messages",
    "  -y, --yes - assume yes for confirmation prompts",
    "  --colour <auto|always|never> - terminal styling (default auto)",
    "  --no-progress - never render progress bars",
    "  --experimental-compat - enable the compatibility path for this run",
    NULL
};

static const char *const OPTS_NONE[] = { NULL };

static const char *const OPTS_SEARCH[] = {
    "  --provider <name> - use a configured provider for search or install",
    NULL
};

static const char *const OPTS_INSTALL[] = {
    "  --provider <name> - use a configured provider for search or install",
    "  --version <tag> - install or update to a specific release",
    "  --asset <name|index> - choose a release asset non-interactively",
    "  --kind <kind> - force appimage, portable-exe, ape, nsis, msi, or dmg",
    "  --name <command> - command name for a portable package",
    "  --no-command - install without creating a command",
    "  --desktop - create a desktop entry (Linux)",
    "  --no-desktop - remove an existing desktop entry",
    "  --terminal - run the desktop entry in a terminal",
    "  --icon <path> - icon for the desktop entry",
    "  --assimilate - convert an APE with the cosmocc assimilate tool",
    "  --channel <stable|beta> - choose the release channel",
    "  --reinstall - reinstall a release that is already installed",
    "  -l, --lock - lock the release at the installed version",
    "  --strict - fail when a checksum is missing or unusable",
    "  --require-signature - fail when no verified signature is available",
    "  --allow-signer-change - accept a changed signer after confirmation",
    "  --dry-run - show what would happen without changing anything",
    "  --experimental-compat - enable the compatibility path for this run",
    NULL
};

static const char *const OPTS_UPDATE[] = {
    "  --all - update every installed package",
    "  --version <tag> - install or update to a specific release",
    "  --asset <name|index> - choose a release asset non-interactively",
    "  --kind <kind> - force appimage, portable-exe, ape, nsis, msi, or dmg",
    "  --channel <stable|beta> - choose the release channel",
    "  --reinstall - refresh the asset even when the version is unchanged",
    "  -l, --lock - lock the release at the version updated to",
    "  --strict - fail when a checksum is missing or unusable",
    "  --require-signature - fail when no verified signature is available",
    "  --allow-signer-change - accept a changed signer after confirmation",
    "  --dry-run - show what would happen without changing anything",
    "  --experimental-compat - enable the compatibility path for this run",
    NULL
};

static const char *const OPTS_UNINSTALL[] = {
    "  --dry-run - show what would happen without changing anything",
    NULL
};

static const char *const OPTS_LIST[] = {
    "  --outdated - list only packages with newer releases",
    NULL
};

static const char *const OPTS_PROVIDERS[] = {
    "  --type <auto|github|gitea|gitlab|rngit> - type for add, update or recover",
    "  --api <url> - API base URL for add, update or recover",
    "  --token-env <VAR> - environment variable holding the API token",
    "  --url <url> - new instance URL (update)",
    "  --force - accept a type change with installed packages (update)",
    "  --purge - uninstall the provider's packages when removing",
    "  --abandon - leave the provider's packages orphaned when removing",
    NULL
};

static const char *const OPTS_ROLLBACK[] = {
    "  --dry-run - show what would happen without changing anything",
    "  -l, --lock - keep the rolled-back version pinned",
    NULL
};

static const char *const OPTS_DOCTOR[] = {
    "  --diagnose - report only (the default)",
    "  --heal - report, then apply fixes with confirmations",
    NULL
};

static const cmd_help CMDS[] = {
    {"search",    "<name> [options]", OPTS_SEARCH, CMD_SEARCH},
    {"install",   "<name> [options]", OPTS_INSTALL, CMD_INSTALL},
    {"uninstall", "<name> [options]", OPTS_UNINSTALL, CMD_UNINSTALL},
    {"update",    "<name>|--all [options]", OPTS_UPDATE, CMD_UPDATE},
    {"list",      "[options]", OPTS_LIST, CMD_LIST},
    {"info",      "<name> [options]", OPTS_NONE, CMD_INFO},
    {"providers", "[subcommand] [options]", OPTS_PROVIDERS, CMD_PROVIDERS},
    {"rollback",  "<name> [options]", OPTS_ROLLBACK, CMD_ROLLBACK},
    {"doctor",    "[options]", OPTS_DOCTOR, CMD_DOCTOR},
    {"adopt",     "<name> [options]", OPTS_NONE, CMD_ADOPT},
    {"about",     "[options]", OPTS_NONE, CMD_ABOUT},
    {"lock",      "<provider:owner/repo> [options]", OPTS_NONE, CMD_LOCK},
    {"unlock",    "<provider:owner/repo> [options]", OPTS_NONE, CMD_UNLOCK},
    {"completions", "<bash|zsh>", OPTS_NONE, CMD_COMPLETIONS},
    {"help",      "[command]", OPTS_NONE, CMD_HELP},
};

// command names in registry order (shell completion shares this list); fills
// at most cap entries and returns how many
int cmd_names(const char **out, int cap)
{
    int n = 0;
    for (size_t k = 0; k < sizeof(CMDS) / sizeof(CMDS[0]) && n < cap; k++)
        out[n++] = CMDS[k].name;
    return n;
}

const cmd_help *cmd_lookup(const char *name)
{
    for (size_t k = 0; k < sizeof(CMDS) / sizeof(CMDS[0]); k++)
        if (!strcmp(name, CMDS[k].name)) return &CMDS[k];
    return NULL;
}

// registry rows for generators (shell completion); fills at most cap
// entries and returns how many
int cmd_help_all(const cmd_help **out, int cap)
{
    int n = 0;
    for (size_t k = 0; k < sizeof(CMDS) / sizeof(CMDS[0]) && n < cap; k++)
        out[n++] = &CMDS[k];
    return n;
}

const char *const *cmd_help_commands(void) { return HELP_COMMANDS; }
const char *const *cmd_global_options(void) { return GLOBAL_OPTIONS; }

void cmd_parse(char **argv, int argc, cmd_opts *out)
{
    static char *empty[] = {NULL};
    static char *pos[64];
    int n = 0;

    memset(out, 0, sizeof(*out));
    out->args = empty;
    for (int i = 0; i < argc; i++) {
        const char *a = argv[i];
        if (!strcmp(a, "--strict")) out->strict = 1;
        else if (!strcmp(a, "--yes") || !strcmp(a, "-y")) out->assume_yes = 1;
        else if (!strcmp(a, "--require-signature")) out->require_signature = 1;
        else if (!strcmp(a, "--allow-signer-change")) out->allow_signer_change = 1;
        else if (!strcmp(a, "--dry-run")) out->dry_run = 1;
        else if (!strcmp(a, "--json")) out->json = 1;
        else if (!strcmp(a, "--quiet")) out->quiet = 1;
        else if (!strcmp(a, "--all")) out->all = 1;
        else if (!strcmp(a, "--outdated")) out->outdated = 1;
        else if (!strcmp(a, "--channel")) VALV(out->channel, "--channel");
        else if (!strcmp(a, "--provider")) VALV(out->provider, "--provider");
        else if (!strcmp(a, "--asset")) VALV(out->asset, "--asset");
        else if (!strcmp(a, "--version")) VALV(out->version, "--version");
        else if (!strcmp(a, "--kind")) VALV(out->kind, "--kind");
        else if (!strcmp(a, "--name")) VALV(out->name, "--name");
        else if (!strcmp(a, "--icon")) VALV(out->icon, "--icon");
        else if (!strcmp(a, "--no-command")) out->no_command = 1;
        else if (!strcmp(a, "--assimilate")) out->assimilate = 1;
        else if (!strcmp(a, "--desktop")) out->desktop = 1;
        else if (!strcmp(a, "--no-desktop")) out->no_desktop = 1;
        else if (!strcmp(a, "--terminal")) out->terminal = 1;
        // C-only flags
        else if (!strcmp(a, "--reinstall")) out->reinstall = 1;
        else if (!strcmp(a, "-l") || !strcmp(a, "--lock")) out->lock = 1;
        else if (!strcmp(a, "--help")) out->help = 1;
        else if (!strcmp(a, "--colour")) VALV(out->colour, "--colour");
        else if (!strncmp(a, "--colour=", 9)) out->colour = a + 9;
        // hidden US alias: existing scripts and muscle memory keep working
        else if (!strcmp(a, "--color")) VALV(out->colour, "--color");
        else if (!strncmp(a, "--color=", 8)) out->colour = a + 8;
        else if (!strcmp(a, "--no-progress")) out->no_progress = 1;
        else if (!strcmp(a, "--diagnose")) out->diagnose = 1;
        else if (!strcmp(a, "--heal")) out->heal = 1;
        else if (!strcmp(a, "--experimental-compat")) out->experimental_compat = 1;
        else if (n < 64) pos[n++] = argv[i];   // unknown flags included
    }
    pos[n] = NULL;
    out->args = pos;
    out->nargs = n;

    // an invalid --colour must never degrade silently: a flag the user
    // typed is a usage error (rc 2), not a quiet fall back to auto
    if (out->colour != NULL && strcmp(out->colour, "auto")
        && strcmp(out->colour, "always") && strcmp(out->colour, "never")) {
        out->usage_error = 1;
        out->usage_msg = "Invalid --colour value (auto, always or never)";
        return;
    }

    if (n == 0) { out->id = CMD_HELP; return; }
    const char *c = pos[0];
    for (size_t k = 0; k < sizeof(CMDS) / sizeof(CMDS[0]); k++)
        if (!strcmp(c, CMDS[k].name)) {
            out->id = CMDS[k].id;
            /* CmdParser update arity: --all takes no repository, a
               plain update takes exactly one (Usage.unknown with a
               blank argument prints the usage text alone) */
            if (out->id == CMD_UPDATE) {
                if (out->all) {
                    if (n > 1) {
                        out->usage_error = 1;
                        out->usage_msg =
                            "--all does not take a repository";
                    }
                } else if (n != 2) {
                    out->usage_error = 1;
                    out->usage_msg = "";
                }
            }
            return;
        }
    out->id = CMD_USAGE;
    out->usage_error = 1;
    out->usage_msg = NULL;      // caller prints "Error: Unknown command: X"
}
