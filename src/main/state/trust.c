// Signing-key trust store: a per-repo fingerprint manifest (grm.trust)
// beside the other state files. The keys themselves live in grm_keyring/
// (ape-get's own gpg home); ~/.gnupg is consulted for verification but
// never written.

#include "state.h"

#include "fs.h"
#include "json.h"
#include "path.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

char *state_keyring_dir(const state_ctx *st)
{
    return path_join(st->root, "grm_keyring");
}

static bool s_corrupt_warned;

// The whole file plus the parsed repos object; the jobject spans alias
// rtext, so both buffers must outlive it.
typedef struct { char *text, *rtext; jobject repos; } trust_db;

static void trust_db_close(trust_db *db)
{
    jobject_free(&db->repos);
    free(db->rtext);
    free(db->text);
    memset(db, 0, sizeof(*db));
}

// Absent file = empty store. A corrupt file warns once per process and
// reads as empty; it is only ever replaced by an explicit trust_record.
static void trust_db_open(const state_ctx *st, trust_db *db)
{
    memset(db, 0, sizeof(*db));
    char *file = path_join(st->root, "grm_trust.json");
    db->text = file != NULL ? fs_read_file(file, NULL) : NULL;
    free(file);
    if (db->text == NULL) return;
    jobject top = {0};
    bool ok = jobject_parse(db->text, &top);
    if (ok) {
        int f = jobject_find(&top, "repos");
        if (f >= 0)
            db->rtext = strndup(top.vals[f],
                (size_t)(top.vends[f] - top.vals[f]));
        else
            ok = false;
    }
    jobject_free(&top);
    if (ok && db->rtext != NULL) ok = jobject_parse(db->rtext, &db->repos);
    if (!ok) {
        trust_db_close(db);
        if (!s_corrupt_warned) {
            s_corrupt_warned = true;
            fprintf(stderr, "Warning: trust records under %s are corrupt;"
                " treated as empty\n", st->root);
        }
    }
}

// True when key names the drop target: exact "prov:repo", or any provider
// when prov is NULL and the tail after the last colon matches repo.
static bool key_matches(const char *key, const char *prov, const char *repo)
{
    if (repo == NULL) return false;
    if (prov != NULL) {
        char *want = path_fmt("%s:%s", prov, repo);
        bool m = want != NULL && !strcmp(key, want);
        free(want);
        return m;
    }
    size_t kl = strlen(key), rl = strlen(repo);
    return kl > rl + 1 && key[kl - rl - 1] == ':'
        && !strcmp(key + kl - rl, repo);
}

// Re-emit the store applying at most one set (exact key) and one drop.
static bool trust_emit(const state_ctx *st, trust_db *db,
    const char *set_ref, const char *set_entry,
    const char *drop_prov, const char *drop_repo)
{
    jobject *r = &db->repos;
    jbuf m = {0};
    jb_lit(&m, "{\"kind\":\"grm.trust\",\"schema_version\":1,\"repos\":{");
    bool first = true, set_done = set_ref == NULL;
    for (int k = 0; k < r->n; k++) {
        const char *key = r->keys[k];
        if (drop_repo != NULL && key_matches(key, drop_prov, drop_repo))
            continue;
        if (!first) jb_lit(&m, ",");
        first = false;
        jb_json_string(&m, key);
        jb_lit(&m, ":");
        if (set_ref != NULL && !strcmp(key, set_ref)) {
            set_done = true;
            jb_lit(&m, set_entry != NULL ? set_entry : "null");
            continue;
        }
        char *raw = strndup(r->vals[k], (size_t)(r->vends[k] - r->vals[k]));
        jb_lit(&m, raw != NULL ? raw : "null");
        free(raw);
    }
    if (!set_done) {
        if (!first) jb_lit(&m, ",");
        jb_json_string(&m, set_ref);
        jb_lit(&m, ":");
        jb_lit(&m, set_entry != NULL ? set_entry : "null");
    }
    jb_lit(&m, "}}");
    char *file = path_join(st->root, "grm_trust.json");
    bool ok = m.p != NULL && file != NULL && fs_write_file(file, m.p);
    free(file);
    jb_free(&m);
    return ok;
}

// Raw member text of one repo record, or NULL.
static char *rec_raw(const jobject *r, int idx)
{
    return strndup(r->vals[idx], (size_t)(r->vends[idx] - r->vals[idx]));
}

