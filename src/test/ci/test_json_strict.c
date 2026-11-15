// Strict-parser gate for json.c: RFC 8259 numbers, whole-document
// consumption, integer-only getters, and one shared unescape path.
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "json.h"

static int fails;

#define CHECK(cond) do {                                        \
    if (!(cond)) {                                              \
        fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__,  \
            #cond);                                             \
        fails++;                                                \
    }                                                           \
} while (0)

static bool valid(const char *doc)
{
    return json_valid(doc, strlen(doc));
}

static void numbers(void)
{
    CHECK(!valid("01"));
    CHECK(!valid("00"));
    CHECK(!valid("-01"));
    CHECK(valid("0"));
    CHECK(valid("-0"));
    CHECK(valid("10"));
    CHECK(valid("1.5"));
    CHECK(!valid("1."));
    CHECK(!valid(".5"));
    CHECK(valid("1e3"));
    CHECK(!valid("{\"a\":01}"));
    CHECK(!valid("{\"a\":-00}"));
    CHECK(!valid("[1,01]"));
    CHECK(valid("[0, -0, 12, -34, 0.5e-2]"));
}

static void whole_document(void)
{
    jobject o;
    CHECK(!jobject_parse("{}garbage", &o));
    CHECK(!jobject_parse("{ } x", &o));
    CHECK(!jobject_parse("{\"a\":1} trailing", &o));
    CHECK(!jobject_parse("{\"a\":1}}", &o));
    CHECK(jobject_parse("{}  \r\n", &o));
    jobject_free(&o);
    CHECK(jobject_parse("{\"a\":1,\"b\":[1,2]}", &o));
    jobject_free(&o);

    jspan *sp = NULL;
    CHECK(json_array_spans("[]", &sp) == 0);
    CHECK(json_array_spans("[] x", &sp) == -1);
    CHECK(json_array_spans("[1]garbage", &sp) == -1);
    CHECK(json_array_spans("[1] ", &sp) == 1);
    if (sp) free(sp);
    sp = NULL;
    CHECK(json_array_spans("[1,2]", &sp) == 2);
    if (sp) free(sp);
    CHECK(json_array_spans("[01]", &sp) == -1);
}

static bool get_int(const char *doc, const char *key, long *v)
{
    return json_get_int(doc, key, v);
}

static bool span_int(const char *lit, long *out)
{
    return json_span_int(lit, lit + strlen(lit), out);
}

static void int_getters(void)
{
    long v = 12345;
    CHECK(get_int("{\"a\":1.5}", "a", &v) == false);
    CHECK(get_int("{\"a\":1e2}", "a", &v) == false);
    CHECK(get_int("{\"a\":01}", "a", &v) == false);
    CHECK(get_int("{\"a\":1.}", "a", &v) == false);
    CHECK(get_int("{\"a\":9223372036854775808}", "a", &v) == false);
    CHECK(get_int("{\"a\":99999999999999999999}", "a", &v) == false);
    CHECK(get_int("{\"a\":1.5e10}", "a", &v) == false);
    v = -1;
    CHECK(get_int("{\"a\":5}", "a", &v) && v == 5);
    v = -1;
    CHECK(get_int("{\"a\":-0}", "a", &v) && v == 0);
    v = 0;
    CHECK(get_int("{\"a\":9223372036854775807}", "a", &v)
          && v == LONG_MAX);
    v = 0;
    CHECK(get_int("{\"a\":-9223372036854775808}", "a", &v)
          && v == LONG_MIN);

    CHECK(!span_int("1.5", &v));
    CHECK(span_int("-0", &v) && v == 0);
    CHECK(!span_int("99999999999999999999", &v));
    CHECK(!span_int("9223372036854775808", &v));
    CHECK(span_int("9223372036854775807", &v) && v == LONG_MAX);
}

static bool get_str(const char *doc, const char *key, char **out)
{
    return json_get_string(doc, key, out);
}

static bool span_str(const char *lit, char **out)
{
    return json_span_string(lit, lit + strlen(lit), out);
}

static void one_unescape(void)
{
    char *s = NULL;

    // the review's reproduction: escapes that are path separators
    CHECK(get_str("{\"p\":\"\\u002fC\\u002fUsers\\u002fK\"}", "p", &s)
          && !strcmp(s, "/C/Users/K"));
    free(s);

    // control escapes map to their characters, not the bare letter
    CHECK(get_str("{\"p\":\"a\\bb\\ff\\n\"}", "p", &s)
          && !strcmp(s, "a\bb\ff\n"));
    free(s);

    // quotes, backslash, solidus
    CHECK(get_str("{\"p\":\"\\\"\\\\\\/\"}", "p", &s)
          && !strcmp(s, "\"\\/"));
    free(s);

    // BMP and astral escapes agree with the span decoder byte for byte
    CHECK(get_str("{\"p\":\"caf\\u00e9\"}", "p", &s)
          && !strcmp(s, "caf\xc3\xa9"));
    free(s);
    CHECK(get_str("{\"p\":\"\\ud83d\\ude00x\"}", "p", &s)
          && !strcmp(s, "\xf0\x9f\x98\x80" "x"));
    free(s);
    CHECK(span_str("\"caf\\u00e9\"", &s) && !strcmp(s, "caf\xc3\xa9"));
    free(s);
    CHECK(span_str("\"\\ud83d\\ude00\"", &s) && !strcmp(s, "\xf0\x9f\x98\x80"));
    free(s);

    // strict surrogates: unpaired halves are rejected by both paths
    CHECK(!get_str("{\"p\":\"\\ud800\"}", "p", &s));
    CHECK(!get_str("{\"p\":\"\\udc00\"}", "p", &s));
    CHECK(!span_str("\"\\udbffx\"", &s));
    // NUL escapes would silently truncate every C-string consumer
    CHECK(!get_str("{\"p\":\"a\\u0000b\"}", "p", &s));
    CHECK(!span_str("\"\\u0000\"", &s));
}

int main(void)
{
    numbers();
    whole_document();
    int_getters();
    one_unescape();
    if (fails) {
        fprintf(stderr, "json_strict: %d failed\n", fails);
        return 1;
    }
    printf("json_strict: all checks passed\n");
    return 0;
}
