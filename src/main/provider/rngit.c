#include "provider.h"
#include "provider/internal.h"
#include "path.h"
#include "proc.h"
#include <ctype.h>
#include <dirent.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

// rngit provider: shells out to the rngit CLI; proc_run is its only door
// out, no http edge. rngit runs never overlap (single-threaded binary).

// Timeout floors over config network_timeout_seconds: a view request gets
// at least 360 s, a fetch transfer at least 7200 s.
#define RNGIT_VIEW_MS 360000
#define RNGIT_FETCH_MS 7200000

static const char MISSING[] =
    "rngit is not installed; install RNS with 'pip install rns'.";

static void rm_tree(const char *path)
{
    struct stat sb;
    if (lstat(path, &sb) != 0) return;
    if (!S_ISDIR(sb.st_mode)) { unlink(path); return; }
    DIR *d = opendir(path);
    if (d != NULL) {
        struct dirent *de;
        while ((de = readdir(d)) != NULL) {
            if (strcmp(de->d_name, ".") == 0
                || strcmp(de->d_name, "..") == 0) continue;
            char *kid = path_join(path, de->d_name);
            if (kid != NULL) { rm_tree(kid); free(kid); }
        }
        closedir(d);
    }
    rmdir(path);
}

// Runs rngit in a fresh scratch cwd (restored before returning); the caller
// owns and rm_tree's *dir_out. Returns proc_run's code, -3 without a scratch dir.
static long rngit_run(const char *const argv[], int timeout_ms,
                      char **dir_out, char **out)
{
    char tmpl[64];
    snprintf(tmpl, sizeof tmpl, "/tmp/github-release-manager-XXXXXX");
    int saved = open(".", O_RDONLY);
    if (saved < 0 || mkdtemp(tmpl) == NULL) {
        if (saved >= 0) close(saved);
        return -3;
    }
    if (chdir(tmpl) != 0) { rmdir(tmpl); close(saved); return -3; }
    long rc = proc_run(argv, timeout_ms, out);
    if (fchdir(saved) != 0) (void)chdir("/");
    close(saved);
    *dir_out = strdup(tmpl);
    return rc;
}

static char *last_nonempty(const char *t)
{
    const char *start = NULL;
    size_t len = 0;
    const char *p = t;
    for (;;) {
        const char *e = strchr(p, '\n');
        size_t span = e != NULL ? (size_t)(e - p) : strlen(p);
        size_t a = 0, b = span;
        while (a < b && isspace((unsigned char)p[a])) a++;
        while (b > a && isspace((unsigned char)p[b - 1])) b--;
        if (b > a) { start = p + a; len = b - a; }
        if (e == NULL) break;
        p = e + 1;
    }
    return start != NULL ? strndup(start, len) : strdup("");
}

// Classified on lower-cased output; the rnsd hint rides only with a
// literal-case "Could not resolve path". "timed out" matches as substring (C-only).
static prov_reason rngit_classify(const char *raw, const char *action,
                                  const char *target, char *msg, size_t cap)
{
    char *low = strdup(raw != NULL ? raw : "");
    for (char *p = low; *p != '\0'; p++)
        *p = (char)tolower((unsigned char)*p);
    prov_reason why = PROV_OTHER;
    const char *hint = "";
    if (strstr(low, "could not resolve path") != NULL
        || strstr(low, "link establishment failed") != NULL
        || strstr(low, "could not recall") != NULL) {
        why = PROV_UNREACHABLE;
        if (raw != NULL && strstr(raw, "Could not resolve path") != NULL)
            hint = " (check the Reticulum interfaces or start rnsd)";
    } else if (strstr(low, "not found") != NULL
            || strstr(low, "no releases") != NULL) {
        why = PROV_NOT_FOUND;
    } else if (strstr(low, "timed out") != NULL) {
        why = PROV_TIMEOUT;
    }
    char *line = last_nonempty(raw != NULL ? raw : "");
    char cut[304];
    if (strlen(line) > 300) {
        memcpy(cut, line, 300);
        strcpy(cut + 300, "...");
    } else {
        strcpy(cut, line);
    }
    snprintf(msg, cap, "rngit could not %s %s: %s%s", action, target,
        cut, hint);
    free(low);
    free(line);
    return why;
}

