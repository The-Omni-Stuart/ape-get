/*
 * test_release_pages.c — the release listing walks pages under one
 * publication policy (N13).
 *
 * http_get_hdr is wrapped to serve canned API listings, so the provider
 * adapters run for real. Cases: the hit lives past a full first page; the
 * page budget (5 x 20) ends the scan with its own message; a short page
 * stops the scan early; a 404 past page 1 is just the end of the listing;
 * beta accepts a prerelease, stable must not; a pinned draft tag is
 * rejected on every provider that reports the flag.
 */
#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "http.h"
#include "provider.h"
#include "provider/internal.h"
#include "state.h"

static int ncalls;
static char last_url[1024];
static char *(*g_gen)(int page, const char *url);

bool __wrap_http_get_hdr(const char *url, const char *name, const char *value,
                         int timeout_ms, http_resp *out, char *errbuf,
                         size_t errcap)
{
    int page = 1;
    const char *p;

    (void)name; (void)value; (void)timeout_ms; (void)errbuf; (void)errcap;
    ncalls++;
    snprintf(last_url, sizeof(last_url), "%s", url);
    p = strstr(url, "&page=");
    if (p != NULL)
        page = atoi(p + 6);

    memset(out, 0, sizeof(*out));
    char *body = g_gen != NULL ? g_gen(page, url) : NULL;
    if (body == NULL) {
        out->status = 404;
        return true;
    }
    out->status = 200;
    out->body = body;
    out->len = strlen(body);
    return true;
}

/* ---- canned listings ---- */

static char *gh_obj(const char *tag, int draft, int pre)
{
    char buf[512];
    snprintf(buf, sizeof(buf),
        "{\"tag_name\":\"%s\",\"draft\":%s,\"prerelease\":%s,"
        "\"assets\":[{\"name\":\"tool.AppImage\","
        "\"browser_download_url\":\"https://dl.test/tool.AppImage\"}]}",
        tag, draft ? "true" : "false", pre ? "true" : "false");
    return strdup(buf);
}

/* array of n copies of gh_obj, comma-joined */
static char *gh_array(int n, const char *tag, int draft, int pre)
{
    char *all = calloc(1, (size_t)n * 400 + 2);
    if (all == NULL)
        return NULL;
    strcat(all, "[");
    for (int k = 0; k < n; k++) {
        char *o = gh_obj(tag, draft, pre);
        if (k > 0)
            strcat(all, ",");
        strcat(all, o);
        free(o);
    }
    strcat(all, "]");
    return all;
}

static char *gl_obj(const char *tag, int upcoming)
{
    char buf[512];
    snprintf(buf, sizeof(buf),
        "{\"tag_name\":\"%s\",\"upcoming_release\":%s,"
        "\"assets\":{\"links\":[{\"name\":\"tool.deb\","
        "\"url\":\"https://dl.test/tool.deb\"}]}}",
        tag, upcoming ? "true" : "false");
    return strdup(buf);
}

static char *gl_page2(void)
{
    char *all = calloc(1, 600);
    if (all == NULL)
        return NULL;
    strcat(all, "[");
    char *o = gl_obj("5.5.5", 0);
    strcat(all, o);
    free(o);
    strcat(all, "]");
    return all;
}

static char *gen_drafts_then_pub(int page, const char *url)
{
    (void)url;
    if (page == 1)
        return gh_array(PROV_PAGE_SIZE, "d", 1, 0);
    if (page == 2)
        return gh_array(1, "1.2.3", 0, 0);
    return NULL;
}

static char *gen_all_drafts(int page, const char *url)
{
    (void)url;
    if (page <= PROV_PAGE_BUDGET)
        return gh_array(PROV_PAGE_SIZE, "d", 1, 0);
    return NULL;
}

static char *gen_short_drafts(int page, const char *url)
{
    (void)url;
    if (page == 1)
        return gh_array(3, "d", 1, 0);
    return NULL;
}

static char *gen_page2_404(int page, const char *url)
{
    (void)url;
    if (page == 1)
        return gh_array(PROV_PAGE_SIZE, "d", 1, 0);
    return NULL;   /* server says the listing ended after page 1 */
}

