// info command: full detail for one installed release, table or JSON.

#include "cmd.h"
#include "doctor.h"
#include "fs.h"
#include "json.h"
#include "manifest.h"
#include "path.h"
#include "pipeline.h"
#include "provider.h"
#include "repo.h"
#include "state.h"
#include "txn.h"
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include "main.h"

static char *describe_verification(const jobject *root)
{
    int vi = jobject_find(root, "verification");
    if (vi < 0) return strdup("not recorded");
    char *t = span_dup(root->vals[vi], root->vends[vi]);
    jobject vo = {0};
    if (t == NULL || !jobject_parse(t, &vo)) {
        free(t);
        return strdup("not recorded");
    }
    char *status = jstr(&vo, "status");
    char *method = jstr(&vo, "method");
    char *ca = jstr(&vo, "checksum_asset");
    char *out;
    if (method == NULL)
        out = path_fmt("%s", status != NULL ? status : "null");
    else if (ca != NULL)
        out = path_fmt("%s (%s, %s)", status != NULL ? status : "null",
            method, ca);
    else
        out = path_fmt("%s (%s)", status != NULL ? status : "null", method);
    free(status);
    free(method);
    free(ca);
    jobject_free(&vo);
    free(t);
    return out;
}

static char *describe_signature(const jobject *root)
{
    int si = jobject_find(root, "signature");
    if (si < 0) return strdup("not recorded");
    char *t = span_dup(root->vals[si], root->vends[si]);
    jobject so = {0};
    if (t == NULL || !jobject_parse(t, &so)) {
        free(t);
        return strdup("not recorded");
    }
    char *status = jstr(&so, "status");
    char *mech = jstr(&so, "mechanism");
    char *signer = jstr(&so, "signer");
    char *identity = jstr(&so, "identity");
    int ni = jobject_find(&so, "notarized");
    int notarized = -1;
    if (ni >= 0) {
        size_t n = (size_t)(so.vends[ni] - so.vals[ni]);
        if (n == 4 && !memcmp(so.vals[ni], "true", 4)) notarized = 1;
        else if (n == 5 && !memcmp(so.vals[ni], "false", 5)) notarized = 0;
    }
    char *out;
    if (status != NULL && !strcmp(status, "verified") && identity != NULL
        && *identity != '\0') {
        const char *who = signer != NULL ? signer : "unknown signer";
        char *base = identity != NULL
            ? path_fmt("%s (%s)", who, identity)
            : strdup(who);
        const char *nota = notarized < 0 ? ""
            : (notarized == 1 ? ", notarized" : ", not notarized");
        out = path_fmt("verified (%s) by %s%s",
            mech != NULL ? mech : "null", base != NULL ? base : "", nota);
        free(base);
    } else if (mech != NULL) {
        out = path_fmt("%s (%s)", status != NULL ? status : "null", mech);
    } else {
        out = path_fmt("%s", status != NULL ? status : "null");
    }
    free(status);
    free(mech);
    free(signer);
    free(identity);
    jobject_free(&so);
    free(t);
    return out;
}

