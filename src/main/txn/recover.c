// Startup recovery: prepared journals roll back, committing ones
// roll forward; journal-named paths run only when they resolve
// inside the managed roots; a journal-absent boot sweeps staged
// .tmp-<id> files under the state root's applications/ tree only.

#include "txn.h"
#include "txn/internal.h"
#include "crypto.h"
#include "fs.h"
#include "path.h"

#include <ctype.h>
#include <dirent.h>
#include <errno.h>
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
    const char *back = strrchr(path, '\\');
    if (back != NULL && (slash == NULL || back > slash)) slash = back;
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

static bool file_matches(const char *path, const char *expected)
{
    char actual[65];
    struct stat sb;
    if (expected == NULL || strlen(expected) != 64
        || lstat(path, &sb) != 0 || !S_ISREG(sb.st_mode)
        || !crypto_sha256_file(path, actual)) return false;
    for (int i = 0; i < 64; i++)
        if (tolower((unsigned char)expected[i]) != actual[i]) return false;
    return true;
}

// Legacy journals can still apply a present stage. Without an expected
// digest, a missing stage is ambiguous: the final may be the old version.
// Check present stages too, so corrupt bytes cannot replace a valid final.
static bool apply_file(const char *tmp, const char *final_path,
    const char *expected)
{
    struct stat sb;
    bool staged = lstat(tmp, &sb) == 0;
    if (!staged && errno != ENOENT) return false;
    char *fin = final_path != NULL ? strdup(final_path) : strip_tmp(tmp);
    if (fin == NULL) return false;
    bool ok = staged ? S_ISREG(sb.st_mode)
        && (expected == NULL || file_matches(tmp, expected))
        && fs_rename(tmp, fin) : file_matches(fin, expected);
    free(fin);
    if (!ok) errno = EINVAL;
    return ok;
}

static bool apply_manifest(const txn *t)
{
    if (t->new_manifest_tmp != NULL)
        return apply_file(t->new_manifest_tmp, t->new_manifest,
            t->new_manifest_sha256);
    // Older committed records name only the final manifest. Its existence
    // alone cannot distinguish the intended record from a stale one either.
    return t->new_manifest == NULL
        || file_matches(t->new_manifest, t->new_manifest_sha256);
}

static bool paths_overlap(const char *a, const char *b)
{
    size_t an = strlen(a), bn = strlen(b);
    return !strcmp(a, "/") || !strcmp(b, "/") || !strcmp(a, b)
        || (an < bn && !strncmp(a, b, an) && b[an] == '/')
        || (bn < an && !strncmp(a, b, bn) && a[bn] == '/');
}

// Resolve existing ancestors too: a missing bin directory is valid, but a
// symlink in its parent path must not hide overlap with the app-state tree.
static char *resolve_missing(const char *path)
{
    char *probe = strdup(path);
    if (probe == NULL) return NULL;
    char *resolved = NULL;
    for (;;) {
        char *prefix = realpath(probe, NULL);
        if (prefix != NULL) {
            const char *tail = path + strlen(probe);
            resolved = *tail == '\0' ? strdup(prefix)
                : path_join(prefix, *tail == '/' || *tail == '\\' ? tail + 1 : tail);
            free(prefix);
            break;
        }
        struct stat sb;
        if (errno != ENOENT) break;
        if (lstat(probe, &sb) == 0 || errno != ENOENT
            || !strcmp(probe, "/")) break;
        char *slash = strrchr(probe, '/');
        char *back = strrchr(probe, '\\');
        if (back != NULL && (slash == NULL || back > slash)) slash = back;
        if (slash == NULL || slash[1] == '\0') break;
        if (slash == probe || (slash == probe + 2 && probe[1] == ':')) slash[1] = '\0';
        else *slash = '\0';
    }
    free(probe);
    return resolved;
}

static bool directory_or_absent(const char *path)
{
    struct stat sb;
    return path != NULL && (lstat(path, &sb) == 0
        ? S_ISDIR(sb.st_mode) : errno == ENOENT);
}

// String prefixes miss case aliases on APFS/Windows. Compare directory
// identities along existing ancestors; -1 means containment is unknown.
static int has_ancestor(const char *path, const struct stat *ancestor)
{
    char *p = strdup(path);
    if (p == NULL) return -1;
    int found = 0;
    for (;;) {
        struct stat sb;
        if (stat(p, &sb) == 0) {
            if (sb.st_dev == ancestor->st_dev && sb.st_ino == ancestor->st_ino) {
                found = 1;
                break;
            }
        } else if (errno != ENOENT) { found = -1; break; }
        char *parent = dir_of(p);
        if (parent == NULL) { found = -1; break; }
        bool done = !strcmp(parent, p);
        free(p);
        p = parent;
        if (done) break;
    }
    free(p);
    return found;
}

