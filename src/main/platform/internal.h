#ifndef APEGET_PLATFORM_INTERNAL_H
#define APEGET_PLATFORM_INTERNAL_H

// Inside the platform module: the calls that reach outside the C library the
// rest of the program lives in.

#include <stdint.h>
#include <uchar.h>

// Which file this process is running from, as the NT kernel names it: the
// kernel's own reply, untouched - the name written into `wide` and its length,
// the size of `cap` quoted back when the name did not fit and the buffer holds
// only the beginning of it, and zero when the kernel has nothing to say. Named
// in UTF-16 code units, the one place this program speaks that; what the reply
// means is decided where it is read.
uint32_t platform_win_module_name(char16_t *wide, uint32_t cap);

#endif
