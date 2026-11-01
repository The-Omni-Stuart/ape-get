// Compat manifest scan: the applications tree walked for wine
// wrappers whose launcher no longer resolves; warnings only.

#include "doctor/internal.h"

#include "compat.h"
#include "fs.h"
#include "json.h"
#include "path.h"

#include <dirent.h>
#include <stdlib.h>
#include <string.h>

// A compat manifest whose wrapper binary no longer resolves is a warning;
// read-only scan that, unlike the state-module one, never quarantines.
static void compat_check_manifest(const char *file, const char *wine_cfg,
    strv *warnings)
{
    size_t n = 0;
    char *text = fs_read_file(file, &n);
    if (text == NULL) return;
    jobject root = {0};
    if (!jobject_parse(text, &root)) { free(text); return; }
    char *layer = NULL;
    int ci = jobject_find(&root, "compat");
    if (ci >= 0) json_span_string(root.vals[ci], root.vends[ci], &layer);
    if (layer != NULL && strcmp(layer, COMPAT_WINE) == 0) {
        char *prov = NULL, *repo = NULL, *launcher = NULL;
        int pi = jobject_find(&root, "provider");
        if (pi >= 0) json_span_string(root.vals[pi], root.vends[pi], &prov);
        int ri = jobject_find(&root, "repo");
        if (ri >= 0) json_span_string(root.vals[ri], root.vends[ri], &repo);
        int ii = jobject_find(&root, "install");
        if (ii >= 0) {
            char *it = strndup(root.vals[ii],
                (size_t)(root.vends[ii] - root.vals[ii]));
            jobject io = {0};
            if (it != NULL && jobject_parse(it, &io)) {
                int ui = jobject_find(&io, "uninstall");
                if (ui >= 0) {
                    char *ut = strndup(io.vals[ui],
                        (size_t)(io.vends[ui] - io.vals[ui]));
                    jobject uo = {0};
                    if (ut != NULL && jobject_parse(ut, &uo)) {
                        int up = jobject_find(&uo, "path");
                        if (up >= 0)
                            json_span_string(uo.vals[up], uo.vends[up],
                                &launcher);
                    }
                    jobject_free(&uo);
                    free(ut);
                }
            }
            jobject_free(&io);
            free(it);
        }
        char *detail = compat_wrapper_problem(launcher, wine_cfg);
        if (detail != NULL) {
            sv_add(warnings, path_fmt("compat wine for %s:%s no longer "
                "resolves (%s)", prov != NULL ? prov : "?",
                repo != NULL ? repo : "?", detail));
            free(detail);
        }
        free(launcher);
        free(prov);
        free(repo);
    }
    free(layer);
    jobject_free(&root);
    free(text);
}

void compat_walk(const char *dir, const char *wine_cfg, strv *w)
{
    DIR *d = opendir(dir);
    if (d == NULL) return;
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (e->d_name[0] == '.') continue;
        char *p = path_join(dir, e->d_name);
        if (p == NULL) continue;
        if (fs_is_dir(p)) compat_walk(p, wine_cfg, w);
        else if (strcmp(e->d_name, "grm_manifest.json") == 0)
            compat_check_manifest(p, wine_cfg, w);
        free(p);
    }
    closedir(d);
}
