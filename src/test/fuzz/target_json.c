// fuzz target: JSON parser and printer, incl. depth and duplicate-key edges.
// Round-trip asserts writer → reader equality (the reader's \u drop is expected).

#include "json.c"

#include "fuzz_util.h"

static void drop_dropped(const char *s, char *out)
{
    size_t w = 0;
    for (const unsigned char *p = (const unsigned char *)s; *p != 0; p++)
        if (*p != 0x7f && !(*p < 0x20 && *p != '\n' && *p != '\t'
            && *p != '\r'))
            out[w++] = (char)*p;
    out[w] = '\0';
}

static void writer_round_trip(const char *s)
{
    jbuf b = {0};
    jb_lit(&b, "{\"k\":");
    jb_json_string(&b, s);
    jb_lit(&b, ",\"j\":2}");
    FUZZ_ASSERT(json_valid(b.p, b.n));
    char *got = NULL;
    FUZZ_ASSERT(json_get_string(b.p, "k", &got));
    char *want = malloc(strlen(s) + 1);
    if (want == NULL) return;                 // allocator OOM is not a bug
    drop_dropped(s, want);
    FUZZ_ASSERT(strcmp(got, want) == 0);
    free(want);
    free(got);
    jb_free(&b);
}

static void one(const char *s, void *ud)
{
    (void)ud;
    char *v = NULL;
    (void)json_get_string(s, "k", &v); free(v);
    (void)json_get_string(s, "s", &v); free(v);
    (void)json_has_member(s, "m");
    (void)json_top_array(s, "a");
    long i;
    (void)json_get_int(s, "i", &i);
    const char *vs, *ve;
    (void)json_raw(s, "r", &vs, &ve);

    jspan *sp = NULL;
    int n = json_array_spans(s, &sp);
    for (int k = 0; k < n; k++) {
        char *sv = NULL;
        if (json_span_string(sp[k].s, sp[k].e, &sv)) free(sv);
        bool bv;
        (void)json_span_bool(sp[k].s, sp[k].e, &bv);
        long iv;
        (void)json_span_int(sp[k].s, sp[k].e, &iv);
    }
    free(sp);

    jobject o;
    if (jobject_parse(s, &o)) {
        for (int k = 0; k < o.n; k++) {
            char *sv = NULL;
            if (json_span_string(o.vals[k], o.vends[k], &sv)) free(sv);
            bool bv;
            (void)json_span_bool(o.vals[k], o.vends[k], &bv);
            long iv;
            (void)json_span_int(o.vals[k], o.vends[k], &iv);
        }
        (void)jobject_find(&o, "a");
        jobject_free(&o);
    }
    writer_round_trip(s);
}

void fuzz_target(const unsigned char *data, size_t size)
{
    // json_valid is length-aware, so it gets the raw bytes
    (void)json_valid((const char *)data, size);
    fuzz_for_each_segment(data, size, one, NULL);
}
