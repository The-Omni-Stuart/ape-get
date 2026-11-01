#ifndef APEGET_PATH_H
#define APEGET_PATH_H

#include <stdbool.h>
#include <stddef.h>

// Path utilities. Returned strings are malloc'd and owned by the caller;
// the process is short-lived so teardown is exit().

char *path_home(void);                 // $HOME; NULL when unset
char *path_data_home(void);            // $XDG_DATA_HOME or <home>/.local/share
char *path_state_root(void);           // <data home>/github-release-manager
char *path_join(const char *a, const char *b);
char *path_fmt(const char *fmt, ...);  // printf-allocated; %s only for now

// Deletion-path containment: true when file resolves (symlinks and ..
// included) strictly inside dir. A textual prefix match is not containment.
bool path_under(const char *file, const char *dir);

// Collapses . and .. segments of an absolute path; purely lexical, a symlink
// component is not resolved. NULL for relative or empty input.
char *path_norm_dup(const char *in);

#endif
