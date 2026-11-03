/* Round-trip test helper (decode half): apply a patch through the exact
   product path (pipeline xdelta_apply_fd) to a file. */
#include <fcntl.h>
#include <stdio.h>
#include <unistd.h>

int xdelta_apply_fd(int src_fd, const char *patch_path, const char *out_path);

int main(int argc, char **argv)
{
    if (argc != 4) {
        fprintf(stderr, "usage: %s SRC PATCH OUT\n", argv[0]);
        return 2;
    }
    int fd = open(argv[1], O_RDONLY);
    if (fd < 0) {
        fprintf(stderr, "cannot open %s\n", argv[1]);
        return 1;
    }
    int rc = xdelta_apply_fd(fd, argv[2], argv[3]);
    close(fd);
    return rc == 0 ? 0 : 1;
}