static char *gen_pre_mixed(int page, const char *url)
{
    (void)url;
    if (page == 1) {
        char *all = calloc(1, 1200);
        if (all == NULL)
            return NULL;
        char *a = gh_obj("3.0.0-rc1", 0, 1);
        char *b = gh_obj("2.9.0", 0, 0);
        strcat(all, "[");
        strcat(all, a);
        strcat(all, ",");
        strcat(all, b);
        strcat(all, "]");
        free(a);
        free(b);
        return all;
    }
    return NULL;
}

static char *gen_tag_draft(int page, const char *url)
{
    (void)page; (void)url;
    return gh_obj("v1.0.0", 1, 0);   /* single object, not an array */
}

static char *gen_upcoming_then_pub(int page, const char *url)
{
    (void)url;
    if (page == 1) {
        char *all = calloc(1, (size_t)PROV_PAGE_SIZE * 300 + 2);
        if (all == NULL)
            return NULL;
        strcat(all, "[");
        for (int k = 0; k < PROV_PAGE_SIZE; k++) {
            char *o = gl_obj("u", 1);
            if (k > 0)
                strcat(all, ",");
            strcat(all, o);
            free(o);
        }
        strcat(all, "]");
        return all;
    }
    if (page == 2)
        return gl_page2();
    return NULL;
}

/* ---- assertions ---- */

static int bad;

static void expect(int cond, const char *what, const char *got)
{
    if (!cond) {
        fprintf(stderr, "  %s: %s\n", what, got != NULL ? got : "false");
        bad = 1;
    }
}

static provider_entry entry(const char *type, const char *api)
{
    provider_entry e;
    e.name = strdup("t");
    e.type = strdup(type);
    e.url = strdup("https://t.test");
    e.api = strdup(api);
    e.token_env = NULL;
    e.built_in = false;
    return e;
}

