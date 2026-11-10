/* Round-trip test helper (encode half): synthesise a deterministic
   source/target pair and encode a delta with the vendored xdelta3 built
   test-only with the encoder on. The decode half runs the product path
   (xdelta_apply_fd); see test_delta_roundtrip.cmake. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <xdelta3.h>

#define SRC_SIZE (256u * 1024u)
/* multi-window pair: 1 MiB with edits either side of the 512 KiB
   window the CLI encoder must use (Ubuntu's xdelta3 floor), so the
   patch genuinely carries two windows */
#define MW_SIZE (1024u * 1024u)

static void fill(unsigned char *b, unsigned long long *state, size_t n)
{
    for (size_t i = 0; i < n; i++) {
        *state = (*state * 6364136223846793005ULL + 1442695040888963407ULL);
        b[i] = (unsigned char)((*state >> 33) & 0xffu);
    }
}

static int write_file(const char *path, const unsigned char *b, size_t n)
{
    FILE *f = fopen(path, "wb");
    if (f == NULL) return 1;
    size_t w = fwrite(b, 1, n, f);
    fclose(f);
    return w == n ? 0 : 1;
}

int main(int argc, char **argv)
{
    if (argc != 2) {
        fprintf(stderr, "usage: %s DIR\n", argv[0]);
        return 2;
    }
    char p[1024];
    unsigned long long st = 20261103u;
    unsigned char *src = malloc(SRC_SIZE), *dst = malloc(SRC_SIZE);
    if (src == NULL || dst == NULL) return 1;
    fill(src, &st, SRC_SIZE);
    memcpy(dst, src, SRC_SIZE);
    /* edits the delta must carry: rewrites, an insertion and a tail cut */
    memset(dst + 1000, 0xa5, 900);
    memmove(dst + 65536 + 4096, dst + 65536, 4096);
    memset(dst + 65536, 0x5c, 4096);
    fill(dst + SRC_SIZE / 2, &st, SRC_SIZE / 2);

    snprintf(p, sizeof p, "%s/src.bin", argv[1]);
    if (write_file(p, src, SRC_SIZE)) return 1;
    snprintf(p, sizeof p, "%s/dst.bin", argv[1]);
    if (write_file(p, dst, SRC_SIZE)) return 1;

    unsigned char *src2 = malloc(MW_SIZE), *dst2 = malloc(MW_SIZE);
    if (src2 == NULL || dst2 == NULL) return 1;
    fill(src2, &st, MW_SIZE);
    memcpy(dst2, src2, MW_SIZE);
    memset(dst2 + 1000, 0x3b, 900);
    memset(dst2 + 700 * 1024, 0x71, 4096);
    snprintf(p, sizeof p, "%s/src2.bin", argv[1]);
    if (write_file(p, src2, MW_SIZE)) return 1;
    snprintf(p, sizeof p, "%s/dst2.bin", argv[1]);
    if (write_file(p, dst2, MW_SIZE)) return 1;

    size_t cap = SRC_SIZE + (SRC_SIZE / 2) + 65536;
    unsigned char *patch = malloc(cap);
    size_t patched = 0;
    if (patch == NULL) return 1;
    if (xd3_encode_memory(dst, SRC_SIZE /* target */, src, SRC_SIZE,
                          patch, &patched, cap, 0) != 0
        || patched == 0)
        return 1;
    snprintf(p, sizeof p, "%s/patch.bin", argv[1]);
    if (write_file(p, patch, patched)) return 1;
    return 0;
}
