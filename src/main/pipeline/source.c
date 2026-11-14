// Install-source resolution: owner/repo or bare name -> provider:path.

#include "pipeline.h"
#include "pipeline/internal.h"

#include "compat.h"
#include "crypto.h"
#include "pipeline/checksums.h"
#include "fs.h"
#include "json.h"
#include "manifest.h"
#include "path.h"
#include "proc.h"
#include "provider.h"
#include "ui.h"
#include "repo.h"
#include "state.h"
#include "txn.h"
#include "verify.h"

#include <ctype.h>
#include <errno.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

// Resolve owner/repo or a bare name to provider:path across every
// configured provider, name-sorted (providers_load sorts).
typedef struct { char *key, *provider, *path, *desc; } src_option;

static bool search_type(const provider_entry *e, const char *query,
                        int timeout_ms, prov_search *out)
{
    if (!strcasecmp(e->type, "gitea"))
        return provider_gitea_search(e, query, timeout_ms, out);
    if (!strcasecmp(e->type, "github"))
        return provider_github_search(e, query, timeout_ms, out);
    if (!strcasecmp(e->type, "gitlab"))
        return provider_gitlab_search(e, query, timeout_ms, out);
    provider_search_unsupported(out);
    return false;
}

static void src_free(src_option *v, int n)
{
    for (int k = 0; k < n; k++) {
        free(v[k].key); free(v[k].provider);
        free(v[k].path); free(v[k].desc);
    }
}

// key = "<provider>:<path lowercased>"; first insertion wins
static bool src_add(src_option *found, int *n, int cap,
                    const char *provider, const char *path,
                    const char *desc)
{
    char key[1100];
    int m = snprintf(key, sizeof(key), "%s:", provider);
    for (const char *p = path; *p != '\0' && m + 1 < (int)sizeof(key); p++)
        key[m++] = (char)(*p >= 'A' && *p <= 'Z' ? *p + 32 : *p);
    key[m] = '\0';
    for (int k = 0; k < *n; k++)
        if (!strcmp(found[k].key, key)) return true;
    if (*n >= cap) return true;
    found[*n].key = strdup(key);
    found[*n].provider = strdup(provider);
    found[*n].path = strdup(path);
    found[*n].desc = desc != NULL ? strdup(desc) : NULL;
    if (found[*n].key == NULL || found[*n].provider == NULL
        || found[*n].path == NULL) return false;
    (*n)++;
    return true;
}

