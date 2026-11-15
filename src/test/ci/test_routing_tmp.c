// Side-file download probe (dest == NULL) for provider_download routing:
// the buffered fetch is stubbed at its seam, so the mkstemp temp file's
// whole life is observable — it must land in TMPDIR during the request
// and be gone whenever the fetch failed, in TMPDIR and in /tmp alike.
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "http.h"
#include "provider.h"
#include "state.h"

static int fails;

#define CHECK(cond) do {                                        \
    if (!(cond)) {                                              \
        fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__,  \
            #cond);                                             \
        fails++;                                                \
    }                                                           \
} while (0)

static char tmpdir[256];
static bool seen_in_tmpdir, seen_in_etc_tmp;

#define CHECKSUM_BODY "deadbeef  tool.AppImage\n"

static int count_side_temps(const char *dir)
{
    DIR *d = opendir(dir);
    if (d == NULL) return -1;
    int n = 0;
    struct dirent *de;
    while ((de = readdir(d)) != NULL)
        if (strncmp(de->d_name, "ape-get-dl-", 11) == 0) n++;
    closedir(d);
    return n;
}

// belt and braces for repeated red/green runs: never leave probe debris
// behind, wherever an unfixed build happened to put it
static void purge_side_temps(const char *dir)
{
    DIR *d = opendir(dir);
    if (d == NULL) return;
    struct dirent *de;
    char path[512];
    while ((de = readdir(d)) != NULL) {
        if (strncmp(de->d_name, "ape-get-dl-", 11) != 0) continue;
        snprintf(path, sizeof path, "%s/%s", dir, de->d_name);
        remove(path);
    }
    closedir(d);
}

static http_resp stub_resp;   // served verbatim by the wrapped getter

static void set_stub_body(const char *text)
{
    free(stub_resp.body);
    stub_resp.body = strdup(text);   // the consumer frees it via http_resp_free
    stub_resp.len = strlen(text);
}

bool __wrap_http_get_hdr_prog(const char *url, const char *name,
                              const char *value, int timeout_ms,
                              http_prog cb, void *ud, http_resp *out,
                              char *errbuf, size_t errcap)
{
    (void)url; (void)name; (void)value; (void)timeout_ms;
    (void)cb; (void)ud; (void)errbuf; (void)errcap;
    seen_in_tmpdir = count_side_temps(tmpdir) > 0;
    seen_in_etc_tmp = count_side_temps("/tmp") > 0;
    *out = stub_resp;
    return true;
}

int main(void)
{
    snprintf(tmpdir, sizeof tmpdir, "/tmp/ape-get-route-probe-XXXXXX");
    CHECK(mkdtemp(tmpdir) != NULL);
    setenv("TMPDIR", tmpdir, 1);
    purge_side_temps("/tmp");   // start from a clean host state

    provider_entry e = {0};
    e.name = (char *)"probe";
    e.type = (char *)"gitea";
    e.url = (char *)"https://provider.test";
    e.api = (char *)"https://provider.test/api";

    // a 404 side-file fetch must fail without leaving a temp behind
    memset(&stub_resp, 0, sizeof stub_resp);
    stub_resp.status = 404;
    char msg[256] = "", *tmp_path = NULL;    bool ok = provider_download(&e,
        "https://provider.test/x.sha256", "x.sha256", 5000, 0,
        NULL, NULL, NULL, &tmp_path, msg, sizeof msg);
    CHECK(!ok && tmp_path == NULL);
    CHECK(strstr(msg, "not found") != NULL);

    // the fetch ran with the mkstemp file inside TMPDIR, not /tmp
    CHECK(seen_in_tmpdir);
    CHECK(!seen_in_etc_tmp);

    // and no failure path may leave ape-get-dl-* anywhere
    CHECK(count_side_temps(tmpdir) == 0);
    CHECK(count_side_temps("/tmp") == 0);
    purge_side_temps("/tmp");   // a red-phase leak must not poison later runs

    // empty body: same promise
    memset(&stub_resp, 0, sizeof stub_resp);
    stub_resp.status = 200;
    ok = provider_download(&e, "https://provider.test/x.sha256",
        "x.sha256", 5000, 0, NULL, NULL, NULL, &tmp_path, msg, sizeof msg);
    CHECK(!ok && strstr(msg, "empty") != NULL);
    CHECK(count_side_temps(tmpdir) == 0);

    // success still hands the caller an owned, filled file
    memset(&stub_resp, 0, sizeof stub_resp);
    stub_resp.status = 200;
    set_stub_body(CHECKSUM_BODY);
    ok = provider_download(&e, "https://provider.test/x.sha256",
        "x.sha256", 5000, 0, NULL, NULL, NULL, &tmp_path, msg, sizeof msg);
    CHECK(ok && tmp_path != NULL);
    if (ok && tmp_path != NULL) {
        CHECK(strncmp(tmp_path, tmpdir, strlen(tmpdir)) == 0);
        char got[64] = "";
        FILE *f = fopen(tmp_path, "rb");
        CHECK(f != NULL);
        if (f != NULL) {
            CHECK(fread(got, 1, sizeof got, f) == (size_t)strlen(CHECKSUM_BODY));
            fclose(f);
        }
        CHECK(!strcmp(got, CHECKSUM_BODY));
        remove(tmp_path);
        free(tmp_path);
    }
    // stub body ownership passed to the consumer's http_resp_free
    CHECK(count_side_temps(tmpdir) == 0);
    rmdir(tmpdir);

    if (fails) {
        fprintf(stderr, "routing_tmp: %d failed\n", fails);
        return 1;
    }
    printf("routing_tmp: all checks passed\n");
    return 0;
}
