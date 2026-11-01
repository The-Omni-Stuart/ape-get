// Record validity for grm_manifest.json: the value readers and the
// kept-field checks; a member of the wrong type means corrupt.

#include "manifest.h"
#include "state/internal.h"

#include <ctype.h>
#include <stdlib.h>
#include <string.h>

#include "json.h"

// --- value readers: a member of the wrong type means corrupt, not merely invalid ---

// true when the member is present and a string ("" included; NULL when
// absent); *bad when present but of the wrong type
bool str_member(const jobject *o, const char *key, char **out,
                       bool *bad)
{
    *out = NULL;
    int i = jobject_find(o, key);
    if (i < 0) return false;
    if (json_span_string(o->vals[i], o->vends[i], out)) return true;
    *bad = true;
    return false;
}

// empty or all whitespace, Java isBlank-style
static bool blank(const char *s)
{
    if (s == NULL) return true;
    while (*s) { if (!isspace((unsigned char)*s)) return false; s++; }
    return true;
}

// timestamp shape: ISO-8601 string or epoch number; unparseable strings and
// other types mean corrupt, not null
static bool instant_ok(const jobject *o, const char *key, bool *bad)
{
    int i = jobject_find(o, key);
    if (i < 0) return false;
    const char *s = o->vals[i], *e = o->vends[i];
    if (e - s >= 2 && *s == '"') {
        char probe[32];
        size_t n = (size_t)(e - s - 2);
        if (n >= sizeof(probe)) n = sizeof(probe) - 1;
        memcpy(probe, s + 1, n);
        probe[n] = '\0';
        // timestamps need at least yyyy-MM-ddT
        return n >= 11 && probe[4] == '-' && probe[7] == '-'
            && probe[10] == 'T';
    }
    if (s < e && (isdigit((unsigned char)*s) || *s == '-')) return true;
    if (e - s == 4 && strncmp(s, "null", 4) == 0) return false;
    *bad = true;
    return false;
}

// record validity over the kept fields; `path` receives install.path
// when the record is valid
bool manifest_is_valid(const jobject *root, char **path)
{
    bool bad = false;
    char *kind = NULL;
    str_member(root, "kind", &kind, &bad);
    bool ok = !bad && kind != NULL && strcmp(kind, "grm.manifest") == 0;
    free(kind);

    long schema = 0;
    int si = jobject_find(root, "schema_version");
    if (si >= 0) {
        if (!json_span_int(root->vals[si], root->vends[si], &schema))
            ok = false;
    } else {
        ok = false;
    }

    char *provider = NULL;
    str_member(root, "provider", &provider, &bad);
    ok = ok && !bad && !blank(provider);

    char *repo = NULL;
    str_member(root, "repo", &repo, &bad);
    ok = ok && !bad && !blank(repo);
    if (ok) ok = repo_dir_ok(provider, repo);
    free(repo);
    free(provider);

    int ai = jobject_find(root, "asset");
    ok = ok && ai >= 0;
    if (ai >= 0) {
        char *at = dup_span(root->vals[ai], root->vends[ai]);
        jobject ao = {0};
        if (at != NULL && jobject_parse(at, &ao)) {
            char *nm = NULL, *url = NULL;
            bool aok = true;
            bool n_present = str_member(&ao, "name", &nm, &aok);
            bool u_present = str_member(&ao, "url", &url, &aok);
            ok = ok && aok && n_present && !blank(nm)
                && u_present && !blank(url);
            free(nm);
            free(url);
        } else {
            ok = false;
        }
        jobject_free(&ao);
        free(at);
    }

    int ii = jobject_find(root, "install");
    ok = ok && ii >= 0;
    if (ii >= 0) {
        char *it = dup_span(root->vals[ii], root->vends[ii]);
        jobject io2 = {0};
        if (it != NULL && jobject_parse(it, &io2)) {
            char *pth = NULL;
            bool iok = true;
            bool p_present = str_member(&io2, "path", &pth, &iok);
            ok = ok && iok && p_present && !blank(pth);
            if (ok && path != NULL) *path = pth;
            else free(pth);
        } else {
            ok = false;
        }
        jobject_free(&io2);
        free(it);
    }

    bool ibad = false;
    ok = ok && instant_ok(root, "installed_at", &ibad);
    if (!ibad) {
        bool ubad = false;
        if (jobject_find(root, "updated_at") >= 0) {
            bool up_present = instant_ok(root, "updated_at", &ubad);
            ok = ok && up_present;
        }
        ok = ok && !ibad && !ubad;
    }

    // compat, when set, is a known layer name
    {
        bool cbad = false;
        char *compat = NULL;
        bool present = str_member(root, "compat", &compat, &cbad);
        ok = ok && !cbad;
        if (present && compat != NULL)
            ok = ok && strcmp(compat, "wine") == 0;
        free(compat);
    }
    return ok;
}

// validity of an already parsed manifest (rollback's backup check)
bool manifest_json_valid(const jobject *root)
{
    return manifest_is_valid(root, NULL);
}
