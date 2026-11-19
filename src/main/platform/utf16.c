// The two directions between UTF-16 and the UTF-8 every other path in this
// program is held in. Only the platform module needs them, at the two places
// where the Windows kernel is spoken to directly; the merge and split of a
// surrogate pair live here so neither of the callers has to know what one is.

#include "platform/internal.h"

#include <libc/str/utf16.h>
#include <stdlib.h>
#include <string.h>

// Both write the exact number of code units or bytes they need, so both are
// paired with free(). Neither refuses a length: whoever called them has already
// decided the input is worth carrying.

// One code unit costs at most three bytes, and a joined pair is two units that
// cost four between them.
char *platform_utf8_of_utf16(const char16_t *wide)
{
    size_t units = 0;
    while (wide[units] != 0) units++;
    char *out = malloc(units * 3 + 1);
    if (out == NULL) return NULL;
    size_t w = 0;
    for (size_t r = 0; wide[r] != 0; r++) {
        unsigned u = wide[r];
        // Two code units name one character beyond the Basic Multilingual
        // Plane, and the pair has to be joined before it can be written.
        if (IsHighSurrogate(u) && IsLowSurrogate(wide[r + 1])) {
            u = MergeUtf16(u, wide[r + 1]);
            r++;
        }
        if (u < 0x80) {
            out[w++] = (char)u;
        } else if (u < 0x800) {
            out[w++] = (char)(0xc0 | (u >> 6));
            out[w++] = (char)(0x80 | (u & 0x3f));
        } else if (u < 0x10000) {
            out[w++] = (char)(0xe0 | (u >> 12));
            out[w++] = (char)(0x80 | ((u >> 6) & 0x3f));
            out[w++] = (char)(0x80 | (u & 0x3f));
        } else {
            out[w++] = (char)(0xf0 | (u >> 18));
            out[w++] = (char)(0x80 | ((u >> 12) & 0x3f));
            out[w++] = (char)(0x80 | ((u >> 6) & 0x3f));
            out[w++] = (char)(0x80 | (u & 0x3f));
        }
    }
    out[w] = '\0';
    return out;
}

// A character beyond the Basic Multilingual Plane is named by a pair, and the
// arithmetic is the inverse of the merge above.
static void write_pair(char16_t *out, size_t *w, unsigned u)
{
    u -= 0x10000;
    out[(*w)++] = (char16_t)(0xd800 + (u >> 10));
    out[(*w)++] = (char16_t)(0xdc00 + (u & 0x3ff));
}

// Bytes that are not valid UTF-8 are carried over as the code units of the same
// value, which is what Windows itself makes of a name it cannot decode: a path
// is not rewritten into a different path on the way past this line. That rule
// also bounds the buffer, since one byte never costs more than one unit.
char16_t *platform_utf16_of_utf8(const char *text)
{
    size_t n = strlen(text);
    char16_t *out = malloc((n + 1) * sizeof *out);
    if (out == NULL) return NULL;
    size_t w = 0;
    for (size_t r = 0; r < n; ) {
        unsigned char b = (unsigned char)text[r];
        unsigned u;
        size_t more;
        if (b < 0x80) {
            u = b;
            more = 0;
        } else if ((b & 0xe0) == 0xc0 && r + 1 < n) {
            u = b & 0x1fu;
            more = 1;
        } else if ((b & 0xf0) == 0xe0 && r + 2 < n) {
            u = b & 0x0fu;
            more = 2;
        } else if ((b & 0xf8) == 0xf0 && r + 3 < n) {
            u = b & 0x07u;
            more = 3;
        } else {
            out[w++] = b;   // a stray byte: keep it rather than lose it
            r++;
            continue;
        }
        for (size_t i = 1; i <= more; i++)
            u = (u << 6) | ((unsigned char)text[r + i] & 0x3fu);
        r += more + 1;
        if (u < 0x10000) out[w++] = (char16_t)u;
        else write_pair(out, &w, u);
    }
    out[w] = 0;
    return out;
}
