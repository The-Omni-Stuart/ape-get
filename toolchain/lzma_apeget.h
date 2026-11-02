/* liblzma 5.8.x decoder-only configuration for ape-get (pipeline module):
   .xz container decode, LZMA1 + LZMA2, CRC32/CRC64/SHA-256 checks,
   small table-driven CRCs and LZMA1 decoder mode. No encoders, no
   BCJ/simple or delta filters, no MT or alt formats — those sources are
   not vendored at all (vendor/liblzma/PIN); the remaining exclusions are
   the same HAVE_* switches upstream's configure would set. */
#ifndef APEGET_LZMA_CONFIG_H
#define APEGET_LZMA_CONFIG_H

#define HAVE_DECODER_LZMA1
#define HAVE_DECODER_LZMA2
#define HAVE_CHECK_CRC32
#define HAVE_CHECK_CRC64
#define HAVE_CHECK_SHA256
#define HAVE_SMALL

/* cosmocc is GCC ≥ 4.2 on both arches; byte-order itself is picked up
   from __BYTE_ORDER__ by tuklib_integer.h. Unaligned access stays
   byte-wise safe (routers first). */
#define HAVE___BUILTIN_BSWAPXX
#define HAVE_INTTYPES_H
#define HAVE_STDINT_H
#define HAVE_STDBOOL_H

#endif
