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

// search command (read-only: no lock/ensure)

static void search_report_failure(const char *provider, const prov_search *r)
{
    if (r->reason == PROV_NOT_FOUND) return;   // not-found stays silent, original quirk
    if (r->reason == PROV_UNSUPPORTED)
        ui_warn_subject(stderr, provider,
            "%s (use provider:owner/repo)", r->message);
    else
        ui_warn_subject(stderr, provider, "%s", r->message);
}

static bool search_run_one(provider_entry *e, const char *query,
                           int timeout_ms, prov_search *res)
{
    memset(res, 0, sizeof(*res));
    if (!strcasecmp(e->type, "gitea"))
        return provider_gitea_search(e, query, timeout_ms, res);
    if (!strcasecmp(e->type, "github"))
        return provider_github_search(e, query, timeout_ms, res);
    if (!strcasecmp(e->type, "gitlab"))
        return provider_gitlab_search(e, query, timeout_ms, res);
    provider_search_unsupported(res);
    return false;
}

static void search_entry_json(jbuf *b, const char *provider,
                              const prov_repo *hit)
{
    jb_lit(b, "{\"provider\":");
    jb_json_string(b, provider);
    jb_lit(b, ",\"repo\":");
    if (hit->full_name != NULL) jb_json_string(b, hit->full_name);
    else jb_lit(b, "null");
    jb_lit(b, ",\"description\":");
    if (hit->description != NULL) jb_json_string(b, hit->description);
    else jb_lit(b, "null");
    jb_lit(b, "}");
}

static int search_one(const cmd_opts *o, state_ctx *st,
                      provider_entry *e, const char *query)
{
    prov_search res;
    if (!search_run_one(e, query, st->timeout_seconds * 1000, &res)) {
        search_report_failure(e->name, &res);
        prov_search_free(&res);
        return 1;
    }
    if (o->json) {
        jbuf b = {0};
        jb_lit(&b, "{\"results\":[");
        for (int k = 0; k < res.n; k++) {
            if (k) jb_lit(&b, ",");
            search_entry_json(&b, e->name, &res.items[k]);
        }
        jb_lit(&b, "]}");
        puts(b.p);
        jb_free(&b);
        prov_search_free(&res);
        return 0;
    }
    if (res.n == 0) {
        puts("No results found.");
        prov_search_free(&res);
        return 0;
    }
    printf("Found %d repositories.\n", res.n);
    char pfx[512];
    if (strcasecmp(e->name, "github") == 0) pfx[0] = '\0';
    else snprintf(pfx, sizeof(pfx), "%s:", e->name);
    for (int k = 0; k < res.n; k++)
        printf("%s%s: %s\n", pfx,
               res.items[k].full_name != NULL ? res.items[k].full_name : "null",
               res.items[k].description != NULL ? res.items[k].description
                                                : "null");
    if (!o->quiet)
        fprintf(stderr, "To install a release, use the 'install' command"
                        " followed by the repository name.\n");
    prov_search_free(&res);
    return 0;
}

static int search_all(const cmd_opts *o, state_ctx *st, const char *query)
{
    provider_entry *list = NULL;
    int n = providers_load(st, &list);
    if (n == 0) {
        fprintf(stderr, "No providers are configured.\n");
        return 1;
    }
    if (!o->json && !o->quiet)
        fprintf(stderr, "Searching %d provider(s)...\n", n);
    prov_search *results = calloc((size_t)n, sizeof(*results));
    int *okv = calloc((size_t)n, sizeof(*okv));
    int succeeded = 0;
    size_t hits = 0;
    for (int k = 0; k < n && results != NULL && okv != NULL; k++) {
        okv[k] = search_run_one(&list[k], query,
                                st->timeout_seconds * 1000, &results[k]) ? 1 : 0;
        if (okv[k]) { succeeded++; hits += (size_t)results[k].n; }
    }

    if (o->json) {
        // the JSON report counts every failure as an error, 404s included
        jbuf b = {0};
        jb_lit(&b, "{\"results\":[");
        for (int k = 0; results != NULL && k < n; k++)
            for (int h = 0; okv[k] && h < results[k].n; h++) {
                if (b.n > strlen("{\"results\":[")) jb_lit(&b, ",");
                search_entry_json(&b, list[k].name, &results[k].items[h]);
            }
        jb_lit(&b, "],\"errors\":[");
        bool first = true;
        for (int k = 0; results != NULL && k < n; k++) {
            if (okv[k]) continue;
            if (!first) jb_lit(&b, ",");
            first = false;
            jb_lit(&b, "{\"provider\":");
            jb_json_string(&b, list[k].name);
            jb_lit(&b, ",\"error\":");
            jb_json_string(&b, results[k].message);
            jb_lit(&b, "}");
        }
        jb_lit(&b, "]}");
        puts(b.p);
        jb_free(&b);
    } else {
        for (int k = 0; results != NULL && k < n; k++)
            if (!okv[k]) search_report_failure(list[k].name, &results[k]);
        if (succeeded == 0) {
            put_s(stderr, UI_ERROR, "All provider searches failed.");
        } else if (hits == 0) {
            puts("No results found.");
        } else {
            for (int k = 0; results != NULL && k < n; k++)
                for (int h = 0; okv[k] && h < results[k].n; h++)
                    printf("%s:%s: %s\n", list[k].name,
                           results[k].items[h].full_name != NULL
                               ? results[k].items[h].full_name : "null",
                           results[k].items[h].description != NULL
                               ? results[k].items[h].description : "");
            printf("Found %zu repositories across %d provider(s).\n",
                   hits, succeeded);
            if (!o->quiet)
                fprintf(stderr, "Use provider:owner/repo to install"
                                " one of them.\n");
        }
    }
    int rc = succeeded > 0 ? 0 : 1;
    for (int k = 0; results != NULL && k < n; k++) prov_search_free(&results[k]);
    free(results);
    free(okv);
    providers_free(list, n);
    return rc;
}

int cmd_search(const cmd_opts *o, state_ctx *st)
{
    if (o->nargs < 2) return run_help("search"); // no query: styled help
    size_t qlen = 1;
    for (int k = 1; k < o->nargs; k++) qlen += strlen(o->args[k]) + 1;
    char *query = malloc(qlen);
    if (query == NULL) return 2;
    query[0] = '\0';
    for (int k = 1; k < o->nargs; k++) {
        if (k > 1) strncat(query, " ", qlen - strlen(query) - 1);
        strncat(query, o->args[k], qlen - strlen(query) - 1);
    }

    const char *pname = o->provider;
    if (pname != NULL) {
        while (isspace((unsigned char)*pname)) pname++;
        if (pname != NULL && *pname == '\0') pname = NULL;
    }
    if (pname == NULL) pname = st->search_all ? "all" : "github";

    if (strcasecmp(pname, "all") == 0) {
        int rc = search_all(o, st, query);
        free(query);
        return rc;
    }
    provider_entry *list = NULL;
    int n = providers_load(st, &list);
    provider_entry *e = providers_find(list, n, pname);
    int rc;
    if (e == NULL) {
        fprintf(stderr, "Provider '%s' is not configured.\n", pname);
        rc = 1;
    } else {
        rc = search_one(o, st, e, query);
    }
    providers_free(list, n);
    free(query);
    return rc;
}
