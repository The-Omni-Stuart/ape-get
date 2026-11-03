// Preflight phase: resolve provider, release, asset, kind and command,
// with every gate that must pass before a download begins.

#include "pipeline/internal.h"

#include "compat.h"
#include "crypto.h"
#include "json.h"
#include "manifest.h"
#include "path.h"
#include "prompt.h"
#include "provider.h"
#include "state.h"
#include "ui.h"

#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>

// true when pool entry `entry` is the .xz twin of the plain name `plain`
static bool is_xz_twin(const char *entry, const char *plain)
{
    size_t el = strlen(entry), pl = strlen(plain);
    return el > 3 && pl + 3 == el
        && !strncasecmp(entry, plain, pl)
        && !strcasecmp(entry + pl, ".xz");
}

// I3/I4: a delta is eligible only when updating, its source version is
// the installed version, and that file's hash still matches the manifest
// record. The check opens the installed file once and hashes through
// that fd; the fd itself is returned so a selected delta keeps using
// exactly these bytes at apply time (no reopen, no swap race).
static int delta_gate(const pipe_ctx *pc, const char *from)
{
    if (!pc->updating || pc->prev == NULL || pc->prev->version == NULL
        || blank(pc->prev->path) || pc->prev->sha256 == NULL)
        return -1;
    if (!ver_eq(from, pc->prev->version)) return -1;
    int fd = open(pc->prev->path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    char sha[65] = "";
    if (!crypto_sha256_fd(fd, sha) || strcmp(sha, pc->prev->sha256) != 0) {
        close(fd);
        return -1;
    }
    if (lseek(fd, 0, SEEK_SET) != 0) { close(fd); return -1; }
    return fd;
}

// Variant rank for grouping (spec §5.3): eligible delta > twin > raw.
// Ineligible deltas return -1: they leave the pool at selection time and
// never spend radio (I3).
static int variant_rank(const pipe_ctx *pc, const char *name,
                        const char *base)
{
    char from[128];
    if (artifact_delta_from(name, from, sizeof from)) {
        int fd = delta_gate(pc, from);
        if (fd < 0) return -1;
        close(fd);
        return 2;
    }
    return is_xz_twin(name, base) ? 1 : 0;
}

// group key: decoded base, artifact extension cut, one variant tag cut
static void group_key(const char *name, char *out, size_t cap)
{
    char b[512];
    if (artifact_base_name(name, b, sizeof b) == 0) { out[0] = '\0'; return; }
    static const char *arts[] = {".appimage", ".ape", ".exe", ".msi",
        ".dmg", ".com", ".bin", NULL};
    for (int k = 0; arts[k] != NULL; k++) {
        size_t al = strlen(arts[k]);
        if (strlen(b) > al && !strcasecmp(b + strlen(b) - al, arts[k])) {
            b[strlen(b) - al] = '\0';
            break;
        }
    }
    arch_strip_variant(b);
    snprintf(out, cap, "%s", b);
}

// collapse transport twins and deltas (delta > .xz > raw) and, per group,
// prefer a single current-arch line over universal entries. Two spellings
// of the current arch (x86_64 and x86-64...) stay ambiguous on purpose:
// the picker shows the user both names rather than letting upload order
// decide.
static int pool_group(pipe_ctx *pc, prov_asset *pool, int n)
{
    if (n < 2) return n;
    char *keys = calloc((size_t)n, 512), *lines = calloc((size_t)n, 512);
    int *rank = calloc((size_t)n, sizeof *rank);
    bool *drop = calloc((size_t)n, sizeof *drop);
    if (keys == NULL || lines == NULL || rank == NULL || drop == NULL) {
        free(keys); free(lines); free(rank); free(drop);
        return n;
    }
    for (int i = 0; i < n; i++) {
        group_key(pool[i].name, keys + (size_t)i * 512, 512);
        size_t ll = artifact_base_name(pool[i].name,
            lines + (size_t)i * 512, 512);
        rank[i] = ll == 0 ? -1
            : variant_rank(pc, pool[i].name, lines + (size_t)i * 512);
        if (rank[i] < 0) drop[i] = true;   // ineligible delta
    }
    for (int i = 0; i < n; i++) {
        if (drop[i]) continue;
        for (int j = i + 1; j < n; j++) {
            if (drop[j]) continue;
            if (strcmp(keys + (size_t)i * 512, keys + (size_t)j * 512) != 0
                || strcmp(lines + (size_t)i * 512, lines + (size_t)j * 512) != 0)
                continue;
            if (rank[j] > rank[i]) drop[i] = true; // higher variant wins
            else drop[j] = true;                  // equal ranks: keep the first
            if (drop[i]) break;
        }
    }
    // arch preference per spec §5.4: non-current tags drop first; a sole
    // current-arch spelling then beats every other line in the group; two
    // spellings of it stay ambiguous and fall to the picker with the
    // non-current lines already gone
    for (int i = 0; i < n; i++) {
        if (drop[i]) continue;
        int a = arch_line_of(pool[i].name);
        if (a != 0 && a != 1 && a != arch_line_current()) drop[i] = true;
    }
    for (int i = 0; i < n; i++) {
        if (drop[i] || arch_line_of(pool[i].name) != arch_line_current())
            continue;
        const char *mykey = keys + (size_t)i * 512;
        const char *myline = lines + (size_t)i * 512;
        bool ambiguous = false;
        for (int j2 = 0; j2 < n && !ambiguous; j2++)
            if (!drop[j2] && j2 != i
                && strcmp(keys + (size_t)j2 * 512, mykey) == 0
                && arch_line_of(pool[j2].name) == arch_line_current()
                && strcmp(lines + (size_t)j2 * 512, myline) != 0)
                ambiguous = true;
        // ambiguous: keep both spellings, but lines that are neither
        // spelling (untagged, fat) still go; sole spelling: it wins all
        for (int j2 = 0; j2 < n; j2++) {
            if (drop[j2] || strcmp(keys + (size_t)j2 * 512, mykey)) continue;
            bool cur = arch_line_of(pool[j2].name) == arch_line_current();
            bool mine = !strcmp(lines + (size_t)j2 * 512, myline);
            if (cur && (mine || ambiguous)) continue;
            drop[j2] = true;
        }
    }
    int out = 0;
    for (int i = 0; i < n; i++)
        if (!drop[i]) pool[out++] = pool[i];
    free(keys); free(lines); free(drop);
    return out == 0 ? n : out;
}

int phase_preflight(pipe_ctx *pc)
{
    pc->pn = providers_load(pc->st, &pc->plist);
    pc->pe = providers_find(pc->plist, pc->pn, pc->fprov);
    if (pc->pe == NULL) {
        pc->detail = path_fmt("Provider '%s' is not configured.", pc->fprov);
        ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "provider missing");
        return PIPE_DONE;
    }

    if (manifests_scan(pc->st->root, &pc->ms, &pc->mc) != 0) {
        ui_msg(stderr, UI_ERROR,
            "Failed to load application state: state scan failed");
        oplog_append(pc->st, pc->op, pc->ref, "-",
            "Failed to load application state: state scan failed");
        return PIPE_DONE;
    }
    for (size_t k = pc->updating ? pc->mc : 0; k < pc->mc; k++) {
        if (!strcmp(pc->ms[k].provider, pc->prov) && !strcmp(pc->ms[k].repo, pc->path)) {
            pc->detail = path_fmt("%s is already installed; use update.", pc->ref);
            ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "already installed");
            oplog_append(pc->st, pc->op, pc->ref, "-",
                pc->detail != NULL ? pc->detail : "already installed");
            return PIPE_DONE;
        }
    }

    bool beta = pc->st->update_channel != NULL
        && !strcasecmp(pc->st->update_channel, "beta");
    if (!blank(pc->o->channel)) {
        if (!strcasecmp(pc->o->channel, "beta")) beta = true;
        else if (strcasecmp(pc->o->channel, "stable")) {
            pc->detail = path_fmt("Unknown channel: %s", pc->o->channel);
            ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "bad channel");
            return PIPE_DONE;
        }
    }

    pc->timeout_ms = (pc->st->timeout_seconds > 0 ? pc->st->timeout_seconds : 30)
        * 1000;

    if (!provider_release_fetch(pc->pe, pc->fpath, pc->version, beta, pc->timeout_ms,
                                &pc->rel)) {
        pc->detail = path_fmt("Failed to read releases from %s: %s", pc->ref,
            pc->rel.message);
        ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "fetch failed");
        oplog_append(pc->st, pc->op, pc->ref, "-",
            pc->detail != NULL ? pc->detail : "fetch failed");
        free(pc->detail);
        pc->detail = NULL;
        if (!pc->updating)
            ui_msg(stderr, UI_ERROR, "Failed to retrieve the release.");
        return PIPE_DONE;
    }

    int req_kind = kind_from_option(pc->o->kind);
    if (req_kind == -3) {
        pc->detail = path_fmt("Unknown package kind: %s", pc->o->kind);
        ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "bad kind");
        return PIPE_DONE;
    }

    // on update, the recorded compat layer of a previous install is the
    // intent; otherwise the flag or the config decides on PE assets
    bool compat_state = pc->updating && pc->prev->compat != NULL;
    bool compat_on = compat_state || pc->o->experimental_compat
        || pc->st->experimental_compat;

    bool asset_named = !blank(pc->o->asset)
        && strspn(pc->o->asset, "0123456789") != strlen(pc->o->asset);
    pc->pool = calloc((size_t)(pc->rel.n > 0 ? pc->rel.n : 1), sizeof(*pc->pool));
    int pool_n = 0;
    bool grouped = false;   // a collapsed pool owes the user one "Selected" line
    if (pc->pool != NULL) {
        bool pe_pool = compat_on
            && (compat_state || req_kind == K_PORTABLE_EXE);
        for (int k = 0; k < pc->rel.n; k++) {
            char b[512];
            artifact_base_name(pc->rel.assets[k].name, b, sizeof b);
            if (pe_pool) {
                if (ci_endswith(b, ".exe"))
                    pc->pool[pool_n++] = pc->rel.assets[k];
                continue;
            }
            if (asset_named) { pc->pool[pool_n++] = pc->rel.assets[k]; continue; }
            if (ci_endswith(b, ".appimage")
                || ci_endswith(b, ".ape")
                || ci_endswith(b, ".com"))
                pc->pool[pool_n++] = pc->rel.assets[k];
        }
        // a Windows PE asset is a fallback, never a competitor: only an
        // empty native pool for an auto-select lets it in
        if (pool_n == 0 && compat_on && req_kind < 0 && !asset_named
            && !pc->updating) {
            for (int k = 0; k < pc->rel.n; k++) {
                char b[512];
                artifact_base_name(pc->rel.assets[k].name, b, sizeof b);
                if (ci_endswith(b, ".exe"))
                    pc->pool[pool_n++] = pc->rel.assets[k];
            }
            pool_n = arch_prefer(pc->pool, pool_n);
        }
        if (req_kind >= 0) {
            int f = 0;
            for (int k = 0; k < pool_n; k++)
                if (kind_matches(req_kind,
                        kind_from_asset_name(pc->pool[k].name), asset_named))
                    pc->pool[f++] = pc->pool[k];
            pool_n = f;
        }
        if (!pe_pool) {
            int g = pool_group(pc, pc->pool, pool_n);
            grouped = g != pool_n;
            pool_n = g;   // any collapse owes the user one "Selected" line
        }
    }
    if (pc->pool == NULL || pool_n == 0) {
        ui_msg(stderr, UI_ERROR,
            "No compatible package found for this platform.");
        return PIPE_DONE;
    }

    int sel = -1;
    int picked = 0; // an interactive pick equals --asset <index>

    if (!blank(pc->o->asset)) {
        char *v = (char *)pc->o->asset;
        if (strspn(v, "0123456789") == strlen(v)) {
            long idx = atol(v);
            if (idx >= 1 && idx <= pool_n) sel = (int)idx - 1;
        }
        if (sel < 0)
            for (int k = 0; k < pool_n; k++) {
                char plain[512];
                size_t nl = strlen(pc->pool[k].name);
                bool twin = nl > 3
                    && !strcasecmp(pc->pool[k].name + nl - 3, ".xz");
                snprintf(plain, sizeof plain, "%s", pc->pool[k].name);
                if (twin) plain[nl - 3] = '\0';
                if (!strcasecmp(pc->pool[k].name, v)
                    || (twin && !strcasecmp(plain, v))) { sel = k; break; }
            }
        // --asset selects the logical artifact: a name may also hit the
        // group through its decoded base (delta or twin survivor lines)
        if (sel < 0) {
            char arg[512];
            snprintf(arg, sizeof arg, "%s", v);
            size_t al = strlen(arg);
            if (al > 3 && !strcasecmp(arg + al - 3, ".xz")) arg[al - 3] = '\0';
            for (int k = 0; k < pool_n; k++) {
                char b[512];
                if (artifact_base_name(pc->pool[k].name, b, sizeof b) != 0
                    && !strcasecmp(b, arg)) { sel = k; break; }
            }
        }
        if (sel < 0) {
            pc->detail = path_fmt("No installable asset named '%s'. "
                "Available assets:", v);
            ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "no asset");
            free(pc->detail);
            pc->detail = NULL;
            for (int k = 0; k < pool_n; k++) {
                char *line = path_fmt("  %d: %s", k + 1, pc->pool[k].name);
                ui_msg(stderr, UI_DIM, "%s", line != NULL ? line : "");
                free(line);
            }
        return PIPE_DONE;
        }
    } else if (pc->updating) {
        for (int k = 0; k < pool_n; k++) {
            char plain[512];
            size_t nl = strlen(pc->pool[k].name);
            bool twin = nl > 3
                && !strcasecmp(pc->pool[k].name + nl - 3, ".xz");
            snprintf(plain, sizeof plain, "%s", pc->pool[k].name);
            if (twin) plain[nl - 3] = '\0';
            if (pc->prev->asset != NULL
                && (!strcmp(pc->pool[k].name, pc->prev->asset)
                    || !strcmp(plain, pc->prev->asset))) { sel = k; break; }
        }
        if (sel < 0 && pool_n == 1) sel = 0;
        if (sel < 0) {
            ui_msg(stderr, UI_BOLD,
                "Multiple assets found; choose one with --asset:");
            for (int k = 0; k < pool_n; k++) {
                char *line = path_fmt("  %d: %s", k + 1, pc->pool[k].name);
                ui_msg(stderr, UI_DIM, "%s", line != NULL ? line : "");
                free(line);
            }
            sel = choose_index(pc->o, "an asset", "asset", (size_t)pool_n);
            if (sel < 0) return PIPE_DONE;
            picked = 1;
        }
    } else if (pool_n == 1) {
        sel = 0;
    } else {
        ui_msg(stderr, UI_BOLD,
            "Multiple assets found; choose one with --asset:");
        for (int k = 0; k < pool_n; k++) {
            char *line = path_fmt("  %d: %s", k + 1, pc->pool[k].name);
            ui_msg(stderr, UI_DIM, "%s", line != NULL ? line : "");
            free(line);
        }
        sel = choose_index(pc->o, "an asset", "asset", (size_t)pool_n);
        if (sel < 0) return PIPE_DONE;
        picked = 1;
    }
    pc->asset = &pc->pool[sel];
    const char *leaf = strrchr(pc->asset->name, '/') != NULL
        ? strrchr(pc->asset->name, '/') + 1 : pc->asset->name;
    if (artifact_base_name(leaf, pc->base, sizeof pc->base) == 0)
        snprintf(pc->base, sizeof pc->base, "%s", leaf);

    // delta eligibility finalises before any fetch (I3/I4): the hashed
    // fd stays open so the apply stage consumes exactly these bytes;
    // the group's smallest full variant is remembered for the single
    // re-selection an in-flight patch failure earns (I3)
    pc->delta_pass = false;
    {
        char from[128];
        if (artifact_delta_from(pc->asset->name, from, sizeof from)) {
            pc->delta_fd = delta_gate(pc, from);
            if (pc->delta_fd >= 0) {
                pc->delta_pass = true;
                char gkey[512];
                group_key(pc->asset->name, gkey, sizeof gkey);
                int fbk = -1;
                for (int k = 0; k < pc->rel.n; k++) {
                    const char *nm = pc->rel.assets[k].name;
                    char b[512], k2[512];
                    if (artifact_base_name(nm, b, sizeof b) == 0) continue;
                    if (artifact_delta_from(nm, from, sizeof from)) continue;
                    group_key(nm, k2, sizeof k2);
                    if (strcmp(k2, gkey) != 0 || strcasecmp(b, pc->base) != 0)
                        continue;
                    if (fbk < 0 || is_xz_twin(nm, b)) {
                        fbk = k;
                        if (is_xz_twin(nm, b)) break;
                    }
                }
                if (fbk >= 0) {
                    pc->fb_asset = pc->rel.assets[fbk];
                    pc->fb_ok = true;
                }
            } else {
                pc->detail = path_fmt("Delta %s does not match the installed "
                    "version or its recorded hash; install the full package.",
                    pc->asset->name);
                ui_msg(stderr, UI_ERROR, "%s",
                    pc->detail != NULL ? pc->detail : "delta not applicable");
                free(pc->detail);
                pc->detail = NULL;
                return PIPE_DONE;
            }
        }
    }

    if (req_kind >= 0) pc->kind = req_kind;
    else {
        pc->kind = pc->updating ? kind_from_manifest_kind(pc->prev->kind) : K_UNKNOWN;
        if (pc->kind == K_UNKNOWN) pc->kind = kind_from_asset_name(pc->asset->name);
    }
    bool forced = req_kind >= 0 || !blank(pc->o->asset) || picked;
    if (pc->kind == K_UNKNOWN) {
        pc->detail = path_fmt("Cannot determine how to install %s; use --kind.",
            pc->asset->name);
        ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "unknown kind");
        return PIPE_DONE;
    }
    // an .exe on the compat path installs as PORTABLE_EXE (the
    // sole compat scope); NSIS never reaches this code on Linux
    pc->compat_run = compat_state
        || (compat_on && ci_endswith(pc->asset->name, ".exe")
            && (pc->kind == K_EXE || pc->kind == K_PORTABLE_EXE));
    if (pc->compat_run) pc->kind = K_PORTABLE_EXE;
    if (pc->compat_run && pc->o->desktop) {
        ui_msg(stderr, UI_ERROR, "--desktop is not supported for "
            "compatibility-layer installs.");
        return PIPE_DONE;
    }
    if (!pc->compat_run && forced && !kind_supported_on_linux(pc->kind)) {
        pc->detail = path_fmt("Package kind %s is not supported on LINUX.",
            kind_manifest_name(pc->kind));
        ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "unsupported");
        return PIPE_DONE;
    }
    bool cmd_failed = false;
    if (kind_is_portable(pc->kind) && !pc->o->no_command) {
        if (pc->updating && pc->prev->command != NULL && *pc->prev->command != '\0')
            pc->command = strdup(pc->prev->command);
        else if (!blank(pc->o->name)) {
            if (!cn_valid(pc->o->name)) {
                pc->detail = path_fmt("Invalid command name: %s", pc->o->name);
                ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "bad name");
                cmd_failed = true;
            } else {
                pc->command = strdup(pc->o->name);
            }
        } else {
            pc->command = cn_from_asset(pc->path, pc->base);
            if (pc->command == NULL || !cn_valid(pc->command)) {
                pc->detail = path_fmt("Invalid command name: %s",
                    pc->command != NULL ? pc->command : "");
                ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "bad name");
                free(pc->command);
                pc->command = NULL;
                cmd_failed = true;
            }
        }
        if (cmd_failed) {
            free(pc->detail);
            pc->detail = NULL;
        return PIPE_DONE;
        }
    }
    if (pc->o->assimilate && pc->kind != K_APE) {
        ui_msg(stderr, UI_ERROR,
            "--assimilate is only supported for APE packages.");
        return PIPE_DONE;
    }
    if (pc->o->desktop && pc->o->no_desktop) {
        ui_msg(stderr, UI_ERROR,
            "--desktop and --no-desktop cannot be used together.");
        return PIPE_DONE;
    }
    if (!pc->o->desktop && pc->o->icon != NULL)
        ui_msg(stderr, UI_WARN, "--icon has no effect without --desktop.");
    if (!pc->o->desktop && pc->o->terminal)
        ui_msg(stderr, UI_WARN,
            "--terminal has no effect without --desktop.");
    if (pc->o->desktop && (!kind_is_portable(pc->kind) || pc->command == NULL)) {
        ui_msg(stderr, UI_ERROR,
            "--desktop requires a portable package with a command.");
        return PIPE_DONE;
    }

    bool clash = false;
    if (pc->command != NULL) {
        for (size_t k = 0; k < pc->mc && !clash; k++) {
            if (pc->ms[k].command == NULL
                || strcmp(pc->ms[k].command, pc->command) != 0)
                continue;
            if (!strcmp(pc->ms[k].provider, pc->prov) && !strcmp(pc->ms[k].repo, pc->path))
                continue;
            pc->detail = path_fmt("Command '%s' is already used by %s:%s; "
                "choose another with --name.", pc->command, pc->ms[k].provider,
                pc->ms[k].repo);
            ui_msg(stderr, UI_ERROR, "%s", pc->detail != NULL ? pc->detail : "clash");
            clash = true;
        }
    }
    if (clash) {
        free(pc->detail);
        pc->detail = NULL;
        return PIPE_DONE;
    }

    pc->launcher = pc->command != NULL
        ? path_fmt("%s/%s", pc->st->install_root, pc->command) : NULL;

    // up-to-date means an identical asset URL, or the same logical
    // artifact at the same version (twins and deltas change the URL,
    // never the identity); --reinstall re-runs even an identical asset
    bool same_version = pc->updating && pc->prev->asset != NULL
        && pc->prev->version != NULL
        && pc->rel.version != NULL && pc->prev->version != NULL
        && ver_eq(pc->prev->version, pc->rel.version)
        && !strcmp(pc->prev->asset, pc->base);
    if (pc->updating && !pc->o->reinstall
        && ((pc->prev->asset_url != NULL && pc->asset->url != NULL
             && !strcmp(pc->prev->asset_url, pc->asset->url))
            || same_version)) {
        if (pc->report) {
            if (pc->o->json) {
                jbuf b = {0};
                jb_lit(&b, "{\"up_to_date\":{\"provider\":");
                jb_json_string(&b, pc->prov);
                jb_lit(&b, ",\"repo\":");
                jb_json_string(&b, pc->path);
                jb_lit(&b, ",\"version\":");
                json_null_or_string(&b, pc->prev->version);
                jb_lit(&b, "}}");
                puts(b.p);
                jb_free(&b);
            } else if (!pc->o->quiet) {
                ui_msg(stderr, UI_OK, "Already up to date.");
            }
        }
        if (pc->to_out != NULL && pc->prev->version != NULL)
            *pc->to_out = strdup(pc->prev->version);
        pc->rv = RES_UP_TO_DATE;
        return PIPE_DONE;
    }

    // what the collapsed pool settled on, said once and after the
    // up-to-date gate so a no-op update stays a one-line answer
    if (grouped) ui_msg(stderr, UI_DIM, "Selected asset: %s", pc->asset->name);

    if (pc->o->dry_run) {
      if (pc->report) {
        if (pc->o->json) {
            jbuf b = {0};
            jb_lit(&b, pc->updating
                ? "{\"dry_run\":true,\"action\":\"update\",\"provider\":"
                : "{\"dry_run\":true,\"action\":\"install\",\"provider\":");
            jb_json_string(&b, pc->prov);
            jb_lit(&b, ",\"repo\":");
            jb_json_string(&b, pc->path);
            bool f = false;
            jb_kv(&b, "version", pc->rel.version, &f);
            jb_kv(&b, "asset", pc->asset->name, &f);
            jb_kv(&b, "source", pc->asset->url, &f);
            jb_kv(&b, "launcher", pc->launcher, &f);
            jb_lit(&b, ",\"kind\":");
            jb_json_string(&b, kind_manifest_name(pc->kind));
            jb_kv(&b, "command", pc->command, &f);
            jb_lit(&b, "}");
            puts(b.p);
            jb_free(&b);
        } else {
            if (pc->updating)
                printf("Dry run: would update %s to %s.\n", pc->ref,
                    pc->rel.version != NULL ? pc->rel.version : "(unknown version)");
            else
                printf("Dry run: would install %s %s.\n", pc->ref,
                    pc->rel.version != NULL ? pc->rel.version : "(unknown version)");
            printf("  Asset:  %s\n", pc->asset->name);
            printf("  Source: %s\n", pc->asset->url);
            if (pc->launcher != NULL) printf("  Launcher: %s\n", pc->launcher);
            puts("No changes were made.");
        }
      }
        if (pc->to_out != NULL && pc->rel.version != NULL)
            *pc->to_out = strdup(pc->rel.version);
        pc->rv = RES_PLANNED;
        return PIPE_DONE;
    }

    // the compat warning prints under -y too and is never skipped
    if (pc->compat_run) {
        char *cerr = NULL;
        pc->wine_abs = compat_detect_wine(pc->st->wine_path, &cerr);
        if (pc->wine_abs == NULL) {
            ui_msg(stderr, UI_ERROR, "%s", cerr != NULL ? cerr
                : "No wine detected. Install wine and run again, or set "
                  "wine_path in the config to your wine binary.");
            free(cerr);
            return PIPE_DONE;
        }
        pc->detail = path_fmt("Warning: %s is a Windows program and is not "
            "officially supported on Linux; it may not work at all, or "
            "may not work as expected. A compatibility layer (wine) was "
            "detected.", pc->asset->name);
        ui_msg(stderr, UI_WARN, "%s",
            pc->detail != NULL ? pc->detail : "Warning: compat layer.");
        free(pc->detail);
        pc->detail = NULL;
    }

    {
        pc->detail = pc->updating
            ? path_fmt("Update %s %s -> %s (%s)?", pc->ref,
                pc->prev->version != NULL ? pc->prev->version : "unknown",
                pc->rel.version != NULL ? pc->rel.version : "null", pc->asset->name)
            : path_fmt("Install %s %s (%s)?", pc->ref,
                pc->rel.version != NULL ? pc->rel.version : "null", pc->asset->name);
        bool ok = !pc->ask || confirm_action(pc->o, pc->detail != NULL ? pc->detail : "", false);
        free(pc->detail);
        pc->detail = NULL;
        if (!ok) {
            ui_msg(stderr, UI_WARN,
                pc->updating ? "Update declined." : "Install declined.");
            oplog_append(pc->st, pc->op, pc->ref, "-",
                pc->updating ? "Update declined." : "Install declined.");
        return PIPE_DONE;
        }
    }
    return PIPE_GO;
}
