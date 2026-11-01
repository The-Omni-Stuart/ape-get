// fuzz target: checksum-file parser (BSD and sha256sum shapes, name
// normalisation, ambiguity).

#include "pipeline/checksums.c"

#include "fuzz_util.h"

static void one_name(const char *name, void *content)
{
    bool has, amb;
    char *sha = NULL;
    checksum_lookup((const char *)content, name, &has, &amb, &sha);
    free(sha);
}

static void one_piece(const char *s, void *ud)
{
    (void)ud;
    char *n = norm_name(s);
    free(n);
    char *name = NULL, *hash = NULL;
    if (bsd_line(s, &name, &hash)) { free(name); free(hash); }
    (void)is_sha256_hex(s);
}

void fuzz_target(const unsigned char *data, size_t size)
{
    bool has, amb;
    char *sha = NULL;
    checksum_lookup((const char *)data, "app.bin", &has, &amb, &sha);
    free(sha);
    fuzz_for_each_segment(data, size, one_name, (void *)data);
    fuzz_for_each_segment(data, size, one_piece, NULL);
}
