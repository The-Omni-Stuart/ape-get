#include "json.h"
#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// ---- strict validating parser ----

typedef struct { const char *s, *e, *key_start; } J;

static void skip(J *j)
{
    while (j->s < j->e && (*j->s == ' ' || *j->s == '\t'
        || *j->s == '\n' || *j->s == '\r')) j->s++;
}

static bool lit(J *j, const char *w)
{
    size_t n = strlen(w);
    if ((size_t)(j->e - j->s) < n || memcmp(j->s, w, n) != 0) return false;
    j->s += n;
    return true;
}

static bool string_(J *j)
{
    if (j->s == j->e || *j->s != '"') return false;
    j->s++;
    while (j->s < j->e) {
        unsigned char c = (unsigned char)*j->s++;
        if (c == '"') return true;
        if (c < 0x20) return false;
        if (c == '\\') {
            if (j->s == j->e) return false;
            char e = *j->s++;
            if (e == 'u') {
                for (int k = 0; k < 4; k++)
                    if (j->s == j->e || !isxdigit((unsigned char)*j->s++)) return false;
            } else if (strchr("\"\\/bfnrt", e) == NULL) return false;
        }
    }
    return false;
}

static bool digits(J *j)
{
    int n = 0;
    while (j->s < j->e && isdigit((unsigned char)*j->s)) { j->s++; n++; }
    return n > 0;
}

static bool number_(J *j)
{
    if (j->s < j->e && *j->s == '-') j->s++;
    if (!digits(j)) return false;
    if (j->s < j->e && *j->s == '.') { j->s++; if (!digits(j)) return false; }
    if (j->s < j->e && (*j->s == 'e' || *j->s == 'E')) {
        j->s++;
        if (j->s < j->e && (*j->s == '-' || *j->s == '+')) j->s++;
        if (!digits(j)) return false;
    }
    return true;
}

static bool value(J *j, int depth);

static bool object_(J *j, int depth)
{
    j->s++;
    skip(j);
    if (j->s != j->e && *j->s == '}') { j->s++; return true; }
    for (;;) {
        skip(j);
        if (!string_(j)) return false;
        skip(j);
        if (j->s == j->e || *j->s != ':') return false;
        j->s++;
        skip(j);
        if (!value(j, depth + 1)) return false;
        skip(j);
        if (j->s == j->e) return false;
        char c = *j->s++;
        if (c == '}') return true;
        if (c != ',') return false;
    }
}

static bool array_(J *j, int depth)
{
    j->s++;
    skip(j);
    if (j->s != j->e && *j->s == ']') { j->s++; return true; }
    for (;;) {
        skip(j);
        if (!value(j, depth + 1)) return false;
        skip(j);
        if (j->s == j->e) return false;
        char c = *j->s++;
        if (c == ']') return true;
        if (c != ',') return false;
    }
}

static bool value(J *j, int depth)
{
    if (depth > 128 || j->s == j->e) return false;
    switch (*j->s) {
    case '"': return string_(j);
    case '{': return object_(j, depth);
    case '[': return array_(j, depth);
    case 't': return lit(j, "true");
    case 'f': return lit(j, "false");
    case 'n': return lit(j, "null");
    default:  return number_(j);
    }
}

bool json_valid(const char *s, size_t n)
{
    J j = { s, s + n, NULL };
    skip(&j);
    if (!value(&j, 0)) return false;
    skip(&j);
    return j.s == j.e;
}

// ---- top-level object member lookup ----

// Positions j->s on the member's value when matched, else returns false.
static bool scan_object(J *j, const char *key)
{
    if (j->s == j->e || *j->s != '{') return false;
    j->s++;
    size_t klen = strlen(key);
    skip(j);
    if (j->s != j->e && *j->s == '}') return false;
    for (;;) {
        skip(j);
        j->key_start = j->s;
        if (!string_(j)) return false;
        size_t ksize = (size_t)(j->s - j->key_start - 2);
        bool match = ksize == klen && memcmp(j->key_start + 1, key, klen) == 0;
        skip(j);
        if (j->s == j->e || *j->s != ':') return false;
        j->s++;
        skip(j);
        const char *vs = j->s;
        if (!value(j, 0)) return false;
        if (match) { j->s = vs; return true; }
        skip(j);
        if (j->s == j->e) return false;
        char c = *j->s++;
        if (c == '}') return false;
        if (c != ',') return false;
    }
}

static bool seek(const char *s, const char *key, J *out)
{
    J j = { s, s + strlen(s), NULL };
    skip(&j);
    *out = j;
    return scan_object(out, key);
}

bool json_get_string(const char *s, const char *key, char **out)
{
    J j;
    if (!seek(s, key, &j)) return false;
    const char *vs = j.s;
    if (vs == j.e || *vs != '"') return false;
    if (!string_(&j)) return false;
    const char *p = vs + 1, *end = j.s - 1;
    char *r = malloc((size_t)(end - p) + 1);
    if (r == NULL) return false;
    char *w = r;
    while (p < end) {
        if (*p == '\\' && p + 1 < end) {
            p++;
            if (*p == 'u') { p += 5; continue; }   // skip u + 4 hex
            *w++ = *p == 'n' ? '\n' : *p == 't' ? '\t' : *p == 'r' ? '\r' : *p;
            p++;
        } else *w++ = *p++;
    }
    *w = '\0';
    *out = r;
    return true;
}