// info output for one manifest: root/text is the freshly parsed file, entry
// the scan row for the same file. Member order is the frozen JSON shape
static void app_info_json(jbuf *b, const char *text, const jobject *root,
                          const manifest_entry *e)
{
    char *prov = jstr(root, "provider"), *repo = jstr(root, "repo");
    char *ver = jstr(root, "version"), *inst = jstr(root, "installed_at");
    char *upd = jstr(root, "updated_at");

    int ii = jobject_find(root, "install");
    jobject io = {0};
    char *it = ii >= 0 ? span_dup(root->vals[ii], root->vends[ii]) : NULL;
    if (it != NULL) jobject_parse(it, &io);
    char *iroot = jstr(&io, "root"), *cmd = jstr(&io, "command");
    char *kind = jstr(&io, "kind"), *desktop = jstr(&io, "desktop_entry");

    int ui = jobject_find(&io, "uninstall");
    jobject uo = {0};
    char *ut = ui >= 0 ? span_dup(io.vals[ui], io.vends[ui]) : NULL;
    if (ut != NULL) jobject_parse(ut, &uo);
    char *upath = jstr(&uo, "path"), *pcode = jstr(&uo, "product_code");
    bool portable = kind != NULL && (!strcmp(kind, "appimage")
        || !strcmp(kind, "portable-exe") || !strcmp(kind, "ape"));
    bool no_command_portable = cmd == NULL && portable && upath != NULL
        && e->path != NULL && !strcmp(e->path, upath);
    const char *launcher = no_command_portable ? NULL : upath;

    int ai = jobject_find(root, "asset");
    jobject ao = {0};
    char *at = ai >= 0 ? span_dup(root->vals[ai], root->vends[ai]) : NULL;
    if (at != NULL) jobject_parse(at, &ao);

    int vi = jobject_find(root, "verification");
    jobject vo = {0};
    char *vt = vi >= 0 ? span_dup(root->vals[vi], root->vends[vi]) : NULL;
    if (vt != NULL) jobject_parse(vt, &vo);

    int si = jobject_find(root, "signature");
    jobject so = {0};
    char *st2 = si >= 0 ? span_dup(root->vals[si], root->vends[si]) : NULL;
    if (st2 != NULL) jobject_parse(st2, &so);

    jb_lit(b, "{\"installed_at\":");
    json_null_or_string(b, inst != NULL ? inst : e->installed);
    jb_lit(b, ",\"updated_at\":");
    json_null_or_string(b, upd);
    jb_lit(b, ",\"install_path\":");
    json_null_or_string(b, e->path);
    jb_lit(b, ",\"install_state\":");
    if (e->path != NULL)
        jb_json_string(b,
            access(e->path, F_OK) == 0 ? "installed" : "missing");
    else
        jb_lit(b, "\"unknown\"");
    if (e->locked) jb_lit(b, ",\"locked\":true");
    jb_lit(b, ",\"release_id\":");
    int ri = jobject_find(root, "release_id");
    if (ri >= 0
        && !(root->vends[ri] - root->vals[ri] == 4
            && !memcmp(root->vals[ri], "null", 4))) {
        char *raw = span_dup(root->vals[ri], root->vends[ri]);
        jb_lit(b, raw != NULL ? raw : "null");
        free(raw);
    } else {
        jb_lit(b, "null");
    }
    jb_lit(b, ",\"install_root\":");
    json_null_or_string(b, iroot);
    jb_lit(b, ",\"product_code\":");
    json_null_or_string(b, pcode);
    jb_lit(b, ",\"desktop_entry\":");
    json_null_or_string(b, desktop);
    jb_lit(b, ",\"provider\":");
    jb_json_string(b, prov != NULL ? prov : e->provider);
    jb_lit(b, ",\"repo\":");
    jb_json_string(b, repo != NULL ? repo : e->repo);
    jb_lit(b, ",\"version\":");
    json_null_or_string(b, ver);
    jb_lit(b, ",\"asset\":{\"name\":");
    jb_jstr(b, &ao, "name");
    jb_lit(b, ",\"url\":");
    jb_jstr(b, &ao, "url");
    jb_lit(b, ",\"sha256\":");
    jb_jstr(b, &ao, "sha256");
    jb_lit(b, "},\"launcher\":");
    json_null_or_string(b, launcher);
    jb_lit(b, ",\"verification\":{\"checksum_asset\":");
    jb_jstr(b, &vo, "checksum_asset");
    jb_lit(b, ",\"status\":");
    jb_jstr(b, &vo, "status");
    jb_lit(b, ",\"method\":");
    jb_jstr(b, &vo, "method");
    jb_lit(b, ",\"expected\":");
    jb_jstr(b, &vo, "expected");
    jb_lit(b, ",\"actual\":");
    jb_jstr(b, &vo, "actual");
    jb_lit(b, "},\"signature\":{\"status\":");
    jb_jstr(b, &so, "status");
    jb_lit(b, ",\"mechanism\":");
    jb_jstr(b, &so, "mechanism");
    jb_lit(b, ",\"signer\":");
    jb_jstr(b, &so, "signer");
    jb_lit(b, ",\"identity\":");
    jb_jstr(b, &so, "identity");
    jb_lit(b, ",\"notarized\":");
    int ni = jobject_find(&so, "notarized");
    if (ni >= 0
        && !(so.vends[ni] - so.vals[ni] == 4
            && !memcmp(so.vals[ni], "null", 4))) {
        char *raw = span_dup(so.vals[ni], so.vends[ni]);
        jb_lit(b, raw != NULL ? raw : "null");
        free(raw);
    } else {
        jb_lit(b, "null");
    }
    jb_lit(b, "},\"backups\":[");
    int bi = jobject_find(root, "backups");
    if (bi >= 0) {
        char *bt = span_dup(root->vals[bi], root->vends[bi]);
        jspan *sp = NULL;
        int n = bt != NULL ? json_array_spans(bt, &sp) : -1;
        int put = 0; // commas follow the emitted entries: an element that
                     // fails to parse must not earn a separator
        for (int k = 0; bt != NULL && k < n; k++) {
            char *el = span_dup(sp[k].s, sp[k].e);
            jobject bo = {0};
            if (el != NULL && jobject_parse(el, &bo)) {
                if (put) jb_lit(b, ",");
                put++;
                jb_lit(b, "{\"created_at\":");
                jb_jstr(b, &bo, "created_at");
                jb_lit(b, ",\"version\":");
                jb_jstr(b, &bo, "version");
                jb_lit(b, ",\"path\":");
                jb_jstr(b, &bo, "path");
                jb_lit(b, ",\"artifact\":");
                jb_jstr(b, &bo, "artifact");
                jb_lit(b, "}");
            }
            jobject_free(&bo);
            free(el);
        }
        free(sp);
        free(bt);
    }
    jb_lit(b, "],\"kind\":");
    json_null_or_string(b, kind);
    jb_lit(b, ",\"command\":");
    json_null_or_string(b, cmd);
    jb_lit(b, "}");
    (void)text;

    free(prov); free(repo); free(ver); free(inst); free(upd);
    free(iroot); free(cmd); free(kind); free(desktop);
    free(upath); free(pcode);
    jobject_free(&ao); free(at);
    jobject_free(&vo); free(vt);
    jobject_free(&so); free(st2);
    jobject_free(&uo); free(ut);
    jobject_free(&io); free(it);
}

