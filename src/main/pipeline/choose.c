// Interactive numbered pick when no --asset flag was given.

#include "pipeline/internal.h"

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

// Interactive numbered pick (C-only UX): json/-y/non-TTY decline with no
// output; invalid input re-prompts, EOF cancels. 0-based index or -1.
long choose_index(const cmd_opts *o, const char *what,
                         const char *none, size_t n)
{
    if (o->json || o->assume_yes || !isatty(fileno(stdin))) return -1;
    for ( ; ; ) {
        fprintf(stderr, "Choose %s [1-%zu]: ", what, n);
        fflush(stderr);
        char ln[80];
        if (fgets(ln, sizeof ln, stdin) == NULL) {
            fprintf(stderr, "No %s selected.\n", none);
            return -1;
        }
        char *endp = NULL;
        long v = strtol(ln, &endp, 10);
        while (endp != NULL && *endp == ' ') endp++;
        if (endp != NULL && v >= 1 && (size_t)v <= n
            && (*endp == '\0' || *endp == '\n' || *endp == '\r'))
            return v - 1;
        fprintf(stderr, "Invalid choice. Valid options are: 1 - %zu\n", n);
    }
}
