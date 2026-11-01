// On a TTY ask `Continue? [y/N] ` on stderr; only y/yes (case-insensitive,
// trimmed) proceeds. Non-TTY (piped/CI) refuses with the --yes hint.

#include "prompt.h"

#include "ui.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>

bool confirm_action(const cmd_opts *o, const char *question,
    bool destructive)
{
    if (o->assume_yes) return true;
    ui_msg(stderr, UI_NONE, "%s", question);
    if (isatty(fileno(stdin))) {
        ui_prompt_continue(stderr, destructive);
        fflush(stderr);
        char ln[80];
        if (fgets(ln, sizeof ln, stdin) == NULL) return false;
        size_t a = 0, z = strlen(ln);
        while (a < z && (ln[a] == ' ' || ln[a] == '\t')) a++;
        while (z > a && (ln[z-1] == '\n' || ln[z-1] == '\r'
            || ln[z-1] == ' ' || ln[z-1] == '\t')) z--;
        ln[z] = '\0';
        return strcasecmp(ln + a, "y") == 0
            || strcasecmp(ln + a, "yes") == 0;
    }
    fprintf(stderr,
        "Confirmation required. Re-run with --yes (or -y) to approve.\n");
    return false;
}
