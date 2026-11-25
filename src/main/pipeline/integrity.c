// Verification of the staged download: sha256 checksum candidates,
// then gpg signatures and the signer pin.

#include "pipeline/internal.h"

#include "crypto.h"
#include "fs.h"
#include "manifest.h"
#include "path.h"
#include "pipeline/checksums.h"
#include "prompt.h"
#include "provider.h"
#include "state.h"
#include "ui.h"
#include "verify.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <unistd.h>

int phase_checksum(pipe_ctx *pc)
{
    // exact <target>{.sha256,.sha256sum,.sha256.txt,.digest} candidates
    // first, then the aggregate names
    pc->vr.status = V_MISSING;
    int cand[64];
    int cn = 0;
    size_t al = strlen(pc->base);
    static const char *xext[] = {".sha256", ".sha256sum", ".sha256.txt",
        ".digest", NULL};
    static const char *agg[] = {"sha256sums", "sha256sums.txt",
        "sha256sums.sha256", "sha256sum.txt", "checksums.txt",
        "checksums.sha256", "checksum.txt", "sha256.txt", NULL};
    for (int k = 0; k < pc->rel.n && cn < 64; k++)
        for (int e = 0; xext[e] != NULL; e++) {
            size_t xl = strlen(xext[e]);
            if (strlen(pc->rel.assets[k].name) == al + xl
                && !strncasecmp(pc->rel.assets[k].name, pc->base, al)
                && !strcasecmp(pc->rel.assets[k].name + al, xext[e])) {
                cand[cn++] = k;
                break;
            }
        }
    for (int k = 0; k < pc->rel.n && cn < 64; k++)
        for (int a = 0; agg[a] != NULL; a++)
            if (!strcasecmp(pc->rel.assets[k].name, agg[a])) {
                bool dup = false;
                for (int i = 0; i < cn; i++)
                    dup = dup || cand[i] == k;
                if (!dup) cand[cn++] = k;
                break;
            }

    bool saw_entries = false;
    for (int c = 0; c < cn; c++) {
        int k = cand[c];
        free(pc->vr.checksum_asset);
        pc->vr.checksum_asset = strdup(pc->rel.assets[k].name);
        char *ctmp = NULL;
        char cmsg[700] = "";
        if (!provider_download(pc->pe, pc->rel.assets[k].url,
                pc->rel.assets[k].name, pc->timeout_ms, 0, NULL, NULL, NULL,
                &ctmp, cmsg, sizeof(cmsg))) {
            char esafe[2800];   // provider error text is remote bytes
            ui_esc_into(esafe, sizeof esafe, cmsg);
            pc->detail = path_fmt("Checksum verification failed: %s", esafe);
            ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "checksum failed");
            oplog_append(pc->st, pc->op, pc->ref, "-",
                pc->detail != NULL ? pc->detail : "checksum failed");
            free(pc->detail);
            pc->detail = NULL;
            remove(pc->tmp);
            free(pc->tmp);
            pc->tmp = NULL;
    return PIPE_DONE;
        }
        struct stat sb;
        if (stat(ctmp, &sb) == 0 && sb.st_size > 1048576) {
            pc->vr.status = V_INVALID;
            remove(ctmp);
            free(ctmp);
            break;
        }
        size_t cl = 0;
        char *content = fs_read_file(ctmp, &cl);
        remove(ctmp);
        free(ctmp);
        if (content == NULL) continue;
        bool has = false, amb = false;
        char *sha = NULL;
        checksum_lookup(content, pc->base, &has, &amb, &sha);
        free(content);
        if (!has) { free(sha); continue; }
        saw_entries = true;
        if (amb) {
            free(sha);
            pc->vr.status = V_INVALID;
            break;
        }
        if (sha == NULL) continue;
        char actual[65];
        if (!crypto_sha256_file(pc->tmp, actual)) {
            free(sha);
            pc->vr.status = V_INVALID;
            break;
        }
        pc->vr.expected = sha;
        pc->vr.actual = strdup(actual);
        pc->vr.status = strcasecmp(sha, actual) == 0 ? V_VERIFIED
                                                 : V_MISMATCH;
        break;
    }
    if (cn > 0 && pc->vr.status == V_MISSING)
        pc->vr.status = saw_entries ? V_UNKNOWN_ASSET : V_INVALID;
    return PIPE_GO;
}