// Journal-supplied paths are filesystem instructions. Nothing named here
// is executed unless it physically resolves inside a managed root; a
// symlink is judged where it sits (deleting a link never touches its
// target), anything else by its own resolved location.
static bool contained(const char *path, const char *root,
    const char *install_root)
{
    struct stat sb;
    char *probe;
    if (lstat(path, &sb) == 0 && S_ISLNK(sb.st_mode)) {
        char *dir = dir_of(path);
        probe = dir != NULL ? resolve_missing(dir) : NULL;
        free(dir);
    } else {
        probe = resolve_missing(path);
    }
    if (probe == NULL) return false;
    const char *roots[2] = { root, install_root };
    bool ok = false;
    for (int k = 0; k < 2 && !ok; k++) {
        if (roots[k] == NULL) continue;
        size_t n = strlen(roots[k]);
        ok = !strncmp(probe, roots[k], n)
            && (probe[n] == '\0' || probe[n] == '/');
    }
    free(probe);
    return ok;
}

// Refuse every instruction set that names a present (or unprovable) path
// outside the managed roots; absent paths execute nothing and stay legal
// so legacy journals keep parsing. NULL entries are absent fields.
static bool instructions_trusted(const char *const *paths, size_t n,
    const char *root, const char *install_root)
{
    struct stat sb;
    for (size_t k = 0; k < n; k++) {
        if (paths[k] == NULL) continue;
        if (lstat(paths[k], &sb) != 0 && errno == ENOENT) continue;
        if (!contained(paths[k], root, install_root)) return false;
    }
    return true;
}

// Only a dedicated applications/<provider>/<package> directory is a tree
// deletion target. Never use old_generation: older writers put dirname of
// an adopted binary there, which can be the shared install root.
static char *uninstall_app_dir(const txn *t, const char *root,
    const char *install_root)
{
    char *mf = t->old_manifest_path != NULL
        ? resolve_missing(t->old_manifest_path) : NULL;
    char *norm_root = resolve_missing(root);
    char *apps = norm_root != NULL ? path_join(norm_root, "applications") : NULL;
    char *dir = mf != NULL ? dir_of(mf) : NULL;
    char *inst = install_root != NULL ? resolve_missing(install_root) : NULL;
    char *raw_dir = t->old_manifest_path != NULL ? dir_of(t->old_manifest_path) : NULL;
    char *raw_provider = raw_dir != NULL ? dir_of(raw_dir) : NULL;
    char *raw_apps = raw_provider != NULL ? dir_of(raw_provider) : NULL;
    bool ok = apps != NULL && dir != NULL && inst != NULL
        && directory_or_absent(raw_apps) && directory_or_absent(raw_provider)
        && directory_or_absent(raw_dir);
    if (ok) {
        size_t n = strlen(apps);
        const char *tail = strlen(dir) > n && !strncmp(dir, apps, n)
            && dir[n] == '/' ? dir + n + 1 : NULL;
        const char *slash = tail != NULL ? strchr(tail, '/') : NULL;
        const char *name = strrchr(mf, '/');
        ok = slash != NULL && slash != tail && slash[1] != '\0'
            && strchr(slash + 1, '/') == NULL && name != NULL
            && !(slash - tail == 1 && tail[0] == '.')
            && !(slash - tail == 2 && tail[0] == '.' && tail[1] == '.')
            && strcmp(slash + 1, ".") && strcmp(slash + 1, "..")
            && !strcmp(name + 1, "grm_manifest.json")
            && !paths_overlap(dir, inst);
    }
    struct stat sb;
    if (ok && lstat(t->old_manifest_path, &sb) == 0)
        ok = S_ISREG(sb.st_mode);
    else if (ok && errno != ENOENT) ok = false;
    if (ok && lstat(dir, &sb) == 0) {
        char *rd = realpath(dir, NULL), *ra = realpath(apps, NULL);
        ok = S_ISDIR(sb.st_mode) && rd != NULL && ra != NULL
            && path_under(rd, ra) && !paths_overlap(rd, inst);
        if (ok) ok = has_ancestor(inst, &sb) == 0;
        struct stat is;
        if (ok && stat(inst, &is) == 0)
            ok = S_ISDIR(is.st_mode) && has_ancestor(rd, &is) == 0;
        else if (ok && errno != ENOENT) ok = false;
        free(rd);
        free(ra);
    } else if (ok && errno != ENOENT) {
        ok = false;
    }
    free(mf);
    free(norm_root);
    free(apps);
    free(inst);
    free(raw_dir);
    free(raw_provider);
    free(raw_apps);
    if (!ok) { free(dir); return NULL; }
    return dir;
}

static bool unlink_missing_ok(const char *path)
{
    return path == NULL || unlink(path) == 0 || errno == ENOENT;
}

