// Backup records: copy the live install + manifest copy into
// appDir/backups/<epochMillis>-<uuid>/, extend the record list; trimmed
// generations are deleted only once the new manifest activated.

#include "state.h"
#include "fs.h"
#include "json.h"
#include "manifest.h"
#include "path.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static void uuid4(char out[37])
{
    unsigned char b[16];
    FILE *f = fopen("/dev/urandom", "rb");
    if (f == NULL || fread(b, 1, sizeof b, f) != sizeof b) {
        if (f != NULL) fclose(f);
        for (size_t k = 0; k < sizeof b; k++) b[k] = (unsigned char)rand();
    } else
        fclose(f);
    b[6] = (unsigned char)((b[6] & 0x0f) | 0x40);
    b[8] = (unsigned char)((b[8] & 0x3f) | 0x80);
    sprintf(out,
        "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
        b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
        b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]);
}

char *manifest_backups_array(const char *file)
{
    size_t n = 0;
    char *text = file != NULL ? fs_read_file(file, &n) : NULL;
    if (text == NULL) return strdup("[]");
    const char *vs, *ve;
    char *r = NULL;
    if (json_raw(text, "backups", &vs, &ve))
        r = strndup(vs, (size_t)(ve - vs));
    free(text);
    return r != NULL ? r : strdup("[]");
}

static void now_iso(char *buf, size_t cap)
{
    time_t t = time(NULL);
    struct tm tmv;
    gmtime_r(&t, &tmv);
    snprintf(buf, cap, "%04d-%02d-%02dT%02d:%02d:%02dZ",
        tmv.tm_year + 1900, tmv.tm_mon + 1, tmv.tm_mday,
        tmv.tm_hour, tmv.tm_min, tmv.tm_sec);
}

