// Self-integration: after successful commands (queries included, under the
// lock), copy the executable into the install
// root, journalled and staged .tmp-<id> for recovery; never fails the caller.

#include "pipeline.h"
#include "repo.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdlib.h>

#ifdef APEGET_SELF

#include "crypto.h"
#include "fs.h"
#include "json.h"
#include "manifest.h"
#include "path.h"
#include "txn.h"

#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#ifndef APEGET_VERSION
#define APEGET_VERSION "0.0.0-dev"
#endif

#define SELF_PROVIDER "self"
#define SELF_NAME "ape-get"
#define SELF_REF "self:" SELF_NAME

static void self_warn(const char *fmt, ...)
{
    fprintf(stderr, "self-integration: ");
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
}

// first-run note: info-level, suppressed by --quiet/--json
static void self_note(const cmd_opts *o, const char *line)
{
    if (!o->quiet && !o->json) fprintf(stderr, "%s\n", line);
}

static void self_instant(char *buf, size_t cap)
{
    time_t t = time(NULL);
    struct tm tm;
    gmtime_r(&t, &tm);
    snprintf(buf, cap, "%04d-%02d-%02dT%02d:%02d:%02dZ",
        tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday,
        tm.tm_hour, tm.tm_min, tm.tm_sec);
}

// One journal phase of the self transaction; new-side fields only (a
// first run has no old side). btmp NULL when only the manifest moves.
static bool self_journal(const state_ctx *st, const char *phase,
    const char *id, const char *created, const char *mfile,
    const char *mtmp, const char *btmp, bool history)
{
    txn t;
    txn_init(&t);
    t.id = strdup(id);
    t.op = strdup("self-update");
    t.ref = strdup(SELF_REF);
    t.phase = strdup(phase);
    t.created_at = strdup(created);
    if (mfile != NULL) t.new_manifest = strdup(mfile);
    if (mtmp != NULL) t.new_manifest_tmp = strdup(mtmp);
    if (btmp != NULL) t.new_binary_tmp = strdup(btmp);
    t.history_written = history;
    bool ready = true;
    if (strcmp(phase, "prepared")) {
        char sha[65];
        ready = mtmp != NULL && crypto_sha256_file(mtmp, sha)
            && (t.new_manifest_sha256 = strdup(sha)) != NULL;
        if (btmp != NULL)
            ready = ready && crypto_sha256_file(btmp, sha)
                && (t.new_asset_sha256 = strdup(sha)) != NULL;
    }
    char *j = ready ? txn_build(&t) : NULL;
    bool ok = j != NULL && txn_write(st->root, j);
    free(j);
    txn_free(&t);
    return ok;
}

static void self_journal_drop(const state_ctx *st)
{
    char *jp = path_join(st->root, "grm_transaction.json");
    if (jp != NULL) { remove(jp); free(jp); }
}

// APE probe: real programs carry ".cosmo" magic plus a zip end-of-central-directory;
// the standalone loader (which owns /proc/self/exe when a polyglot runs) has neither
static bool self_tail_ok(int fd)
{
    off_t sz = lseek(fd, 0, SEEK_END);
    if (sz < 128) return false;
    unsigned char tail[128];
    if (pread(fd, tail, sizeof tail, sz - (off_t)sizeof tail)
        != (ssize_t)sizeof tail) return false;
    size_t n = sizeof tail;
    if (memcmp(tail + n - 22, "PK\x05\x06", 4) != 0) return false;
    n -= 22; // step over the end-of-central-directory
    for (size_t i = n; i-- > 4; )
        if (memcmp(tail + i, ".cosmo\n", 7) == 0) return true;
    return false;
}

static bool self_file_is_apeget(const char *path)
{
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    bool ok = self_tail_ok(fd);
    close(fd);
    return ok;
}

// argv[0] as the loader forwarded it, resolved against PATH when bare; when
// the loader owns /proc/self/exe this is the only surviving name for our file
static char *self_argv0;

