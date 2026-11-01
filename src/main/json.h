#ifndef APEGET_JSON_H
#define APEGET_JSON_H

#include <stdbool.h>
#include <stddef.h>

// Minimal strict JSON: validation for state files, top-level member lookup,
// and an escape-correct writer for --json output.

bool json_valid(const char *s, size_t n);

// Top-level member of an object. False when the input is not an object or
// the member is absent or of the wrong type; json_get_string mallocs *out.
bool json_get_string(const char *s, const char *key, char **out);
bool json_get_int(const char *s, const char *key, long *out);
bool json_has_member(const char *s, const char *key);
bool json_top_array(const char *s, const char *key);

// Raw span of a top-level member value [vs, ve), or false.
bool json_raw(const char *s, const char *key, const char **vs, const char **ve);

// Element spans of a JSON array (caller frees *out); count, or -1 invalid.
typedef struct jspan { const char *s, *e; } jspan;
int json_array_spans(const char *s, jspan **out);

// Flat DOM for one object: keys decoded, values kept as raw spans in file
// order. Non-object input fails.
typedef struct jobject {
    char **keys; const char **vals; const char **vends;
    int n, cap;
} jobject;
bool jobject_parse(const char *s, jobject *o);
void jobject_free(jobject *o);
int jobject_find(const jobject *o, const char *key);   // index or -1

// Span decoders; span_string unescapes and maps a null value to *out NULL.
bool json_span_string(const char *vs, const char *ve, char **out);
bool json_span_bool(const char *vs, const char *ve, bool *out);
bool json_span_int(const char *vs, const char *ve, long *out);

// Growable output buffer; jb_lit appends a NUL-terminated literal,
// jb_json_string appends s JSON-escaped inside quotes.
typedef struct jbuf { char *p; size_t n, cap; } jbuf;
void jb_lit(jbuf *b, const char *lit);
void jb_json_string(jbuf *b, const char *s);
void jb_free(jbuf *b);

// JSON value writers: null-or-escaped string, and a "key":<string|null>
// pair with a leading comma unless *first.
void json_null_or_string(jbuf *b, const char *s);
void jb_kv(jbuf *b, const char *key, const char *v, bool *first);

#endif
