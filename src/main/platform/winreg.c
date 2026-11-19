// The user's Path value in the registry, read and written through the NT layer
// rather than by asking reg.exe - which was the plan until it was tried on a
// Windows machine, where it turned out this program cannot start a child
// process at all: proc.c issues the Linux syscalls for it straight to a kernel
// that is not there, deliberately, to keep cosmo's process emulation out of the
// size gate. So the tool that edits the registry from a shell is unreachable
// here even though it is on every install, and the registry is reached the way
// the tool itself reaches it.

#include "platform.h"
#include "platform/internal.h"

#include <libc/nt/enum/keyaccess.h>
#include <libc/nt/enum/regtype.h>
#include <libc/nt/registry.h>
#include <stdlib.h>

// The Path value belongs to the machine running this, not to the layout setup
// has been asked to write: setup can be told to lay out for Windows while it
// runs on some other system, and there these calls would be questions put to a
// kernel that has no registry to answer them. Both calls below say no rather
// than asking, so nothing outside this file has to remember to ask first.
static bool registry_here(void)
{
    return platform_current() == OS_WINDOWS;
}

// HKCU\Environment is the only key either call touches: it is the current
// user's own environment, so the program can neither edit a machine-wide Path
// behind anyone's back nor need anyone's permission to change its own.
#define ENV_SUBKEY u"Environment"
#define PATH_VALUE u"Path"

// Zero means the call worked. These are named for the Win32 API, whose BOOL
// would say the opposite, but what comes back is the NT status: the probe that
// first ran them on a real machine read the Path correctly under a handle that
// the signature called a failure. Nothing short of a Windows kernel can see
// this the other way round, which is how every call in this file came to be
// treated as a refusal while the registry was doing nothing of the kind.

char16_t *platform_win_reg_read(void)
{
    int64_t key;
    if (!registry_here()) return NULL;
    if (RegOpenKeyEx(kNtHkeyCurrentUser, ENV_SUBKEY, 0, kNtKeyRead, &key) != 0)
        return NULL;

    // The size of the value first, rather than a buffer guessed at: a Path
    // runs to thousands of characters on a machine with a lot installed, and a
    // fixed buffer would be a limit on what a person may keep in theirs. The
    // call answers with the size whether or not the empty buffer can hold it.
    uint32_t bytes = 0;
    int type = 0;
    RegQueryValueEx(key, PATH_VALUE, 0, &type, 0, &bytes);
    // No Path, or a size that is not a whole number of characters, or a value
    // held as something other than text: all of them are reported as no value
    // at all, which the caller treats as an empty Path. None of them is a
    // reason to write over what the machine holds.
    if (bytes == 0 || bytes % sizeof(char16_t) != 0
        || (type != kNtRegSz && type != kNtRegExpandSz)) {
        RegCloseKey(key);
        return NULL;
    }
    // One code unit beyond the size quoted, so the block is safe to read as
    // text whether or not the stored string came with its own terminator: the
    // registry does not promise one.
    char16_t *wide = malloc(bytes + sizeof(char16_t));
    if (wide == NULL) {
        RegCloseKey(key);
        return NULL;
    }
    if (RegQueryValueEx(key, PATH_VALUE, 0, &type, (unsigned char *)wide,
        &bytes) != 0) {
        free(wide);
        RegCloseKey(key);
        return NULL;
    }
    wide[bytes / sizeof(char16_t)] = 0;
    RegCloseKey(key);
    return wide;
}

bool platform_win_reg_write(const char16_t *wide)
{
    int64_t key;
    if (!registry_here()) return false;
    // The key is not created when it is missing: it belongs to every account
    // that has ever logged in, and a machine without it is not one this program
    // has any business writing into.
    if (RegOpenKeyEx(kNtHkeyCurrentUser, ENV_SUBKEY, 0, kNtKeyWrite, &key) != 0)
        return false;
    uint32_t units = 0;
    while (wide[units] != 0) units++;
    // REG_EXPAND_SZ, the type Windows itself writes here and the one that makes
    // %USERPROFILE% mean something: a REG_SZ would leave such entries standing
    // for their own percent signs. The terminator counts towards the size, as
    // it does for every string value, so that whatever reads the Path next does
    // not run past the end of it.
    bool ok = RegSetValueEx(key, PATH_VALUE, 0, kNtRegExpandSz,
        (const unsigned char *)wide,
        (units + 1) * (uint32_t)sizeof(char16_t)) == 0;
    RegCloseKey(key);
    return ok;
}