void self_set_argv0(const char *argv0)
{
    if (argv0 != NULL && self_argv0 == NULL) self_argv0 = strdup(argv0);
}

static char *self_resolve(const char *p)
{
    if (p == NULL) return NULL;
    if (strchr(p, '/') != NULL) {
        struct stat sb;
        return stat(p, &sb) == 0 ? strdup(p) : NULL;
    }
    const char *pathv = getenv("PATH");
    while (pathv != NULL && *pathv != '\0') {
        const char *e = strchr(pathv, ':');
        size_t l = e != NULL ? (size_t)(e - pathv) : strlen(pathv);
        char *cand = path_fmt("%.*s/%s", (int)l, pathv, p);
        if (cand != NULL && self_file_is_apeget(cand)) return cand;
        free(cand);
        if (e == NULL) break;
        pathv = e + 1;
    }
    return NULL;
}

// /proc/self/exe normally names the .com, but an MZ-polyglot without
// binfmt_misc routes through the loader (no APE trailer); argv[0] recovers it
static char *self_exe_path(void)
{
    char buf[8192];
    ssize_t n = readlink("/proc/self/exe", buf, sizeof buf - 1);
    if (n > 0) {
        buf[n] = '\0';
        if (self_file_is_apeget(buf)) return strdup(buf);
    }
    char *alt = self_argv0 != NULL && self_argv0[0] != '\0'
        ? self_resolve(self_argv0) : NULL;
    if (alt != NULL) return alt;
    return n > 0 ? strdup(buf) : NULL; // keep a valid /proc path for staged fakes
}

static bool self_identical(const char *a, const char *b)
{
    struct stat sa, sb;
    if (stat(a, &sa) != 0 || stat(b, &sb) != 0) return false;
    if (!S_ISREG(sa.st_mode) || !S_ISREG(sb.st_mode)) return false;
    if (sa.st_size != sb.st_size) return false;
    FILE *fa = fopen(a, "rb"), *fb = fopen(b, "rb");
    if (fa == NULL || fb == NULL) {
        if (fa != NULL) fclose(fa);
        if (fb != NULL) fclose(fb);
        return false;
    }
    bool ok = true;
    char ba[65536], bb[65536];
    size_t na;
    while ((na = fread(ba, 1, sizeof ba, fa)) > 0) {
        size_t nb = fread(bb, 1, sizeof bb, fb);
        if (nb != na || memcmp(ba, bb, na) != 0) { ok = false; break; }
    }
    if (ok && !feof(fb)) ok = false;
    fclose(fa);
    fclose(fb);
    return ok;
}

// copy + fsync + 0755 (umask-proof); O_EXCL so a racing self-instance
// can never interleave two staged files
static bool self_copy_exec(const char *from, const char *to,
    char *err, size_t cap)
{
    FILE *in = fopen(from, "rb");
    if (in == NULL) {
        snprintf(err, cap, "cannot read %s: %s", from, strerror(errno));
        return false;
    }
    int fd = open(to, O_WRONLY | O_CREAT | O_EXCL, 0755);
    if (fd < 0) {
        fclose(in);
        snprintf(err, cap, "cannot write %s: %s", to, strerror(errno));
        return false;
    }
    FILE *out = fdopen(fd, "wb");
    bool ok = out != NULL;
    if (ok) {
        char buf[65536];
        size_t n;
        while ((n = fread(buf, 1, sizeof buf, in)) > 0)
            if (fwrite(buf, 1, n, out) != n) { ok = false; break; }
        if (ok && ferror(in)) ok = false;
        if (ok && (fflush(out) != 0 || fsync(fileno(out)) != 0))
            ok = false;
    }
    fclose(in);
    if (out != NULL) fclose(out);
    else close(fd);
    if (ok && chmod(to, 0755) != 0) ok = false;
    if (!ok) {
        remove(to);
        if (err[0] == '\0')
            snprintf(err, cap, "cannot stage the copy of %s", from);
    }
    return ok;
}

