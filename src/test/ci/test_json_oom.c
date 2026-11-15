// Deterministic allocator-fault proof for json.c's growth paths: the
// module is compiled into this probe so realloc can fail on cue, and
// every fallible growth must answer with a clean failure, never a crash
// into a lost or NULL block.
#include <ctype.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int fail_nth, realloc_calls;   // fail_nth = which call returns NULL

static void *oom_realloc(void *p, size_t n)
{
    if (fail_nth > 0 && ++realloc_calls == fail_nth) return NULL;
    return realloc(p, n);
}
#define realloc(p, n) oom_realloc(p, n)

#include "json.c"

static int fails;

#define CHECK(cond) do {                                        \
    if (!(cond)) {                                              \
        fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__,  \
            #cond);                                             \
        fails++;                                                \
    }                                                           \
} while (0)

// 12 members grow the three parallel arrays twice (3 reallocs per
// growth round), so calls 1..6 each get a failing run
static void jobject_growth_oom(void)
{
    const char *doc =
        "{\"a\":1,\"b\":2,\"c\":3,\"d\":4,\"e\":5,\"f\":6,"
        "\"g\":7,\"h\":8,\"i\":9,\"j\":10,\"k\":11,\"l\":12}";
    for (fail_nth = 1; fail_nth <= 6; fail_nth++) {
        realloc_calls = 0;
        jobject o;
        CHECK(!jobject_parse(doc, &o));   // must not deref a lost block
        CHECK(o.keys == NULL && o.n == 0);
    }
    fail_nth = 0;
    jobject o;
    CHECK(jobject_parse(doc, &o) && o.n == 12);
    jobject_free(&o);
}

static void jbuf_growth_oom(void)
{
    char big[400];
    memset(big, 'x', sizeof big - 1);
    big[sizeof big - 1] = '\0';
    for (fail_nth = 1; fail_nth <= 3; fail_nth++) {
        realloc_calls = 0;
        jbuf b = {0};
        jb_lit(&b, "{\"a\":");     // first growth
        jb_lit(&b, big);           // the growth that may fail
        jb_lit(&b, "}");           // must be refused once poisoned
        jb_json_string(&b, big);   // and again here
        // the one documented contract: p == NULL means out of memory
        CHECK(b.p == NULL && b.n == 0);
        jb_free(&b);
    }
    fail_nth = 0;
    jbuf b = {0};
    jb_lit(&b, "{\"a\":");
    jb_json_string(&b, "x\"y");
    CHECK(b.p != NULL && !strcmp(b.p, "{\"a\":\"x\\\"y\""));
    jb_free(&b);
}

// 13 elements grow the span array twice: calls 1..2 each fail once
static void array_growth_oom(void)
{
    const char *doc = "[1,2,3,4,5,6,7,8,9,10,11,12,13]";
    for (fail_nth = 1; fail_nth <= 2; fail_nth++) {
        realloc_calls = 0;
        jspan *sp = NULL;
        CHECK(json_array_spans(doc, &sp) == -1 && sp == NULL);
    }
    fail_nth = 0;
    jspan *sp = NULL;
    CHECK(json_array_spans(doc, &sp) == 13);
    free(sp);
}

int main(void)
{
    jobject_growth_oom();
    jbuf_growth_oom();
    array_growth_oom();
    fail_nth = 0;
    if (fails) {
        fprintf(stderr, "json_oom: %d failed\n", fails);
        return 1;
    }
    printf("json_oom: all checks passed\n");
    return 0;
}