static char *rec_member(const jobject *r, int idx, const char *key)
{
    char *raw = rec_raw(r, idx);
    jobject eo = {0};
    char *v = NULL;
    if (raw != NULL && jobject_parse(raw, &eo)) {
        int f = jobject_find(&eo, key);
        if (f >= 0) json_span_string(eo.vals[f], eo.vends[f], &v);
    }
    jobject_free(&eo);
    free(raw);
    return v;
}

bool trust_lookup(const state_ctx *st, const char *prov, const char *repo,
    char **fpr, char **uid)
{
    if (fpr != NULL) *fpr = NULL;
    if (uid != NULL) *uid = NULL;
    trust_db db;
    trust_db_open(st, &db);
    char *ref = path_fmt("%s:%s", prov, repo);
    int idx = ref != NULL ? jobject_find(&db.repos, ref) : -1;
    bool found = idx >= 0;
    if (found) {
        if (fpr != NULL) *fpr = rec_member(&db.repos, idx, "fingerprint");
        if (uid != NULL) *uid = rec_member(&db.repos, idx, "uid");
    }
    free(ref);
    trust_db_close(&db);
    return found;
}

bool trust_record(const state_ctx *st, const char *prov, const char *repo,
    const char *fpr, const char *uid)
{
    trust_db db;
    trust_db_open(st, &db);
    char *ref = path_fmt("%s:%s", prov, repo);
    jbuf e = {0};
    jb_lit(&e, "{\"fingerprint\":");
    jb_json_string(&e, fpr != NULL ? fpr : "");
    jb_lit(&e, ",\"uid\":");
    if (uid != NULL && *uid != '\0') jb_json_string(&e, uid);
    else jb_lit(&e, "null");
    jb_lit(&e, "}");
    bool ok = e.p != NULL && ref != NULL
        && trust_emit(st, &db, ref, e.p, NULL, NULL);
    jb_free(&e);
    free(ref);
    trust_db_close(&db);
    return ok;
}

int trust_remove(const state_ctx *st, const char *prov, const char *repo,
    trust_hit **out)
{
    trust_db db;
    trust_db_open(st, &db);
    int n = 0;
    trust_hit *hits = NULL;
    for (int k = 0; k < db.repos.n; k++) {
        if (!key_matches(db.repos.keys[k], prov, repo)) continue;
        trust_hit *h = realloc(hits, (size_t)(n + 1) * sizeof(*hits));
        if (h == NULL) break;
        hits = h;
        hits[n].ref = strdup(db.repos.keys[k]);
        hits[n].fingerprint = rec_member(&db.repos, k, "fingerprint");
        if (hits[n].ref != NULL) n++;
    }
    bool ok = trust_emit(st, &db, NULL, NULL, prov, repo);
    trust_db_close(&db);
    if (!ok) {
        for (int k = 0; k < n; k++) {
            free(hits[k].ref);
            free(hits[k].fingerprint);
        }
        free(hits);
        *out = NULL;
        return -1;
    }
    *out = hits;
    return n;
}

bool trust_fpr_used(const state_ctx *st, const char *fpr)
{
    trust_db db;
    trust_db_open(st, &db);
    bool used = false;
    for (int k = 0; k < db.repos.n && !used; k++) {
        char *one = rec_member(&db.repos, k, "fingerprint");
        used = one != NULL && fpr != NULL && !strcmp(one, fpr);
        free(one);
    }
    trust_db_close(&db);
    return used;
}

void trust_summary(const state_ctx *st, int *keys, int *repos)
{
    *keys = 0;
    *repos = 0;
    trust_db db;
    trust_db_open(st, &db);
    char *seen[128];
    int ns = 0;
    for (int k = 0; k < db.repos.n; k++) {
        (*repos)++;
        char *one = rec_member(&db.repos, k, "fingerprint");
        if (one == NULL) continue;
        bool dup = false;
        for (int q = 0; q < ns && !dup; q++) dup = !strcmp(seen[q], one);
        if (!dup && ns < (int)(sizeof seen / sizeof seen[0])) seen[ns++] = one;
        else free(one);
    }
    *keys = ns;
    for (int q = 0; q < ns; q++) free(seen[q]);
    trust_db_close(&db);
}
