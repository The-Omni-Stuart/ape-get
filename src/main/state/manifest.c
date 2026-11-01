// Installed-release manifest directories and files: the naming rules
// the Java wrote, plus the locked-member rewrite and purge removal.

#include "manifest.h"
#include "state/internal.h"

#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "fs.h"
#include "json.h"
#include "path.h"

// --- state directory naming ---

// Java String.hashCode, unsigned hex — the suffix state dirs always used
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

char *manifest_provider_dir(const char *provider)
{
    return sanitize(provider);
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

char *manifest_app_dir(const char *root, const char *provider,
                       const char *repo)
{
    char *pdir = manifest_provider_dir(provider);
    if (pdir == NULL) return NULL;
    char *rdir = provider != NULL && strcmp(provider, "self") == 0
        && repo != NULL && *repo != '\0' && strchr(repo, '/') == NULL
        ? sanitize(repo) : repo_directory_name(repo);
    if (rdir == NULL) { free(pdir); return NULL; }
    char *apps = path_join(root, "applications");
    char *pd = path_join(apps, pdir);
    free(apps);
    free(pdir);
    char *rd = path_join(pd, rdir);
    free(pd);
    free(rdir);
    return rd;
}

char *manifest_path(const char *root, const char *provider, const char *repo)
{
    char *dir = manifest_app_dir(root, provider, repo);
    if (dir == NULL) return NULL;
    char *file = path_join(dir, "grm_manifest.json");
    free(dir);
    return file;
}

// provider__repo, '/' → "__", sanitised; "__" in repo appends "--<hex>"
// hashed over the QUALIFIED string (the dir name hashes the repo alone)
char *manifest_install_name(const char *provider, const char *repo)
{
    if (repo == NULL) return NULL;
    char *qualified = provider != NULL && *provider != '\0'
        ? path_fmt("%s__%s", provider, repo) : strdup(repo);
    if (qualified == NULL) return NULL;
    size_t qn = strlen(qualified);
    char *slashed = malloc(qn * 2 + 1); // each '/' grows to "__"
    if (slashed == NULL) { free(qualified); return NULL; }
    size_t o = 0;
    for (size_t i = 0; i < qn; i++) {
        if (qualified[i] == '/') { slashed[o++] = '_'; slashed[o++] = '_'; }
        else slashed[o++] = qualified[i];
    }
    slashed[o] = '\0';
    char *name = sanitize(slashed);
    // sanitise's blank fallback is the literal "app"; the format wants
    // "app-<hash>", and a misfire needs inputs the ref parser rules out
    if (name != NULL && strcmp(name, "app") == 0
        && strcmp(slashed, "app") != 0) {
        char *grown = path_fmt("app-%x", java_hash(slashed));
        free(name);
        name = grown;
    }
    free(slashed);
    if (name != NULL && strstr(repo, "__") != NULL) {
        char *grown = path_fmt("%s--%x", name, java_hash(qualified));
        free(name);
        name = grown;
    }
    free(qualified);
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

bool manifest_remove_app_dir(const char *root, const char *provider,
                             const char *repo)
{
    char *dir = manifest_app_dir(root, provider, repo);
    if (dir == NULL) return false;
    bool ok = rmrf(dir);
    free(dir);
    return ok;
}
