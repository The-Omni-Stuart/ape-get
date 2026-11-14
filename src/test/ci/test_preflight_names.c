// Metadata-only preflight regression: real selection/kind/arch code, no fetch,
// installed-state access, downloads, filesystem writes or child execution.
#include "pipeline/internal.h"
#include "compat.h"
#include "crypto.h"
#include "prompt.h"
#include "ui.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static provider_entry provider = {.name = "fixture", .type = "gitlab"};
static prov_asset assets[2];
static int asset_count;

int providers_load(const state_ctx *st, provider_entry **out)
{ (void)st; *out = &provider; return 1; }
provider_entry *providers_find(provider_entry *p, int n, const char *name)
{ (void)n; (void)name; return p; }
int manifests_scan(const char *root, manifest_entry **out, size_t *n)
{ (void)root; *out = NULL; *n = 0; return 0; }
bool provider_release_fetch(const provider_entry *e, const char *p,
    const char *t, bool b, int ms, prov_release *out)
{
    (void)e; (void)p; (void)t; (void)b; (void)ms;
    *out = (prov_release){.assets = assets, .n = asset_count, .version = "v2"};
    return true;
}
void oplog_append(const state_ctx *s, const char *a, const char *b,
    const char *c, const char *d)
{ (void)s; (void)a; (void)b; (void)c; (void)d; }
void ui_msg(FILE *f, ui_style s, const char *fmt, ...)
{ (void)f; (void)s; (void)fmt; }
bool crypto_sha256_fd(int fd, char out[65])
{ (void)fd; (void)out; abort(); }
char *compat_detect_wine(const char *p, char **err)
{ (void)p; (void)err; abort(); }
bool confirm_action(const cmd_opts *o, const char *q, bool d)
{ (void)o; (void)q; (void)d; abort(); }
long choose_index(const cmd_opts *o, const char *what, const char *none, size_t n)
{ (void)o; (void)what; (void)none; (void)n; abort(); }

#ifndef APEGET_PREFLIGHT_SOURCE
#define APEGET_PREFLIGHT_SOURCE "pipeline/preflight.c"
#endif
#include APEGET_PREFLIGHT_SOURCE

static void check(const char *label, const char *name, const char *other,
    const char *argument, bool updating, bool compat, const char *expected)
{
    // All paths are inert: the provider and state boundaries above use only
    // memory. The caller supplies an isolated root during native/ASan runs.
    const char *root = getenv("APEGET_TEST_WORK_ROOT");
    if (root == NULL) root = ".";
    state_ctx st = {.root = (char *)root, .install_root = (char *)root,
                    .timeout_seconds = 1, .experimental_compat = compat};
    cmd_opts o = {.dry_run = true, .assume_yes = true, .no_command = true,
                  .asset = argument, .reinstall = true};
    manifest_entry prev = {.provider = "fixture", .repo = "owner/tool",
                           .version = "v1", .asset = "tool.com", .kind = "ape"};
    pipe_ctx c = {.st = &st, .o = &o, .ref = "fixture:owner/tool",
                  .updating = updating, .prev = &prev, .rv = RES_FAILED};
    strcpy(c.fprov, "fixture"); strcpy(c.fpath, "owner/tool");
    strcpy(c.prov, "fixture"); strcpy(c.path, "owner/tool");
    assets[0] = (prov_asset){.name = (char *)name,
                            .url = "https://fixture.invalid/v2/asset"};
    assets[1] = (prov_asset){.name = (char *)other,
                            .url = "https://fixture.invalid/v2/other"};
    asset_count = other != NULL ? 2 : 1;
    int phase = phase_preflight(&c);
    bool ok = phase == PIPE_DONE && (expected != NULL
        ? c.rv == RES_PLANNED && c.asset != NULL
            && strcmp(c.asset->name, expected) == 0
        : c.rv == RES_FAILED && c.asset == NULL);
    if (!ok) {
        fprintf(stderr, "FAIL %s: phase=%d result=%d selected-length=%zu\n",
            label, phase, c.rv, c.asset != NULL ? strlen(c.asset->name) : 0);
        exit(1);
    }
    free(c.pool); free(c.command); free(c.launcher); free(c.detail);
    printf("PASS %s\n", label);
}

