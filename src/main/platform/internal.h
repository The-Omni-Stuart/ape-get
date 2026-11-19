#ifndef APEGET_PLATFORM_INTERNAL_H
#define APEGET_PLATFORM_INTERNAL_H

// Inside the platform module: the calls that reach outside the C library the
// rest of the program lives in, and the two conversions that keep the wide
// characters they speak on the far side of that line.

#include <stdbool.h>
#include <stdint.h>
#include <uchar.h>

// Which file this process is running from, as the NT kernel names it: the
// kernel's own reply, untouched - the name written into `wide` and its length,
// the size of `cap` quoted back when the name did not fit and the buffer holds
// only the beginning of it, and zero when the kernel has nothing to say. What
// the reply means is decided where it is read.
uint32_t platform_win_module_name(char16_t *wide, uint32_t cap);

// The user's own Path value, read from HKCU\Environment as the registry holds
// it: UTF-16 code units in a block the caller frees, and NULL when there is no
// such value or it cannot be read at all - which the caller treats as an empty
// Path, never as a reason to write something in.
char16_t *platform_win_reg_read(void);

// Make `wide` the user's Path value, replacing whatever was there, as the type
// that keeps %USERPROFILE%-style entries expandable: REG_SZ would leave them
// standing for themselves. False when the registry refuses.
bool platform_win_reg_write(const char16_t *wide);

// The same text on both sides of the line, so a path can be read from Windows
// and written back to it without anything in between knowing the difference.
// Both return a block the caller frees, or NULL when there is no room.
char *platform_utf8_of_utf16(const char16_t *wide);
char16_t *platform_utf16_of_utf8(const char *text);

#endif
