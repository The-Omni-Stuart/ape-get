// Journal text: strict load, where kind, schema and the known-field
// check reject anything else, and the canonical render.

#include "txn.h"
#include "txn/internal.h"
#include "fs.h"
#include "json.h"
#include "path.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char *OPS[] = { "install", "update", "reinstall",
    "downgrade", "rollback", "uninstall", "self-update", NULL };
static const char *PHASES[] = { "prepared", "committing", "committed",
    NULL };

// String member of a parsed object; NULL when absent or null.
static char *o_str(const jobject *o, const char *key)
{
    int i = jobject_find(o, key);
    if (i < 0) return NULL;
    char *v = NULL;
    json_span_string(o->vals[i], o->vends[i], &v);
    return v;
}

// Raw span text (kept verbatim for the inline manifest object).
static char *o_raw(const jobject *o, const char *key)
{
    int i = jobject_find(o, key);
    if (i < 0) return NULL;
    size_t n = (size_t)(o->vends[i] - o->vals[i]);
    char *v = malloc(n + 1);
    if (v == NULL) return NULL;
    memcpy(v, o->vals[i], n);
    v[n] = '\0';
    return v;
}

// Parse a nested object member; false when present but not an object.
// *text keeps sub alive (its values point into it), free after extracting.
static bool o_obj(const jobject *o, const char *key, jobject *sub,
    char **text, bool *present)
{
    *text = NULL;
    *present = false;
    int i = jobject_find(o, key);
    if (i < 0) return true;
    if (o->vals[i] == NULL || o->vends[i] - o->vals[i] < 1
        || o->vals[i][0] != '{')
        return false;
    *text = o_raw(o, key);
    if (*text == NULL) return false;
    bool ok = jobject_parse(*text, sub);
    if (ok) *present = true;
    else free(*text), *text = NULL;
    return ok;
}

static bool in_list(const char *v, const char *const *list)
{
    for (int k = 0; list[k] != NULL; k++)
        if (strcmp(v, list[k]) == 0) return true;
    return false;
}

// every listed key must belong to the member set; anything else makes
// the journal unreadable (unknown field)
static bool keys_known(const jobject *o, const char *const *known)
{
    for (int i = 0; i < o->n; i++) {
        bool hit = false;
        for (int k = 0; known[k] != NULL; k++)
            if (strcmp(o->keys[i], known[k]) == 0) { hit = true; break; }
        if (!hit) return false;
    }
    return true;
}

txn_load txn_load_journal(const char *root, txn *t)
{
    txn_init(t);
    char *p = path_join(root, TXN_FILE);
    if (p == NULL) return TXN_UNREADABLE;
    size_t n = 0;
    char *text = fs_read_file(p, &n);
    free(p);
    if (text == NULL) { free(text); return TXN_ABSENT; }

    bool bad = false;
    jobject o = {0};
    if (!jobject_parse(text, &o)) bad = true;
    static const char *const TOP[] = { "kind", "schema_version", "id",
        "op", "ref", "phase", "created_at", "old", "new", "backup",
        "pid", "host", "history_written", NULL };
    static const char *const OLDK[] = { "manifest_path", "manifest",
        "generation", "launcher", "binary", NULL };
    static const char *const NEWK[] = { "generation", "manifest_tmp",
        "manifest", "launcher_tmp", "binary_tmp", "asset_sha256", NULL };
    if (!bad && !keys_known(&o, TOP)) bad = true;
    if (!bad) {
        char *kind = o_str(&o, "kind");
        bad = kind == NULL || strcmp(kind, "grm.transaction") != 0;
        free(kind);
    }
    long sv = 0;
    int si = jobject_find(&o, "schema_version");
    if (!bad && (si < 0 || !json_span_int(o.vals[si], o.vends[si], &sv)
            || sv != 1))
        bad = true;
    if (!bad) {
        t->id = o_str(&o, "id");
        t->op = o_str(&o, "op");
        t->ref = o_str(&o, "ref");
        t->phase = o_str(&o, "phase");
        t->created_at = o_str(&o, "created_at");
        bad = t->id == NULL || t->op == NULL || t->ref == NULL
            || t->phase == NULL || t->created_at == NULL;
    }
    if (!bad) bad = !in_list(t->op, OPS) || !in_list(t->phase, PHASES);
    if (!bad) {
        t->backup = o_str(&o, "backup");
        t->host = o_str(&o, "host");
        int pi = jobject_find(&o, "pid");
        if (pi >= 0)
            json_span_int(o.vals[pi], o.vends[pi], &t->pid);
        int hi = jobject_find(&o, "history_written");
        if (hi >= 0) json_span_bool(o.vals[hi], o.vends[hi],
            &t->history_written);
    }
    jobject oldo = {0}, newo = {0};
    char *old_text = NULL, *new_text = NULL;
    bool oldp = false, newp = false;
    if (!bad) bad = !o_obj(&o, "old", &oldo, &old_text, &oldp);
    if (!bad && oldp && !keys_known(&oldo, OLDK)) bad = true;
    if (!bad && oldp) {
        t->old_manifest_path = o_str(&oldo, "manifest_path");
        t->old_manifest = o_raw(&oldo, "manifest");
        t->old_generation = o_str(&oldo, "generation");
        t->old_launcher = o_str(&oldo, "launcher");
        t->old_binary = o_str(&oldo, "binary");
    }
    if (!bad) bad = !o_obj(&o, "new", &newo, &new_text, &newp);
    if (!bad && newp && !keys_known(&newo, NEWK)) bad = true;
    if (!bad && newp) {
        t->new_generation = o_str(&newo, "generation");
        t->new_manifest = o_str(&newo, "manifest");
        t->new_manifest_tmp = o_str(&newo, "manifest_tmp");
        t->new_launcher_tmp = o_str(&newo, "launcher_tmp");
        t->new_binary_tmp = o_str(&newo, "binary_tmp");
        t->new_asset_sha256 = o_str(&newo, "asset_sha256");
    }
    free(old_text);
    free(new_text);
    jobject_free(&oldo);
    jobject_free(&newo);
    if (bad) { txn_free(t); jobject_free(&o); free(text); return TXN_UNREADABLE; }
    jobject_free(&o);
    free(text);
    return TXN_READ;
}

