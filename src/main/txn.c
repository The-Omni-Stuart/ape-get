// Transaction journal: struct lifecycle, the atomic write (tmp,
// fsync, rename, dir fsync) and id generation. Text and recovery: txn/.

#include "txn.h"
#include "txn/internal.h"
#include "fs.h"
#include "path.h"

#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/random.h>
#include <time.h>
#include <unistd.h>

void txn_init(txn *t) { memset(t, 0, sizeof(*t)); }

void txn_free(txn *t)
{
    free(t->id); free(t->op); free(t->ref); free(t->phase);
    free(t->created_at); free(t->backup); free(t->host);
    free(t->old_manifest_path); free(t->old_manifest);
    free(t->old_generation); free(t->old_launcher); free(t->old_binary);
    free(t->new_generation); free(t->new_manifest_tmp);
    free(t->new_manifest); free(t->new_launcher_tmp);
    free(t->new_binary_tmp); free(t->new_asset_sha256);
    txn_init(t);
}

static bool fsync_dir(const char *dir)
{
    int fd = open(dir, O_RDONLY);
    if (fd < 0) return false;
    bool ok = fsync(fd) == 0;
    close(fd);
    return ok;
}

bool txn_write(const char *root, const char *text)
{
    if (!fs_mkdir_p(root)) return false;
    char *p = path_join(root, TXN_FILE);
    char *t = path_join(root, TXN_FILE ".tmp");
    bool ok = p != NULL && t != NULL;
    if (ok) {
        size_t n = strlen(text);
        int fd = open(t, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        ok = fd >= 0;
        if (ok) {
            size_t w = 0;
            while (w < n) {
                ssize_t r = write(fd, text + w, n - w);
                if (r <= 0) { ok = false; break; }
                w += (size_t)r;
            }
            if (ok) ok = fsync(fd) == 0;
            close(fd);
        }
        if (ok) ok = rename(t, p) == 0;
        if (ok) ok = fsync_dir(root);
        remove(t);
    }
    free(p);
    free(t);
    return ok;
}

void txn_new_id(char out[17])
{
    // 64 bits from getrandom so staging names are not guessable from
    // pid+time; falls back to the time/pid/stack mix if it is absent
    unsigned long long r = 0;
    if (getrandom(&r, sizeof r, 0) != (ssize_t)sizeof r)
        r = (unsigned long long)(unsigned long)time(NULL)
            ^ ((unsigned long long)getpid() << 17)
            ^ (unsigned long long)(size_t)out;
    for (int k = 0; k < 8; k++) {
        r = r * 6364136223846793005ULL + 1442695040888963407ULL;
        out[k * 2] = "0123456789abcdef"[(r >> 33) & 15];
        out[k * 2 + 1] = "0123456789abcdef"[(r >> 17) & 15];
    }
    out[16] = '\0';
}
