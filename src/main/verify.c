#include "verify.h"
#include "path.h"
#include "proc.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// gpg timeout is 60 s; the env override is a scenario-suite hook only
#define GPG_TIMEOUT_MS 60000
static int gpg_timeout_ms(void)
{
    const char *v = getenv("APEGET_TEST_GPG_TIMEOUT_MS");
    if (v != NULL && *v != '\0') {
        long x = strtol(v, NULL, 10);
        if (x > 0) return (int)x;
    }
    return GPG_TIMEOUT_MS;
}

const char *sig_status_str(sig_status s)
{
    switch (s) {
    case S_VERIFIED: return "verified";
    case S_MISSING: return "missing";
    case S_UNAVAILABLE: return "unavailable";
    case S_INVALID: return "invalid";
    default: return "error";
    }
}

void sig_result_free(sig_result *r)
{
    if (r == NULL) return;
    free(r->asset_name);
    free(r->mechanism);
    free(r->signer);
    free(r->identity);
    free(r->detail);
    r->asset_name = r->mechanism = r->signer = r->identity
        = r->detail = NULL;
}

static char *dupn(const char *s, size_t n)
{
    char *r = malloc(n + 1);
    if (r == NULL) return NULL;
    memcpy(r, s, n);
    r[n] = '\0';
    return r;
}

// first line starting with "[GNUPG:] <key>": first whitespace token of the
// trimmed remainder
static char *status_value(const char *out, const char *key)
{
    char pre[64];
    if (snprintf(pre, sizeof(pre), "[GNUPG:] %s", key)
            >= (int)sizeof(pre))
        return NULL;
    size_t pl = strlen(pre);
    const char *p = out;
    while (p != NULL) {
        const char *eol = strchr(p, '\n');
        size_t ll = eol != NULL ? (size_t)(eol - p) : strlen(p);
        if (ll >= pl && !memcmp(p, pre, pl)) {
            const char *v = p + pl, *ve = p + ll;
            while (v < ve && (*v == ' ' || *v == '\t' || *v == '\r')) v++;
            if (v >= ve) return NULL; // empty status value is null
            const char *sp = v;
            while (sp < ve && *sp != ' ' && *sp != '\t' && *sp != '\r')
                sp++;
            return dupn(v, (size_t)(sp - v));
        }
        p = eol != NULL ? eol + 1 : NULL;
    }
    return NULL;
}

// signer: GOODSIG line's text after the first space (a space with no
// further text yields the empty string)
static char *status_signer(const char *out)
{
    static const char pre[] = "[GNUPG:] GOODSIG";
    const char *p = out;
    while (p != NULL) {
        const char *eol = strchr(p, '\n');
        size_t ll = eol != NULL ? (size_t)(eol - p) : strlen(p);
        if (ll >= sizeof(pre) - 1 && !memcmp(p, pre, sizeof(pre) - 1)) {
            const char *end = p + ll, *sp = NULL;
            for (const char *q = p + (sizeof(pre) - 1); q < end; q++)
                if (*q == ' ') { sp = q; break; }
            if (sp == NULL) return NULL;
            sp++; // skip the keyid, then take the rest of the line
            const char *sp2 = NULL;
            for (const char *q = sp; q < end; q++)
                if (*q == ' ') { sp2 = q; break; }
            return sp2 != NULL ? dupn(sp2 + 1, (size_t)(end - sp2 - 1))
                               : dupn("", 0);
        }
        p = eol != NULL ? eol + 1 : NULL;
    }
    return NULL;
}

