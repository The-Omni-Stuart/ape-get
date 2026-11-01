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

// JSON token for a slot: "escaped" or null, quote-aware so the manifest
// template stays one pooled string literal
static char *jtok(const char *s)
{
    if (s == NULL) return strdup("null");
    jbuf b = {0};
    jb_json_string(&b, s);
    return b.p;
}

static const char ADOPT_M_FMT[] =
"{\n"
"  \"schema_version\" : 1,\n"
"  \"release_id\" : null,\n"
"  \"installed_at\" : %s,\n"
"  \"updated_at\" : %s,\n"
"  \"provider_type\" : %s,\n"
"  \"provider_api\" : %s,\n"
"  \"kind\" : \"grm.manifest\",\n"
"  \"provider\" : %s,\n"
"  \"repo\" : %s,\n"
"  \"version\" : null,\n"
"  \"asset\" : {\n"
"    \"name\" : %s,\n"
"    \"url\" : %s,\n"
"    \"sha256\" : null\n"
"  },\n"
"  \"install\" : {\n"
"    \"kind\" : \"unknown\",\n"
"    \"path\" : %s,\n"
"    \"root\" : %s,\n"
"    \"command\" : null,\n"
"    \"desktopEntry\" : null,\n"
"    \"uninstall\" : {\n"
"      \"product_code\" : null,\n"
"      \"path\" : %s,\n"
"      \"args\" : [ ]\n"
"    }\n"
"  },\n"
"  \"verification\" : {\n"
"    \"checksum_asset\" : null,\n"
"    \"status\" : \"unknown\",\n"
"    \"method\" : \"none\",\n"
"    \"expected\" : null,\n"
"    \"actual\" : null\n"
"  },\n"
"  \"backups\" : [ ],\n"
"  \"signature\" : null\n"
"}";

