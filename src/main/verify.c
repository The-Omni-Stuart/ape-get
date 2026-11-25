#include "verify.h"
#include "path.h"
#include "proc.h"
#include "ui.h"

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
    return sig_run_gpg_home(NULL, sig_file, artifact, asset_name, out);
}

// home NULL uses gpg's default keyring (~/.gnupg); a home selects ape-get's
// own trust keyring. Both passes share the sanitised environment, the
// timeout and the status parsing.
sig_status sig_run_gpg_home(const char *home, const char *sig_file,
    const char *artifact, const char *asset_name, sig_result *out)
{
    memset(out, 0, sizeof(*out));
    out->asset_name = strdup(asset_name != NULL ? asset_name : "");
    const char *av[] = { "gpg", "--batch", "--status-fd=1",
        "--homedir", home, "--verify", sig_file, artifact, NULL };
    const char *av_default[] = { "gpg", "--batch", "--status-fd=1",
        "--verify", sig_file, artifact, NULL };
    const char *const *argv = home != NULL ? av : av_default;
    char **env = sanitized_env();
    if (env == NULL) {
        // fail closed: without the allowlist gpg must not inherit the
        // full environment (an OOM must not silently undo the isolation)
        out->status = S_ERROR;
        out->detail = strdup("cannot build the gpg environment");
        return out->status;
    }
    char *o = NULL;
    long rc = proc_run_env(argv, env, gpg_timeout_ms(), &o);
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

// --- ape-get's own trust keyring (a gpg --homedir) ---

// nth colon-separated field (0-based) of one line, as in --with-colons
// output (the primary fingerprint and the uid name both sit at 9).
static char *colon_nth(const char *line, size_t ll, int nth)
{
    int idx = 0;
    size_t a = 0;
    while (a <= ll) {
        size_t b = a;
        while (b < ll && line[b] != ':') b++;
        if (idx == nth) return dupn(line + a, b - a);
        a = b + 1;
        idx++;
    }
    return NULL;
}

static char *colon_line_field(const char *out, const char *prefix, int nth)
{
    size_t pl = strlen(prefix);
    const char *p = out;
    while (p != NULL) {
        const char *eol = strchr(p, '\n');
        size_t ll = eol != NULL ? (size_t)(eol - p) : strlen(p);
        if (ll >= pl && !memcmp(p, prefix, pl))
            return colon_nth(p, ll, nth);
        p = eol != NULL ? eol + 1 : NULL;
    }
    return NULL;
}

static long gpg_home_run(const char *const *argv, char **out)
{
    char **env = sanitized_env();
    if (env == NULL) return -1;
    long rc = proc_run_env(argv, env, gpg_timeout_ms(), out);
    free_env(env);
    return rc;
}

// Primary fingerprint and uid of the key inside key_file, read without
// touching any keyring (show-only import). True when a fingerprint is
// present; uid may be NULL when the key carries no user ID.
bool sig_key_fingerprint(const char *home, const char *key_file,
    char **fpr, char **uid, char **err)
{
    *fpr = NULL; *uid = NULL; *err = NULL;
    const char *av[] = { "gpg", "--batch", "--homedir", home,
        "--with-colons", "--import-options", "show-only", "--import",
        key_file, NULL };
    char *o = NULL;
    long rc = gpg_home_run(av, &o);
    if (rc < 0 && rc != -2) {
        free(o);
        *err = strdup("gpg is not installed");
        return false;
    }
    if (rc == -2) {
        free(o);
        *err = strdup("gpg timed out");
        return false;
    }
    if (o == NULL) o = strdup("");
    *fpr = colon_line_field(o, "fpr:", 9);
    *uid = colon_line_field(o, "uid:", 9);
    bool ok = *fpr != NULL && **fpr != '\0';
    if (!ok) {
        free(*fpr); free(*uid);
        *fpr = *uid = NULL;
        *err = strdup("no PGP public key found in the key asset");
    }
    free(o);
    return ok;
}

bool sig_keyring_import(const char *home, const char *key_file, char **err)
{
    *err = NULL;
    const char *av[] = { "gpg", "--batch", "--homedir", home, "--import",
        key_file, NULL };
    char *o = NULL;
    long rc = gpg_home_run(av, &o);
    free(o);
    if (rc == 0) return true;
    *err = strdup(rc == -2 ? "gpg timed out"
        : (rc < 0 && rc != -2) ? "gpg is not installed"
        : "gpg rejected the key asset");
    return false;
}

bool sig_keyring_delete(const char *home, const char *fpr, char **err)
{
    *err = NULL;
    const char *av[] = { "gpg", "--batch", "--yes", "--homedir", home,
        "--delete-keys", fpr, NULL };
    char *o = NULL;
    long rc = gpg_home_run(av, &o);
    free(o);
    if (rc == 0) return true;
    *err = strdup(rc == -2 ? "gpg timed out"
        : (rc < 0 && rc != -2) ? "gpg is not installed"
        : "gpg could not remove the key");
    return false;
}

// Primary fingerprint of the key holding signing_fpr in the home keyring
// (a signing subkey's fpr differs from its primary's; trust pins the
// primary so routine subkey rotation is not a signer change). home NULL
// is gpg's default keyring. False when the key is not in that keyring.
bool sig_key_primary(const char *home, const char *signing_fpr,
    char **primary, char **err)
{
    *primary = NULL; *err = NULL;
    const char *av[] = { "gpg", "--batch", "--homedir", home,
        "--with-colons", "--list-keys", signing_fpr, NULL };
    const char *av_default[] = { "gpg", "--batch", "--with-colons",
        "--list-keys", signing_fpr, NULL };
    char *o = NULL;
    long rc = gpg_home_run(home != NULL ? av : av_default, &o);
    if (rc != 0) {
        free(o);
        *err = strdup("signing key not in the keyring");
        return false;
    }
    // the first fpr line belongs to the pub entry: the primary key
    *primary = colon_line_field(o, "fpr:", 9);
    free(o);
    if (*primary == NULL || **primary == '\0') {
        free(*primary);
        *primary = NULL;
        *err = strdup("no primary fingerprint found");
        return false;
    }
    return true;
}

char *sig_message(const sig_result *r)
{
    // the asset name is provider-sourced: render it inert for display
    char nbuf[1024];
    ui_esc_into(nbuf, sizeof nbuf,
        r->asset_name != NULL ? r->asset_name : "");
    const char *name = nbuf;
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