// --- TOFU signing-key trust ---
// gpg reported "public key not found": the user's ~/.gnupg was consulted
// first (the default-keyring pass) and always wins; next comes ape-get's
// own keyring, then a key asset published by the same TLS-authenticated
// release. A present-but-unverifiable signature never reaches here.

static bool unknown_key(const sig_result *r)
{
    return r->status == S_INVALID && r->detail != NULL
        && strcmp(r->detail, "public key not found") == 0;
}

// Trust is pinned to the primary fingerprint: a signature made by a
// signing subkey reports the subkey's fingerprint, which differs from
// the primary's and changes on routine subkey rotation. Falls back to
// the signing fingerprint when the keyring cannot name the primary.
static char *primary_of(const char *home, const char *fpr)
{
    if (fpr == NULL) return NULL;
    char *prim = NULL, *err = NULL;
    if (sig_key_primary(home, fpr, &prim, &err)) return prim;
    free(prim);
    free(err);
    return strdup(fpr);
}

// The first release asset that sniffs (not just names) as a PGP public key
// block, staged in a temp file; the payload's own signature is excluded.
static char *fetch_key_asset(pipe_ctx *pc, int sigk)
{
    for (int k = 0; k < pc->rel.n; k++) {
        if (k == sigk) continue;
        if (!ci_endswith(pc->rel.assets[k].name, ".asc")) continue;
        char *kt = NULL;
        char kmsg[700] = "";
        if (!provider_download(pc->pe, pc->rel.assets[k].url,
                pc->rel.assets[k].name, pc->timeout_ms, 65536, NULL,
                NULL, NULL, &kt, kmsg, sizeof(kmsg))) {
            free(kt);
            continue;
        }
        size_t kn = 0;
        char *kc = fs_read_file(kt, &kn);
        bool key = kc != NULL
            && strstr(kc, "-----BEGIN PGP PUBLIC KEY BLOCK-----") != NULL;
        free(kc);
        if (key) return kt;
        remove(kt);
        free(kt);
    }
    return NULL;
}

// A different key on an already-trusted repo is the dangerous event:
// standard confirms (as the signer pin does, --allow-signer-change as the
// ticket), hardened refuses unless the run explicitly carries that flag.
static bool gate_signer_change(pipe_ctx *pc, const char *old_fpr,
    const char *new_fpr, const char *new_uid)
{
    if (pc->st->signer_change == 1 && !pc->o->allow_signer_change) {
        char *d = path_fmt("Unable to %s %s.", pc->op, pc->ref);
        ui_msg(stderr, UI_ERROR, "%s", d != NULL ? d : "unable");
        oplog_append(pc->st, pc->op, pc->ref, "-",
            d != NULL ? d : "unable");
        free(d);
        d = path_fmt("Reason: the release now publishes signing key %s,"
            " but %s is trusted; the policy refuses signer changes.",
            new_fpr, old_fpr);
        ui_msg(stderr, UI_DIM, "%s", d != NULL ? d : "reason");
        free(d);
        ui_msg(stderr, UI_ITALIC, "If you trust the new signer, rerun with"
            " --allow-signer-change.");
        return false;
    }
    char *newd = new_uid != NULL && *new_uid != '\0'
        ? path_fmt("%s (%s)", new_uid, new_fpr) : strdup(new_fpr);
    char *reason = path_fmt("the release now publishes signing key %s,"
        " but %s is trusted", new_fpr, old_fpr);
    char *q = path_fmt("Accept the new signing key for %s?\n"
        "  trusted: %s\n  new:     %s\nOnly continue if you trust the new"
        " signer.", pc->ref, old_fpr, newd != NULL ? newd : new_fpr);
    char *w = path_fmt("Warning: trusted signing key replaced for %s: %s.",
        pc->ref, new_fpr);
    bool ok = resolve_pin(pc->o, pc->st, pc->op, pc->ref, pc->ref, reason,
        "If you trust the new signer, rerun with --allow-signer-change to"
        " accept it and update the trust.", q, w);
    free(reason);
    free(q);
    free(w);
    free(newd);
    return ok;
}

