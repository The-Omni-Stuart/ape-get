// Startup recovery: prepared journals roll back, committing ones
// roll forward; a journal-absent boot sweeps staged .tmp-<id> files.

#include "txn.h"
#include "txn/internal.h"
#include "fs.h"
#include "path.h"

#include <ctype.h>
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

// --- recovery ---

static void info_line(bool quiet, bool json, const char *fmt,
    const char *a, const char *b)
{
    if (quiet || json) return;
    char msg[900];
    snprintf(msg, sizeof(msg), fmt, a != NULL ? a : "",
        b != NULL ? b : "");
    fputs(msg, stderr);
    fputc('\n', stderr);
}

// "<final>.tmp-<id>" minus the tmp suffix; NULL when shapeless.
static char *strip_tmp(const char *path)
{
    const char *dot = NULL;
    for (const char *p = path; (p = strstr(p, ".tmp-")) != NULL; )
        dot = p++;
    if (dot == NULL || dot == path) return NULL;
    size_t n = (size_t)(dot - path);
    char *r = malloc(n + 1);
    if (r != NULL) { memcpy(r, path, n); r[n] = '\0'; }
    return r;
}

static char *dir_of(const char *path)
{
    const char *slash = strrchr(path, '/');
    if (slash == NULL || slash == path) return strdup("/");
    char *r = malloc((size_t)(slash - path) + 1);
    if (r != NULL) { memcpy(r, path, (size_t)(slash - path));
        r[slash - path] = '\0'; }
    return r;
}

static bool is_hex16(const char *s)
{
    for (int k = 0; k < 16; k++) {
        if (!isxdigit((unsigned char)s[k])) return false;
        if (s[k] >= 'A' && s[k] <= 'F') return false;
    }
    return true;
}

// remove <dir>/<name>.tmp-<16hex> orphans, recursively (depth-capped);
// this is the sweep the journal-absent bootstrap runs
static void sweep_dir(const char *dir, int depth)
{
    DIR *d = opendir(dir);
    if (d == NULL || depth < 0) { if (d) closedir(d); return; }
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] == '.') continue;
        char *p = path_join(dir, e->d_name);
        if (p == NULL) continue;
        struct stat sb;
        if (lstat(p, &sb) == 0) {
            if (S_ISDIR(sb.st_mode)) sweep_dir(p, depth - 1);
            else if (S_ISREG(sb.st_mode)) {
                const char *mark = NULL;
                for (const char *q = strstr(e->d_name, ".tmp-"); q != NULL;
                        q = strstr(q + 1, ".tmp-"))
                    mark = q;
                if (mark != NULL && strlen(mark) == 21 && is_hex16(mark + 5))
                    remove(p);
            }
        }
        free(p);
    }
    closedir(d);
}

// one staged rename of the apply step: staged → rename over final,
// already-applied → done, neither → corrupt
static bool apply_rename(const char *tmp, const char *final_path)
{
    if (fs_exists(tmp)) {
        char *fin = final_path != NULL ? strdup(final_path)
            : strip_tmp(tmp);
        if (fin == NULL) return false;
        bool ok = fs_rename(tmp, fin);
        free(fin);
        return ok;
    }
    char *fin = final_path != NULL ? strdup(final_path) : strip_tmp(tmp);
    if (fin == NULL) return false;
    bool ok = fs_exists(fin);
    free(fin);
    return ok;
}

static bool roll_forward(const txn *t, const char *root, char *err,
    size_t errcap)
{
    if (strcmp(t->op, "uninstall") == 0) {
        // mirror image: the rename to .removing was the commit point, so
        // the deletions finish even when the manifest is already gone
        char *dir = t->old_manifest_path != NULL
            ? dir_of(t->old_manifest_path) : NULL;
        char *rem = dir != NULL ? path_join(dir,
            "grm_manifest.json.removing") : NULL;
        if (t->old_manifest_path != NULL && fs_exists(t->old_manifest_path))
            fs_rename(t->old_manifest_path, rem);
        if (rem != NULL) remove(rem);
        if (t->old_launcher != NULL) remove(t->old_launcher);
        if (t->old_generation != NULL
            && fs_is_dir(t->old_generation))
            fs_rm_tree(t->old_generation);
        if (dir != NULL) {
            char *backups = path_join(dir, "backups");
            if (backups != NULL) {
                if (fs_is_dir(backups)) fs_rm_tree(backups);
                free(backups);
            }
            rmdir(dir); // only when empty
        }
        free(rem);
        free(dir);
        return true;
    }
    if ((t->new_binary_tmp != NULL
            && !apply_rename(t->new_binary_tmp, NULL))
        || (t->new_launcher_tmp != NULL
            && !apply_rename(t->new_launcher_tmp, NULL))
        || (t->new_manifest_tmp != NULL
            && !apply_rename(t->new_manifest_tmp, t->new_manifest))) {
        snprintf(err, errcap, "Transaction %s is corrupt; manual "
            "recovery required.", t->id);
        return false;
    }
    (void)root;
    return true;
}