bool provider_rngit_release(const provider_entry *e, const char *path,
                            const char *tag, bool beta, int timeout_ms,
                            prov_release *out)
{
    (void)beta; // rngit releases have no draft/prerelease concept
    if (!proc_have_executable("rngit")) {
        out->reason = PROV_UNREACHABLE;
        snprintf(out->message, sizeof(out->message), "%s", MISSING);
        return false;
    }
    char *url = path_fmt("%s/%s", e->url, path);
    if (url == NULL) return false;
    const char *which = tag != NULL && *tag != '\0' ? tag : "latest";
    const char *const argv[] = { "rngit", "release", url, "view", which,
        NULL };
    int ms = timeout_ms > RNGIT_VIEW_MS ? timeout_ms : RNGIT_VIEW_MS;
    char *dir = NULL, *raw = NULL;
    long rc = rngit_run(argv, ms, &dir, &raw);
    if (dir != NULL) { rm_tree(dir); free(dir); }
    if (rc == -3) {
        out->reason = PROV_OTHER;
        snprintf(out->message, sizeof(out->message),
            "rngit could not read %s: cannot create the scratch directory",
            url);
        free(url);
        return false;
    }
    if (rc == -1) {
        out->reason = PROV_UNREACHABLE;
        snprintf(out->message, sizeof(out->message), "%s", MISSING);
        free(url);
        free(raw);
        return false;
    }
    if (rc == -2) {
        out->reason = PROV_TIMEOUT;
        snprintf(out->message, sizeof(out->message),
            "rngit command timed out after %ds", ms / 1000);
        free(url);
        free(raw);
        return false;
    }
    char *vis = strip_ansi(raw != NULL ? raw : "");
    bool ok = rc == 0 && vis != NULL && parse_view(vis, path, out);
    free(vis);
    if (ok) {
        out->reason = PROV_OK;
        free(raw);
        free(url);
        return true;
    }
    out->reason = rngit_classify(raw, "read", url, out->message,
        sizeof(out->message));
    free(raw);
    free(url);
    return false;
}

bool provider_rngit_download(const provider_entry *e, const char *url,
                             const char *name, int timeout_ms,
                             const char *dest, char **tmp_path,
                             char *msg, size_t cap)
{
    (void)name; // the decoded opaque identity is authoritative
    if (!proc_have_executable("rngit")) {
        snprintf(msg, cap, "%s", MISSING);
        return false;
    }
    char *path = NULL, *tag = NULL, *aname = NULL;
    if (strncmp(url, "rngit://", 8) != 0
        || !opaque_decode(url, &path, &tag, &aname, msg, cap)) return false;
    char *relurl = path_fmt("%s/%s", e->url, path);
    char *spec = path_fmt("%s:%s", tag, aname);
    const char *const argv[] = { "rngit", "release", relurl, "fetch", spec,
        NULL };
    int ms = timeout_ms > RNGIT_FETCH_MS ? timeout_ms : RNGIT_FETCH_MS;
    char *dir = NULL, *raw = NULL;
    long rc = rngit_run(argv, ms, &dir, &raw);
    char *from = dir != NULL ? path_join(dir, aname) : NULL;
    bool ok = false;
    if (rc == -3) {
        snprintf(msg, cap, "rngit could not fetch %s: cannot create the "
            "scratch directory", spec);
    } else if (rc == -1) {
        snprintf(msg, cap, "%s", MISSING);
    } else if (rc == -2) {
        snprintf(msg, cap, "rngit command timed out after %ds", ms / 1000);
    } else if (rc != 0) {
        rngit_classify(raw, "fetch", spec, msg, cap);
    } else {
        struct stat sb;
        if (from == NULL || stat(from, &sb) != 0)
            snprintf(msg, cap,
                "rngit could not fetch %s: fetched file is missing", spec);
        else if (sb.st_size == 0)
            snprintf(msg, cap, "Downloaded file is empty: %s", aname);
        else {
            char tmp[600];
            if (dest != NULL) {
                if (snprintf(tmp, sizeof tmp, "%s", dest)
                        >= (int)sizeof tmp) {
                    snprintf(msg, cap, "staging path too long");
                    goto done;
                }
            } else {
                // mkstemp, not a PID-derived name: guessable names are
                // symlink-truncation bait on shared /tmp
                snprintf(tmp, sizeof tmp, "/tmp/ape-get-dl-XXXXXX");
                int tfd = mkstemp(tmp);
                if (tfd == -1) {
                    snprintf(msg, cap, "cannot write %s", tmp);
                    goto done;
                }
                close(tfd);
            }
            FILE *in = fopen(from, "rb"), *f = fopen(tmp, "wb");
            if (in == NULL || f == NULL) {
                if (in != NULL) fclose(in);
                if (f != NULL) { fclose(f); remove(tmp); }
                snprintf(msg, cap, "cannot write %s", tmp);
            } else {
                char buf[8192];
                size_t r;
                ok = true;
                while ((r = fread(buf, 1, sizeof buf, in)) > 0)
                    if (fwrite(buf, 1, r, f) != r) { ok = false; break; }
                fclose(in);
                if (fclose(f) != 0) ok = false;
                if (!ok) { remove(tmp); snprintf(msg, cap, "cannot write %s", tmp); }
                else *tmp_path = strdup(tmp);
            }
        }
    }
done:
    if (dir != NULL) { rm_tree(dir); free(dir); } // removed on every outcome
    free(raw);
    free(from);
    free(path);
    free(tag);
    free(aname);
    free(relurl);
    free(spec);
    return ok;
}
