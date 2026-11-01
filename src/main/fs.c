#include "fs.h"
#include "path.h"
#include <dirent.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <unistd.h>

bool fs_exists(const char *path)
{
    struct stat st;
    return path != NULL && stat(path, &st) == 0;
}

bool fs_is_dir(const char *path)
{
    struct stat st;
    return path != NULL && stat(path, &st) == 0 && S_ISDIR(st.st_mode);
}

bool fs_is_regular(const char *path)
{
    struct stat st;
    return path != NULL && stat(path, &st) == 0 && S_ISREG(st.st_mode);
}

bool fs_is_symlink(const char *path)
{
    struct stat st;
    return path != NULL && lstat(path, &st) == 0 && S_ISLNK(st.st_mode);
}

bool fs_mkdir_p(const char *path)
{
    char *p = strdup(path);
    if (p == NULL) return false;
    for (char *s = p + 1; ; s++) {
        if (*s != '/' && *s != '\0') continue;
        char c = *s;
        *s = '\0';
        if (mkdir(p, 0755) != 0 && errno != EEXIST) { free(p); return false; }
        if (c == '\0') break;
        *s = c;
    }
    free(p);
    return true;
}

char *fs_read_file(const char *path, size_t *len)
{
    FILE *f = fopen(path, "rb");
    if (f == NULL) return NULL;
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return NULL; }
    long n = ftell(f);
    if (n < 0) { fclose(f); return NULL; }
    rewind(f);
    char *buf = malloc((size_t)n + 1);
    if (buf == NULL) { fclose(f); return NULL; }
    size_t got = fread(buf, 1, (size_t)n, f);
    fclose(f);
    if (got != (size_t)n) { free(buf); return NULL; }
    buf[got] = '\0';
    if (len != NULL) *len = got;
    return buf;
}

bool fs_write_file(const char *path, const char *text)
{
    // Staged write: fsync'ed sibling renamed over the target; an interrupted
    // write never truncates it, and recovery sweeps the .tmp-<pid> crash orphan.
    char *tmp = path_fmt("%s.tmp-%d", path, (int)getpid());
    if (tmp == NULL) return false;
    size_t n = strlen(text);
    bool ok = false;
    FILE *f = fopen(tmp, "wb");
    if (f != NULL) {
        ok = fwrite(text, 1, n, f) == n && fflush(f) == 0
            && fsync(fileno(f)) == 0;
        if (fclose(f) != 0) ok = false;
    }
    if (ok) ok = fs_rename(tmp, path);
    remove(tmp); // no-op once renamed away
    free(tmp);
    return ok;
}

bool fs_rename(const char *from, const char *to)
{
    return rename(from, to) == 0;
}

bool fs_free_bytes(const char *path, unsigned long long *out)
{
    if (path == NULL) return false;
    char *p = NULL;
    const char *probe = path;
    if (!fs_exists(path)) {
        const char *slash = strrchr(path, '/');
        if (slash == NULL || slash == path) return false;
        p = strndup(path, (size_t)(slash - path));
        if (p == NULL) return false;
        probe = p;
    }
    struct statvfs sv;
    bool ok = statvfs(probe, &sv) == 0;
    if (ok) {
        unsigned long long blk = sv.f_frsize ? sv.f_frsize : sv.f_bsize;
        *out = (unsigned long long)sv.f_bavail * blk;
    }
    free(p);
    return ok;
}

static bool in_backups(const char *path, const char *apps_dir)
{
    if (!fs_is_dir(apps_dir)) return false;
    size_t pre = strlen(apps_dir);
    if (strncmp(path, apps_dir, pre) != 0) return false;
    return strstr(path + pre, "/backups/") != NULL;
}

static int count_walk(const char *dir, const char *apps_dir)
{
    DIR *d = opendir(dir);
    if (d == NULL) return 0;
    int n = 0;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] == '.') continue;
        char *p = path_join(dir, e->d_name);
        if (p == NULL) continue;
        if (fs_is_dir(p)) n += count_walk(p, apps_dir);
        else if (strcmp(e->d_name, "grm_manifest.json") == 0
                 && fs_is_regular(p) && !in_backups(p, apps_dir)) n++;
        free(p);
    }
    closedir(d);
    return n;
}

int fs_count_manifests(const char *state_root)
{
    char *apps = path_join(state_root, "applications");
    int n = fs_is_dir(apps) ? count_walk(apps, apps) : 0;
    free(apps);
    return n;
}

bool fs_copy_file(const char *from, const char *to)
{
    struct stat sb;
    if (stat(from, &sb) != 0 || !S_ISREG(sb.st_mode)) return false;
    FILE *in = fopen(from, "rb");
    if (in == NULL) return false;
    FILE *out = fopen(to, "wb");
    if (out == NULL) { fclose(in); return false; }
    bool ok = true;
    char buf[65536];
    size_t n;
    while ((n = fread(buf, 1, sizeof buf, in)) > 0)
        if (fwrite(buf, 1, n, out) != n) { ok = false; break; }
    if (ferror(in)) ok = false;
    fclose(in);
    if (fclose(out) != 0) ok = false;
    if (ok) chmod(to, sb.st_mode & 07777);
    return ok;
}

bool fs_rm_tree(const char *path)
{
    struct stat sb;
    if (lstat(path, &sb) != 0) return false;
    if (!S_ISDIR(sb.st_mode)) return unlink(path) == 0;
    DIR *d = opendir(path);
    if (d == NULL) return false;
    bool ok = true;
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (!strcmp(de->d_name, ".") || !strcmp(de->d_name, ".."))
            continue;
        char *child = path_join(path, de->d_name);
        if (child == NULL || !fs_rm_tree(child)) ok = false;
        free(child);
    }
    closedir(d);
    if (rmdir(path) != 0) ok = false;
    return ok;
}

bool fs_copy_tree(const char *from, const char *to)
{
    struct stat sb;
    if (stat(from, &sb) != 0) return false;
    if (!S_ISDIR(sb.st_mode))
        return fs_copy_file(from, to);
    if (mkdir(to, 0777) != 0 && errno != EEXIST) return false;
    DIR *d = opendir(from);
    if (d == NULL) return false;
    bool ok = true;
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (!strcmp(de->d_name, ".") || !strcmp(de->d_name, ".."))
            continue;
        char *a = path_join(from, de->d_name), *b = path_join(to,
            de->d_name);
        if (a == NULL || b == NULL || !fs_copy_tree(a, b)) ok = false;
        free(a);
        free(b);
    }
    closedir(d);
    chmod(to, sb.st_mode & 07777);
    return ok;
}
