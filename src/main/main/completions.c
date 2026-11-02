#include "cmd.h"
#include "json.h"
#include "main.h"

#include <stdio.h>
#include <string.h>

// completions: a bash script generated from the help tables, so no second
// flag list can drift; dynamic words come from ape-get's own JSON output -
// local state only, so Tab never waits on the network.

// PROVIDER_VERBS mirrors providers.c's subcommand dispatch.
static const char *const PROVIDER_VERBS[] = {
    "list", "show", "add", "update", "remove", "recover", NULL
};

// REF_CMDS: commands whose first positional names an installed release
// (padded with spaces so the script can substring-match).
static const char *const REF_CMDS =
    " uninstall update info rollback adopt lock unlock ";

// help lines are "  --flag <value> - text" or "  -y, --yes - text"; the
// flags are the comma-separated words ahead of the " - ".
static void add_flag_words(jbuf *b, const char *const *lines)
{
    bool first = true;
    for (const char *const *l = lines; *l != NULL; l++) {
        const char *s = *l;
        while (*s == ' ') s++;
        const char *end = strstr(s, " - ");
        if (end == NULL) continue;
        for (const char *p = s; p < end; ) {
            const char *c = memchr(p, ',', (size_t)(end - p));
            const char *stop = c != NULL ? c : end;
            while (p < stop && *p == ' ') p++;
            const char *sp = memchr(p, ' ', (size_t)(stop - p));
            if (sp != NULL) stop = sp;   // drop the <value> placeholder
            if (stop - p > 1 && *p == '-') {
                char tok[64];
                size_t n = (size_t)(stop - p);
                if (n >= sizeof tok) n = sizeof tok - 1;
                memcpy(tok, p, n);
                tok[n] = '\0';
                jb_lit(b, first ? "" : " ");
                jb_lit(b, tok);
                first = false;
            }
            if (c == NULL) break;
            p = c + 1;
        }
    }
}

// a flag whose help line carries a <placeholder> takes a value; the script
// uses this list to skip option values when hunting for the command word
static void add_value_words(jbuf *b, const char *const *lines,
    char seen[][64], int *n)
{
    for (const char *const *l = lines; *l != NULL; l++) {
        const char *s = *l;
        while (*s == ' ') s++;
        const char *end = strstr(s, " - ");
        if (end == NULL) continue;
        bool value = false;
        for (const char *p = s; p + 1 < end; p++)
            if (p[0] == ' ' && p[1] == '<') { value = true; break; }
        if (!value) continue;
        const char *stop = s + 1;
        while (stop < end && stop[0] != ' ' && stop[0] != ',') stop++;
        size_t len = (size_t)(stop - s);
        if (len < 2 || *s != '-' || len >= 64 || *n >= 40) continue;
        char tok[64];
        memcpy(tok, s, len);
        tok[len] = '\0';
        bool dup = false;
        for (int k = 0; k < *n; k++)
            if (!strcmp(seen[k], tok)) { dup = true; break; }
        if (dup) continue;
        memcpy(seen[*n], tok, len + 1);
        (*n)++;
        jb_lit(b, b->n ? " " : "");
        jb_lit(b, tok);
    }
}

static void add_command_words(jbuf *b)
{
    const char *names[32];
    int n = cmd_names(names, (int)(sizeof names / sizeof names[0]));
    for (int k = 0; k < n; k++) {
        jb_lit(b, k ? " " : "");
        jb_lit(b, names[k]);
    }
}

