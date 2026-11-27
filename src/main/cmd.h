#ifndef APEGET_CMD_H
#define APEGET_CMD_H

// CLI parsing + usage text.

typedef enum cmd_id {
    CMD_NONE = 0,
    CMD_SEARCH, CMD_INSTALL, CMD_UNINSTALL, CMD_UPDATE, CMD_LIST,
    CMD_INFO, CMD_PROVIDERS, CMD_ROLLBACK, CMD_DOCTOR, CMD_ADOPT,
    CMD_HELP, CMD_USAGE,
    CMD_LOCK, CMD_UNLOCK, CMD_ABOUT, CMD_COMPLETIONS,
    CMD_UNTRUST, CMD_CONFIG, CMD_SETUP
} cmd_id;

typedef struct cmd_opts {
    int strict, assume_yes, require_signature, allow_signer_change;
    int dry_run, json, quiet, all, outdated, no_command, assimilate;
    int desktop, no_desktop, terminal, reinstall, lock, diagnose, heal;
    int experimental_compat, no_progress, help;
    const char *channel, *provider, *asset, *version, *kind, *name, *icon;
    const char *colour;
    const char *override_platform;   // setup only: the system to lay out for
    char **args;   // positional args after flag stripping; NUL-terminated
    int nargs;
    cmd_id id;
    int usage_error;   // missing option value: usage_msg prints with usage, exit 2
    const char *usage_msg;
} cmd_opts;

// Parse argv (no argv[0]). Owns nothing; strings alias argv.
void cmd_parse(char **argv, int argc, cmd_opts *out);

// Command names in registry order, for shell completion; fills at most
// cap, returns the count.
int cmd_names(const char **out, int cap);

// Styled help (main.c): global page for NULL, per-command page for a known
// command, "Unknown command: X" + global page otherwise.
int run_help(const char *cmd);

// Help-rendering registry: cmd_lookup is NULL for unknown names; options
// are NULL-terminated. extra_min/extra_max bound the positional words that
// follow the command name (max <0 means unbounded); arity is enforced while
// parsing, so a command never silently ignores a word.
typedef struct {
    const char *name;
    const char *usage;   // argument part of the per-command usage line
    const char *const *options;
    int id;
    int extra_min, extra_max;
} cmd_help;
const cmd_help *cmd_lookup(const char *name);
int cmd_help_all(const cmd_help **out, int cap);
const char *const *cmd_help_commands(void);
const char *const *cmd_global_options(void);

// registry name for a command id (NULL if none): lets a missing/extra-arg
// usage error re-print that command's help
const char *cmd_usage_name(cmd_id id);

#endif