int main(void)
{
    prov_release r;
    char m[512];

    /* 1. Gitea: published release past a full page of drafts */
    {
        provider_entry e = entry("gitea", "https://gitea.test/api/v1");
        ncalls = 0;
        g_gen = gen_drafts_then_pub;
        memset(&r, 0, sizeof(r));
        bool ok = provider_gitea_release(&e, "me/tool", NULL, false, 1000, &r);
        snprintf(m, sizeof(m), "ncalls=%d", ncalls);
        expect(ok, "release past page 1 not found", r.message);
        expect(ncalls == 2, "wrong fetch count for 2-page scan", m);
        expect(strstr(last_url, "page=2") != NULL,
            "second fetch did not ask for page 2", last_url);
        expect(ok && r.version != NULL && strcmp(r.version, "1.2.3") == 0,
            "wrong version found", r.version);
        prov_release_free(&r);
        free(e.name); free(e.type); free(e.url); free(e.api);
    }

    /* 2. page budget: 5 full pages of drafts end the scan, distinctly */
    {
        provider_entry e = entry("gitea", "https://gitea.test/api/v1");
        ncalls = 0;
        g_gen = gen_all_drafts;
        memset(&r, 0, sizeof(r));
        bool ok = provider_gitea_release(&e, "me/tool", NULL, false, 1000, &r);
        snprintf(m, sizeof(m), "ncalls=%d", ncalls);
        expect(!ok, "all-drafts listing must fail", NULL);
        expect(ncalls == PROV_PAGE_BUDGET, "budget not 5 fetches", m);
        expect(strstr(r.message, "first 100 releases") != NULL,
            "budget message missing", r.message);
        prov_release_free(&r);
        free(e.name); free(e.type); free(e.url); free(e.api);
    }

    /* 3. a short page ends the scan: one fetch, plain message */
    {
        provider_entry e = entry("gitea", "https://gitea.test/api/v1");
        ncalls = 0;
        g_gen = gen_short_drafts;
        memset(&r, 0, sizeof(r));
        bool ok = provider_gitea_release(&e, "me/tool", NULL, false, 1000, &r);
        snprintf(m, sizeof(m), "ncalls=%d", ncalls);
        expect(!ok, "drafts-only listing must fail", NULL);
        expect(ncalls == 1, "short page triggered extra fetch", m);
        expect(ok || strcmp(r.message, "no published release") == 0,
            "unexpected message", r.message);
        prov_release_free(&r);
        free(e.name); free(e.type); free(e.url); free(e.api);
    }

    /* 4. a 404 past page 1 ends the scan like a short page */
    {
        provider_entry e = entry("gitea", "https://gitea.test/api/v1");
        ncalls = 0;
        g_gen = gen_page2_404;
        memset(&r, 0, sizeof(r));
        bool ok = provider_gitea_release(&e, "me/tool", NULL, false, 1000, &r);
        snprintf(m, sizeof(m), "ncalls=%d", ncalls);
        expect(!ok, "drafts-only listing must fail", NULL);
        expect(ncalls == 2, "404 past page 1 not treated as listing end", m);
        expect(ok || strcmp(r.message, "no published release") == 0,
            "404 page 2 reported as an error", r.message);
        prov_release_free(&r);
        free(e.name); free(e.type); free(e.url); free(e.api);
    }

    /* 5. GitHub beta: prerelease on page 2 is eligible */
    {
        provider_entry e = entry("github", "https://api.github.test");
        ncalls = 0;
        g_gen = gen_drafts_then_pub;   /* page 2: published stable works too */
        memset(&r, 0, sizeof(r));
        bool ok = provider_github_release(&e, "me/tool", NULL, true, 1000, &r);
        expect(ok, "beta scan did not reach page 2", r.message);
        prov_release_free(&r);

        ncalls = 0;
        g_gen = gen_pre_mixed;
        memset(&r, 0, sizeof(r));
        ok = provider_github_release(&e, "me/tool", NULL, true, 1000, &r);
        expect(ok && r.version != NULL
            && strcmp(r.version, "3.0.0-rc1") == 0,
            "beta did not take the prerelease", r.version);
        prov_release_free(&r);
        free(e.name); free(e.type); free(e.url); free(e.api);
    }

    /* 6. stable must skip the prerelease and take the published one */
    {
        provider_entry e = entry("gitea", "https://gitea.test/api/v1");
        ncalls = 0;
        g_gen = gen_pre_mixed;
        memset(&r, 0, sizeof(r));
        bool ok = provider_gitea_release(&e, "me/tool", NULL, false, 1000, &r);
        expect(ok && r.version != NULL && strcmp(r.version, "2.9.0") == 0,
            "stable took a prerelease", r.version);
        prov_release_free(&r);
        free(e.name); free(e.type); free(e.url); free(e.api);
    }

    /* 7. pinned draft tag is rejected, not installed */
    {
        provider_entry e = entry("github", "https://api.github.test");
        ncalls = 0;
        g_gen = gen_tag_draft;
        memset(&r, 0, sizeof(r));
        bool ok = provider_github_release(&e, "me/tool", "v1.0.0", false,
                                          1000, &r);
        expect(!ok, "pinned draft release accepted", NULL);
        expect(ok || strstr(r.message, "is a draft") != NULL,
            "pinned draft message missing", r.message);
        prov_release_free(&r);

        memset(&r, 0, sizeof(r));
        g_gen = gen_tag_draft;
        ok = provider_gitea_release(&e, "me/tool", "v1.0.0", false, 1000, &r);
        expect(!ok, "pinned draft (gitea) accepted", NULL);
        prov_release_free(&r);
        free(e.name); free(e.type); free(e.url); free(e.api);
    }

    /* 8. GitLab: scheduled releases skipped, published one found */
    {
        provider_entry e = entry("gitlab", "https://gitlab.test/api/v4");
        ncalls = 0;
        g_gen = gen_upcoming_then_pub;
        memset(&r, 0, sizeof(r));
        bool ok = provider_gitlab_release(&e, "grp%2Ftool", NULL, false,
                                          1000, &r);
        expect(ok, "GitLab scan did not reach page 2", r.message);
        expect(ok && r.version != NULL && strcmp(r.version, "5.5.5") == 0,
            "GitLab took a scheduled release", r.version);
        prov_release_free(&r);
        free(e.name); free(e.type); free(e.url); free(e.api);
    }

    if (bad)
        fprintf(stderr, "FAIL\n");
    else
        printf("PASS\n");
    return bad ? 1 : 0;
}