static bool self_on_path(const char *dir)
{
    const char *p = getenv("PATH");
    size_t dl = strlen(dir);
    while (p != NULL && *p != '\0') {
        const char *e = strchr(p, ':');
        size_t l = e != NULL ? (size_t)(e - p) : strlen(p);
        if (l == dl && strncmp(p, dir, l) == 0) return true;
        if (e == NULL) break;
        p = e + 1;
    }
    return false;
}

// manifest shape: byte-parallel with the install writer in
// pipeline/run.c, so scanners and `info` see one manifest kind
static char *self_manifest_json(const char *target, const char *app_dir,
    const char *installed_at, const char *source)
{
    char sha[65];
    if (!crypto_sha256_file(source, sha)) return NULL;
    char now[40];
    self_instant(now, sizeof now);
    jbuf b = {0};
    jb_lit(&b, "{\"kind\":\"grm.manifest\",\"schema_version\":1,"
        "\"provider\":\"self\",\"repo\":\"" SELF_NAME "\",\"version\":");
    jb_json_string(&b, APEGET_VERSION);
    jb_lit(&b, ",\"release_id\":null,\"asset\":{\"name\":\"" SELF_NAME
        "\",\"url\":");
    char *furl = path_fmt("file://%s", target);
    jb_json_string(&b, furl != NULL ? furl : target);
    free(furl);
    jb_lit(&b, ",\"sha256\":");
    jb_json_string(&b, sha);
    jb_lit(&b, "},\"install\":{\"kind\":\"ape\",\"path\":");
    jb_json_string(&b, target);
    jb_lit(&b, ",\"root\":");
    jb_json_string(&b, app_dir);
    jb_lit(&b, ",\"command\":\"" SELF_NAME "\",\"desktop_entry\":null,"
        "\"uninstall\":{\"path\":");
    jb_json_string(&b, target);
    jb_lit(&b, ",\"args\":[],\"product_code\":null}},\"verification\":"
        "{\"status\":\"missing\",\"method\":\"sha256\",\"checksum_asset\":"
        "null,\"expected\":null,\"actual\":null},\"backups\":[],"
        "\"installed_at\":");
    jb_json_string(&b, installed_at);
    jb_lit(&b, ",\"updated_at\":");
    jb_json_string(&b, now);
    jb_lit(&b, ",\"signature\":{\"status\":\"missing\",\"mechanism\":null,"
        "\"signer\":null,\"identity\":null,\"notarized\":null},"
        "\"provider_type\":null,\"provider_api\":null}");
    return b.p;
}

static bool self_write_manifest(const char *path, const char *text)
{
    if (!fs_write_file(path, text)) return false;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd >= 0) { fsync(fd); close(fd); }
    return chmod(path, 0600) == 0;
}

// Refresh only an unambiguous self record. Do not turn corrupt/foreign state
// into a fresh installation (or discard extensions we do not understand).
static bool self_manifest_valid(const char *txt, size_t len, jobject *o)
{
    if (!json_valid(txt, len) || !jobject_parse(txt, o)
        || !manifest_json_valid(o)) return false;
    long schema = 0;
    if (!json_get_int(txt, "schema_version", &schema) || schema != 1)
        return false;
    for (int i = 0; i < o->n; i++)
        for (int j = 0; j < i; j++)
            if (strcmp(o->keys[i], o->keys[j]) == 0) return false;
    char *provider = NULL, *repo = NULL;
    bool ok = json_get_string(txt, "provider", &provider)
        && json_get_string(txt, "repo", &repo)
        && provider != NULL && strcmp(provider, SELF_PROVIDER) == 0
        && repo != NULL && strcmp(repo, SELF_NAME) == 0;
    free(provider);
    free(repo);
    return ok;
}

