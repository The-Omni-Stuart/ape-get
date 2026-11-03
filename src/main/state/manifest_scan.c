// Scan under applications/: read each record, quarantine corrupt
// files, report foreign kinds, collect and sort the valid entries.

#include "manifest.h"
#include "state/internal.h"

#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>

#include "fs.h"
#include "json.h"
#include "path.h"

// --- scan ---

typedef struct {
    manifest_entry *items;
    size_t n, cap;
    bool failed;
} mlist;

static void push(mlist *l, manifest_entry *e)
{
    if (l->n == l->cap) {
        size_t cap = l->cap ? l->cap * 2 : 8;
        manifest_entry *g = realloc(l->items, cap * sizeof(*g));
        if (g == NULL) { l->failed = true; return; }
        l->items = g;
        l->cap = cap;
    }
    l->items[l->n++] = *e;
}

static void quarantine(const char *file)
{
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    long long millis = (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
    char *q = path_fmt("%s.corrupt-%lld", file, millis);
    if (q == NULL) return;
    if (fs_rename(file, q))
        fprintf(stderr, "Warning: corrupt manifest moved to %s\n", q);
    free(q);
}

// extra install fields consumed by the uninstall flows
static void read_install_fields(const jobject *root, manifest_entry *e)
{
    int ii = jobject_find(root, "install");
    if (ii < 0) return;
    char *it = dup_span(root->vals[ii], root->vends[ii]);
    jobject io = {0};
    if (it == NULL || !jobject_parse(it, &io)) { free(it); return; }
    bool b = false;
    str_member(&io, "kind", &e->kind, &b);
    str_member(&io, "desktop_entry", &e->desktop, &b);
    str_member(&io, "command", &e->command, &b);
    int ui = jobject_find(&io, "uninstall");
    if (ui >= 0) {
        char *ut = dup_span(io.vals[ui], io.vends[ui]);
        jobject uo = {0};
        if (ut != NULL && jobject_parse(ut, &uo))
            str_member(&uo, "path", &e->launcher, &b);
        jobject_free(&uo);
        free(ut);
    }
    jobject_free(&io);
    free(it);
}
// invalid records are quarantined, foreign kinds reported and skipped,
// valid ones appended to l
static void read_manifest(const char *file, mlist *l)
{
    size_t n = 0;
    char *text = fs_read_file(file, &n);
    if (text == NULL) return;
    jobject root = {0};
    if (!jobject_parse(text, &root)) {
        jobject_free(&root);
        free(text);
        quarantine(file);
        return;
    }
    bool bad = false;
    char *kind = NULL;
    bool has_kind = str_member(&root, "kind", &kind, &bad);
    if (bad) {
        jobject_free(&root);
        free(text);
        quarantine(file);
        return;
    }
    if (has_kind && strcmp(kind, "grm.manifest") != 0) {
        fprintf(stderr, "Ignoring foreign manifest: %s\n", file);
        free(kind);
        jobject_free(&root);
        free(text);
        return;
    }
    free(kind);

    char *path = NULL;
    if (!manifest_is_valid(&root, &path)) {
        free(path);
        jobject_free(&root);
        free(text);
        quarantine(file);
        return;
    }

    char *provider = NULL, *repo = NULL, *version = NULL;
    bool b = false;
    str_member(&root, "provider", &provider, &b);
    str_member(&root, "repo", &repo, &b);
    str_member(&root, "version", &version, &b);
    char *installed = NULL, *updated = NULL;
    str_member(&root, "installed_at", &installed, &b);
    str_member(&root, "updated_at", &updated, &b);
    char *asset = NULL, *asset_url = NULL, *asset_sha = NULL;
    int ai = jobject_find(&root, "asset");
    if (ai >= 0) {
        char *at = dup_span(root.vals[ai], root.vends[ai]);
        jobject ao = {0};
        if (at != NULL && jobject_parse(at, &ao)) {
            str_member(&ao, "name", &asset, &b);
            str_member(&ao, "url", &asset_url, &b);
            str_member(&ao, "sha256", &asset_sha, &b);
        }
        jobject_free(&ao);
        free(at);
    }
    int li = jobject_find(&root, "locked");
    bool locked = false;
    if (li >= 0) json_span_bool(root.vals[li], root.vends[li], &locked);
    char *compat = NULL;
    str_member(&root, "compat", &compat, &b);

    manifest_entry e = {
        .file = strdup(file), .provider = provider, .repo = repo,
        .version = version, .path = path, .command = NULL, .asset = asset,
        .asset_url = asset_url, .sha256 = asset_sha,
        .installed = installed, .updated = updated, .locked = locked,
        .compat = compat,
    };
    if (e.file == NULL || e.provider == NULL || e.repo == NULL)
        l->failed = true;
    else {
        read_install_fields(&root, &e);
        int si = jobject_find(&root, "signature");
        if (si >= 0) {
            char *st = dup_span(root.vals[si], root.vends[si]);
            jobject so = {0};
            if (st != NULL && jobject_parse(st, &so)) {
                str_member(&so, "status", &e.sig_status, &b);
                str_member(&so, "identity", &e.sig_identity, &b);
                str_member(&so, "signer", &e.sig_signer, &b);
            }
            jobject_free(&so);
            free(st);
        }
        push(l, &e);
    }
    jobject_free(&root);
    free(text);
}

// readdir order is fine (results get sorted); manifests under a
// "backups" path part are skipped
static bool has_backups_part(const char *path)
{
    const char *p = path;
    while ((p = strstr(p, "backups")) != NULL) {
        bool start = p == path || p[-1] == '/';
        bool end = p[7] == '/' || p[7] == '\0';
        if (start && end) return true;
        p += 7;
    }
    return false;
}

// Depth cap matching the recovery sweeps: the real tree is provider/app
// deep, so anything deeper is corruption or a loop, not state.
#define SCAN_MAX_DEPTH 8

static void scan_dir(const char *dir, mlist *l, int depth)
{
    DIR *d = opendir(dir);
    if (d == NULL) { l->failed = true; return; }
    struct dirent *de;
    while ((de = readdir(d)) != NULL) {
        if (de->d_name[0] == '.'
            && (de->d_name[1] == '\0'
                || (de->d_name[1] == '.' && de->d_name[2] == '\0')))
            continue;
        char *child = path_join(dir, de->d_name);
        if (child == NULL) { l->failed = true; continue; }
        struct stat st;
        // lstat: never follow a symlinked directory, or a link pointing
        // at an ancestor would rescan state forever
        if (lstat(child, &st) != 0) { free(child); continue; }
        if (S_ISDIR(st.st_mode)) {
            if (depth < SCAN_MAX_DEPTH) scan_dir(child, l, depth + 1);
        } else if (S_ISREG(st.st_mode)
                   && strcmp(de->d_name, "grm_manifest.json") == 0
                   && !has_backups_part(child)) {
            read_manifest(child, l);
        }
        free(child);
    }
    closedir(d);
}

static int cmp_entries(const void *a, const void *b)
{
    const manifest_entry *x = a, *y = b;
    int r = strcmp(x->repo, y->repo);
    if (r == 0) r = strcmp(x->provider, y->provider);
    return r;
}

int manifests_scan(const char *root, manifest_entry **out, size_t *count)
{
    *out = NULL;
    *count = 0;
    char *apps = path_join(root, "applications");
    if (apps == NULL) return -1;
    mlist l = {0};
    if (fs_is_dir(apps)) scan_dir(apps, &l, 0);
    free(apps);
    if (l.failed) {
        for (size_t i = 0; i < l.n; i++) {
            free(l.items[i].file); free(l.items[i].provider);
            free(l.items[i].repo); free(l.items[i].version);
            free(l.items[i].path);
        }
        free(l.items);
        return -1;
    }
    if (l.n > 1) qsort(l.items, l.n, sizeof(*l.items), cmp_entries);
    *out = l.items;
    *count = l.n;
    return 0;
}

void manifest_entry_free(manifest_entry *e)
{
    free(e->file);
    free(e->provider);
    free(e->repo);
    free(e->version);
    free(e->path);
    free(e->kind);
    free(e->launcher);
    free(e->desktop);
    free(e->command);
    free(e->asset);
    free(e->asset_url);
    free(e->sha256);
    free(e->installed);
    free(e->updated);
    free(e->sig_status);
    free(e->sig_identity);
    free(e->sig_signer);
    free(e->compat);
}
