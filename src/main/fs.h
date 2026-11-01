#ifndef APEGET_FS_H
#define APEGET_FS_H

#include <stdbool.h>
#include <stddef.h>

// Filesystem helpers.

bool fs_exists(const char *path);
bool fs_is_dir(const char *path);
bool fs_is_symlink(const char *path);
bool fs_is_regular(const char *path);
bool fs_mkdir_p(const char *path);

// Whole file in a NUL-terminated malloc'd buffer; NULL on any failure.
char *fs_read_file(const char *path, size_t *len);
bool fs_write_file(const char *path, const char *text);

// Replaces an existing target.
bool fs_rename(const char *from, const char *to);

// Usable bytes of the volume hosting path; probes the path, else its
// immediate parent, and gives up (false) if that is missing too.
bool fs_free_bytes(const char *path, unsigned long long *out);

// grm_manifest.json regular files under <root>/applications, except those
// below a "backups" directory: the valid-state count.
int fs_count_manifests(const char *state_root);

#endif

// Regular-file copy preserving the mode; parents must exist. Directories
// are not copied here (callers recurse).
bool fs_copy_file(const char *from, const char *to);

// rm -rf: a symlink is unlinked, a tree deleted recursively.
bool fs_rm_tree(const char *path);

// Directory copy: files, subdirs, modes preserved.
bool fs_copy_tree(const char *from, const char *to);
