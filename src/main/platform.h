#ifndef APEGET_PLATFORM_H
#define APEGET_PLATFORM_H

// Platform policy; this slice carries Linux roots.

// Default install root <home>/.local/bin, NULL without $HOME.
char *platform_install_root_default(void);

#endif
