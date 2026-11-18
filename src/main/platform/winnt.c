// The NT kernel asked directly, which is the only way this program can learn
// the name of its own executable on a Windows system: the /proc/self/exe link
// that answers on the unixes is not there, and the loader's com name is served
// only to a process that came in through the loader rather than as a PE. One
// call lives here, and this is the one file that includes anything NT, so the
// wide characters stop with the kernel that speaks them.

#include "platform/internal.h"

#include <libc/nt/runtime.h>

uint32_t platform_win_module_name(char16_t *wide, uint32_t cap)
{
    // Zero is the running executable. What the kernel gives back is passed on
    // as it stands, including the answer that says the name did not fit.
    return GetModuleFileName(0, wide, cap);
}