// info trust lines inherit semantic colour: verified green; missing/unknown
// yellow; anything wrong red
static ui_style trust_style(const char *s)
{
    if (!strncmp(s, "verified", 8)) return UI_OK;
    if (!strncmp(s, "missing", 7) || !strcmp(s, "not recorded")
        || !strcmp(s, "unknown_asset") || !strcmp(s, "unavailable")
        || !strcmp(s, "none") || !strcmp(s, "null"))
        return UI_WARN;
    return UI_ERROR;
}

// info is not stateful: no lock, no ensure; the manifest scan still prints
// its quarantine warnings
int info_run(const cmd_opts *o, state_ctx *st)
{
    const char *arg = o->nargs >= 2 ? o->args[1] : NULL;
    char prov[128], path[512], ref[700];
    if (arg == NULL || *arg == '\0') return run_help("info");
    bool bare = strchr(arg, ':') == NULL;
    if (!bare && !parse_ref(arg, prov, sizeof(prov), path, sizeof(path)))
        return 1;

    manifest_entry *ms = NULL;
    size_t mc = 0;
    if (manifests_scan(st->root, &ms, &mc) != 0) {
        put_s(stderr, UI_ERROR, "Failed to load application state: state scan failed");
        return 1;
    }
    if (bare) {
        // short-name resolution (see uninstall): a miss reproduces the
        // parser's provider-required error exactly
        bool many = false;
        manifest_entry *r = resolve_installed(o, arg, ms, mc, &many);
        if (r != NULL) {
            snprintf(prov, sizeof(prov), "%s", r->provider);
            snprintf(path, sizeof(path), "%s", r->repo);
        } else {
            if (many) put_s(stderr, UI_ITALIC, "Use provider:owner/repo to choose one.");
            else parse_ref(arg, prov, sizeof(prov), path, sizeof(path));
            manifest_list_free(ms, mc);
            return 1;
        }
    }
    snprintf(ref, sizeof(ref), "%s:%s", prov, path);
    manifest_entry *m = NULL;
    for (size_t k = 0; k < mc; k++)
        if (m == NULL && !strcmp(ms[k].provider, prov)
            && !strcmp(ms[k].repo, path))
            m = &ms[k];
    if (m == NULL) {
        char msg[768];
        snprintf(msg, sizeof(msg), "Release %s is not found.", ref);
        put_s(stderr, UI_ERROR, "%s", msg);
        manifest_list_free(ms, mc);
        return 1;
    }

    size_t n = 0;
    char *text = fs_read_file(m->file, &n);
    jobject root = {0};
    if (text == NULL || !jobject_parse(text, &root)) {
        put_s(stderr, UI_ERROR,
            "Failed to load application state: manifest unreadable");
        free(text);
        manifest_list_free(ms, mc);
        return 1;
    }

    if (o->json) {
        jbuf b = {0};
        app_info_json(&b, text, &root, m);
        puts(b.p);
        jb_free(&b);
        goto done;
    }

    char v[2048];
#define KV(label, ...) do { \
        snprintf(v, sizeof v, ": " __VA_ARGS__); \
        ui_line_kv(stdout, label, v); \
    } while (0)
    KV("Repository", "%s", m->repo);
    KV("Provider", "%s", m->provider);
    KV("Version", "%s", m->version != NULL ? m->version : "unknown");
    KV("Installed", "%s", m->installed != NULL ? m->installed : "null");
    KV("Updated", "%s", m->updated != NULL ? m->updated : "null");
    KV("Path", "%s", m->path);
    KV("Install state", "%s", access(m->path, F_OK) == 0 ? "installed" : "missing");
    if (m->locked) {
        snprintf(v, sizeof v, ": locked (update --all skips it)");
        ui_line_kv_value(stdout, "Update lock", v, UI_WARN);
    }
    KV("Asset", "%s", m->asset != NULL ? m->asset : "null");
    if (m->kind != NULL) KV("Kind", "%s", m->kind);
    if (m->compat != NULL) KV("Compat", "%s.", m->compat);
    if (m->command != NULL) KV("Command", "%s", m->command);
    if (m->desktop != NULL) KV("Desktop entry", "%s", m->desktop);
    char *cs = describe_verification(&root);
    char *sg = describe_signature(&root);
    put_out("");
    snprintf(v, sizeof v, ": %s", cs);
    ui_line_kv_value(stdout, "Checksum", v, trust_style(cs));
    snprintf(v, sizeof v, ": %s", sg);
    ui_line_kv_value(stdout, "Signature", v, trust_style(sg));
