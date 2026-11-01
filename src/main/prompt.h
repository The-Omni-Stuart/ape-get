#ifndef APEGET_PROMPT_H
#define APEGET_PROMPT_H

// The one confirmation prompt every command asks through; isatty gates it.

#include <stdbool.h>

#include "cmd.h"

bool confirm_action(const cmd_opts *o, const char *question,
    bool destructive);

#endif
