// fuzz target: rngit output parser (untrusted process stdout): ANSI strip, the
// "Release :" / "Artifacts (" view grammar, opaque URL round-trip, errors.

#include "provider/rngit.c"
#include "provider/rngit_parse.c"

#include "fuzz_util.h"

static void free_release(prov_release *r)
{
    for (int k = 0; k < r->n; k++) {
        free(r->assets[k].name);
        free(r->assets[k].url);
    }
    free(r->assets);
    free(r->version);
    memset(r, 0, sizeof *r);
}

static void one(const char *s, void *ud)
{
    (void)ud;
    char *vis = strip_ansi(s);
    if (vis != NULL) {
        prov_release rel;
        memset(&rel, 0, sizeof rel);
        if (parse_view(vis, "g/r", &rel)) free_release(&rel);
        free(vis);
    }
    char msg[512];
    (void)rngit_classify(s, "read", "g/r", msg, sizeof msg);
    free(last_nonempty(s));

    // decode side over arbitrary base64-ish payloads
    char *url = malloc(strlen(s) + 9);
    if (url == NULL) return;
    strcpy(url, "rngit://");
    strcat(url, s);
    char *p = NULL, *t = NULL, *n = NULL, m2[256];
    if (opaque_decode(url, &p, &t, &n, m2, sizeof m2)) { free(p); free(t);
        free(n); }
    else { free(p); free(t); free(n); }
    free(url);

    // encode → decode round-trip when the name survives validation
    char *opaque = opaque_url("g/r", "v1", s);
    if (opaque != NULL) {
        char *p2 = NULL, *t2 = NULL, *n2 = NULL, m3[256];
        if (opaque_decode(opaque, &p2, &t2, &n2, m3, sizeof m3)) {
            FUZZ_ASSERT(strcmp(p2, "g/r") == 0);
            FUZZ_ASSERT(strcmp(t2, "v1") == 0);
            FUZZ_ASSERT(strcmp(n2, s) == 0);
        }
        free(p2); free(t2); free(n2);
        free(opaque);
    }
}

void fuzz_target(const unsigned char *data, size_t size)
{
    (void)size;
    fuzz_for_each_segment(data, size, one, NULL);
}
