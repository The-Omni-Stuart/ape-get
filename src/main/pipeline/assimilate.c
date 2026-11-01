// APE assimilation: turn a staged .com into a native executable.

#include "pipeline/internal.h"

#include "path.h"
#include "proc.h"
#include "ui.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// Run the cosmocc assimilate tool on the staged APE (magic already confirmed
// by the caller); returns a malloc'd <file>.native path, *err set on failure.
char *assimilate_asset(const char *file, const char *disp_name,
    char **err)
{
    *err = NULL;
    if (!proc_have_executable("assimilate")) {
        const char *m =
            "--assimilate requires the cosmocc assimilate tool on PATH.";
        ui_msg(stderr, UI_ERROR, "%s", m);
        *err = strdup(m);
        return NULL;
    }
    char *native = path_fmt("%s.native", file);
    if (native == NULL) return NULL;
    char *av[] = { "assimilate", "-e", "-o", native, (char *)file, NULL };
    char *out = NULL;
    long rc = proc_run((const char *const *)av, 300000, &out);
    if (rc == 0) {
        free(out);
        return native;
    }
    if (out == NULL) out = strdup("");
    size_t n = strlen(out);
    while (n > 0 && isspace((unsigned char)out[n - 1])) out[--n] = '\0';
    char *p = out;
    while (*p != '\0' && isspace((unsigned char)*p)) p++;
    if (rc == -2) p = (char *)"timed out";
    char *msg = *p != '\0'
        ? path_fmt("Failed to assimilate %s: %s", disp_name, p)
        : path_fmt("Failed to assimilate %s.", disp_name);
    ui_msg(stderr, UI_ERROR, "%s", msg != NULL ? msg : "Failed to assimilate.");
    *err = msg != NULL ? msg : strdup("Failed to assimilate.");
    free(out);
    remove(native);
    free(native);
    return NULL;
}