bool resolve_source_ref(const cmd_opts *o, state_ctx *st,
                               const char *argument, char *out, size_t cap)
{
    const char *a = argument;
    while (isspace((unsigned char)*a)) a++;
    if (*a == '\0' || strchr(a, ':') != NULL) {
        snprintf(out, cap, "%s", argument);
        return true;
    }
    char path[600];
    size_t len = strlen(a);
    while (len > 0 && isspace((unsigned char)a[len - 1])) len--;
    const char *core = a;
    while (*core == '/') core++;
    len = strlen(core);
    while (len > 0 && core[len - 1] == '/') len--;
    if (len + 1 > sizeof(path)) {
        ui_msg(stderr, UI_ERROR, "Invalid repository");
        return false;
    }
    memcpy(path, core, len);
    path[len] = '\0';
    if (len == 0 || strstr(path, "//") != NULL
        || strchr(path, '\\') != NULL) {
        char *d = path_fmt("Invalid repository: %s", argument);
        ui_msg(stderr, UI_ERROR, "%s", d != NULL ? d : "invalid repository");
        free(d);
        return false;
    }

    if (!blank(o->provider)) {
        if (strchr(path, '/') == NULL) {
            ui_msg(stderr, UI_ERROR, "--provider needs an owner/repo argument.");
            return false;
        }
        snprintf(out, cap, "%s:%s", o->provider, path);
        return true;
    }

    provider_entry *list = NULL;
    int n = providers_load(st, &list);
    if (n == 0) {
        ui_msg(stderr, UI_ERROR,
            "No providers are configured; use provider:owner/repo.");
        return false;
    }
    int timeout_ms = (st->timeout_seconds > 0 ? st->timeout_seconds : 30)
        * 1000;
    bool slashed = strchr(path, '/') != NULL;
    const char *name = strrchr(path, '/') != NULL
        ? strrchr(path, '/') + 1 : path;
    if (!o->json && !o->quiet) {
        char *l = path_fmt("Looking for %s across %d provider(s)...",
            path, n);
        ui_msg(stderr, UI_DIM, "%s", l != NULL ? l : "");
        free(l);
    }

    src_option found[64];
    memset(found, 0, sizeof(found));
    int nf = 0;
    bool memfail = false;
    bool lookup_failed = false;
    if (slashed) {
        for (int k = 0; k < n; k++) {
            if (!strcasecmp(list[k].type, "rngit")) {
                ui_warn_subject(stderr, list[k].name,
                    "repository lookup is not supported"
                    " (use provider:owner/repo)");
                continue;
            }
            prov_release rel;
            memset(&rel, 0, sizeof(rel));
            if (provider_release_fetch(&list[k], path, NULL, false,
                    timeout_ms, &rel)) {
                memfail = !src_add(found, &nf, 64, list[k].name, path,
                    NULL);
            } else if (rel.reason != PROV_NOT_FOUND) {
                if (rel.reason != PROV_UNSUPPORTED) lookup_failed = true;
                ui_warn_subject(stderr, list[k].name, "%s", rel.message);
            }
            prov_release_free(&rel);
            if (memfail) break;
        }
    } else {
        for (int k = 0; k < n; k++) {
            prov_search res;
            memset(&res, 0, sizeof(res));
            bool ok = search_type(&list[k], name, timeout_ms, &res);
            if (ok) {
                for (int h = 0; h < res.n; h++) {
                    const char *fn = res.items[h].full_name;
                    if (fn == NULL) continue;
                    const char *seg = strrchr(fn, '/');
                    seg = seg != NULL ? seg + 1 : fn;
                    if (strcasecmp(seg, name) != 0) continue;
                    memfail = !src_add(found, &nf, 64, list[k].name, fn,
                        res.items[h].description);
                    if (memfail) break;
                }
            } else if (res.reason != PROV_NOT_FOUND) {
                if (res.reason != PROV_UNSUPPORTED) lookup_failed = true;
                if (res.reason == PROV_UNSUPPORTED)
                    ui_warn_subject(stderr, list[k].name,
                        "%s (use provider:owner/repo)", res.message);
                else
                    ui_warn_subject(stderr, list[k].name, "%s", res.message);
            }
            prov_search_free(&res);
            if (memfail) break;
        }
    }

    if (nf == 0) {
        char *l = lookup_failed
            ? path_fmt("Could not resolve '%s': one or more providers failed. "
                "Try again or use provider:owner/repo.", argument)
            : slashed
            ? path_fmt("No repository with a published release matching "
                "'%s'; try 'search %s' or use provider:owner/repo.",
                argument, name)
            : path_fmt("No repository matching '%s'; try 'search %s' or "
                "use provider:owner/repo.", argument, name);
        ui_msg(stderr, UI_ERROR, "%s", l != NULL ? l : "no match");
        free(l);
        src_free(found, nf);
        providers_free(list, n);
        return false;
    }
    if (nf == 1) {
        snprintf(out, cap, "%s:%s", found[0].provider, found[0].path);
        if (!o->json && !o->quiet) {
            char *l = path_fmt("Using %s.", out);
            ui_msg(stderr, UI_REF, "%s", l != NULL ? l : "");
            free(l);
        }
        src_free(found, nf);
        providers_free(list, n);
        return true;
    }

    char *l = path_fmt("Multiple sources found for '%s':", path);
    ui_msg(stderr, UI_BOLD, "%s", l != NULL ? l : "");
    free(l);
    for (int k = 0; k < nf; k++) {
        char *e = found[k].desc != NULL && *found[k].desc != '\0'
            ? path_fmt("  %d: %s:%s - %s", k + 1, found[k].provider,
                found[k].path, found[k].desc)
            : path_fmt("  %d: %s:%s", k + 1, found[k].provider,
                found[k].path);
        ui_msg(stderr, UI_DIM, "%s", e != NULL ? e : "");
        free(e);
    }
    ui_msg(stderr, UI_ITALIC, "Install with provider:owner/repo to choose one.");
    long pick = choose_index(o, "a source", "source", (size_t)nf);
    if (pick >= 0) {
        snprintf(out, cap, "%s:%s", found[pick].provider, found[pick].path);
        if (!o->json && !o->quiet) {
            char *u = path_fmt("Using %s.", out);
            ui_msg(stderr, UI_REF, "%s", u != NULL ? u : "");
            free(u);
        }
        src_free(found, nf);
        providers_free(list, n);
        return true;
    }
    src_free(found, nf);
    providers_free(list, n);
    return false;
}