static const char *self_version_identity(const char *v)
{
    return v[0] == 'v' && v[1] >= '0' && v[1] <= '9' ? v + 1 : v;
}

// Only plain X.Y.Z versions are orderable here. Unknown tags/suffixes need an
// explicit update, and a stale baked version must never downgrade a release.
static bool self_version_core(const char *v, unsigned long out[3])
{
    v = self_version_identity(v);
    for (int i = 0; i < 3; i++) {
        if (*v < '0' || *v > '9') return false;
        char *end;
        errno = 0;
        out[i] = strtoul(v, &end, 10);
        if (errno == ERANGE || (end - v > 1 && *v == '0')) return false;
        if (i == 2) return *end == '\0';
        if (*end != '.') return false;
        v = end + 1;
    }
    return false;
}

static bool self_version_newer(const char *running, const char *recorded)
{
    unsigned long a[3], b[3];
    if (!self_version_core(running, a) || !self_version_core(recorded, b))
        return false;
    for (int i = 0; i < 3; i++)
        if (a[i] != b[i]) return a[i] > b[i];
    return false;
}

static bool self_raw_append(jbuf *b, const char *s, const char *e)
{
    size_t n = (size_t)(e - s);
    if (n >= b->cap - b->n) return false;
    memcpy(b->p + b->n, s, n);
    b->n += n;
    b->p[b->n] = '\0';
    return true;
}

// Re-record only the version observation, keeping raw policy/history/provenance.
// Existing trust remains valid only for the recorded bytes; a different binary
// must pass the ordinary update/repair path, not inherit its old signature.
static void self_refresh(const char *mfile, const char *target,
    const char *txt, const jobject *o)
{
    char *ver = NULL;
    if (!json_get_string(txt, "version", &ver) || ver == NULL
        || strcmp(self_version_identity(ver),
            self_version_identity(APEGET_VERSION)) == 0) {
        free(ver);
        return;
    }
    if (!self_version_newer(APEGET_VERSION, ver)) {
        free(ver);
        self_warn("self version is newer than the baked version or cannot "
            "be ordered; keeping its recorded metadata");
        return;
    }
    free(ver);
    const char *as, *ae;
    char *asset = NULL, *recorded = NULL;
    char sha[65];
    bool same = json_raw(txt, "asset", &as, &ae)
        && (asset = strndup(as, (size_t)(ae - as))) != NULL
        && json_get_string(asset, "sha256", &recorded)
        && recorded != NULL && strlen(recorded) == 64
        && crypto_sha256_file(target, sha) && strcasecmp(recorded, sha) == 0;
    free(asset);
    free(recorded);
    if (!same) {
        self_warn("self manifest does not identify the running binary; "
            "keeping its recorded metadata. Use an explicit update or repair.");
        return;
    }
    char now[40];
    self_instant(now, sizeof now);
    // self_raw_append bypasses jb_grow, so reserve all possible output
    // up front, including worst-case version escaping and a missing updated_at.
    size_t extra = 6 * sizeof(APEGET_VERSION) + sizeof now + 64;
    size_t len = strlen(txt);
    if (len > SIZE_MAX - extra) return;
    jbuf b = {.cap = len + extra};
    b.p = malloc(b.cap);
    if (b.p == NULL) return;
    const char *cursor = txt;
    for (int i = 0; i < o->n; i++) {
        bool version = strcmp(o->keys[i], "version") == 0;
        if (!version && strcmp(o->keys[i], "updated_at") != 0) continue;
        if (!self_raw_append(&b, cursor, o->vals[i])) goto drop;
        jb_json_string(&b, version ? APEGET_VERSION : now);
        cursor = o->vends[i];
    }
    if (jobject_find(o, "updated_at") < 0) {
        const char *end = txt + len;
        while (end > txt && strchr(" \t\r\n", end[-1]) != NULL) end--;
        if (!self_raw_append(&b, cursor, end - 1)) goto drop;
        jb_lit(&b, ",\"updated_at\":");
        jb_json_string(&b, now);
        cursor = end - 1;
    }
    if (!self_raw_append(&b, cursor, txt + len)) goto drop;
    if (!json_valid(b.p, b.n)) { jb_free(&b); return; }
    char id[17];
    txn_new_id(id);
    char *t2 = path_fmt("%s.tmp-%s", mfile, id);
    if (t2 != NULL) {
        if (self_write_manifest(t2, b.p)) fs_rename(t2, mfile);
        remove(t2);
    }
    free(t2);
drop:
    jb_free(&b);
}

