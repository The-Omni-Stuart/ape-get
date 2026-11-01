#include "platform.h"
#include "path.h"
#include <stdlib.h>

char *platform_install_root_default(void)
{
    char *h = path_home();
    if (h == NULL) return NULL;
    char *r = path_join(h, ".local/bin");
    free(h);
    return r;
}
