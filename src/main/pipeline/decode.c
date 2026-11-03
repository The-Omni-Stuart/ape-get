// .xz transport-envelope decode: one streaming pass with hostile-input
// guards (docs/spec-mesh-delivery.md §4, I2/I5/I6). Output and input
// sizes are capped before anything is allocated or trusted; the caller
// verifies the decoded bytes afterwards.
// Plus the xdelta apply stage: patch + single-fd installed source
// (already hashed by the caller, I4) -> final bytes, streamed with the
// same output cap.
#include "internal.h"
#include <errno.h>
#include <fcntl.h>
#include <lzma.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <xdelta3.h>

#define XZ_OUT_CAP   (8u << 20)   // decoded artifact hard cap
#define XZ_IN_CAP    (16u << 20)  // envelope cap: honest xz is ~its output
#define XZ_DICT_CAP  (8u << 20)   // CI's own dict; bigger refuses to decode
#define XZ_MEMLIMIT  (16u << 20)  // liblzma allocator cap (worst RAM ~9 MiB)

// LZMA2 dict-size property byte -> bytes, the same decode as
// lzma_lzma2_props_decode: (2 + p&1) << (p/2 + 11); above 40 (which
// means 4 GiB) and the 0xC0 bits are invalid.
static uint32_t lzma2_props_dict(unsigned char b)
{
    if ((b & 0xc0u) != 0 || b > 40) return UINT32_MAX;   // invalid/4 GiB
    return (2u + (b & 1u)) << (b / 2u + 11u);
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

// ---- xdelta apply -------------------------------------------------------
// VCDIFF header/window-indicator bits (xdelta3.c's private definitions,
// mirrored for the pre-parse only).
#define VD_HDR_SECONDARY 0x01u
#define VD_HDR_CODETABLE 0x02u
#define VD_HDR_APPHEADER 0x04u
#define VD_SRCORTGT      0x03u

// VCD-integer: big-endian base-128. Anything wider than 32 bits, or a
// truncated encoding, fails (*ok = 0).
static unsigned long long vcdint(const unsigned char *p, size_t len,
                                 size_t *pos, int *ok)
{
    unsigned long long v = 0;
    *ok = 0;
    for (int i = 0; i < 5; i++) {
        if (*pos >= len) return 0;
        unsigned char b = p[(*pos)++];
        v = (v << 7) | (unsigned long long)(b & 0x7f);
        if (v > 0xffffffffull) return 0;
        if ((b & 0x80u) == 0) { *ok = 1; return v; }
    }
    return 0;
}

// Early-exit parse of the VCDIFF header and first window header (I6,
// fail-soft): any anomaly answers "not a delta", never a crash. Bounds
// checked here: the source window must sit inside the installed file
// and the target window inside the decode cap.
static bool vcd_first_window_ok(const unsigned char *h, size_t len,
                                unsigned long long src_size)
{
    if (len < 12 || memcmp(h, "\xd6\xc3\xc4\x00", 4) != 0) return false;
    size_t pos = 4;
    unsigned char hdr_ind = h[pos++];
    if ((hdr_ind & ~0x7u) != 0) return false;
    if (hdr_ind & (VD_HDR_SECONDARY | VD_HDR_CODETABLE)) return false;
    int ok = 0;
    if (hdr_ind & VD_HDR_APPHEADER) {
        unsigned long long n = vcdint(h, len, &pos, &ok);
        if (!ok || n > len - pos) return false;
        pos += (size_t) n;
    }
    if (pos + 1 > len) return false;
    unsigned char win_ind = h[pos++];
    if ((win_ind & ~0x7u) != 0) return false;
    unsigned long long cpylen = 0, cpyoff = 0;
    if (win_ind & VD_SRCORTGT) {
        cpylen = vcdint(h, len, &pos, &ok);
        if (!ok) return false;
        cpyoff = vcdint(h, len, &pos, &ok);
        if (!ok) return false;
    }
    (void) vcdint(h, len, &pos, &ok);      // delta-encoding length
    if (!ok) return false;
    unsigned long long tgtlen = vcdint(h, len, &pos, &ok);
    if (!ok || tgtlen == 0 || tgtlen > XZ_OUT_CAP) return false;
    if (win_ind & VD_SRCORTGT) {
        if (cpylen > src_size) return false;
        if (cpyoff > src_size - cpylen) return false;
    }
    return true;
}

// Read the fd fully (from offset 0) into a buffer; refuses anything
// above the decode cap. The bytes come from the same open file the
// caller hashed, so the patch sees exactly the gated content (I4).
static bool slurp_fd(int fd, unsigned char **buf, size_t *len)
{
    struct stat sb;
    if (fstat(fd, &sb) != 0 || sb.st_size <= 0
        || (unsigned long long) sb.st_size > XZ_OUT_CAP) return false;
    if (lseek(fd, 0, SEEK_SET) != 0) return false;
    unsigned char *b = malloc((size_t) sb.st_size);
    if (b == NULL) return false;
    size_t got = 0;
    while (got < (size_t) sb.st_size) {
        ssize_t n = read(fd, b + got, (size_t) sb.st_size - got);
        if (n == 0) break;
        if (n < 0) {
            if (errno == EINTR) continue;
            free(b);
            return false;
        }
        got += (size_t) n;
    }
    if (got != (size_t) sb.st_size) { free(b); return false; }
    *buf = b;
    *len = got;
    return true;
}

// Apply the .xdelta patch in patch_path to the bytes behind src_fd
// (positioned at 0, hashed by the caller) and write the target to
// out_path. 0 on success; anything else means: out_path is gone, take
// the full variant (the caller's single re-selection).
int xdelta_apply_fd(int src_fd, const char *patch_path, const char *out_path)
{
    unsigned char *src = NULL;
    size_t src_len = 0;
    if (!slurp_fd(src_fd, &src, &src_len)) return -1;

    int pfd = open(patch_path, O_RDONLY | O_CLOEXEC);
    if (pfd < 0) { free(src); return -1; }
    unsigned char head[4096];
    ssize_t hn = read(pfd, head, sizeof head);
    if (hn < 0) hn = 0;
    if (!vcd_first_window_ok(head, (size_t) hn, src_len)
        || lseek(pfd, 0, SEEK_SET) != 0) {
        close(pfd);
        free(src);
        return -1;
    }
    int out = open(out_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (out < 0) { close(pfd); free(src); return -1; }

    xd3_stream stream;
    xd3_config cfg;
    xd3_source xsrc;
    memset(&stream, 0, sizeof stream);
    memset(&cfg, 0, sizeof cfg);
    memset(&xsrc, 0, sizeof xsrc);
    int ok = 0, fed_any = 0;
    unsigned long long total = 0;
    unsigned char ib[4096];

    if (xd3_config_stream(&stream, &cfg) == 0) {
        xsrc.blksize = (usize_t) src_len;
        xsrc.onblk = (usize_t) src_len;
        xsrc.curblk = src;
        xsrc.curblkno = 0;
        xsrc.max_winsize = (xoff_t) src_len;
        if (xd3_set_source_and_size(&stream, &xsrc, (xoff_t) src_len) == 0) {
            for (;;) {
                int ret = xd3_decode_input(&stream);
                if (ret == XD3_OUTPUT) {
                    if (total + stream.avail_out > XZ_OUT_CAP) break;
                    if (!drain(out, stream.next_out, stream.avail_out)) break;
                    total += stream.avail_out;
                    xd3_consume_output(&stream);
                    continue;
                }
                if (ret == XD3_GOTHEADER || ret == XD3_WINSTART) {
                    // window headers arrive before xd3 allocates the window
                    // buffer: an oversized window refuses here, not after
                    // the allocation spike (I6)
                    if (stream.dec_tgtlen > XZ_OUT_CAP) break;
                    continue;
                }
                if (ret == XD3_WINFINISH) continue;
                if (ret == XD3_INPUT) {
                    ssize_t n;
                    do n = read(pfd, ib, sizeof ib);
                    while (n < 0 && errno == EINTR);
                    if (n < 0) break;
                    if (n == 0) break;      // input exhausted; close checks
                    fed_any = 1;
                    xd3_avail_input(&stream, ib, (size_t) n);
                    continue;
                }
                break;                      // GETSRCBLK or an error
            }
            ok = fed_any && xd3_close_stream(&stream) == 0 && total > 0;
        }
    }
    xd3_free_stream(&stream);
    close(pfd);
    if (close(out) != 0) ok = 0;
    free(src);
    if (!ok) unlink(out_path);
    return ok ? 0 : -1;
}
