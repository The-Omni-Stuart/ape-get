#ifndef APEGET_FUZZ_UTIL_H
#define APEGET_FUZZ_UTIL_H

#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>

// Shared helpers for the fuzz targets (driver contract in fuzz_main.c).
// fuzz_target(data, size): the buffer is NUL-terminated at [size].

// Invokes fn once per NUL-delimited segment (the strlen-based parsers
// cannot see past an embedded NUL, so every segment is worth a run).
typedef void (*fuzz_seg_fn)(const char *seg, void *ud);
void fuzz_for_each_segment(const unsigned char *data, size_t size,
                           fuzz_seg_fn fn, void *ud);

// <APEGET_FUZZ_WORK>/<name>, created once; NULL when no work dir.
char *fuzz_work_subdir(const char *name);

// On failure: prints to stdout (survives the child), aborts.
#define FUZZ_ASSERT(cond)                                                    \
    do {                                                                     \
        if (!(cond)) {                                                       \
            printf("ASSERTION FAILED: %s (%s:%d)\n", #cond, __FILE__,       \
                   __LINE__);                                                \
            fflush(stdout);                                                  \
            abort();                                                         \
        }                                                                    \
    } while (0)

#endif
