#include "state.h"
#include "path.h"
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

// Operation history (grm_updates.log), one append-only line per mutating
// outcome: <ISO instant> <command> <provider>:<repo> <detail> <ok|error ...>

void oplog_append(const state_ctx *st, const char *command, const char *ref,
                  const char *detail, const char *error_message)
{
    char ts[40];
    struct timespec tp;
    clock_gettime(CLOCK_REALTIME, &tp);
    struct tm tm;
    gmtime_r(&tp.tv_sec, &tm);
    snprintf(ts, sizeof(ts), "%04d-%02d-%02dT%02d:%02d:%02dZ",
        tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday, tm.tm_hour,
        tm.tm_min, tm.tm_sec);

    char line[640];
    if (error_message != NULL) {
        char first[400];
        size_t k = 0;
        while (error_message[k] != '\0' && error_message[k] != '\n'
               && k < sizeof(first) - 1) {
            first[k] = error_message[k];
            k++;
        }
        first[k] = '\0';
        snprintf(line, sizeof(line), "%s %s %s %s error %s\n",
                 ts, command, ref, detail, first);
    } else {
        snprintf(line, sizeof(line), "%s %s %s %s ok\n",
                 ts, command, ref, detail);
    }

    char *file = path_join(st->root, "grm_updates.log");
    FILE *f = file != NULL ? fopen(file, "a") : NULL;
    int e = errno;
    if (f == NULL) {
        fprintf(stderr, "history: %s\n", strerror(e));
        free(file);
        return;
    }
    size_t n = strlen(line);
    if (fwrite(line, 1, n, f) != n)
        fprintf(stderr, "history: write failed\n");
    fclose(f);
    free(file);
}