bool txn_recover_startup(const char *root, const char *install_root,
    bool quiet, bool json, bool *need_history, const char **need_op,
    const char **need_ref, char *op_buf, size_t op_buf_sz, char *ref_buf,
    size_t ref_buf_sz)
{
    if (need_history != NULL) *need_history = false;
    txn t;
    txn_load l = txn_load_journal(root, &t);
    if (l == TXN_ABSENT) {
        char *apps = path_join(root, "applications");
        if (apps != NULL) { sweep_dir(apps, 8); free(apps); }
        if (install_root != NULL) sweep_dir(install_root, 4);
        return true;
    }
    if (l == TXN_UNREADABLE) {
        fputs("Transaction journal is unreadable; manual recovery "
            "required.\n", stderr);
        return false;
    }
    bool ok = true;
    if (strcmp(t.phase, "prepared") == 0) {
        if (t.new_binary_tmp != NULL) remove(t.new_binary_tmp);
        if (t.new_manifest_tmp != NULL) remove(t.new_manifest_tmp);
        if (t.new_launcher_tmp != NULL) remove(t.new_launcher_tmp);
        // a record staged between the two journal writes is not named by
        // this phase; its name is derived, so derive it to clean up
        if (t.new_manifest != NULL && t.id != NULL) {
            char *d = path_fmt("%s.tmp-%s", t.new_manifest, t.id);
            if (d != NULL) { remove(d); free(d); }
        }
        // decode scratch (xz envelope, delta apply) lives only inside the
        // prepared window and is named from the staged path; derive both
        if (t.new_binary_tmp != NULL && t.id != NULL) {
            char *s = path_fmt("%s.xdec.tmp-%s", t.new_binary_tmp, t.id);
            if (s != NULL) { remove(s); free(s); }
            s = path_fmt("%s.xdel.tmp-%s", t.new_binary_tmp, t.id);
            if (s != NULL) { remove(s); free(s); }
        }
        char *p = path_join(root, TXN_FILE);
        if (p != NULL) { remove(p); free(p); }
        info_line(quiet, json, "Rolled back interrupted %s for %s.",
            t.op, t.ref);
    } else {
        char err[800] = "";
        ok = roll_forward(&t, root, err, sizeof(err));
        if (ok) {
            char *p = path_join(root, TXN_FILE);
            if (p != NULL) { remove(p); free(p); }
            if (need_history != NULL && !t.history_written) {
                *need_history = true;
                if (op_buf != NULL && ref_buf != NULL) {
                    snprintf(op_buf, op_buf_sz, "%s", t.op);
                    snprintf(ref_buf, ref_buf_sz, "%s", t.ref);
                    if (need_op != NULL) *need_op = op_buf;
                    if (need_ref != NULL) *need_ref = ref_buf;
                }
            }
            info_line(quiet, json, "Recovered interrupted %s for %s.",
                t.op, t.ref);
        } else {
            fputs(err, stderr);
            fputc('\n', stderr);
        }
    }
    txn_free(&t);
    return ok;
}

char *txn_doctor_issue(const char *root)
{
    txn t;
    txn_load l = txn_load_journal(root, &t);
    if (l == TXN_ABSENT) return NULL;
    if (l == TXN_UNREADABLE)
        return strdup("Transaction journal is unreadable; manual "
            "recovery required.");
    char *r = path_fmt("Pending transaction: %s %s (run doctor --heal "
        "or any stateful command)", t.op, t.ref);
    txn_free(&t);
    return r;
}