int run_completions(const cmd_opts *o)
{
    const char *shell = o->nargs >= 2 ? o->args[1] : NULL;
    if (shell == NULL) return run_help("completions");
    if (strcmp(shell, "bash") && strcmp(shell, "zsh")) {
        put_s(stderr, UI_ERROR, "Unknown shell: %s (want bash or zsh)",
            shell);
        return 2;
    }

    const cmd_help *cmds[32];
    int nc = cmd_help_all(cmds, (int)(sizeof cmds / sizeof cmds[0]));
    char seen[40][64];
    int nseen = 0;
    jbuf words = {0}, globals = {0}, vflags = {0}, verbs = {0};
    add_command_words(&words);
    add_flag_words(&globals, cmd_global_options());
    add_value_words(&vflags, cmd_global_options(), seen, &nseen);
    for (int k = 0; k < nc; k++)
        add_value_words(&vflags, cmds[k]->options, seen, &nseen);
    for (int k = 0; PROVIDER_VERBS[k] != NULL; k++) {
        jb_lit(&verbs, k ? " " : "");
        jb_lit(&verbs, PROVIDER_VERBS[k]);
    }

    fputs("# ape-get completion. Enable with:\n", stdout);
    if (!strcmp(shell, "zsh"))
        fputs("#   autoload -U +X bashcompinit && bashcompinit\n"
              "#   eval \"$(ape-get completions zsh)\"     # ~/.zshrc\n",
              stdout);
    else
        fputs("#   eval \"$(ape-get completions bash)\"      # ~/.bashrc\n"
              "# Optional menu-style Tab (first match inserts, ties cycle;\n"
              "# affects every completion in the shell):\n"
              "#   bind '\"\\t\": menu-complete'\n",
              stdout);
    if (strcmp(shell, "zsh"))
        fputs("# first Tab should list even when ambiguous; without this a\n"
              "# tie like 'install info' prints nothing until the second Tab\n"
              "bind 'set show-all-if-ambiguous on' 2>/dev/null || :\n",
              stdout);

    fputs("_apg_refs() {\n"
          "    ape-get list --json 2>/dev/null |\n"
          "      grep -o '\"provider\":\"[^\"]*\",\"repo\":\"[^\"]*\"' |\n"
          "      sed -n 's/\"provider\":\"\\([^\"]*\\)\",\"repo\":\"\\([^\"]*\\)\"/\\1:\\2/p'\n"
          "}\n"
          "_apg_providers() {\n"
          "    ape-get providers list --json 2>/dev/null |\n"
          "      grep -o '\"name\":\"[^\"]*\"' | sed 's/.*:\"\\([^\"]*\\)\"/\\1/'\n"
          "}\n"
          "_apg_values() {\n"
          "    case \"$1\" in\n"
          "    --colour) echo auto always never ;;\n"
          "    --kind) echo appimage portable-exe ape nsis msi dmg ;;\n"
          "    --channel) echo stable beta ;;\n"
          "    --type) echo auto github gitea gitlab rngit ;;\n"
          "    --provider) _apg_providers ;;\n"
          "    esac\n"
          "}\n", stdout);

    fputs("_apg_flags() {\n    case \"$1\" in\n", stdout);
    for (int k = 0; k < nc; k++) {
        if (cmds[k]->options[0] == NULL) continue;
        jbuf f = {0};
        add_flag_words(&f, cmds[k]->options);
        printf("    %s)\n        echo %s\n        ;;\n", cmds[k]->name,
            f.p != NULL ? f.p : "");
        jb_free(&f);
    }
    printf("    esac\n    echo %s\n}\n", globals.p != NULL ? globals.p : "");

    printf("_apg_commands=\"%s\"\n"
           "_apg_value_flags=\" %s \"\n"
           "_apg_verbs=\"%s\"\n"
           "_apg_ref_cmds=\"%s\"\n",
           words.p != NULL ? words.p : "",
           vflags.p != NULL ? vflags.p : "",
           verbs.p != NULL ? verbs.p : "", REF_CMDS);

    fputs("_ape_get() {\n"
          "    local w cmd=\"\" sub=\"\" vals i=1\n"
          "    local cur=\"${COMP_WORDS[COMP_CWORD]}\"\n"
          "    local prev=\"${COMP_WORDS[COMP_CWORD-1]}\"\n"
          "    vals=$(_apg_values \"$prev\")\n"
          "    if [ -n \"$vals\" ]; then\n"
          "        COMPREPLY=( $(compgen -W \"$vals\" -- \"$cur\") )\n"
          "        return\n"
          "    fi\n"
          "    while [ \"$i\" -lt \"$COMP_CWORD\" ]; do\n"
          "        w=\"${COMP_WORDS[i]}\"\n"
          "        case \"$w\" in\n"
          "        -*)\n"
          "            case \"$_apg_value_flags\" in\n"
          "            *\" $w \"*) i=$((i + 2)); continue ;;\n"
          "            esac\n"
          "            ;;\n"
          "        *)\n"
          "            if [ -z \"$cmd\" ]; then cmd=\"$w\"\n"
          "            elif [ -z \"$sub\" ]; then sub=\"$w\"\n"
          "            fi\n"
          "            ;;\n"
          "        esac\n"
          "        i=$((i + 1))\n"
          "    done\n"
          "    case \"$cur\" in\n"
          "    -*)\n"
          "        COMPREPLY=( $(compgen -W \"$(_apg_flags \"$cmd\")\" -- \"$cur\") )\n"
          "        return\n"
          "        ;;\n"
          "    esac\n"
          "    if [ -z \"$cmd\" ]; then\n"
          "        COMPREPLY=( $(compgen -W \"$_apg_commands\" -- \"$cur\") )\n"
          "        return\n"
          "    fi\n"
          "    case \"$cmd\" in\n"
          "    help)\n"
          "        COMPREPLY=( $(compgen -W \"$_apg_commands\" -- \"$cur\") ) ;;\n"
          "    completions)\n"
          "        COMPREPLY=( $(compgen -W \"bash zsh\" -- \"$cur\") ) ;;\n"
          "    install)\n"
          "        COMPREPLY=( $(compgen -W \"$(_apg_providers | sed 's/$/:/') $(_apg_refs)\" -- \"$cur\") ) ;;\n"
          "    providers)\n"
          "        case \"$sub\" in\n"
          "        show|update|remove|recover)\n"
          "            COMPREPLY=( $(compgen -W \"$(_apg_providers)\" -- \"$cur\") ) ;;\n"
          "        list|add)\n"
          "            COMPREPLY=() ;;\n"
          "        *)\n"
          "            COMPREPLY=( $(compgen -W \"$_apg_verbs\" -- \"$cur\") ) ;;\n"
          "        esac ;;\n"
          "    *)\n"
          "        case \"$_apg_ref_cmds\" in\n"
          "        *\" $cmd \"*)\n"
          "            COMPREPLY=( $(compgen -W \"$(_apg_refs)\" -- \"$cur\") ) ;;\n"
          "        esac ;;\n"
          "    esac\n"
          "}\n"
          "complete -r ape-get 2>/dev/null || :\n"
          "complete -r ape-get.com 2>/dev/null || :\n"
          "complete -F _ape_get ape-get ape-get.com\n",
          stdout);

    jb_free(&words);
    jb_free(&globals);
    jb_free(&vflags);
    jb_free(&verbs);
    return 0;
}
