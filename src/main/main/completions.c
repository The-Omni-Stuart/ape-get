#include "cmd.h"
#include "json.h"
#include "main.h"

#include <stdio.h>
#include <string.h>

// completions: a bash script generated from the help tables, so commands
// and flags never need a second list; zsh sources it via bashcompinit.

// GLOBAL_OPTIONS lines are "  --flag <value> - text" or "  -y, --yes -
// text"; the flags are the comma-separated words ahead of the " - ".
static void add_flag_words(jbuf *b)
{
    bool first = true;
    for (const char *const *l = cmd_global_options(); *l != NULL; l++) {
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
    jbuf words = {0}, flags = {0};
    add_command_words(&words);
    add_flag_words(&flags);
    fputs("# ape-get completion. Enable with:\n"
          "#   eval \"$(ape-get completions bash)\"      # ~/.bashrc\n",
          stdout);
    if (!strcmp(shell, "zsh"))
        fputs("# zsh needs bashcompinit first: autoload bashcompinit && "
              "bashcompinit\n", stdout);
    printf("_ape_get() {\n"
           "    local cur\n"
           "    cur=\"${COMP_WORDS[COMP_CWORD]}\"\n"
           "    if [[ \"$cur\" == -* ]]; then\n"
           "        COMPREPLY=( $(compgen -W \"%s\" -- \"$cur\") )\n"
           "    elif [ \"$COMP_CWORD\" -le 1 ]; then\n"
           "        COMPREPLY=( $(compgen -W \"%s\" -- \"$cur\") )\n"
           "    fi\n"
           "}\n"
           "complete -F _ape_get ape-get ape-get.com\n",
           flags.p != NULL ? flags.p : "", words.p != NULL ? words.p : "");
    jb_free(&words);
    jb_free(&flags);
    return 0;
}
