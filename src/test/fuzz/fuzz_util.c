#include "fuzz_util.h"

#include <sys/stat.h>
#include <sys/types.h>
#include <string.h>

void fuzz_for_each_segment(const unsigned char *data, size_t size,
                           fuzz_seg_fn fn, void *ud)
{
    size_t start = 0;
    for (size_t k = 0; k <= size; k++) {
        if (k == size || data[k] == 0) {
            // the segment ends at the NUL on its own: embedded NULs, and
            // the sentinel the driver appends at [size]
            fn((const char *)data + start, ud);
            start = k + 1;
        }
    }
}

char *fuzz_work_subdir(const char *name)
{
    static char path[4096];
    const char *root = getenv("APEGET_FUZZ_WORK");
    if (root == NULL || *root == '\0') return NULL;
    snprintf(path, sizeof(path), "%s/%s", root, name);
    if (mkdir(path, 0777) != 0) {
        // already there, or unusable; callers treat NULL as skip
        struct stat sb;
        if (stat(path, &sb) != 0 || !S_ISDIR(sb.st_mode)) return NULL;
    }
    return path;
}
