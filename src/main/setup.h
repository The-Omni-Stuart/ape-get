#ifndef APEGET_SETUP_H
#define APEGET_SETUP_H

// The setup command: make the downloaded portable executable the installed
// one. It owns the prompts, the option checks and the order of steps; the
// answers to "which OS is this", "where do executables go" and "how does a
// directory join PATH" all come from the platform module.

#include "cmd.h"

int setup_run(const cmd_opts *o);

// A one-line nudge for a person running a downloaded, uninstalled ape-get at
// a terminal. Every other caller -- a pipe, a JSON reader, a shell eval'ing a
// completion script, a scripted run -- is left alone, because for them the
// bytes on stderr are part of the contract.
void setup_portable_notice(const cmd_opts *o);

#endif