int adopt_run(const cmd_opts *o, state_ctx *st)
{
    const char *arg = o->nargs >= 2 ? o->args[1] : NULL;
    char prov[128], path[512], ref[700];
    char *iname = NULL, *file = NULL, *app_dir = NULL, *mfile = NULL,
         *uri = NULL;
    provider_entry *plist = NULL;
    int pn = 0;
    if (arg == NULL || *arg == '\0') return run_help("adopt");
    if (!parse_ref(arg, prov, sizeof(prov), path, sizeof(path)))
        return 1;
    snprintf(ref, sizeof(ref), "%s:%s", prov, path);

    if (st->install_root == NULL) {
        put_fail("Cannot determine the install root.");
        oplog_append(st, "adopt", ref, "-",
            "Cannot determine the install root.");
        return 1;
    }
    iname = manifest_install_name(prov, path);
    file = iname != NULL ? path_join(st->install_root, iname) : NULL;
    if (file == NULL) return 1;

    struct stat sb;
    if (stat(file, &sb) != 0 || !S_ISREG(sb.st_mode)) {
        char *msg = path_fmt("No installed file found at %s.", file);
        put_fail(msg != NULL ? msg : "No installed file found.");
        oplog_append(st, "adopt", ref, "-",
            msg != NULL ? msg : "No installed file found.");
        free(msg);
        goto fail;
    }

    manifest_entry *ms = NULL;
    size_t mc = 0;
    if (manifests_scan(st->root, &ms, &mc) != 0) {
        put_fail("Failed to load application state: state scan failed");
        oplog_append(st, "adopt", ref, "-",
            "Failed to load application state: state scan failed");
        goto fail;
    }
    bool tracked = false;
    for (size_t k = 0; k < mc; k++)
        if (!strcmp(ms[k].provider, prov) && !strcmp(ms[k].repo, path))
            tracked = true;
    if (tracked) {
        char *msg = path_fmt("Release %s is already tracked.", ref);
        put_s(stderr, UI_WARN, "%s", msg != NULL ? msg : "already tracked");
        oplog_append(st, "adopt", ref, "-",
            msg != NULL ? msg : "already tracked");
        free(msg);
        manifest_list_free(ms, mc);
        goto fail;
    }

    {
        char *q = path_fmt(
            "Adopt %s as %s?\nVersion and checksum are unknown.",
            file, ref);
        bool yes = confirm_action(o, q != NULL ? q : "", false);
        free(q);
        if (!yes) {
            put_s(stderr, UI_WARN, "Adoption declined.");
            oplog_append(st, "adopt", ref, "-", "Adoption declined.");
            manifest_list_free(ms, mc);
            goto fail;
        }
    }
    manifest_list_free(ms, mc);

    // adopted manifest: version and signature unknown, url is the local
    // file, kind from the name; the suite pins its exact byte shape
    app_dir = manifest_app_dir(st->root, prov, path);
    mfile = manifest_path(st->root, prov, path);
    uri = path_fmt("file://%s", file);
    if (app_dir == NULL || mfile == NULL || uri == NULL) goto fail;

    char inst[64];
    time_t mt = sb.st_mtime;
    struct tm mtm;
    gmtime_r(&mt, &mtm);
    snprintf(inst, sizeof(inst),
        "%04d-%02d-%02dT%02d:%02d:%02d.%09ldZ", mtm.tm_year + 1900,
        mtm.tm_mon + 1, mtm.tm_mday, mtm.tm_hour, mtm.tm_min,
        mtm.tm_sec, (long)sb.st_mtim.tv_nsec);
    char upd[64];
    now_instant(upd, sizeof(upd));

    pn = providers_load(st, &plist);
    provider_entry *pe = providers_find(plist, pn, prov);

    const char *ptype = pe != NULL ? pe->type : NULL;
    const char *papi = pe != NULL ? pe->api : NULL;
    char *tok[11];
    tok[0] = jtok(inst);  tok[1] = jtok(upd);   tok[2] = jtok(ptype);
    tok[3] = jtok(papi);  tok[4] = jtok(prov);  tok[5] = jtok(path);
    tok[6] = jtok(iname); tok[7] = jtok(uri);   tok[8] = jtok(file);
    tok[9] = jtok(app_dir); tok[10] = jtok(file);
    bool tok_ok = app_dir != NULL;
    for (int i = 0; i < 11; i++) tok_ok = tok_ok && tok[i] != NULL;
    char *gtext = tok_ok
        ? path_fmt(ADOPT_M_FMT, tok[0], tok[1], tok[2], tok[3], tok[4],
            tok[5], tok[6], tok[7], tok[8], tok[9], tok[10])
        : NULL;
    for (int i = 0; i < 11; i++) free(tok[i]);

    bool saved = gtext != NULL && fs_mkdir_p(app_dir)
        && fs_write_file(mfile, gtext);
    if (saved) chmod(mfile, 0600); // state files are 0600, temp included
    free(gtext);
    if (!saved) {
        put_fail("Failed to adopt package: cannot write state");
        oplog_append(st, "adopt", ref, "-",
            "Failed to adopt package: cannot write state");
        goto fail;
    }
    if (o->json) {
        jbuf j = {0};
        jb_lit(&j, "{\"adopted\":{\"installed_at\":");
        jb_json_string(&j, inst);
        jb_lit(&j, ",\"updated_at\":");
        jb_json_string(&j, upd);
        jb_lit(&j, ",\"install_path\":");
        jb_json_string(&j, file);
        jb_lit(&j, ",\"provider\":");
        jb_json_string(&j, prov);
        jb_lit(&j, ",\"repo\":");
        jb_json_string(&j, path);
        jb_lit(&j, ",\"version\":null,\"asset\":");
        jb_json_string(&j, iname);
        jb_lit(&j, ",\"kind\":\"unknown\",\"command\":null}}");
        puts(j.p);
        jb_free(&j);
    } else {
        char *msg = path_fmt("Adopted %s at %s.", ref, file);
        put_s(stderr, UI_OK, "%s", msg != NULL ? msg : "Adopted.");
        free(msg);
    }
    oplog_append(st, "adopt", ref, "-", NULL);
    providers_free(plist, pn);
    free(uri); free(mfile); free(app_dir); free(file); free(iname);
    return 0;

fail:
    providers_free(plist, pn);
    free(uri);
    free(mfile);
    free(app_dir);
    free(file);
    free(iname);
    return 1;
}
