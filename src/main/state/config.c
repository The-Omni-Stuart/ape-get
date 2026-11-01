// Config record rewrite: the provider add/remove delta re-emitted in
// the original field order. Open, lock and bootstrap: state.c.

#include "state.h"

#include "fs.h"
#include "json.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// --- config rewrite (the original config's field order) ---

char *state_config_edit(const state_ctx *st, const char *add_name,
    const char *type, const char *url, const char *api, const char *token_env,
    const char *remove_name)
{
    bool add_done = add_name == NULL;
    size_t n = 0;
    char *cfg = fs_read_file(st->config_file, &n);
    if (cfg == NULL) return NULL;
    jobject co = {0};
    if (!jobject_parse(cfg, &co)) { jobject_free(&co); free(cfg); return NULL; }

    // text of the entry being added, or replacing one in place
    jbuf ne = {0};
    if (add_name != NULL) {
        jb_lit(&ne, "{\"type\":");
        jb_json_string(&ne, type);
        jb_lit(&ne, ",\"url\":");
        jb_json_string(&ne, url);
        jb_lit(&ne, ",\"api\":");
        if (api && *api) jb_json_string(&ne, api); else jb_lit(&ne, "null");
        jb_lit(&ne, ",\"token_env\":");
        if (token_env && *token_env) jb_json_string(&ne, token_env);
        else jb_lit(&ne, "null");
        jb_lit(&ne, "}");
    }

    // providers map: replace in place, remove drops, add appends
    jbuf m = {0};
    jb_lit(&m, "{");
    bool firstm = true;
    int pi = jobject_find(&co, "providers");
    if (pi >= 0) {
        char *ptext = strndup(co.vals[pi], (size_t)(co.vends[pi] - co.vals[pi]));
        jobject po = {0};
        if (ptext != NULL && jobject_parse(ptext, &po)) {
            for (int k = 0; k < po.n; k++) {
                const char *key = po.keys[k];
                if (remove_name != NULL && strcmp(key, remove_name) == 0) continue;
                bool replace = add_name != NULL && strcmp(key, add_name) == 0;
                if (replace) add_done = true;
                if (!firstm) jb_lit(&m, ",");
                firstm = false;
                jb_json_string(&m, key);
                jb_lit(&m, ":");
                if (replace) { jb_lit(&m, ne.p); continue; }
                char *etext = strndup(po.vals[k], (size_t)(po.vends[k] - po.vals[k]));
                jobject eo = {0};
                char *t = NULL, *u = NULL, *a = NULL, *tok = NULL;
                if (etext != NULL && jobject_parse(etext, &eo)) {
                    int f = jobject_find(&eo, "type");
                    if (f >= 0) json_span_string(eo.vals[f], eo.vends[f], &t);
                    f = jobject_find(&eo, "url");
                    if (f >= 0) json_span_string(eo.vals[f], eo.vends[f], &u);
                    f = jobject_find(&eo, "api");
                    if (f >= 0) json_span_string(eo.vals[f], eo.vends[f], &a);
                    f = jobject_find(&eo, "token_env");
                    if (f >= 0) json_span_string(eo.vals[f], eo.vends[f], &tok);
                    jb_lit(&m, "{\"type\":");
                    if (t) jb_json_string(&m, t); else jb_lit(&m, "null");
                    jb_lit(&m, ",\"url\":");
                    if (u) jb_json_string(&m, u); else jb_lit(&m, "null");
                    jb_lit(&m, ",\"api\":");
                    if (a) jb_json_string(&m, a); else jb_lit(&m, "null");
                    jb_lit(&m, ",\"token_env\":");
                    if (tok) jb_json_string(&m, tok); else jb_lit(&m, "null");
                    jb_lit(&m, "}");
                } else {
                    jb_lit(&m, "null");
                }
                free(t); free(u); free(a); free(tok);
                free(etext);
                jobject_free(&eo);
            }
        }
        free(ptext);
        jobject_free(&po);
    }
    if (!add_done) {
        if (!firstm) jb_lit(&m, ",");
        firstm = false;
        jb_json_string(&m, add_name);
        jb_lit(&m, ":");
        jb_lit(&m, ne.p);
    }
    jb_lit(&m, "}");

    // remaining fields, re-emitted through the config normalization
    jbuf c = {0};
    char *s;
    long i;
    jb_lit(&c, "{\"kind\":");
    if (jobject_find(&co, "kind") >= 0
        && json_span_string(co.vals[jobject_find(&co, "kind")],
               co.vends[jobject_find(&co, "kind")], &s) && s)
        jb_json_string(&c, s);
    else jb_lit(&c, "\"grm.config\"");
    free(s); s = NULL;
    jb_lit(&c, ",\"schema_version\":");
    i = 1;
    if (jobject_find(&co, "schema_version") >= 0)
        json_span_int(co.vals[jobject_find(&co, "schema_version")],
            co.vends[jobject_find(&co, "schema_version")], &i);
    { char num[24]; snprintf(num, sizeof(num), "%ld", i); jb_lit(&c, num); }

    static const char *bools[] = { "strict_verification", "require_signature" };
    for (int b = 0; b < 2; b++) {
        const char *key = b == 0 ? ",\"strict_verification\":" : ",\"require_signature\":";
        jb_lit(&c, key);
        bool v = false;
        int f = jobject_find(&co, bools[b]);
        if (f >= 0) json_span_bool(co.vals[f], co.vends[f], &v);
        jb_lit(&c, v ? "true" : "false");
    }
    jb_lit(&c, ",\"backup_count\":");
    i = 0;
    if (jobject_find(&co, "backup_count") >= 0)
        json_span_int(co.vals[jobject_find(&co, "backup_count")],
            co.vends[jobject_find(&co, "backup_count")], &i);
    { char num[24]; snprintf(num, sizeof(num), "%ld", i); jb_lit(&c, num); }

    jb_lit(&c, ",\"install_root\":");
    int f = jobject_find(&co, "install_root");
    if (f >= 0 && json_span_string(co.vals[f], co.vends[f], &s) && s && *s)
        jb_json_string(&c, s);
    else jb_lit(&c, "null");
    free(s); s = NULL;

    jb_lit(&c, ",\"providers\":");
    jb_lit(&c, m.p ? m.p : "{}");

    jb_lit(&c, ",\"search_all_providers\":");
    f = jobject_find(&co, "search_all_providers");
    if (f < 0) jb_lit(&c, "true");
    else {
        size_t len = (size_t)(co.vends[f] - co.vals[f]);
        bool v = true;
        if (len == 4 && memcmp(co.vals[f], "null", 4) == 0) jb_lit(&c, "null");
        else {
            if (!json_span_bool(co.vals[f], co.vends[f], &v)) v = true;
            jb_lit(&c, v ? "true" : "false");
        }
    }

    jb_lit(&c, ",\"update_channel\":");
    f = jobject_find(&co, "update_channel");
    {
        const char *ch = "stable";
        char *cv = NULL;
        if (f >= 0 && json_span_string(co.vals[f], co.vends[f], &cv) && cv) {
            if (strcmp(cv, "beta") == 0) ch = "beta";
        }
        jb_json_string(&c, ch);
        free(cv);
    }
    jb_lit(&c, ",\"auto_update\":");
    f = jobject_find(&co, "auto_update");
    {
        const char *am = "off";
        char *av = NULL;
        if (f >= 0 && json_span_string(co.vals[f], co.vends[f], &av) && av) {
            if (strcmp(av, "check") == 0) am = "check";
            else if (strcmp(av, "apply") == 0) am = "apply";
        }
        jb_json_string(&c, am);
        free(av);
    }
    jb_lit(&c, ",\"update_interval_hours\":");
    i = 24;
    if (jobject_find(&co, "update_interval_hours") >= 0)
        json_span_int(co.vals[jobject_find(&co, "update_interval_hours")],
            co.vends[jobject_find(&co, "update_interval_hours")], &i);
    if (i <= 0) i = 24;
    { char num[24]; snprintf(num, sizeof(num), "%ld", i); jb_lit(&c, num); }
    jb_lit(&c, ",\"network_timeout_seconds\":");
    i = 30;
    if (jobject_find(&co, "network_timeout_seconds") >= 0)
        json_span_int(co.vals[jobject_find(&co, "network_timeout_seconds")],
            co.vends[jobject_find(&co, "network_timeout_seconds")], &i);
    if (i <= 0) i = 30;
    { char num[24]; snprintf(num, sizeof(num), "%ld", i); jb_lit(&c, num); }
    jb_lit(&c, ",\"proxy\":");
    f = jobject_find(&co, "proxy");
    {
        bool wrote = false;
        if (f >= 0) {
            char *pv = NULL;
            if (json_span_string(co.vals[f], co.vends[f], &pv) && pv != NULL) {
                size_t a = 0, b = strlen(pv);
                while (a < b && isspace((unsigned char)pv[a])) a++;
                while (b > a && isspace((unsigned char)pv[b - 1])) b--;
                if (b > a) {
                    char *trim = strndup(pv + a, b - a);
                    jb_json_string(&c, trim);
                    free(trim);
                    wrote = true;
                }
            }
            free(pv);
            if (!wrote) jb_lit(&c, "null");
        } else jb_lit(&c, "null");
    }
    // These members ride along verbatim when present; the original record
    // never carried them, so those configs stay byte-identical.
    {
        static const char *ck[] = { "experimental_compat", "wine_path" };
        for (int k = 0; k < 2; k++) {
            int cf = jobject_find(&co, ck[k]);
            if (cf < 0) continue;
            jb_lit(&c, ",\"");
            jb_lit(&c, ck[k]);
            jb_lit(&c, "\":");
            char *raw = strndup(co.vals[cf],
                (size_t)(co.vends[cf] - co.vals[cf]));
            jb_lit(&c, raw != NULL ? raw : "null");
            free(raw);
        }
    }
    jb_lit(&c, "}");

    jobject_free(&co);
    jb_free(&m);
    jb_free(&ne);
    free(cfg);
    return c.p;
}