static char *name_of_length(size_t n, const char *suffix, bool prefix_alias)
{
    char *name = malloc(n + 1);
    if (name == NULL || n < strlen(suffix)) abort();
    memset(name, 'a', n);
    if (prefix_alias && n > 511) memcpy(name + 507, ".com", 4);
    memcpy(name + n - strlen(suffix), suffix, strlen(suffix) + 1);
    return name;
}

int main(int argc, char **argv)
{
    const char *only = argc == 2 ? argv[1] : "";
    char *huge = name_of_length(1031, ".com.xz", true);
    // Run each unsafe original branch independently for sanitizer proofs.
    if (!strcmp(only, "explicit-huge")) {
        check(only, huge, NULL, huge, false, false, NULL);
        free(huge); return 0;
    }
    if (!strcmp(only, "update-huge")) {
        check(only, huge, "tool.com.xz", NULL, true, false, "tool.com.xz");
        free(huge); return 0;
    }
    if (*only != '\0') return 2;
    const char *valid[] = {"tool.com", "tool.com.xz", "tool.ape", "tool.ape.xz",
                           "tool.AppImage", "tool.AppImage.xz"};
    for (size_t k = 0; k < sizeof valid / sizeof *valid; k++)
        check(valid[k], valid[k], NULL, NULL, false, false, valid[k]);
    check("explicit-compressed", "tool.com.xz", NULL, "tool.com.xz",
        false, false, "tool.com.xz");
    check("explicit-plain-twin", "tool.com.xz", NULL, "tool.com",
        false, false, "tool.com.xz");
    check("update-plain-twin", "tool.com.xz", NULL, NULL,
        true, false, "tool.com.xz");
    size_t lengths[] = {510, 511, 512, 513, 514, 1031};
    for (size_t k = 0; k < sizeof lengths / sizeof *lengths; k++) {
        size_t n = lengths[k];
        char label[80], *name = name_of_length(n, ".com.xz", true);
        char *plain = strndup(name, n - 3);
        snprintf(label, sizeof label, "explicit-%zu", n);
        check(label, name, NULL, name, false, false, n < 512 ? name : NULL);
        snprintf(label, sizeof label, "plain-alias-%zu", n);
        check(label, name, NULL, plain, false, false, n < 512 ? name : NULL);
        if (n >= 512) {
            snprintf(label, sizeof label, "numeric-%zu", n);
            check(label, name, NULL, "1", false, false, NULL);
            snprintf(label, sizeof label, "auto-skips-%zu", n);
            check(label, name, "tool.com.xz", NULL, false, false, "tool.com.xz");
            snprintf(label, sizeof label, "update-skips-%zu", n);
            check(label, name, "tool.com.xz", NULL, true, false, "tool.com.xz");
        }
        free(plain); free(name);
    }
    char *bounded = name_of_length(511, ".com", false);
    char *argument = malloc(515);
    if (argument == NULL) abort();
    memcpy(argument, bounded, 511); memcpy(argument + 511, "more", 4);
    // The old logical-name fallback silently truncated this argument.
    argument[514] = '\0';
    check("argument-prefix-alias", bounded, NULL, argument, false, false, NULL);
    free(argument); free(bounded);
    char *pe = name_of_length(1031, ".exe.xz", false);
    memcpy(pe + 507, ".exe", 4);
    check("compat-fallback-skips-oversize", pe, NULL, NULL, false, true, NULL);
    free(pe);
    check("missing-name", NULL, "tool.com.xz", NULL, false, false, "tool.com.xz");
    free(huge);
    return 0;
}