// failure detail: BAD signature / public key not found / else the last
// non-blank line, truncated to 200 chars
static char *failure_detail(const char *out)
{
    const char *p = out, *last = NULL;
    size_t lastn = 0;
    while (p != NULL) {
        const char *eol = strchr(p, '\n');
        size_t ll = eol != NULL ? (size_t)(eol - p) : strlen(p);
        bool blank = true;
        for (const char *q = p; q < p + ll; q++)
            if (*q != ' ' && *q != '\t' && *q != '\r') { blank = false; break; }
        if (!blank) {
            char *line = dupn(p, ll);
            if (line != NULL) {
                bool bad = strstr(line, "BAD signature") != NULL;
                bool nopub = strstr(line, "NO_PUBKEY") != NULL;
                free(line);
                if (bad) return strdup("BAD signature");
                if (nopub) return strdup("public key not found");
            }
            last = p;
            lastn = ll;
        }
        p = eol != NULL ? eol + 1 : NULL;
    }
    if (last == NULL) return strdup("gpg rejected the signature");
    size_t n = lastn > 200 ? 200 : lastn;
    return dupn(last, n);
}

// the environment allowlist handed to gpg
static char **sanitized_env(void)
{
    static const char *keep[] = {"PATH", "HOME", "TMPDIR", NULL};
    char **e = calloc(4, sizeof(*e));
    if (e == NULL) return NULL;
    size_t n = 0;
    for (int k = 0; keep[k] != NULL; k++) {
        const char *v = getenv(keep[k]);
        if (v == NULL) continue;
        char *pair = path_fmt("%s=%s", keep[k], v);
        if (pair == NULL) continue;
        e[n++] = pair;
    }
    e[n] = NULL;
    return e;
}

static void free_env(char **e)
{
    for (char **p = e; *p != NULL; p++) free(*p);
    free(e);
}

sig_status sig_run_gpg(const char *sig_file, const char *artifact,
    const char *asset_name, sig_result *out)
{
    memset(out, 0, sizeof(*out));
    out->asset_name = strdup(asset_name != NULL ? asset_name : "");
    const char *av[] = { "gpg", "--batch", "--status-fd=1", "--verify",
        sig_file, artifact, NULL };
    char **env = sanitized_env();
    char *o = NULL;
    long rc = proc_run_env(av, env, gpg_timeout_ms(), &o);
    if (env != NULL) free_env(env);
    if (rc < 0 && rc != -2) {
        free(o);
        out->status = S_UNAVAILABLE;
        out->detail = strdup("gpg is not installed");
        return out->status;
    }
    if (rc == -2) {
        free(o);
        out->status = S_ERROR;
        out->detail = strdup("gpg timed out");
        return out->status;
    }
    if (o == NULL) o = strdup("");
    if (rc != 0) {
        out->status = S_INVALID;
        out->mechanism = strdup("gpg");
        out->detail = failure_detail(o);
        free(o);
        return out->status;
    }
    char *fp = status_value(o, "VALIDSIG");
    if (fp == NULL) {
        free(o);
        out->status = S_INVALID;
        out->mechanism = strdup("gpg");
        out->detail = strdup("gpg reported no valid signature");
        return out->status;
    }
    out->status = S_VERIFIED;
    out->mechanism = strdup("gpg");
    out->signer = status_signer(o);
    out->identity = fp;
    free(o);
    return out->status;
}

char *sig_message(const sig_result *r)
{
    const char *name = r->asset_name != NULL ? r->asset_name : "";
    switch (r->status) {
    case S_VERIFIED: {
        char sb[1024] = "", ib[1024] = "";
        if (r->signer != NULL)
            snprintf(sb, sizeof(sb), " by %s", r->signer);
        if (r->identity != NULL)
            snprintf(ib, sizeof(ib), " (%s)", r->identity);
        return path_fmt("Signature verified for %s%s%s.", name, sb, ib);
    }
    case S_MISSING:
        return path_fmt("No signature found for %s.", name);
    default:
        // base + ": " + detail + "."; base + "." when no detail
        break;
    }
    const char *base = r->status == S_INVALID
        ? "Signature verification failed"
        : r->status == S_UNAVAILABLE
            ? "Signature verification is unavailable"
            : "Signature verification error";
    if (r->detail == NULL) return path_fmt("%s for %s.", base, name);
    return path_fmt("%s for %s: %s.", base, name, r->detail);
}

bool sig_allows_install(const sig_result *r, bool required)
{
    switch (r->status) {
    case S_VERIFIED: return true;
    case S_MISSING:
    case S_UNAVAILABLE: return !required;
    default: return false;
    }
}
