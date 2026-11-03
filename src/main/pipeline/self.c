// Self-integration: after stateful commands, copy the executable into the install
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
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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
    char *j = txn_build(&t);
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
    if (n <= 0) return NULL;
    buf[n] = '\0';
    if (self_file_is_apeget(buf)) return strdup(buf);
    char *alt = self_argv0 != NULL && self_argv0[0] != '\0'
        ? self_resolve(self_argv0) : NULL;
    if (alt != NULL) return alt;
    return strdup(buf); // keep the /proc path: tests stage fakes
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
    const char *installed_at)
{
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
    char sha[65] = "";
    crypto_sha256_file(target, sha);
    jb_lit(&b, ",\"sha256\":");
    if (sha[0] != '\0') jb_json_string(&b, sha);
    else jb_lit(&b, "null");
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

// Re-record the version when the canonical copy itself runs a newer build: silent
// atomic rewrite, no journal; a crash leaves a sweepable .tmp- orphan.
static void self_refresh(const char *mfile, const char *target,
    const char *app_dir, const char *txt)
{
    char *ver = NULL, *inst = NULL;
    if (!json_get_string(txt, "version", &ver) || ver == NULL
        || strcmp(ver, APEGET_VERSION) == 0) {
        free(ver);
        return;
    }
    if (!json_get_string(txt, "installed_at", &inst) || inst == NULL) {
        free(inst);
        inst = NULL;
        if (!json_get_string(txt, "updated_at", &inst) || inst == NULL) {
            free(inst);
            inst = NULL;
        }
    }
    char *m2 = self_manifest_json(target, app_dir, inst);
    free(inst);
    free(ver);
    if (m2 == NULL) return;
    char id[17];
    txn_new_id(id);
    char *t2 = path_fmt("%s.tmp-%s", mfile, id);
    if (t2 != NULL && self_write_manifest(t2, m2)) fs_rename(t2, mfile);
    remove(t2);
    free(t2);
    free(m2);
}

void pipeline_self_after(const cmd_opts *o, state_ctx *st)
{
    const char *off = getenv("APEGET_NO_SELF");
    if (off != NULL && strcmp(off, "1") == 0) return;
    if (o->dry_run) return;

    char *exe = self_exe_path();
    if (exe == NULL) {
        self_warn("could not resolve the running executable");
        return;
    }
    char *mfile = manifest_path(st->root, SELF_PROVIDER, SELF_NAME);
    char *target = path_join(st->install_root, SELF_NAME);
    char *app_dir = NULL, *btmp = NULL, *mtmp = NULL;
    bool jl = false, ml = false, bl = false;
    if (mfile == NULL || target == NULL) goto done;

    bool mf = fs_exists(mfile), tg = fs_exists(target);
    if (mf && tg) {
        struct stat se, stt;
        if (stat(exe, &se) == 0 && stat(target, &stt) == 0
            && se.st_dev == stt.st_dev && se.st_ino == stt.st_ino) {
            app_dir = manifest_app_dir(st->root, SELF_PROVIDER,
                SELF_NAME);
            char *txt = fs_read_file(mfile, NULL);
            if (app_dir != NULL && txt != NULL)
                self_refresh(mfile, target, app_dir, txt);
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

    char *inst = NULL;
    if (mf) {
        char *old = fs_read_file(mfile, NULL);
        if (old != NULL) {
            if (!json_get_string(old, "installed_at", &inst)
                || inst == NULL) {
                free(inst);
                inst = NULL;
            }
            free(old);
        }
    }
    char *mtxt = self_manifest_json(target, app_dir,
        inst != NULL ? inst : created);
    free(inst);
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
