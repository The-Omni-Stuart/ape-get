/* xdelta3 3.2.2 decoder-only configuration for ape-get (pipeline module):
   VCDIFF decode, single translation unit (xdelta3.c amalgamation). No
   encoder (XD3_ENCODER=0), no secondary compressors (patches declaring
   one fail closed with XD3_UNIMPLEMENTED), no CLI, tests or regression
   code — those headers are not vendored at all (vendor/xdelta3/PIN).
   The SIZEOF_ probes replace the autoconf config.h upstream generates. */
#ifndef APEGET_XDELTA3_CONFIG_H
#define APEGET_XDELTA3_CONFIG_H

#define SIZEOF_SIZE_T __SIZEOF_SIZE_T__
#define SIZEOF_UNSIGNED_LONG_LONG __SIZEOF_LONG_LONG__

#define XD3_ENCODER 0
#define SECONDARY_FGK 0
#define SECONDARY_DJW 0
#define SECONDARY_LZMA 0
#define REGRESSION_TEST 0

#endif