static bool roll_forward(const txn *t, const char *root,
    const char *install_root, char *err,
    size_t errcap)
{
    char *rroot = resolve_missing(root);
    char *rinst = install_root != NULL ? resolve_missing(install_root) : NULL;
    if (strcmp(t->op, "uninstall") == 0) {
        const char *const del[] = { t->old_launcher, t->old_binary };
        bool trusted = rroot != NULL
            && instructions_trusted(del, sizeof(del) / sizeof(del[0]),
                                    rroot, rinst);
        char *dir = trusted ? uninstall_app_dir(t, root, install_root) : NULL;
        struct stat sb;
        bool ok = trusted && dir != NULL && unlink_missing_ok(t->old_launcher)
            && unlink_missing_ok(t->old_binary);
        if (ok && lstat(dir, &sb) == 0) ok = fs_rm_tree(dir);
        else if (ok && errno != ENOENT) ok = false;
        free(dir);
        free(rroot);
        free(rinst);
        if (!ok)
            snprintf(err, errcap, "Could not safely finish uninstall "
                "transaction %s; journal retained for recovery.", t->id);
        return ok;
    }
    const char *const stage[] = { t->new_binary_tmp, t->new_launcher_tmp,
        t->new_manifest_tmp, t->new_manifest };
    bool trusted = rroot != NULL
        && instructions_trusted(stage, sizeof(stage) / sizeof(stage[0]),
                                rroot, rinst);
    free(rroot);
    free(rinst);
    if (!trusted) {
        snprintf(err, errcap, "Transaction journal names a path outside "
            "the managed roots; manual recovery required.");
        return false;
    }
    if ((t->new_binary_tmp != NULL
            && !apply_file(t->new_binary_tmp, NULL, t->new_asset_sha256))
        || (t->new_launcher_tmp != NULL
            && !apply_rename(t->new_launcher_tmp, NULL))
        || !apply_manifest(t)) {
        snprintf(err, errcap, "Transaction %s is corrupt; manual "
            "recovery required.", t->id);
        return false;
    }
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
        // Stages live beside their targets under the state root's
        // applications/ tree; the shared install root is never swept by
        // name (R14: an unrelated file ending in .tmp-<16hex> is not ours)
        char *apps = path_join(root, "applications");
        if (apps != NULL) { sweep_dir(apps, 8); free(apps); }
        return true;
    }
    if (l == TXN_UNREADABLE) {
        fputs("Transaction journal is unreadable; manual recovery "
            "required.\n", stderr);
        return false;
    }
    bool ok = true;
    if (strcmp(t.phase, "prepared") == 0) {
        // a record staged between the two journal writes is not named by
        // this phase; its name is derived, so derive it to clean up. The
        // decode scratch (xz envelope, delta apply) lives only inside the
        // prepared window and is named from the staged path.
        char *d = t.new_manifest != NULL && t.id != NULL
            ? path_fmt("%s.tmp-%s", t.new_manifest, t.id) : NULL;
        char *xdec = t.new_binary_tmp != NULL && t.id != NULL
            ? path_fmt("%s.xdec.tmp-%s", t.new_binary_tmp, t.id) : NULL;
        char *xdel = t.new_binary_tmp != NULL && t.id != NULL
            ? path_fmt("%s.xdel.tmp-%s", t.new_binary_tmp, t.id) : NULL;
        char *rroot = resolve_missing(root);
        char *rinst = install_root != NULL
            ? resolve_missing(install_root) : NULL;
        const char *const del[] = { t.new_binary_tmp, t.new_manifest_tmp,
            t.new_launcher_tmp, d, xdec, xdel };
        bool trusted = rroot != NULL
            && instructions_trusted(del, sizeof(del) / sizeof(del[0]),
                                    rroot, rinst);
        free(rroot);
        free(rinst);
        if (trusted) {
            if (t.new_binary_tmp != NULL) remove(t.new_binary_tmp);
            if (t.new_manifest_tmp != NULL) remove(t.new_manifest_tmp);
            if (t.new_launcher_tmp != NULL) remove(t.new_launcher_tmp);
            if (d != NULL) remove(d);
            if (xdec != NULL) remove(xdec);
            if (xdel != NULL) remove(xdel);
            char *p = path_join(root, TXN_FILE);
            if (p != NULL) { remove(p); free(p); }
            info_line(quiet, json, "Rolled back interrupted %s for %s.",
                t.op, t.ref);
        } else {
            fputs("Transaction journal names a path outside the managed "
                "roots; manual recovery required.\n", stderr);
            ok = false;
        }
        free(d);
        free(xdec);
        free(xdel);
    } else {
        char err[800] = "";
        ok = roll_forward(&t, root, install_root, err, sizeof(err));
        if (ok) {
            char *p = path_join(root, TXN_FILE);
            if (p == NULL || (remove(p) != 0 && errno != ENOENT)) {
                snprintf(err, sizeof(err), "Could not remove transaction "
                    "journal; recovery remains pending.");
                ok = false;
            }
            free(p);
        }
        if (ok) {
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
