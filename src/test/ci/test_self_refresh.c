// A narrow self-integration driver: synthetic APE bytes, no child execution.
// The Python test supplies and guards every disposable filesystem path.
#include "crypto.h"
#include "fs.h"
#include "json.h"
#include "path.h"
#include "txn.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static const char *fixture_exe, *unreadable;
static int checked_journals;
static bool stop_commit, fail_hash;

static bool fixture_hash(const char *path, char out[65])
{
    return !(fail_hash && strstr(path, ".tmp-") != NULL)
        && crypto_sha256_file(path, out);
}

static ssize_t fixture_readlink(const char *path, char *buf, size_t cap)
{
    if (strcmp(path, "/proc/self/exe") != 0) abort();
    size_t n = strlen(fixture_exe);
    if (n > cap) n = cap;
    memcpy(buf, fixture_exe, n);
    return (ssize_t)n;
}

static char *fixture_read_file(const char *path, size_t *len)
{
    if (unreadable != NULL && strcmp(path, unreadable) == 0) {
        errno = EACCES;
        return NULL;
    }
    return fs_read_file(path, len);
}

static bool fixture_txn_write(const char *root, const char *text)
{
    char *phase = NULL;
    if (!json_get_string(text, "phase", &phase)) abort();
    if (strcmp(phase, "committing") == 0) {
        const char *s, *e;
        if (!json_raw(text, "new", &s, &e)) abort();
        char *side = strndup(s, (size_t)(e - s));
        static const char *paths[] = {"manifest_tmp", "binary_tmp"};
        static const char *hashes[] = {"manifest_sha256", "asset_sha256"};
        for (int i = 0; i < 2; i++) {
            char *path = NULL, *expected = NULL;
            char actual[65];
            if (!json_get_string(side, paths[i], &path)
                || !json_get_string(side, hashes[i], &expected)
                || !crypto_sha256_file(path, actual)
                || strcmp(expected, actual) != 0) abort();
            free(path);
            free(expected);
        }
        free(side);
        checked_journals++;
    }
    free(phase);
    return txn_write(root, text);
}

static bool fixture_rename(const char *from, const char *to)
{
    // Leave the real committing journal and durable stages for product recovery.
    if (stop_commit) { errno = EIO; return false; }
    return fs_rename(from, to);
}

#ifndef APEGET_SELF_SOURCE
#define APEGET_SELF_SOURCE "pipeline/self.c"
#endif
#define readlink fixture_readlink
#define fs_read_file fixture_read_file
#define fs_rename fixture_rename
#define txn_write fixture_txn_write
#define crypto_sha256_file fixture_hash
#include APEGET_SELF_SOURCE
#undef crypto_sha256_file
#undef txn_write
#undef fs_rename
#undef fs_read_file
#undef readlink

int main(int argc, char **argv)
{
    if (argc != 4) return 2;
    char *root = realpath(argv[1], NULL);
    if (root == NULL || strcmp(root, argv[1]) != 0) return 2;
    state_ctx st = {0};
    st.root = path_join(root, "data/github-release-manager");
    st.install_root = path_join(root, "install");
    char *manifest = manifest_path(st.root, "self", "ape-get");
    fixture_exe = argv[3];
    if (strncmp(fixture_exe, root, strlen(root)) != 0
        || fixture_exe[strlen(root)] != '/') return 2;
    if (strcmp(argv[2], "unreadable") == 0) unreadable = manifest;
    stop_commit = strcmp(argv[2], "stop-commit") == 0;
    fail_hash = strcmp(argv[2], "hash-failure") == 0;
    cmd_opts o = {0};
    o.quiet = true;
    o.dry_run = strcmp(argv[2], "dry-run") == 0;
    if (strcmp(argv[2], "writer") == 0) {
        char *text = fs_read_file(fixture_exe, NULL);
        bool ok = text != NULL && txn_write(st.root, text);
        free(text);
        printf("{\"written\":%s}\n", ok ? "true" : "false");
    } else {
        pipeline_self_after(&o, &st);
        printf("{\"version\":\"%s\",\"checked_journals\":%d}\n",
            APEGET_VERSION, checked_journals);
    }
    free(manifest);
    free(st.install_root);
    free(st.root);
    free(root);
    return 0;
}
