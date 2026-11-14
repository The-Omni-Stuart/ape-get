// Installed-release identity keys, guarded Java-layout compatibility,
// locked-member rewrite and purge removal.

#include "manifest.h"
#include "state/internal.h"

#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "fs.h"
#include "crypto.h"
#include "json.h"
#include "path.h"

// --- state directory naming ---

// Java String.hashCode, unsigned hex — legacy directory suffix only.
static unsigned java_hash(const char *s)
{
    unsigned h = 0;
    for (; *s; s++) h = 31u * h + (unsigned)(unsigned char)*s;
    return h;
}

char *dup_span(const char *b, const char *e)
{
    size_t n = (size_t)(e - b);
    char *t = malloc(n + 1);
    if (t == NULL) return NULL;
    memcpy(t, b, n);
    t[n] = '\0';
    return t;
}

// lowercase, runs outside [a-z0-9._-] collapse to '-', leading/trailing
// [._-] trimmed, all-empty becomes "app"
static char *sanitize(const char *value)
{
    size_t len = strlen(value);
    char *tmp = malloc(len * 3 + 1);  // ASCII lowering is 1:1; *3 is slack
    if (tmp == NULL) return NULL;
    size_t o = 0;
    for (size_t i = 0; i < len; i++) {
        char c = (char)tolower((unsigned char)value[i]);
        bool keep = isdigit((unsigned char)c)
            || (c >= 'a' && c <= 'z')
            || c == '.' || c == '_' || c == '-';
        if (keep) {
            tmp[o++] = c;
        } else if (o == 0 || tmp[o - 1] != '-') {
            tmp[o++] = '-';
        }
    }
    tmp[o] = '\0';
    size_t b = 0;
    while (b < o && (tmp[b] == '.' || tmp[b] == '_' || tmp[b] == '-')) b++;
    while (o > b && (tmp[o - 1] == '.' || tmp[o - 1] == '_' || tmp[o - 1] == '-'))
        o--;
    if (o == b) {
        strcpy(tmp, "app");
        return tmp;
    }
    char *out = strdup(tmp + b);
    out[strlen(tmp + b)] = '\0';
    char *cut = out + o - b;
    *cut = '\0';
    free(tmp);
    return out;
}

// split on '/', every segment must be non-blank, segments sanitise-joined
// with "__", hash suffix when the raw name contains "__"
static char *repo_directory_name(const char *repo)
{
    if (repo == NULL || strchr(repo, '\\') != NULL) return NULL;
    char **segs = NULL;
    int nseg = 0, cap = 0;
    const char *p = repo;
    for (;;) {
        const char *slash = strchr(p, '/');
        size_t len = slash != NULL ? (size_t)(slash - p) : strlen(p);
        if (nseg == cap) {
            char **g = realloc(segs, (size_t)(cap = cap ? cap * 2 : 4) * sizeof(*g));
            if (g == NULL) { free(segs); return NULL; }
            segs = g;
        }
        segs[nseg] = malloc(len + 1);
        if (segs[nseg] == NULL) {
            for (int i = 0; i < nseg; i++) free(segs[i]);
            free(segs);
            return NULL;
        }
        memcpy(segs[nseg], p, len);
        segs[nseg][len] = '\0';
        nseg++;
        if (slash == NULL) break;
        p = slash + 1;
    }
    char *name = NULL;
    if (nseg < 2) goto fail;
    for (int i = 0; i < nseg; i++) {
        const char *s = segs[i];
        while (*s) { if (!isspace((unsigned char)*s)) goto next; s++; }
        goto fail;   // blank segment: the original rejected these
    next:;
    }
    name = strdup("");
    for (int i = 0; i < nseg && name != NULL; i++) {
        char *san = sanitize(segs[i]);
        if (san == NULL) { free(name); name = NULL; break; }
        char *grown = i == 0 ? strdup(san)
                             : path_fmt("%s__%s", name, san);
        free(name);
        free(san);
        name = grown;
    }
    if (name != NULL && strstr(repo, "__") != NULL) {
        char *grown = path_fmt("%s--%x", name, java_hash(repo));
        free(name);
        name = grown;
    }
fail:
    for (int i = 0; i < nseg; i++) free(segs[i]);
    free(segs);
    return name;
}

// '~' cannot occur in a legacy sanitised name. Include the NUL separator
// in the digest so provider/repository boundaries are unambiguous.
static char *identity_name(const char *provider, const char *repo)
{
    if (provider == NULL || repo == NULL) return NULL;
    size_t pn = strlen(provider), rn = strlen(repo);
    if (pn == SIZE_MAX || rn > SIZE_MAX - pn - 1) return NULL;
    char *identity = malloc(pn + 1 + rn);
    if (identity == NULL) return NULL;
    memcpy(identity, provider, pn + 1);
    memcpy(identity + pn + 1, repo, rn);
    char hash[65];
    bool ok = crypto_sha256_data(identity, pn + 1 + rn, hash);
    free(identity);
    return ok ? path_fmt("~%s", hash) : NULL;
}

