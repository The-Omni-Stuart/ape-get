#ifndef APEGET_PROC_H
#define APEGET_PROC_H

#include <stdbool.h>

// Process/environment helpers.

bool proc_have_executable(const char *name);   // PATH search, X_OK
char *proc_find_executable(const char *name);  // malloc'd PATH hit, or NULL
bool proc_dir_on_path(const char *dir);        // exact directory entry on $PATH

// Merged stdout+stderr into malloc'd *out (8 MiB cap); child gets its own
// process group, killed on timeout. Exit code; -1 spawn/wait failure, -2 timeout.
long proc_run(const char *const argv[], int timeout_ms, char **out);

// Same with an explicit NULL-terminated child env (gpg and friends must run
// scrubbed; NULL inherits the parent's) and an explicit child cwd (NULL keeps
// ours). cwd matters for children that write where they run rather than
// wherever they are named — a missing directory is a spawn failure, not a 127.
long proc_run_env(const char *const argv[], char *const env[],
    const char *cwd, int timeout_ms, char **out);

#endif