// First contact is cheap to trust: interactive confirms the full
// fingerprint (flatpak remote-add style); non-interactive (-y or no tty)
// imports with a loud notice naming the revoke command.
static bool gate_tofu(pipe_ctx *pc, const char *fpr, const char *uid,
    const char *keyring)
{
    char ubuf[512];
    ui_esc_into(ubuf, sizeof ubuf, uid != NULL ? uid : "unknown signer");
    if (!pc->o->assume_yes && isatty(fileno(stdin))) {
        char *q = path_fmt("Trust this signing key for %s?\n"
            "  repo:  %s\n  key:   %s\n  fingerprint: %s\n"
            "Only continue if you recognise this key.",
            pc->ref, pc->ref, ubuf, fpr);
        bool yes = confirm_action(pc->o, q != NULL ? q : "", false);
        free(q);
        if (!yes) {
            ui_msg(stderr, UI_WARN, "Key trust declined.");
            return false;
        }
        return true;
    }
    ui_msg(stderr, UI_WARN, "Trusting new signing key for %s"
        " (trust on first use).", pc->ref);
    ui_msg(stderr, UI_NONE, "  key:   %s", ubuf);
    ui_msg(stderr, UI_NONE, "  fingerprint: %s", fpr);
    ui_msg(stderr, UI_NONE, "  stored: %s", keyring);
    ui_msg(stderr, UI_ITALIC, "  revoke: ape-get untrust %s", pc->ref);
    return true;
}