bool backup_create(const state_ctx *st, const manifest_entry *prev,
                   int keep, char **backups_json, char **prune_paths,
                   char *err, size_t errcap)
{
    *backups_json = NULL;
    if (prune_paths != NULL) *prune_paths = NULL;
    if (err != NULL && errcap > 0) err[0] = '\0';
    const char *live = prev->path;
    if (live == NULL || !fs_exists(live)) {
        snprintf(err, errcap, "Installed path does not exist: %s",
            live != NULL ? live : "");
        return false;
    }
    char *app = manifest_app_dir(st->root, prev->provider, prev->repo);
    char *old = app != NULL ? manifest_backups_array(prev->file) : NULL;
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    char uuid[37];
    uuid4(uuid);
    char *bdir = path_fmt("%s/backups/%lld-%s", app != NULL ? app : "",
        (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000, uuid);
    const char *fname = strrchr(live, '/') != NULL
        ? strrchr(live, '/') + 1 : live;
    char *dest = bdir != NULL ? path_join(bdir, fname) : NULL;
    char *bman = bdir != NULL
        ? path_join(bdir, "grm_backup_manifest.json") : NULL;
    size_t n = 0;
    char *mtxt = prev->file != NULL ? fs_read_file(prev->file, &n) : NULL;
    bool ok = old != NULL && bdir != NULL && dest != NULL && bman != NULL
        && mtxt != NULL
        && fs_mkdir_p(bdir)
        && (fs_is_dir(live) ? fs_copy_tree(live, dest)
                            : fs_copy_file(live, dest))
        && fs_write_file(bman, mtxt);
    if (!ok) {
        if (err != NULL && errcap > 0)
            snprintf(err, errcap, "cannot write %s", dest != NULL ? dest : "");
        if (bdir != NULL) fs_rm_tree(bdir);
        free(old); free(app); free(bdir); free(dest); free(bman); free(mtxt);
        return false;
    }

    // backup record, original Backup member order
    char now[40];
    now_iso(now, sizeof(now));
    jbuf rec = {0};
    jb_lit(&rec, "{\"version\":");
    if (prev->version != NULL) jb_json_string(&rec, prev->version);
    else jb_lit(&rec, "null");
    jb_lit(&rec, ",\"path\":");
    jb_json_string(&rec, bdir);
    jb_lit(&rec, ",\"artifact\":");
    jb_json_string(&rec, dest);
    jb_lit(&rec, ",\"install_path\":");
    jb_json_string(&rec, live);
    jb_lit(&rec, ",\"created_at\":");
    jb_json_string(&rec, now);
    jb_lit(&rec, ",\"manifest\":");
    jb_json_string(&rec, bman);
    jb_lit(&rec, "}");

    // merged = previous entries (raw text) then the new record, ascending
    // by created_at; a null created_at counts as oldest
    jspan *sp = NULL;
    int cnt = json_array_spans(old, &sp);
    char **items = calloc((size_t)cnt + 1, sizeof(*items));
    char **keys = calloc((size_t)cnt + 1, sizeof(*keys));
    int m = 0;
    jbuf prune = {0};
    int drop = 0;
    if (items != NULL && keys != NULL && rec.p != NULL) {
        for (int k = 0; k < cnt; k++) {
            items[m] = strndup(sp[k].s, (size_t)(sp[k].e - sp[k].s));
            if (items[m] == NULL) continue;
            jobject bo = {0};
            if (jobject_parse(items[m], &bo)) {
                int ci = jobject_find(&bo, "created_at");
                if (ci >= 0)
                    json_span_string(bo.vals[ci], bo.vends[ci], &keys[m]);
            }
            jobject_free(&bo);
            m++;
        }
        items[m] = strdup(rec.p);
        keys[m] = strdup(now);
        if (items[m] != NULL) m++;
        for (int a = 1; a < m; a++)
            for (int bnd = a; bnd > 0
                && strcmp(keys[bnd - 1] != NULL ? keys[bnd - 1] : "",
                          keys[bnd] != NULL ? keys[bnd] : "") > 0; bnd--) {
                char *t = keys[bnd - 1]; keys[bnd - 1] = keys[bnd]; keys[bnd] = t;
                t = items[bnd - 1]; items[bnd - 1] = items[bnd]; items[bnd] = t;
            }
        // trim the oldest beyond keep; deleting them waits until the new
        // manifest activated, so a failed update keeps every backup (S11)
        drop = m - keep;
        if (drop < 0) drop = 0;
        for (int a = 0; a < drop; a++) {
            jobject bo = {0};
            char *p = NULL;
            if (items[a] != NULL && jobject_parse(items[a], &bo)) {
                int pi = jobject_find(&bo, "path");
                if (pi >= 0 && json_span_string(bo.vals[pi], bo.vends[pi], &p)
                    && *p != '\0') {
                    jb_lit(&prune, prune.n > 0 ? "\n" : "");
                    jb_lit(&prune, p);
                }
                free(p);
            }
            jobject_free(&bo);
            free(items[a]); free(keys[a]);
            items[a] = NULL;
        }
    }
    jbuf arr = {0};
    jb_lit(&arr, "[");
    bool first = true;
    for (int a = drop; items != NULL && a < m; a++) {
        if (items[a] == NULL) continue;
        jb_lit(&arr, first ? "" : ",");
        first = false;
        jb_lit(&arr, items[a]);
    }
    jb_lit(&arr, "]");
    for (int a = 0; items != NULL && a < m; a++) { free(items[a]); free(keys[a]); }
    free(items); free(keys);
    free(sp);
    jb_free(&rec);
    free(old); free(app); free(bdir); free(dest); free(bman); free(mtxt);
    if (arr.p == NULL) {
        free(prune.p);
        return false;
    }
    *backups_json = arr.p;
    if (prune_paths != NULL) *prune_paths = prune.p;
    else free(prune.p);
    return true;
}

void backup_prune_trimmed(const char *app_dir, const char *prune_paths)
{
    if (app_dir == NULL || prune_paths == NULL || *prune_paths == '\0') return;
    char *base = path_join(app_dir, "backups");
    char *rbase = base != NULL ? realpath(base, NULL) : NULL;
    const char *s = prune_paths;
    while (*s != '\0') {
        const char *e = strchr(s, '\n');
        size_t len = e != NULL ? (size_t)(e - s) : strlen(s);
        char *p = strndup(s, len);
        char *rp = p != NULL ? realpath(p, NULL) : NULL;
        // our own generation only: inside backups/ and directly
        // under it — crafted or nested paths are left in place
        if (rp != NULL && rbase != NULL) {
            size_t bn = strlen(rbase);
            const char *tail = rp + bn;
            if (!strncmp(rp, rbase, bn) && tail[0] == '/' && tail[1] != '\0'
                && strchr(tail + 1, '/') == NULL)
                fs_rm_tree(rp);
        }
        free(rp);
        free(p);
        if (e == NULL) break;
        s = e + 1;
    }
    free(base);
    free(rbase);
}
