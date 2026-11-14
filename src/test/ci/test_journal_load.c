// Exercise the actual loader/recovery with narrow, deterministic I/O faults.
#include "txn.h"
#include "fs.h"
#include "path.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

static const char *fault;
static int reads;

static int fault_errno(const char *prefix)
{
    size_t n = strlen(prefix);
    if (strncmp(fault, prefix, n)) return 0;
    const char *name = fault + n;
    if (!strcmp(name, "eio")) return EIO;
    if (!strcmp(name, "enomem")) return ENOMEM;
    if (!strcmp(name, "eacces")) return EACCES;
    if (!strcmp(name, "enotdir")) return ENOTDIR;
    if (!strcmp(name, "eloop")) return ELOOP;
    if (!strcmp(name, "enoent")) return ENOENT;
    return 0;
}

static char *fixture_path_join(const char *root, const char *name)
{
    if (!strcmp(fault, "path-oom")) { errno = ENOMEM; return NULL; }
    return path_join(root, name);
}

static int fixture_lstat(const char *path, struct stat *sb)
{
    int error = fault_errno("lstat-");
    if (error) { errno = error; return -1; }
    int result = lstat(path, sb);
    if (result == 0 && !strcmp(fault, "entry-special")) sb->st_mode = S_IFIFO;
    return result;
}

static int fixture_stat(const char *path, struct stat *sb)
{
    int error = fault_errno("target-");
    if (error) { errno = error; return -1; }
    int result = stat(path, sb);
    if (result == 0 && !strcmp(fault, "target-special")) sb->st_mode = S_IFIFO;
    return result;
}

static char *fixture_read(const char *path, size_t *n)
{
    reads++;
    int error = fault_errno("read-");
    if (error || !strcmp(fault, "read-no-errno")) {
        errno = error;
        return NULL;
    }
    return fs_read_file(path, n);
}

// Only the loader's filesystem calls are replaced. Recovery is linked intact.
#define path_join fixture_path_join
#define lstat(path, sb) fixture_lstat(path, sb)
#define stat(path, sb) fixture_stat(path, sb)
#define fs_read_file fixture_read
#include "txn/journal.c"
#undef fs_read_file
#undef stat
#undef lstat
#undef path_join

int main(int argc, char **argv)
{
    if (argc != 3) return 2;
    char *root = realpath(argv[2], NULL);
    if (root == NULL || strcmp(root, argv[2]) != 0) return 2;
    char *state = path_join(root, "data/github-release-manager");
    char *install = path_join(root, "install");
    if (state == NULL || install == NULL) return 2;
    fault = argv[1];
    txn t;
    txn_load load = txn_load_journal(state, &t);
    txn_free(&t);
    char *issue = txn_doctor_issue(state);
    bool unreadable_issue = issue != NULL && strstr(issue, "unreadable") != NULL;
    free(issue);
    bool history = true;
    bool recovered = txn_recover_startup(state, install, true, true,
        &history, NULL, NULL, NULL, 0, NULL, 0);
    printf("{\"load\":\"%s\",\"recovered\":%s,\"history\":%s,"
        "\"unreadable_issue\":%s,\"reads\":%d}\n",
        load == TXN_ABSENT ? "ABSENT" : load == TXN_READ ? "READ" : "UNREADABLE",
        recovered ? "true" : "false", history ? "true" : "false",
        unreadable_issue ? "true" : "false", reads);
    free(state); free(install); free(root);
    return 0;
}