// The unknown-key path for the staged signature in stmp; fills pc->sres
// with the final verdict and sets pc->failed when the run must stop.
static void verify_trust_path(pipe_ctx *pc, const char *stmp, int sigk)
{
    char *kr = state_keyring_dir(pc->st);
    if (kr == NULL) return;
    if (!fs_mkdir_p(kr)) { free(kr); return; } // gpg needs the home to exist
    char *pub = path_join(kr, "pubring.kbx");
    bool have_ring = pub != NULL && fs_is_regular(pub);
    free(pub);
    if (have_ring) {
        sig_result r2;
        sig_status st2 = sig_run_gpg_home(kr, stmp, pc->tmp, pc->base, &r2);
        if (st2 == S_VERIFIED) {
            sig_result_free(&pc->sres);
            pc->sres = r2;
            char *prim = primary_of(kr, pc->sres.identity);
            char *rec = NULL, *ru = NULL;
            trust_lookup(pc->st, pc->prov, pc->path, &rec, &ru);
            if (rec == NULL) {
                trust_record(pc->st, pc->prov, pc->path,
                    prim != NULL ? prim : pc->sres.identity,
                    pc->sres.signer);
            } else if (prim != NULL && strcasecmp(rec, prim) != 0) {
                if (gate_signer_change(pc, rec, prim, pc->sres.signer)) {
                    trust_record(pc->st, pc->prov, pc->path, prim,
                        pc->sres.signer);
                    pc->signer_accepted = true;
                } else {
                    pc->failed = true;
                }
            }
            free(rec);
            free(ru);
            free(prim);
            free(kr);
            return;
        }
        if (!unknown_key(&r2)) {
            sig_result_free(&pc->sres);
            pc->sres = r2; // a real failure against our keyring too
            free(kr);
            return;
        }
        sig_result_free(&r2);
    }
    char *kt = fetch_key_asset(pc, sigk);
    if (kt == NULL) { free(kr); return; } // stays the unknown-key refusal
    char *fpr = NULL, *uid = NULL, *kerr = NULL;
    if (!sig_key_fingerprint(kr, kt, &fpr, &uid, &kerr)) {
        char esafe[1024];
        ui_esc_into(esafe, sizeof esafe,
            kerr != NULL ? kerr : "key asset unusable");
        ui_msg(stderr, UI_WARN, "Key asset unusable: %s", esafe);
        free(kerr);
        free(kt);
        free(kr);
        return;
    }
    char *rec = NULL, *ru = NULL;
    trust_lookup(pc->st, pc->prov, pc->path, &rec, &ru);
    bool rotation = rec != NULL && strcasecmp(rec, fpr) != 0;
    bool proceed = true;
    if (rotation) {
        proceed = gate_signer_change(pc, rec, fpr, uid);
        if (!proceed) pc->failed = true; // the gate already reported it
    } else if (rec == NULL) {
        proceed = gate_tofu(pc, fpr, uid, kr);
        if (!proceed) pc->failed = true;
    }
    // same fingerprint recorded: silent (the keyring may have been wiped)
    free(rec);
    free(ru);
    if (proceed) {
        free(kerr);
        kerr = NULL;
        if (!sig_keyring_import(kr, kt, &kerr)) {
            char esafe[1024];
            ui_esc_into(esafe, sizeof esafe,
                kerr != NULL ? kerr : "import failed");
            ui_msg(stderr, UI_ERROR,
                "Failed to import the signing key: %s", esafe);
        } else {
            if (rotation) pc->signer_accepted = true;
            sig_result_free(&pc->sres);
            sig_run_gpg_home(kr, stmp, pc->tmp, pc->base, &pc->sres);
            // the asset's own fingerprint is its primary: record it
            // whatever signing key the payload turned out to carry
            trust_record(pc->st, pc->prov, pc->path, fpr, uid);
        }
    }
    free(kerr);
    free(fpr);
    free(uid);
    free(kt);
    free(kr);
}

