// Report assembly: one call renders the human lines or the --json
// document; heal entries join only when --heal ran.

#include "doctor/internal.h"

#include "json.h"
#include "state.h"
#include "ui.h"

#include <stdio.h>
#include <string.h>

static void json_array(jbuf *b, strv *l)
{
    jb_lit(b, "[");
    for (int k = 0; k < l->n; k++) {
        if (k) jb_lit(b, ",");
        jb_json_string(b, l->v[k]);
    }
    jb_lit(b, "]");
}

void report_lines(const state_ctx *st, strv *issues, strv *warnings,
                  bool gpg_ok, long long free_bytes, bool json, bool healthy,
                  strv *healed, strv *declined, const tool_line *tools,
                  int ntools)
{
    if (json) {
        jbuf b = {0};
        jb_lit(&b, "{\"state_root\":");
        jb_json_string(&b, st->root);
        jb_lit(&b, ",\"signature_tools\":[{\"name\":\"gpg\",\"required\":true,\"available\":");
        jb_lit(&b, gpg_ok ? "true" : "false");
        jb_lit(&b, "}],\"free_bytes\":");
        char num[24];
        snprintf(num, sizeof(num), "%lld", free_bytes);
        jb_lit(&b, num);
        jb_lit(&b, ",\"healthy\":");
        jb_lit(&b, healthy ? "true" : "false");
        jb_lit(&b, ",\"config\":");
        jb_json_string(&b, st->config_file);
        jb_lit(&b, ",\"index\":");
        jb_json_string(&b, st->index_file);
        jb_lit(&b, ",\"manifests\":");
        snprintf(num, sizeof(num), "%d", st->manifests);
        jb_lit(&b, num);
        jb_lit(&b, ",\"issues\":");
        json_array(&b, issues);
        jb_lit(&b, ",\"warnings\":");
        json_array(&b, warnings);
        jb_lit(&b, ",\"pins\":[]");
        if (healed != NULL) {
            // C-only heal arrays, appended only under --heal
            jb_lit(&b, ",\"healed\":[");
            for (int k = 0; k < healed->n; k++) {
                if (k) jb_lit(&b, ",");
                jb_lit(&b, healed->v[k]);
            }
            jb_lit(&b, "],\"heal_declined\":");
            json_array(&b, declined);
        }
        jb_lit(&b, "}");
        puts(b.p);
        jb_free(&b);
        return;
    }
    char ln[2048];
#define DKV(label, ...) do { \
        snprintf(ln, sizeof ln, ": " __VA_ARGS__); \
        ui_line_kv(stdout, label, ln); \
    } while (0)
    DKV("State root", "%s", st->root);
    DKV("Config", "%s", st->config_file);
    DKV("Index", "%s", st->index_file);
    DKV("Manifests", "%d", st->manifests);
    if (free_bytes < 0) DKV("Free space", "unknown");
    else DKV("Free space", "%lld MB", free_bytes / (1024 * 1024));
#undef DKV
    puts("");
    if (gpg_ok) ui_msg(stdout, UI_OK, "Signature tool: gpg available");
    else ui_msg(stdout, UI_ERROR, "Signature tool: gpg missing");
    for (int k = 0; k < ntools; k++)
        ui_line_kv_value(stdout, tools[k].label, tools[k].value,
            tools[k].style);
    for (int k = 0; k < warnings->n; k++)
        ui_msg(stdout, UI_WARN, "Warning: %s", warnings->v[k]);
    for (int k = 0; k < issues->n; k++)
        ui_line(stdout, UI_ERROR, issues->v[k]);
    puts("");
}

static void jb_jns(jbuf *b, const char *s)
{
    if (s == NULL) jb_lit(b, "null");
    else jb_json_string(b, s);
}

// One healed item for the JSON array: {action, ref, detail}.
void heal_add(strv *l, const char *action, const char *ref,
                     const char *detail)
{
    jbuf b = {0};
    jb_lit(&b, "{\"action\":");
    jb_json_string(&b, action);
    jb_lit(&b, ",\"ref\":");
    jb_jns(&b, ref);
    jb_lit(&b, ",\"detail\":");
    jb_jns(&b, detail);
    jb_lit(&b, "}");
    if (b.p != NULL) sv_add(l, b.p);  // the list takes over the buffer
    else jb_free(&b);
}