bool json_get_int(const char *s, const char *key, long *out)
{
    J j;
    if (!seek(s, key, &j)) return false;
    const char *vs = j.s;
    if (!value(&j, 0) || j.s == vs || (*vs != '-' && !isdigit((unsigned char)*vs)))
        return false;
    char buf[24];
    size_t n = (size_t)(j.s - vs);
    if (n >= sizeof(buf)) return false;
    memcpy(buf, vs, n);
    buf[n] = '\0';
    *out = strtol(buf, NULL, 10);
    return true;
}

bool json_has_member(const char *s, const char *key)
{
    J j;
    if (!seek(s, key, &j)) return false;
    return value(&j, 0);
}

bool json_top_array(const char *s, const char *key)
{
    J j;
    if (!seek(s, key, &j)) return false;
    return *j.s == '[' && value(&j, 0);
}

bool json_raw(const char *s, const char *key, const char **vs, const char **ve)
{
    J j;
    if (!seek(s, key, &j)) return false;
    *vs = j.s;
    if (!value(&j, 0)) return false;
    *ve = j.s;
    return true;
}

int json_array_spans(const char *s, jspan **out)
{
    J j = { s, s + strlen(s), NULL };
    skip(&j);
    if (j.s == j.e || *j.s != '[') return -1;
    j.s++;
    skip(&j);
    *out = NULL;
    if (j.s != j.e && *j.s == ']') return 0;
    jspan *spans = NULL;
    int n = 0, cap = 0;
    for (;;) {
        skip(&j);
        const char *vs = j.s;
        if (!value(&j, 0)) goto fail;
        if (n == cap) {
            cap = cap ? cap * 2 : 8;
            spans = realloc(spans, (size_t)cap * sizeof(*spans));
            if (spans == NULL) goto fail;
        }
        spans[n].s = vs;
        spans[n].e = j.s;
        n++;
        skip(&j);
        if (j.s == j.e) goto fail;
        char c = *j.s++;
        if (c == ']') { *out = spans; return n; }
        if (c != ',') goto fail;
    }
fail:
    free(spans);
    return -1;
}

static int hex4(const char *p, const char *end)
{
    if (end - p < 4) return -1;
    int v = 0;
    for (int i = 0; i < 4; i++) {
        char c = p[i];
        int d = c >= '0' && c <= '9' ? c - '0'
              : c >= 'a' && c <= 'f' ? c - 'a' + 10
              : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
        if (d < 0) return -1;
        v = v * 16 + d;
    }
    return v;
}

static char *utf8_append(char *w, unsigned long cp)
{
    if (cp < 0x80) {
        *w++ = (char)cp;
    } else if (cp < 0x800) {
        *w++ = (char)(0xC0 | (cp >> 6));
        *w++ = (char)(0x80 | (cp & 0x3F));
    } else if (cp < 0x10000) {
        *w++ = (char)(0xE0 | (cp >> 12));
        *w++ = (char)(0x80 | ((cp >> 6) & 0x3F));
        *w++ = (char)(0x80 | (cp & 0x3F));
    } else {
        *w++ = (char)(0xF0 | (cp >> 18));
        *w++ = (char)(0x80 | ((cp >> 12) & 0x3F));
        *w++ = (char)(0x80 | ((cp >> 6) & 0x3F));
        *w++ = (char)(0x80 | (cp & 0x3F));
    }
    return w;
}

static bool unquote(const char *vs, const char *ve, char **out)
{
    // vs/ve bracket a JSON string value including the quotes
    if (ve - vs < 2 || *vs != '"' || ve[-1] != '"') return false;
    const char *p = vs + 1, *end = ve - 1;
    char *r = malloc((size_t)(end - p) + 1);
    if (r == NULL) return false;
    char *w = r;
    while (p < end) {
        if (*p == '\\' && p + 1 < end) {
            p++;
            if (*p == 'u') {
                long cp = hex4(p + 1, end);
                if (cp < 0) { free(r); return false; }
                p += 5;
                if (cp >= 0xD800 && cp <= 0xDBFF) {
                    if (end - p < 6 || p[0] != '\\' || p[1] != 'u')
                        { free(r); return false; }
                    long lo = hex4(p + 2, end);
                    if (lo < 0xDC00 || lo > 0xDFFF) { free(r); return false; }
                    cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                    p += 6;
                } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
                    free(r); return false;   // unpaired low surrogate
                }
                w = utf8_append(w, (unsigned long)cp);
                continue;
            }
            *w++ = *p == 'n' ? '\n' : *p == 't' ? '\t' : *p == 'r' ? '\r' : *p;
            p++;
        } else *w++ = *p++;
    }
    *w = '\0';
    *out = r;
    return true;
}

