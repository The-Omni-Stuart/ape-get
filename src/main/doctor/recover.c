// Provider recovery from installed manifests: type and API read back
// off the packages that reference the provider, then confirmed.

#include "doctor.h"
#include "doctor/internal.h"

#include "fs.h"
#include "json.h"
#include "manifest.h"
#include "prompt.h"
#include "state.h"
#include "ui.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

// malloc'd unescaped string value of key, NULL when absent or null
static char *rec_str(const jobject *o, const char *key)
{
    int i = jobject_find(o, key);
    if (i < 0) return NULL;
    char *s = NULL;
    json_span_string(o->vals[i], o->vends[i], &s);
    return s;
}

void sv_add_uniq(strv *l, const char *s)
{
    for (int k = 0; k < l->n; k++)
        if (!strcmp(l->v[k], s)) return;
    if (l->n < 8) sv_add(l, strdup(s));
}

// Render a string set exactly as Java's HashSet does (spread hashCode & 15
// buckets, insertion order within one): the bytes live in an error message.
static void jset_line(const char *label, strv *l)
{
    unsigned h[8];
    for (int k = 0; k < l->n && k < 8; k++) {
        unsigned x = 0;
        for (const char *p = l->v[k]; *p != '\0'; p++)
            x = x * 31u + (unsigned char)*p;
        h[k] = x ^ (x >> 16);
    }
    jbuf s = {0};
    jb_lit(&s, label);
    jb_lit(&s, "[");
    for (int b = 0, emitted = 0; b < 16; b++)
        for (int k = 0; k < l->n && k < 8; k++)
            if ((int)(h[k] & 15u) == b) {
                if (emitted++) jb_lit(&s, ", ");
                jb_lit(&s, l->v[k]);
            }
    jb_lit(&s, "]");
    ui_line(stderr, UI_DIM, s.p != NULL ? s.p : "");
    jb_free(&s);
}

static const char *recover_type_canon(const char *v)
{
    if (!strcasecmp(v, "github")) return "github";
    if (!strcasecmp(v, "gitea")) return "gitea";
    if (!strcasecmp(v, "gitlab")) return "gitlab";
    if (!strcasecmp(v, "rngit")) return "rngit";
    return NULL;
}

static char *url_from_api(const char *type, const char *api)
{
    size_t n = strlen(api);
    while (n > 0 && api[n - 1] == '/') n--;
    if (!strcmp(type, "rngit")) return strndup(api, n);
    const char *suf = NULL;
    if (!strcmp(type, "gitea")) suf = "/api/v1";
    else if (!strcmp(type, "gitlab")) suf = "/api/v4";
    else if (!strcmp(type, "github")) {
        if (n == 22 && !memcmp(api, "https://api.github.com", 22))
            return strdup("https://github.com");
        suf = "/api/v3";
    }
    if (suf == NULL) return NULL;
    size_t sl = strlen(suf);
    if (n >= sl && !memcmp(api + n - sl, suf, sl)) n -= sl;
    return strndup(api, n);
}

int providers_recover_core(const cmd_opts *o, state_ctx *st, const char *name,
    const char *type_ov, const char *api_ov, const char *tok_ov,
    bool tok_given, pv_recovered *out)
{
    manifest_entry *ms = NULL;
    size_t mc = 0;
    if (manifests_scan(st->root, &ms, &mc) != 0) {
        ui_msg(stderr, UI_ERROR,
            "Failed to read installed packages: state scan failed");
        return 2;
    }
    int nref = 0;
    strv types = {0}, apis = {0};
    for (size_t k = 0; k < mc; k++) {
        if (ms[k].provider == NULL || strcmp(ms[k].provider, name) != 0)
            continue;
        nref++;
        size_t n = 0;
        char *text = fs_read_file(ms[k].file, &n);
        jobject root = {0};
        if (text != NULL && jobject_parse(text, &root)) {
            char *pt = rec_str(&root, "provider_type");
            char *pa = rec_str(&root, "provider_api");
            if (pt != NULL && *pt != '\0') sv_add_uniq(&types, pt);
            if (pa != NULL && *pa != '\0') sv_add_uniq(&apis, pa);
            free(pt);
            free(pa);
        }
        jobject_free(&root);
        free(text);
    }
    for (size_t k = 0; k < mc; k++) manifest_entry_free(&ms[k]);
    free(ms);
    if (nref == 0) {
        ui_msg(stderr, UI_ERROR,
            "No installed packages reference provider '%s'.", name);
        return 2;
    }
    if (types.n == 0 || apis.n == 0) {
        ui_msg(stderr, UI_ERROR,
            "Installed manifests do not record the provider type and API.");
        ui_msg(stderr, UI_ERROR,
            "Pass --type and --api to recover this provider.");
        return 2;
    }
    if (types.n > 1 || apis.n > 1) {
        ui_msg(stderr, UI_ERROR,
            "Installed manifests disagree about this provider:");
        jset_line("  types: ", &types);
        jset_line("  APIs:  ", &apis);
        return 2;
    }
    const char *typev = type_ov != NULL ? type_ov : types.v[0];
    const char *ltype = recover_type_canon(typev);
    if (ltype == NULL) {
        ui_msg(stderr, UI_ERROR, "Unknown provider type: %s", typev);
        return 2;
    }
    const char *apiv = api_ov != NULL ? api_ov : apis.v[0];
    const char *tok = tok_given ? tok_ov : NULL;
    char *url = url_from_api(ltype, apiv);
    char *api = url != NULL
        ? provider_resolved_api(ltype, url, *apiv != '\0' ? apiv : NULL) : NULL;
    if (url == NULL || api == NULL || !provider_valid_name(name)
        || !provider_url_ok(ltype, url) || !provider_token_ok(tok)
        || (*apiv != '\0' && !provider_url_ok(ltype, apiv))) {
        ui_msg(stderr, UI_ERROR, "Recovered provider config is invalid.");
        free(url);
        free(api);
        return 2;
    }
    char q[1024];
    snprintf(q, sizeof q, "Recover provider %s as %s (%s) for %d "
        "installed package(s)?", name, ltype, apiv, nref);
    if (!confirm_action(o, q, false)) {
        ui_msg(stderr, UI_WARN, "Provider recovery declined.");
        free(url);
        free(api);
        return 1;
    }
    char *cfg = state_config_edit(st, name, ltype, url, api, tok, NULL);
    bool saved = cfg != NULL && fs_write_file(st->config_file, cfg);
    free(cfg);
    if (!saved) {
        ui_msg(stderr, UI_ERROR, "Failed to save providers");
        free(url);
        free(api);
        return 2;
    }
    if (!o->json && !o->quiet)
        ui_msg(stderr, UI_OK, "Recovered provider %s.", name);
    if (out != NULL) {
        out->type = strdup(ltype);
        out->url = url;
        out->api = api;
        out->token_env = tok != NULL ? strdup(tok) : NULL;
        return 0;
    }
    free(url);
    free(api);
    return 0;
}
