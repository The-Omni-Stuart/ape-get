// fuzz target: .xz transport-envelope decode (xz_decode_file) against
// hostile envelopes — headers, caps and the stream-terminal rules.

#include "pipeline/decode.c"

#include "fuzz_util.h"

#include <stdio.h>
#include <string.h>

void fuzz_target(const unsigned char *data, size_t size)
{
    char *dir = fuzz_work_subdir("xz");
    if (dir == NULL) return;
    char in[1024], out[1024];
    snprintf(in, sizeof in, "%s/e.xz", dir);
    snprintf(out, sizeof out, "%s/e.out", dir);
    FILE *f = fopen(in, "wb");
    if (f == NULL) return;
    if (size > 0) fwrite(data, 1, size, f);
    if (fclose(f) != 0) return;
    struct stat sb;
    if (xz_decode_file(in, out) == 0) {
        FUZZ_ASSERT(stat(out, &sb) == 0);
        FUZZ_ASSERT((unsigned long long) sb.st_size <= (8ull << 20));
        FUZZ_ASSERT(remove(out) == 0);
    } else {
        FUZZ_ASSERT(stat(out, &sb) != 0);   // failure must delete output
    }
    remove(in);
}