int phase_verify(pipe_ctx *pc)
{
    bool strict = pc->o->strict != 0 || pc->st->strict_verification;
    const char *base = pc->vr.status == V_MISSING
        ? "No checksum found for "
        : pc->vr.status == V_UNKNOWN_ASSET
            ? "Checksum file does not contain "
            : pc->vr.status == V_INVALID ? "Checksum file is invalid for "
                                     : "";
    bool allowed = pc->vr.status == V_VERIFIED || !strict;
    char safe_base[2048];   // pc->base is a provider asset name
    ui_esc_into(safe_base, sizeof safe_base, pc->base);
    if (pc->vr.status == V_MISMATCH) {
        pc->detail = path_fmt(
            "Checksum mismatch for %s: expected %s, got %s."
            " Refusing to install.", safe_base, pc->vr.expected,
            pc->vr.actual);
        ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "mismatch");
        oplog_append(pc->st, pc->op, pc->ref, "-",
            pc->detail != NULL ? pc->detail : "checksum mismatch");
        free(pc->detail);
        pc->detail = NULL;
        pc->failed = true;
    } else if (!allowed) {
        pc->detail = path_fmt("%s%s. Refusing to install (strict mode).",
            base, safe_base);
        ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "refused");
        oplog_append(pc->st, pc->op, pc->ref, "-",
            pc->detail != NULL ? pc->detail : "refused");
        free(pc->detail);
        pc->detail = NULL;
        pc->failed = true;
    } else if (pc->vr.status != V_VERIFIED) {
        pc->detail = path_fmt("%s%s. Continuing without verification.",
            base, safe_base);
        ui_msg(stderr, UI_WARN, "%s", pc->detail != NULL ? pc->detail : "unverified");
        free(pc->detail);
        pc->detail = NULL;
    }
    if (pc->failed) return PIPE_CLEANUP;

    // fetch exactly <asset>.sig/.asc and verify via gpg; an
    // already-verified manifest satisfies the gate outright
    bool prev_verified = pc->prev != NULL && pc->prev->sig_status != NULL
        && strcmp(pc->prev->sig_status, "verified") == 0;
    bool required = pc->o->require_signature || pc->st->require_signature;
    pc->sres_used = true;
    int sigk = -1;
    size_t alen = strlen(pc->base);
    for (int k = 0; k < pc->rel.n && sigk < 0; k++) {
        static const char *sx[] = { ".sig", ".asc", NULL };
        size_t nl = strlen(pc->rel.assets[k].name);
        for (int e = 0; sx[e] != NULL; e++) {
            size_t xl = strlen(sx[e]);
            if (nl == alen + xl
                && !strncasecmp(pc->rel.assets[k].name, pc->base, alen)
                && !strcasecmp(pc->rel.assets[k].name + alen, sx[e])) {
                sigk = k;
                break;
            }
        }
    }
    if (sigk < 0) {
        pc->sres.status = S_MISSING;
        pc->sres.asset_name = strdup(pc->base);
    } else {
        char *stmp = NULL;
        char smsg[700] = "";
        if (!provider_download(pc->pe, pc->rel.assets[sigk].url,
                pc->rel.assets[sigk].name, pc->timeout_ms, 0, NULL, NULL, NULL,
                &stmp, smsg, sizeof(smsg))) {
            pc->sres.status = S_ERROR;
            pc->sres.asset_name = strdup(pc->base);
            pc->sres.detail = path_fmt("signature download failed: %s",
                smsg);
        } else {
            struct stat ssb;
            long sig_max = 65536; // C-side cap on sig files
            const char *sv = getenv("APEGET_TEST_SIG_MAX");
            if (sv != NULL && *sv != '\0') {
                long x = strtol(sv, NULL, 10);
                if (x > 0) sig_max = x;
            }
            if (stat(stmp, &ssb) == 0 && (unsigned long)ssb.st_size
                    > (unsigned long)sig_max) {
                pc->sres.status = S_ERROR;
                pc->sres.asset_name = strdup(pc->base);
                pc->sres.detail = strdup(
                    "signature file exceeds the size cap");
            } else {
                sig_run_gpg(stmp, pc->tmp, pc->base, &pc->sres);
                if (unknown_key(&pc->sres))
                    verify_trust_path(pc, stmp, sigk);
            }
            remove(stmp);
            free(stmp);
        }
    }
    if (pc->failed) return PIPE_CLEANUP;
    char *sm = sig_message(&pc->sres);
    // required-signature is an independent policy: a previously verified
    // manifest relaxes the new signature check only when no verified
    // signature is required — a signer override (below) may accept a
    // different verified identity, never a missing or invalid signature
    bool allow = (prev_verified && !required)
        || sig_allows_install(&pc->sres, required);
    if (!allow) {
        char *w = path_fmt("%s Refusing to install.",
            sm != NULL ? sm : "");
        ui_msg(stderr, UI_ERROR, "%s", w != NULL ? w : sm != NULL ? sm : "sig");
        oplog_append(pc->st, pc->op, pc->ref, "-", w != NULL ? w : "signature");
        free(w);
        free(sm);
        pc->failed = true;
        return PIPE_CLEANUP;
    }
    // every verified signature pins the repo's fingerprint, whichever
    // keyring it came from (the user's ~/.gnupg included); first record
    // only — a recorded key is never silently replaced here
    if (pc->sres.status == S_VERIFIED && pc->sres.identity != NULL) {
        char *rec = NULL, *ru = NULL;
        trust_lookup(pc->st, pc->prov, pc->path, &rec, &ru);
        if (rec == NULL) {
            char *prim = primary_of(NULL, pc->sres.identity);
            trust_record(pc->st, pc->prov, pc->path,
                prim != NULL ? prim : pc->sres.identity, pc->sres.signer);
            free(prim);
        }
        free(rec);
        free(ru);
    }
    if (pc->sres.status == S_VERIFIED) {
        if (!pc->o->quiet && !pc->o->json)
            ui_msg(stderr, UI_OK, "%s", sm != NULL ? sm : "");
    } else if (!prev_verified) {
        char *w = path_fmt("%s Continuing without a verified "
            "signature.", sm != NULL ? sm : "");
        ui_msg(stderr, UI_WARN, "%s", w != NULL ? w : sm != NULL ? sm : "sig");
        free(w);
    }
    free(sm);

    // an update over a verified manifest must keep the same signer identity;
    // C stores no pins, so the "pinned to" branch is the unverified case
    if (pc->updating && prev_verified) {
        bool unverified = pc->sres.status != S_VERIFIED;
        bool changed = pc->sres.identity == NULL
            || pc->prev->sig_identity == NULL
            || strcasecmp(pc->prev->sig_identity, pc->sres.identity) != 0;
        // the trust path just confirmed this exact key swap; the pin
        // conflict is the same event, never a second prompt
        if (pc->signer_accepted) changed = false;
        const char *hint = unverified
            ? "If you trust this release, rerun with"
              " --allow-signer-change to clear the pinned signer."
            : "If you trust the new signer, rerun with"
              " --allow-signer-change to accept it and update"
              " the pin.";
        if (unverified || changed) {
            char *oldd = describe_manifest_sig(pc->prev);
            char *newd = NULL;
            char *reason = NULL, *q = NULL, *w = NULL;
            if (unverified) {
                reason = path_fmt("it is pinned to %s, but no "
                    "verified signature is available",
                    oldd != NULL ? oldd : "not recorded");
                q = path_fmt("Clear the signer pin for %s?\n"
                    "  pinned: %s\n  now:    no verified signature\n"
                    "Only continue if you trust this release.",
                    pc->prev->repo, oldd != NULL ? oldd : "not recorded");
                w = path_fmt("Warning: signer pin cleared for %s.",
                    pc->prev->repo);
            } else {
                newd = pc->sres.identity != NULL
                    ? path_fmt("%s (%s)", pc->sres.signer != NULL
                          ? pc->sres.signer : "unknown signer",
                          pc->sres.identity)
                    : strdup(pc->sres.signer != NULL ? pc->sres.signer
                          : "unknown signer");
                reason = path_fmt("the signer changed from %s to %s",
                    oldd != NULL ? oldd : "not recorded",
                    newd != NULL ? newd : "");
                q = path_fmt("Accept the signer change for %s?\n"
                    "  from: %s\n  to:   %s\nOnly continue if you"
                    " trust the new signer.",
                    pc->prev->repo, oldd != NULL ? oldd : "not recorded",
                    newd != NULL ? newd : "");
                w = path_fmt("Warning: signer pin updated for %s"
                    " to %s.", pc->prev->repo,
                    newd != NULL ? newd : "");
            }
            if (!resolve_pin(pc->o, pc->st, pc->op, pc->ref, pc->prev->repo, reason,
                    hint, q, w)) {
                free(reason);
                free(q);
                free(w);
                free(oldd);
                free(newd);
                pc->failed = true;
                return PIPE_CLEANUP;
            }
            free(reason);
            free(q);
            free(w);
            free(oldd);
            free(newd);
        }
    }

    // magic beats any compat claim: the file is then a native APE
    if (has_ape_magic(pc->tmp)) { pc->kind = K_APE; pc->compat_run = false; }
    else if (pc->kind == K_APE) {
        pc->detail = path_fmt("Asset is not an APE: %s", safe_base);
        ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "not ape");
        oplog_append(pc->st, pc->op, pc->ref, "-",
            pc->detail != NULL ? pc->detail : "not ape");
        free(pc->detail);
        pc->detail = NULL;
        pc->failed = true;
        return PIPE_CLEANUP;
    }
    return PIPE_GO;
}