void pipeline_self_after(const cmd_opts *o, state_ctx *st)
{
    const char *off = getenv("APEGET_NO_SELF");
    if (off != NULL && strcmp(off, "1") == 0) return;
    if (o->dry_run) return;

    // Registration writes state, so it serialises on the lock even when a
    // query command reached it; a busy lock means a mutation is running
    // and this run simply skips registration.
    if (!state_lock_held() && !state_lock(st->root)) return;
    // Recovery owns any present journal, including an unreadable one. Check
    // before either first registration or an existing manifest refresh.
    txn pending;
    txn_load load = txn_load_journal(st->root, &pending);
    txn_free(&pending);
    if (load != TXN_ABSENT) {
        self_warn("transaction journal is pending or unreadable; recover it first");
        return;
    }

    char *exe = self_exe_path();
    if (exe == NULL) {
        self_warn("could not resolve the running executable");
        return;
    }
    char *mfile = manifest_path(st->root, SELF_PROVIDER, SELF_NAME);
    char *target = path_join(st->install_root, SELF_NAME);
    char *app_dir = NULL, *btmp = NULL, *mtmp = NULL;
    bool jl = false, ml = false, bl = false;
    if (mfile == NULL) {
        self_warn("cannot resolve an owned self manifest; use an explicit update or repair");
        goto done;
    }
    if (target == NULL) goto done;

    struct stat ms;
    bool mf = lstat(mfile, &ms) == 0;
    if (!mf && errno != ENOENT) {
        self_warn("cannot inspect the self manifest; leaving it alone");
        goto done;
    }
    bool tg = fs_exists(target);
    if (mf) {
        if (!tg) {
            self_warn("self manifest exists but its binary is missing; "
                "use an explicit update or repair");
            goto done;
        }
        struct stat se, stt;
        if (stat(exe, &se) == 0 && stat(target, &stt) == 0
            && se.st_dev == stt.st_dev && se.st_ino == stt.st_ino) {
            size_t n = 0;
            char *txt = fs_read_file(mfile, &n);
            jobject o2 = {0};
            if (txt != NULL && self_manifest_valid(txt, n, &o2))
                self_refresh(mfile, target, txt, &o2);
            else self_warn("cannot read a valid self manifest; leaving it alone");
            jobject_free(&o2);
            free(txt);
        }
        goto done;
    }

    // an existing non-identical file is never overwritten
    if (tg && !self_identical(exe, target)) {
        self_warn("%s already exists; leaving it alone.", target);
        goto done;
    }
    bool need_copy = !tg;

    char id[17], created[40];
    txn_new_id(id);
    self_instant(created, sizeof created);

    app_dir = manifest_app_dir(st->root, SELF_PROVIDER, SELF_NAME);
    if (app_dir == NULL) goto done;
    if (!fs_mkdir_p(app_dir)
        || (need_copy && !fs_mkdir_p(st->install_root))) {
        self_warn("cannot create the install directories");
        goto abort_clean;
    }
    btmp = need_copy ? path_fmt("%s.tmp-%s", target, id) : NULL;
    mtmp = path_fmt("%s.tmp-%s", mfile, id);
    if (need_copy && btmp == NULL) goto abort_clean;
    if (mtmp == NULL || !self_journal(st, "prepared", id, created, mfile,
        mtmp, btmp, false)) {
        self_warn("cannot write the transaction journal");
        goto abort_clean;
    }
    jl = true;

    if (need_copy) {
        char cerr[200] = "";
        if (!self_copy_exec(exe, btmp, cerr, sizeof cerr)) {
            self_warn("%s", cerr[0] != '\0' ? cerr
                : "cannot copy the running executable");
            goto abort_clean;
        }
        bl = true;
    }

    char *mtxt = self_manifest_json(target, app_dir, created,
        need_copy ? btmp : target);
    if (mtxt == NULL || !self_write_manifest(mtmp, mtxt)) {
        self_warn("cannot write the self manifest");
        free(mtxt);
        goto abort_clean;
    }
    free(mtxt);
    ml = true;

    if (!self_journal(st, "committing", id, created, mfile, mtmp, btmp,
        false)) {
        self_warn("cannot update the transaction journal");
        goto abort_clean;
    }
    // past the commit point: failures leave the journal for the next
    // stateful command to roll forward
    if (need_copy) {
        if (!fs_rename(btmp, target)) {
            self_warn("cannot install %s: %s", target, strerror(errno));
            goto done;
        }
        bl = false;
    }
    if (!fs_rename(mtmp, mfile)) {
        self_warn("cannot install the self manifest");
        goto done;
    }
    ml = false;

    oplog_append(st, "self-update", SELF_REF, "-", NULL);
    self_journal_drop(st);
    jl = false;

    char line[4352];
    snprintf(line, sizeof line, "ape-get configured: %s. If you ran it "
        "from a download, you can safely delete that file.", target);
    self_note(o, line);
    if (!self_on_path(st->install_root)) {
        snprintf(line, sizeof line, "Add %s to your PATH.",
            st->install_root);
        self_note(o, line);
    }
    goto done;

abort_clean:
    // nothing committed: drop the journal and whatever this run staged; past
    // the commit point failures exit via done, leaving the journal for recovery
    if (bl && btmp != NULL) remove(btmp);
    if (ml && mtmp != NULL) remove(mtmp);
    if (jl) self_journal_drop(st);
done:
    free(btmp);
    free(mtmp);
    free(app_dir);
    free(mfile);
    free(target);
    free(exe);
}

