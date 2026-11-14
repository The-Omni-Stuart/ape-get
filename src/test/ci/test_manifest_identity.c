// Actual naming/prepare/record functions; no downloads or child execution.
#include "fs.h"
#include "json.h"
#include "manifest.h"
#include "path.h"
#include "repo.h"
#include "pipeline/internal.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const char *unreadable;
static char *fixture_read(const char *file, size_t *n)
{
    if (unreadable != NULL && strcmp(file, unreadable) == 0) {
        errno = EACCES;
        return NULL;
    }
    return fs_read_file(file, n);
}

// Deterministic EACCES exercises lookup without depending on test-user uid.
#define fs_read_file fixture_read
#include "state/manifest.c"
#undef fs_read_file

static void field(jbuf *b, const char *key, const char *value)
{
    if (b->n > 1) jb_lit(b, ",");
    jb_json_string(b, key);
    jb_lit(b, ":");
    if (value != NULL) jb_json_string(b, value);
    else jb_lit(b, "null");
}

int main(int argc, char **argv)
{
    if (argc < 5 || argc > 6) return 2;
    char *root = realpath(argv[2], NULL);
    if (root == NULL || strcmp(root, argv[2]) != 0) return 2;
    if (!strcmp(argv[1], "parse")) {
        char provider[128], repo[512];
        char *ref = path_fmt("%s:%s", argv[3], argv[4]);
        if (ref == NULL) return 1;
        bool accepted = repo_parse_ref(ref, provider, sizeof(provider), repo, sizeof(repo));
        jbuf result = {0};
        jb_lit(&result, accepted ? "{\"accepted\":true" : "{\"accepted\":false");
        field(&result, "repo", accepted ? repo : NULL);
        jb_lit(&result, "}");
        puts(result.p);
        jb_free(&result); free(ref); free(root);
        return 0;
    }
    state_ctx st = {0};
    st.root = path_join(root, "data/github-release-manager");
    st.install_root = path_join(root, "install");
    st.index_file = path_join(st.root, "grm_index.json");
    if (argc == 6) unreadable = argv[5];
    jbuf b = {0};
    jb_lit(&b, "{");
    char *app = manifest_app_dir(st.root, argv[3], argv[4]);
    char *mf = manifest_path(st.root, argv[3], argv[4]);
    char *install = manifest_install_name(argv[3], argv[4]);
    field(&b, "app", app);
    field(&b, "manifest", mf);
    field(&b, "install", install);
    if (!strcmp(argv[1], "prepare")) {
        if (strlen(argv[3]) >= 128 || strlen(argv[4]) >= 512) return 2;
        cmd_opts opts = {0};
        pipe_ctx pc = {0};
        pc.o = &opts;
        pc.st = &st;
        pc.op = "install";
        pc.ref = path_fmt("%s:%s", argv[3], argv[4]);
        strcpy(pc.prov, argv[3]);
        strcpy(pc.path, argv[4]);
        pc.rel.version = "1.0.0";
        prov_asset asset = {.name = "marker"};
        pc.asset = &asset;
        if (phase_prepare(&pc) != PIPE_GO || pc.failed) return 1;
        char *again = manifest_app_dir(st.root, argv[3], argv[4]);
        if (app == NULL || again == NULL || strcmp(app, again)
            || strcmp(app, pc.app_dir) || strcmp(mf, pc.j_mfile)) return 1;
        pc.mtmp = path_fmt("%s.tmp-%s", pc.j_mfile, pc.j_id);
        jbuf record = {0};
        jb_lit(&record, "{\"kind\":\"grm.manifest\",\"schema_version\":1,");
        jb_lit(&record, "\"provider\":"); jb_json_string(&record, argv[3]);
        jb_lit(&record, ",\"repo\":"); jb_json_string(&record, argv[4]);
        jb_lit(&record, ",\"asset\":{\"name\":\"marker\",\"url\":\"file:///marker\"},");
        jb_lit(&record, "\"installed_at\":\"2026-11-14T00:00:00Z\",\"install\":{\"path\":");
        jb_json_string(&record, pc.j_staged); jb_lit(&record, "}}");
        if (!fs_write_file(pc.mtmp, record.p)) return 1;
        phase_record(&pc);
        if (pc.failed || !fs_exists(mf)) return 1;
        char *after = manifest_app_dir(st.root, argv[3], argv[4]);
        if (after == NULL || strcmp(after, app)) return 1;
        field(&b, "prepared", pc.j_mfile);
        field(&b, "recorded", after);
        free(after); free(again); jb_free(&record);
        free((char *)pc.ref); free(pc.app_dir); free(pc.j_mfile);
        free(pc.j_staged); free(pc.j_vdir); free(pc.mtmp);
    } else if (strcmp(argv[1], "paths")) return 2;
    jb_lit(&b, "}");
    puts(b.p);
    jb_free(&b);
    free(app); free(mf); free(install);
    free(st.root); free(st.install_root); free(st.index_file); free(root);
    return 0;
}