bool json_span_string(const char *vs, const char *ve, char **out)
{
    if (ve - vs == 4 && memcmp(vs, "null", 4) == 0) { *out = NULL; return true; }
    return unquote(vs, ve, out);
}

bool json_span_bool(const char *vs, const char *ve, bool *out)
{
    if (ve - vs == 4 && memcmp(vs, "true", 4) == 0) { *out = true; return true; }
    if (ve - vs == 5 && memcmp(vs, "false", 5) == 0) { *out = false; return true; }
    return false;
}

bool json_span_int(const char *vs, const char *ve, long *out)
{
    char buf[24];
    size_t n = (size_t)(ve - vs);
    if (n == 0 || n >= sizeof(buf)) return false;
    memcpy(buf, vs, n);
    buf[n] = '\0';
    char *endp = NULL;
    long v = strtol(buf, &endp, 10);
    if (*endp != '\0') return false;
    *out = v;
    return true;
}

bool jobject_parse(const char *s, jobject *o)
{
    memset(o, 0, sizeof(*o));
    J j = { s, s + strlen(s), NULL };
    skip(&j);
    if (j.s == j.e || *j.s != '{') return false;
    j.s++;
    skip(&j);
    if (j.s != j.e && *j.s == '}') return true;
    for (;;) {
        skip(&j);
        j.key_start = j.s;
        if (!string_(&j)) goto fail;
        char *key;
        if (!unquote(j.key_start, j.s, &key)) goto fail;
        skip(&j);
        if (j.s == j.e || *j.s != ':') { free(key); goto fail; }
        j.s++;
        skip(&j);
        const char *vs = j.s;
        if (!value(&j, 0)) { free(key); goto fail; }
        if (o->n == o->cap) {
            o->cap = o->cap ? o->cap * 2 : 8;
            o->keys = realloc(o->keys, (size_t)o->cap * sizeof(*o->keys));
            o->vals = realloc(o->vals, (size_t)o->cap * sizeof(*o->vals));
            o->vends = realloc(o->vends, (size_t)o->cap * sizeof(*o->vends));
        }
        o->keys[o->n] = key;
        o->vals[o->n] = vs;
        o->vends[o->n] = j.s;
        o->n++;
        skip(&j);
        if (j.s == j.e) goto fail;
        char c = *j.s++;
        if (c == '}') return true;
        if (c != ',') goto fail;
    }
fail:
    jobject_free(o);
    return false;
}

void jobject_free(jobject *o)
{
    for (int k = 0; k < o->n; k++) free(o->keys[k]);
    free(o->keys);
    free(o->vals);
    free(o->vends);
    memset(o, 0, sizeof(*o));
}

int jobject_find(const jobject *o, const char *key)
{
    for (int k = 0; k < o->n; k++)
        if (strcmp(o->keys[k], key) == 0) return k;
    return -1;
}

// ---- writer ----

static void jb_grow(jbuf *b, size_t need)
{
    if (b->n + need + 1 <= b->cap) return;
    size_t cap = b->cap ? b->cap : 128;
    while (cap < b->n + need + 1) cap *= 2;
    b->p = realloc(b->p, cap);
    b->cap = cap;
}

void jb_lit(jbuf *b, const char *lit)
{
    size_t n = strlen(lit);
    jb_grow(b, n);
    memcpy(b->p + b->n, lit, n);
    b->n += n;
    b->p[b->n] = '\0';
}

void jb_json_string(jbuf *b, const char *s)
{
    jb_lit(b, "\"");
    for (const unsigned char *p = (const unsigned char *)s; *p; p++) {
        unsigned char c = *p;
        char esc[7] = "\\u0000";
        if (c == '"' || c == '\\') { esc[1] = c; jb_grow(b, 2), b->p[b->n++] = '\\', b->p[b->n++] = c, b->p[b->n] = '\0'; }
        else if (c == '\n') jb_lit(b, "\\n");
        else if (c == '\t') jb_lit(b, "\\t");
        else if (c == '\r') jb_lit(b, "\\r");
        else if (c < 0x20 || c == 0x7f) {
            esc[4] = "0123456789abcdef"[c >> 4];   // keep esc[6] = NUL
            esc[5] = "0123456789abcdef"[c & 15];
            jb_lit(b, esc);
        } else { jb_grow(b, 1), b->p[b->n++] = c, b->p[b->n] = '\0'; }
    }
    jb_lit(b, "\"");
}

void jb_free(jbuf *b)
{
    free(b->p);
    b->p = NULL;
    b->n = b->cap = 0;
}

void json_null_or_string(jbuf *b, const char *s)
{
    if (s == NULL) jb_lit(b, "null");
    else jb_json_string(b, s);
}

// emit "key":<string|null> with a leading comma unless first
void jb_kv(jbuf *b, const char *key, const char *v, bool *first)
{
    jb_lit(b, *first ? "" : ",");
    *first = false;
    jb_lit(b, "\"");
    jb_lit(b, key);
    jb_lit(b, "\":");
    json_null_or_string(b, v);
}