// Windows device basenames remain reserved even with an extension. Use the
// same component on every host; never let APFS/Windows case folding alias it.
static bool portable_component(const char *name)
{
    if (name == NULL || *name == '\0' || strlen(name) > 200) return false;
    char *san = sanitize(name);
    bool ok = san != NULL && strcmp(san, name) == 0;
    free(san);
    if (!ok) return false;
    size_t n = strcspn(name, ".");
    if (n == 3 && (!strncmp(name, "con", 3) || !strncmp(name, "prn", 3)
        || !strncmp(name, "aux", 3) || !strncmp(name, "nul", 3))) return false;
    if (n == 4 && (!strncmp(name, "com", 3) || !strncmp(name, "lpt", 3))
        && name[3] >= '1' && name[3] <= '9') return false;
    return true;
}

char *manifest_provider_dir(const char *provider)
{
    return portable_component(provider) ? strdup(provider)
        : identity_name(provider, "");
}

// the self entry's repo is a bare name, never owner/repo; its directory
// is the sanitised name under applications/self
bool repo_dir_ok(const char *provider, const char *repo)
{
    if (provider != NULL && strcmp(provider, "self") == 0)
        return repo != NULL && *repo != '\0'
            && strchr(repo, '/') == NULL && strchr(repo, '\\') == NULL;
    char *d = repo_directory_name(repo);
    bool ok = d != NULL;
    free(d);
    return ok;
}

static char *app_directory(const char *root, const char *pdir, const char *rdir)
{
    if (pdir == NULL || rdir == NULL) return NULL;
    char *apps = path_join(root, "applications");
    char *pd = apps != NULL ? path_join(apps, pdir) : NULL;
    free(apps);
    char *rd = pd != NULL ? path_join(pd, rdir) : NULL;
    free(pd);
    return rd;
}

// 0: absent; 1: exact valid identity; -1: present but no ownership proof.
// This lookup deliberately never quarantines or rewrites a legacy record.
static int directory_identity(const char *dir, const char *provider,
                              const char *repo)
{
    char *file = dir != NULL ? path_join(dir, "grm_manifest.json") : NULL;
    if (file == NULL) return -1;
    struct stat sb;
    if (lstat(file, &sb) != 0) {
        int result = errno == ENOENT ? 0 : -1;
        free(file);
        return result;
    }
    size_t n = 0;
    char *text = S_ISREG(sb.st_mode) ? fs_read_file(file, &n) : NULL;
    free(file);
    jobject o = {0};
    bool ok = text != NULL && strlen(text) == n && json_valid(text, n)
        && jobject_parse(text, &o) && manifest_json_valid(&o);
    for (int i = 0; ok && i < o.n; i++)
        for (int j = 0; j < i; j++)
            if (strcmp(o.keys[i], o.keys[j]) == 0) ok = false;
    char *p = NULL, *r = NULL;
    long schema = 0;
    ok = ok && json_get_int(text, "schema_version", &schema) && schema == 1
        && json_get_string(text, "provider", &p)
        && json_get_string(text, "repo", &r)
        && strcmp(p, provider) == 0 && strcmp(r, repo) == 0;
    free(p);
    free(r);
    jobject_free(&o);
    free(text);
    return ok ? 1 : -1;
}

char *manifest_app_dir(const char *root, const char *provider,
                       const char *repo)
{
    if (provider == NULL || !repo_dir_ok(provider, repo)) return NULL;
    char *pdir = manifest_provider_dir(provider);
    char *rdir = identity_name(provider, repo);
    char *dir = app_directory(root, pdir, rdir);
    free(pdir);
    free(rdir);
    if (dir == NULL) return NULL;
    int current = directory_identity(dir, provider, repo);
    if (current != 0) {
        if (current < 0) { free(dir); return NULL; }
        return dir;
    }
    char *lp = sanitize(provider);
    char *lr = strcmp(provider, "self") == 0 && strchr(repo, '/') == NULL
        ? sanitize(repo) : repo_directory_name(repo);
    char *legacy = app_directory(root, lp, lr);
    free(lp);
    free(lr);
    if (legacy == NULL) { free(dir); return NULL; }
    int old = directory_identity(legacy, provider, repo);
    if (old == 1) { free(dir); return legacy; }
    // Automatic self registration must not step around a lost policy record.
    // Explicit package operations can keep foreign ordinary legacy identities
    // separate; self must retain the conservative S04 repair requirement.
    struct stat sb;
    bool self_policy = !strcmp(provider, "self") && !strcmp(repo, "ape-get")
        && (old < 0 || lstat(legacy, &sb) == 0 || errno != ENOENT);
    free(legacy);
    if (self_policy) { free(dir); return NULL; }
    return dir;
}

