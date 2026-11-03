// fuzz target: xdelta apply (xdelta_apply_fd) against hostile patches
// and the pre-parse; the installed source is a deterministic 64 KiB
// buffer standing in for the gated file.

#include "pipeline/decode.c"

#include "fuzz_util.h"

#include <stdio.h>
#include <string.h>

static unsigned char g_src[1 << 16];

void fuzz_target(const unsigned char *data, size_t size)
{
    char *dir = fuzz_work_subdir("xdelta");
    if (dir == NULL) return;
    if (g_src[0] == 0 && g_src[1] == 0) {   // deterministic source body
        unsigned long long s = 99;
        for (size_t i = 0; i < sizeof g_src; i++) {
            s = s * 6364136223846793005ULL + 1442695040888963407ULL;
            g_src[i] = (unsigned char)((s >> 33) & 0xffu);
        }
    }
    char sp[1024], pp[1024], outp[1024];
    snprintf(sp, sizeof sp, "%s/src.bin", dir);
    snprintf(pp, sizeof pp, "%s/p.bin", dir);
    snprintf(outp, sizeof outp, "%s/out.bin", dir);
    FILE *f = fopen(sp, "wb");
    if (f == NULL) return;
    fwrite(g_src, 1, sizeof g_src, f);
    if (fclose(f) != 0) return;
    f = fopen(pp, "wb");
    if (f == NULL) return;
    if (size > 0) fwrite(data, 1, size, f);
    if (fclose(f) != 0) return;

    int sfd = open(sp, O_RDONLY);
    if (sfd < 0) return;
    struct stat sb;
    if (xdelta_apply_fd(sfd, pp, outp) == 0) {
        FUZZ_ASSERT(stat(outp, &sb) == 0);
        FUZZ_ASSERT((unsigned long long) sb.st_size <= (8ull << 20));
        FUZZ_ASSERT(remove(outp) == 0);
    } else {
        FUZZ_ASSERT(stat(outp, &sb) != 0);  // failure must delete output
    }
    close(sfd);
    remove(sp);
    remove(pp);
}
