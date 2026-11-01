// fuzz target: URL/RepoRef parser. Rejection messages go to stderr, silenced
// by the driver when a sanitizer log_path is configured.

#include "repo.c"

#include "fuzz_util.h"

static void one(const char *s, void *ud)
{
    (void)ud;
    char prov[64], path[8192];
    (void)repo_parse_ref(s, prov, sizeof prov, path, sizeof path);
}

void fuzz_target(const unsigned char *data, size_t size)
{
    (void)size;
    fuzz_for_each_segment(data, size, one, NULL);
}