char *manifest_path(const char *root, const char *provider, const char *repo)
{
    char *dir = manifest_app_dir(root, provider, repo);
    if (dir == NULL) return NULL;
    char *file = path_join(dir, "grm_manifest.json");
    free(dir);
    return file;
}

// Lossless portable identities retain provider__owner__repo install names;
// every ambiguous/long transform uses the reserved complete identity key.
char *manifest_install_name(const char *provider, const char *repo)
{
    if (repo == NULL) return NULL;
    // Retain familiar lossless names; transformed identities get a reserved
    // full digest, including provider and namespace boundaries.
    bool lossless = portable_component(provider) && strstr(provider, "__") == NULL
        && strstr(repo, "__") == NULL;
    for (const char *p = repo; lossless && *p;) {
        const char *slash = strchr(p, '/');
        char *seg = slash != NULL ? dup_span(p, slash) : strdup(p);
        lossless = portable_component(seg);
        free(seg);
        if (slash == NULL) break;
        p = slash + 1;
    }
    if (!lossless) return identity_name(provider, repo);
    size_t pn = strlen(provider), n = pn + 2;
    for (const char *p = repo; *p; p++) {
        n += *p == '/' ? 2 : 1;
        if (n > 200) return identity_name(provider, repo);
    }
    char *name = malloc(n + 1);
    if (name == NULL) return NULL;
    memcpy(name, provider, pn);
    name[pn] = name[pn + 1] = '_';
    size_t o = pn + 2;
    for (const char *p = repo; *p; p++) {
        if (*p == '/') { name[o++] = '_'; name[o++] = '_'; }
        else name[o++] = *p;
    }
    name[o] = '\0';
    return name;
}

// --- locked member ---

bool manifest_get_locked(const char *file, bool *locked)
{
    size_t n = 0;
    char *text = fs_read_file(file, &n);
    if (text == NULL) return false;
    jobject o = {0};
    bool ok = jobject_parse(text, &o);
    if (ok) {
        int i = jobject_find(&o, "locked");
        *locked = false;
        if (i >= 0) ok = json_span_bool(o.vals[i], o.vends[i], locked);
    }
    jobject_free(&o);
    free(text);
    return ok;
}

// rewrite every member verbatim (order preserved), dropping or replacing
// "locked"; reserialisation whitespace may differ from the original writer
bool manifest_set_locked(const char *file, bool locked)
{
    size_t n = 0;
    char *text = fs_read_file(file, &n);
    if (text == NULL) return false;
    jobject o = {0};
    if (!jobject_parse(text, &o)) { jobject_free(&o); free(text); return false; }
    jbuf b = {0};
    jb_lit(&b, "{");
    for (int i = 0; i < o.n; i++) {
        if (strcmp(o.keys[i], "locked") == 0) continue;
        if (b.n > 1) jb_lit(&b, ",");
        jb_json_string(&b, o.keys[i]);
        jb_lit(&b, ":");
        char *raw = dup_span(o.vals[i], o.vends[i]);
        if (raw == NULL) { jb_free(&b); jobject_free(&o); free(text); return false; }
        jb_lit(&b, raw);
        free(raw);
    }
    if (locked) {
        if (b.n > 1) jb_lit(&b, ",");
        jb_lit(&b, "\"locked\":true");
    }
    jb_lit(&b, "}");
    bool ok = b.p != NULL && fs_write_file(file, b.p);
    jb_free(&b);
    jobject_free(&o);
    free(text);
    return ok;
}

// --- purge support ---

static bool rmrf(const char *path)
{
    // lstat first: a symlink-to-directory must be unlinked, not opened
    // and deleted through, or purge would reach outside the state tree
    struct stat sb;
    if (lstat(path, &sb) != 0) return errno == ENOENT;
    if (!S_ISDIR(sb.st_mode))
        return unlink(path) == 0 || errno == ENOENT;
    DIR *d = opendir(path);
    if (d == NULL) return unlink(path) == 0 || access(path, F_OK) != 0;
    struct dirent *de;
    bool ok = true;
    while ((de = readdir(d)) != NULL) {
        if (de->d_name[0] == '.'
            && (de->d_name[1] == '\0'
                || (de->d_name[1] == '.' && de->d_name[2] == '\0')))
            continue;
        char *child = path_join(path, de->d_name);
        if (child == NULL) { ok = false; break; }
        ok = rmrf(child) && ok;
        free(child);
    }
    closedir(d);
    return ok && rmdir(path) == 0;
}

bool manifest_remove_app_dir(const char *manifest_file)
{
    // The caller holds the exact owned record, even after unlinking it.
    const char *slash = manifest_file != NULL ? strrchr(manifest_file, '/') : NULL;
    if (slash == NULL || strcmp(slash + 1, "grm_manifest.json") != 0) return false;
    char *dir = dup_span(manifest_file, slash);
    if (dir == NULL) return false;
    bool ok = rmrf(dir);
    free(dir);
    return ok;
}
