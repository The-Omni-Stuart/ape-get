// Fuzz target: the POSIX single-quote routine of the wine wrapper and its
// private un-quoter, asserted as a round-trip (control chars are refused).

#include "compat.c"

#include "fuzz_util.h"

static void one(const char *s, void *ud)
{
    (void)ud;
    char *err = NULL;
    char *q = compat_shell_quote(s, &err);
    if (q == NULL) {
        FUZZ_ASSERT(err != NULL);
        free(err);
        return;
    }
    FUZZ_ASSERT(err == NULL);
    char *back = unquote(q);
    FUZZ_ASSERT(back != NULL);
    FUZZ_ASSERT(strcmp(back, s) == 0);
    free(back);
    free(q);
}

void fuzz_target(const unsigned char *data, size_t size)
{
    (void)size;
    fuzz_for_each_segment(data, size, one, NULL);
}
