// Verification of the staged download: sha256 checksum candidates,
// then gpg signatures and the signer pin.

#include "pipeline/internal.h"

#include "crypto.h"
#include "fs.h"
#include "manifest.h"
#include "path.h"
#include "pipeline/checksums.h"
#include "provider.h"
#include "state.h"
#include "ui.h"
#include "verify.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>

int phase_checksum(pipe_ctx *pc)
{
    // exact <target>{.sha256,.sha256sum,.sha256.txt,.digest} candidates
    // first, then the aggregate names
    pc->vr.status = V_MISSING;
    int cand[64];
    int cn = 0;
    size_t al = strlen(pc->asset->name);
    static const char *xext[] = {".sha256", ".sha256sum", ".sha256.txt",
        ".digest", NULL};
    static const char *agg[] = {"sha256sums", "sha256sums.txt",
        "sha256sums.sha256", "sha256sum.txt", "checksums.txt",
        "checksums.sha256", "checksum.txt", "sha256.txt", NULL};
    for (int k = 0; k < pc->rel.n && cn < 64; k++)
        for (int e = 0; xext[e] != NULL; e++) {
            size_t xl = strlen(xext[e]);
            if (strlen(pc->rel.assets[k].name) == al + xl
                && !strncasecmp(pc->rel.assets[k].name, pc->asset->name, al)
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
                pc->rel.assets[k].name, pc->timeout_ms, NULL, NULL, NULL,
                &ctmp, cmsg, sizeof(cmsg))) {
            pc->detail = path_fmt("Checksum verification failed: %s", cmsg);
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
        checksum_lookup(content, pc->asset->name, &has, &amb, &sha);
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
    if (pc->vr.status == V_MISMATCH) {
        pc->detail = path_fmt(
            "Checksum mismatch for %s: expected %s, got %s."
            " Refusing to install.", pc->asset->name, pc->vr.expected,
            pc->vr.actual);
        ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "mismatch");
        oplog_append(pc->st, pc->op, pc->ref, "-",
            pc->detail != NULL ? pc->detail : "checksum mismatch");
        free(pc->detail);
        pc->detail = NULL;
        pc->failed = true;
    } else if (!allowed) {
        pc->detail = path_fmt("%s%s. Refusing to install (strict mode).",
            base, pc->asset->name);
        ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "refused");
        oplog_append(pc->st, pc->op, pc->ref, "-",
            pc->detail != NULL ? pc->detail : "refused");
        free(pc->detail);
        pc->detail = NULL;
        pc->failed = true;
    } else if (pc->vr.status != V_VERIFIED) {
        pc->detail = path_fmt("%s%s. Continuing without verification.",
            base, pc->asset->name);
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
    size_t alen = strlen(pc->asset->name);
    for (int k = 0; k < pc->rel.n && sigk < 0; k++) {
        static const char *sx[] = { ".sig", ".asc", NULL };
        size_t nl = strlen(pc->rel.assets[k].name);
        for (int e = 0; sx[e] != NULL; e++) {
            size_t xl = strlen(sx[e]);
            if (nl == alen + xl
                && !strncasecmp(pc->rel.assets[k].name, pc->asset->name, alen)
                && !strcasecmp(pc->rel.assets[k].name + alen, sx[e])) {
                sigk = k;
                break;
            }
        }
    }
    if (sigk < 0) {
        pc->sres.status = S_MISSING;
        pc->sres.asset_name = strdup(pc->asset->name);
    } else {
        char *stmp = NULL;
        char smsg[700] = "";
        if (!provider_download(pc->pe, pc->rel.assets[sigk].url,
                pc->rel.assets[sigk].name, pc->timeout_ms, NULL, NULL, NULL,
                &stmp, smsg, sizeof(smsg))) {
            pc->sres.status = S_ERROR;
            pc->sres.asset_name = strdup(pc->asset->name);
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
                pc->sres.asset_name = strdup(pc->asset->name);
                pc->sres.detail = strdup(
                    "signature file exceeds the size cap");
            } else {
                sig_run_gpg(stmp, pc->tmp, pc->asset->name, &pc->sres);
            }
            remove(stmp);
            free(stmp);
        }
    }
    char *sm = sig_message(&pc->sres);
    bool allow = prev_verified
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
        pc->detail = path_fmt("Asset is not an APE: %s", pc->asset->name);
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
