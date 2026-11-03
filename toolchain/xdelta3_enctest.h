/* xdelta3 3.2.2 test-only configuration (rt_xdelta_enc): the same
   decoder-only switches as the product build with the encoder on, so
   the round-trip test can produce real patches. Secondary compressors
   stay off, matching the -S none contract the client relies on. */
#ifndef APEGET_XDELTA3_ENCTEST_H
#define APEGET_XDELTA3_ENCTEST_H

#define SIZEOF_SIZE_T __SIZEOF_SIZE_T__
#define SIZEOF_UNSIGNED_LONG_LONG __SIZEOF_LONG_LONG__

#define XD3_ENCODER 1
#define SECONDARY_FGK 0
#define SECONDARY_DJW 0
#define SECONDARY_LZMA 0
#define REGRESSION_TEST 0

#endif