static void put_opt_str(jbuf *b, const char *key, const char *v,
    bool *first)
{
    if (v == NULL) return;
    if (!*first) jb_lit(b, ",");
    *first = false;
    jb_lit(b, "\"");
    jb_lit(b, key);
    jb_lit(b, "\":");
    jb_json_string(b, v);
}

char *txn_build(const txn *t)
{
    jbuf b = {0};
    jb_lit(&b, "{\"kind\":\"grm.transaction\",\"schema_version\":1,");
    jb_lit(&b, "\"id\":");
    jb_json_string(&b, t->id != NULL ? t->id : "");
    jb_lit(&b, ",\"op\":");
    jb_json_string(&b, t->op != NULL ? t->op : "");
    jb_lit(&b, ",\"ref\":");
    jb_json_string(&b, t->ref != NULL ? t->ref : "");
    jb_lit(&b, ",\"phase\":");
    jb_json_string(&b, t->phase != NULL ? t->phase : "");
    jb_lit(&b, ",\"created_at\":");
    jb_json_string(&b, t->created_at != NULL ? t->created_at : "");
    if (t->old_manifest_path != NULL || t->old_manifest != NULL
        || t->old_generation != NULL || t->old_launcher != NULL
        || t->old_binary != NULL) {
        jb_lit(&b, ",\"old\":{");
        bool first = true;
        put_opt_str(&b, "manifest_path", t->old_manifest_path, &first);
        if (t->old_manifest != NULL) {
            if (!first) jb_lit(&b, ",");
            first = false;
            jb_lit(&b, "\"manifest\":");
            jb_lit(&b, t->old_manifest); // verbatim inline object
        }
        put_opt_str(&b, "generation", t->old_generation, &first);
        put_opt_str(&b, "launcher", t->old_launcher, &first);
        put_opt_str(&b, "binary", t->old_binary, &first);
        jb_lit(&b, "}");
    }
    if (t->new_generation != NULL || t->new_manifest != NULL
        || t->new_manifest_tmp != NULL || t->new_launcher_tmp != NULL
        || t->new_binary_tmp != NULL || t->new_asset_sha256 != NULL) {
        jb_lit(&b, ",\"new\":{");
        bool nf = true;
        put_opt_str(&b, "generation", t->new_generation, &nf);
        put_opt_str(&b, "manifest_tmp", t->new_manifest_tmp, &nf);
        put_opt_str(&b, "manifest", t->new_manifest, &nf);
        put_opt_str(&b, "launcher_tmp", t->new_launcher_tmp, &nf);
        put_opt_str(&b, "binary_tmp", t->new_binary_tmp, &nf);
        put_opt_str(&b, "asset_sha256", t->new_asset_sha256, &nf);
        jb_lit(&b, "}");
    }
    if (t->backup != NULL) {
        jb_lit(&b, ",\"backup\":");
        jb_json_string(&b, t->backup);
    }
    if (t->pid > 0) {
        char pid[32];
        snprintf(pid, sizeof(pid), ",\"pid\":%ld", t->pid);
        jb_lit(&b, pid);
    }
    if (t->host != NULL) {
        jb_lit(&b, ",\"host\":");
        jb_json_string(&b, t->host);
    }
    if (t->history_written) jb_lit(&b, ",\"history_written\":true");
    jb_lit(&b, "}");
    return b.p;
}