#undef KV
    free(cs);
    free(sg);
    int ii = jobject_find(&root, "install");
    if (ii >= 0) {
        char *it = span_dup(root.vals[ii], root.vends[ii]);
        jobject io = {0};
        if (it != NULL && jobject_parse(it, &io)) {
            int ui = jobject_find(&io, "uninstall");
            if (ui >= 0) {
                char *ut = span_dup(io.vals[ui], io.vends[ui]);
                jobject uo = {0};
                if (ut != NULL && jobject_parse(ut, &uo)) {
                    char *pc = jstr(&uo, "product_code");
                    if (pc != NULL && *pc != '\0') {
                        snprintf(v, sizeof v, ": %s", pc);
                        ui_line_kv(stdout, "Product code", v);
                    }
                    free(pc);
                }
                jobject_free(&uo);
                free(ut);
            }
        }
        jobject_free(&io);
        free(it);
    }
    int bc = 0;
    jspan *bsp = NULL;
    char *bt = NULL;
    int bi = jobject_find(&root, "backups");
    if (bi >= 0) {
        bt = span_dup(root.vals[bi], root.vends[bi]);
        if (bt != NULL) bc = json_array_spans(bt, &bsp);
        if (bc < 0) bc = 0;
    }
    put_out("");
    snprintf(v, sizeof v, ": %d", bc);
    ui_line_kv(stdout, "Backups", v);
    for (int k = 0; k < bc; k++) {
        char *el = span_dup(bsp[k].s, bsp[k].e);
        jobject bo = {0};
        if (el != NULL && jobject_parse(el, &bo)) {
            char *bv = jstr(&bo, "version");
            char *bcr = jstr(&bo, "created_at");
            char *bp = jstr(&bo, "path");
            char bref[600], brest[600];
            snprintf(bref, sizeof bref, "  - %s",
                bv != NULL ? bv : "unknown");
            snprintf(brest, sizeof brest, " (%s) %s",
                bcr != NULL ? bcr : "null", bp != NULL ? bp : "null");
            ui_line_ref(stdout, bref, brest);
            free(bv);
            free(bcr);
            free(bp);
        }
        jobject_free(&bo);
        free(el);
    }
    free(bsp);
    free(bt);

done:
    jobject_free(&root);
    free(text);
    manifest_list_free(ms, mc);
    return 0;
}
