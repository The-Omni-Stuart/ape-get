// Exercise source-resolution errors without DNS or live provider dependencies.
#include "pipeline.h"
#include "pipeline/internal.h"
#include "provider.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static prov_reason result_reason;

int providers_load(const state_ctx *st, provider_entry **out)
{
    (void)st;
    *out = calloc(1, sizeof(**out));
    if (*out == NULL) return 0;
    (*out)->name = strdup("fixture");
    (*out)->type = strdup("github");
    return 1;
}

void providers_free(provider_entry *list, int n)
{
    (void)n;
    free(list->name);
    free(list->type);
    free(list);
}

static bool search_result(prov_search *out)
{
    memset(out, 0, sizeof(*out));
    out->reason = result_reason;
    snprintf(out->message, sizeof(out->message), "fixture provider unavailable");
    return false;
}

bool provider_github_search(const provider_entry *e, const char *q, int ms,
                            prov_search *out)
{
    (void)e; (void)q; (void)ms;
    return search_result(out);
}

bool provider_gitea_search(const provider_entry *e, const char *q, int ms,
                           prov_search *out)
{
    return provider_github_search(e, q, ms, out);
}

bool provider_gitlab_search(const provider_entry *e, const char *q, int ms,
                            prov_search *out)
{
    return provider_github_search(e, q, ms, out);
}

void provider_search_unsupported(prov_search *out)
{
    memset(out, 0, sizeof(*out));
    out->reason = PROV_UNSUPPORTED;
}

void prov_search_free(prov_search *out) { (void)out; }
void prov_release_free(prov_release *out) { (void)out; }

bool provider_release_fetch(const provider_entry *e, const char *path,
                            const char *tag, bool beta, int ms,
                            prov_release *out)
{
    (void)e; (void)path; (void)tag; (void)beta; (void)ms;
    memset(out, 0, sizeof(*out));
    out->reason = result_reason;
    snprintf(out->message, sizeof(out->message), "fixture provider unavailable");
    return false;
}

long choose_index(const cmd_opts *o, const char *what, const char *none, size_t n)
{
    (void)o; (void)what; (void)none; (void)n;
    return -1;
}

int main(int argc, char **argv)
{
    if (argc != 3) return 2;
    result_reason = !strcmp(argv[1], "missing") ? PROV_NOT_FOUND : PROV_UNREACHABLE;
    state_ctx st = {.timeout_seconds = 1};
    cmd_opts options = {.quiet = true};
    char resolved[256];
    return resolve_source_ref(&options, &st, argv[2], resolved, sizeof(resolved))
        ? 1 : 0;
}
