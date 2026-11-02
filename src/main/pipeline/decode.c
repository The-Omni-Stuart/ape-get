// .xz transport-envelope decode: one streaming pass with hostile-input
// guards (docs/spec-mesh-delivery.md §4, I2/I5/I6). Output and input
// sizes are capped before anything is allocated or trusted; the caller
// verifies the decoded bytes afterwards.
#include "internal.h"
#include <errno.h>
#include <fcntl.h>
#include <lzma.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define XZ_OUT_CAP   (8u << 20)   // decoded artifact hard cap
#define XZ_IN_CAP    (16u << 20)  // envelope cap: honest xz is ~its output
#define XZ_DICT_CAP  (8u << 20)   // CI's own dict; bigger refuses to decode
#define XZ_MEMLIMIT  (16u << 20)  // liblzma allocator cap (worst RAM ~12 MiB)

// LZMA2 dict-size property byte -> bytes (same decode as lzma2_dec.c)
static uint32_t lzma2_props_dict(unsigned char b)
{
    if ((b & (1u << 6)) == 0) return (1u << 12) + ((b & 0x3fu) << 12);
    return (2u + (b & 1u)) << (b >> 1);
}

// xz multibyte integer: 7-bit groups, low bit first, high bit continues.
// Non-compact padding (final byte 0x00 after a continuation) is invalid.
static bool vli(const unsigned char *p, size_t len, size_t *pos,
                unsigned long long *out)
{
    unsigned long long v = 0;
    for (size_t i = 0; i < 9; i++) {
        if (*pos >= len) return false;
        unsigned char b = p[*pos];
        *pos += 1;
        v += (unsigned long long)(b & 0x7f) << (i * 7);
        if (i == 8 && (b & 0x7f) == 0) return false;
        if ((b & 0x80) == 0) {
            if (b == 0x00 && i > 0) return false;
            *out = v;
            return true;
        }
    }
    return false;
}

// Parse the stream header and the first block header without liblzma
// (which cannot expose them before allocating). Accept only what our CI
// emits: LZMA2-only filter chain, non-none stream check, dict <= cap.
static bool xz_headers_ok(const unsigned char *h, size_t len)
{
    if (len < 24 || memcmp(h, "\xfd" "7zXZ\0", 6) != 0) return false;
    if (h[6] != 0) return false;                   // stream flags byte 0
    unsigned char check = h[7] & 0x0fu;            // stream flags byte 1
    if (check != 1 && check != 4 && check != 10)
        return false;                              // crc32/crc64/sha256 only
    if ((h[7] & 0xf0u) != 0) return false;         // reserved stream flags
    if (len < 13) return false;
    size_t pos = 12;                               // first block header
    unsigned long long hs = ((h[pos] & 0x3fu) + 1) * 4;  // quadruples, +1
    size_t hend = pos + (size_t)hs;                // header ends after its CRC32
    if (hend > len) return false;
    pos++;
    unsigned char flags = h[pos];
    if ((flags & 0x3cu) != 0) return false;        // reserved block flags
    pos++;
    if ((flags & 0x40u) && !vli(h, hend, &pos, &hs)) return false;
    if ((flags & 0x80u) && !vli(h, hend, &pos, &hs)) return false;
    if ((flags & 3u) + 1 != 1) return false;       // exactly one filter
    unsigned long long fid, psz;
    if (!vli(h, hend, &pos, &fid)) return false;
    if (!vli(h, hend, &pos, &psz)) return false;
    if (fid != 0x21 || psz != 1) return false;     // LZMA2, one props byte
    if (pos + 1 > hend - 4) return false;          // props before the CRC32
    return lzma2_props_dict(h[pos]) <= XZ_DICT_CAP;
}

static bool drain(int fd, const unsigned char *buf, size_t len)
{
    size_t w = 0;
    while (w < len) {
        ssize_t k;
        do k = write(fd, buf + w, len - w);
        while (k < 0 && errno == EINTR);
        if (k < 0) return false;
        w += (size_t) k;
    }
    return true;
}

// Decode in_path (.xz) into out_path (caller-chosen .tmp form).
// 0 on success; anything else means: out_path is gone, take another asset.
int xz_decode_file(const char *in_path, const char *out_path)
{
    struct stat sb;
    if (stat(in_path, &sb) != 0 || (unsigned long long) sb.st_size > XZ_IN_CAP)
        return -1;
    unsigned char head[64];
    int hfd = open(in_path, O_RDONLY | O_CLOEXEC);
    if (hfd < 0) return -1;
    ssize_t hn = read(hfd, head, sizeof head);
    close(hfd);
    if (hn < 24 || !xz_headers_ok(head, (size_t) hn)) return -1;

    int in = open(in_path, O_RDONLY | O_CLOEXEC);
    if (in < 0) return -1;
    int out = open(out_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (out < 0) { close(in); return -1; }

    lzma_stream strm = LZMA_STREAM_INIT;
    if (lzma_stream_decoder(&strm, XZ_MEMLIMIT, 0) != LZMA_OK) {
        close(in); close(out); unlink(out_path);
        return -1;
    }
    unsigned char ib[4096], ob[4096];
    size_t total = 0;
    int eof_in = 0, ok = 0;
    lzma_ret r = LZMA_OK;
    for (;;) {
        if (strm.avail_in == 0 && !eof_in) {
            ssize_t n;
            do n = read(in, ib, sizeof ib);
            while (n < 0 && errno == EINTR);
            if (n < 0) break;
            if (n == 0) eof_in = 1;
            strm.next_in = ib;
            strm.avail_in = (size_t) n;
        }
        strm.next_out = ob;
        strm.avail_out = sizeof ob;
        r = lzma_code(&strm, eof_in ? LZMA_FINISH : LZMA_RUN);
        total += sizeof ob - strm.avail_out;
        if (total > XZ_OUT_CAP) break;
        if (!drain(out, ob, sizeof ob - strm.avail_out)) break;
        if (r == LZMA_STREAM_END) {
            // padding after the stream is forbidden; flags 0 already
            // rejects concatenated streams, this catches trailing bytes
            ok = strm.avail_in == 0;
            break;
        }
        if (r != LZMA_OK) break;
        if (eof_in) break;   // FINISH outstanding: never returns LZMA_OK
    }
    lzma_end(&strm);
    close(in);
    if (close(out) != 0) ok = 0;
    if (!ok) unlink(out_path);
    return ok ? 0 : -1;
}