bool self_enabled(void)
{
    // the run-level switch is part of the answer: a caller asking whether
    // self-integration will happen must not be told "yes" by the build flags
    const char *off = getenv("APEGET_NO_SELF");
    return !(off != NULL && strcmp(off, "1") == 0);
}

self_state self_install_status(const state_ctx *st, char **target)
{
    if (target != NULL) *target = NULL;
    char *exe = self_exe_path();
    char *path = path_join(st->install_root, SELF_NAME);
    self_state s = SELF_NONE;
    if (exe != NULL && path != NULL && fs_exists(path))
        s = self_identical(exe, path) ? SELF_OK : SELF_BLOCKED;
    if (target != NULL) *target = path;
    else free(path);
    free(exe);
    return s;
}

#else /* !APEGET_SELF */

void self_set_argv0(const char *argv0)
{
    (void)argv0; // APEGET_SELF=OFF: mechanism absent from the binary
}

void pipeline_self_after(const cmd_opts *o, state_ctx *st)
{
    (void)o;
    (void)st;
}

bool self_enabled(void) { return false; }

self_state self_install_status(const state_ctx *st, char **target)
{
    (void)st;
    if (target != NULL) *target = NULL;
    return SELF_NONE;
}

#endif /* APEGET_SELF */

// self-update's upstream: releases ship from this repository. The env hook
// is test-only, matching the harness convention for the other APEGET_ vars.
bool self_upstream(char *prov, size_t psz, char *path, size_t ssz)
{
    const char *ref = getenv("APEGET_SELF_UPSTREAM");
    if (ref == NULL || *ref == '\0')
        ref = "github:The-Omni-Stuart/ape-get";
    return repo_parse_ref(ref, prov, psz, path, ssz);
}
