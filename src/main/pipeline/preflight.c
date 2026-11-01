// Preflight phase: resolve provider, release, asset, kind and command,
// with every gate that must pass before a download begins.

#include "pipeline/internal.h"

#include "compat.h"
#include "json.h"
#include "manifest.h"
#include "path.h"
#include "prompt.h"
#include "provider.h"
#include "state.h"
#include "ui.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>

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
    if (pc->pool != NULL) {
        bool pe_pool = compat_on
            && (compat_state || req_kind == K_PORTABLE_EXE);
        for (int k = 0; k < pc->rel.n; k++) {
            if (pe_pool) {
                if (ci_endswith(pc->rel.assets[k].name, ".exe"))
                    pc->pool[pool_n++] = pc->rel.assets[k];
                continue;
            }
            if (asset_named) { pc->pool[pool_n++] = pc->rel.assets[k]; continue; }
            if (ci_endswith(pc->rel.assets[k].name, ".appimage")
                || ci_endswith(pc->rel.assets[k].name, ".ape")
                || ci_endswith(pc->rel.assets[k].name, ".com"))
                pc->pool[pool_n++] = pc->rel.assets[k];
        }
        pool_n = arch_prefer(pc->pool, pool_n);
        // a Windows PE asset is a fallback, never a competitor: only an
        // empty native pool for an auto-select lets it in
        if (pool_n == 0 && compat_on && req_kind < 0 && !asset_named
            && !pc->updating) {
            for (int k = 0; k < pc->rel.n; k++)
                if (ci_endswith(pc->rel.assets[k].name, ".exe"))
                    pc->pool[pool_n++] = pc->rel.assets[k];
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
            for (int k = 0; k < pool_n; k++)
                if (!strcasecmp(pc->pool[k].name, v)) { sel = k; break; }
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
        for (int k = 0; k < pool_n; k++)
            if (pc->prev->asset != NULL
                && !strcmp(pc->pool[k].name, pc->prev->asset)) { sel = k; break; }
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
            pc->command = cn_from_asset(pc->path, pc->asset->name);
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

    // up-to-date means an identical asset URL; --reinstall re-runs even
    // an identical asset
    if (pc->updating && !pc->o->reinstall && pc->prev->asset_url != NULL
        && pc->asset->url != NULL && !strcmp(pc->prev->asset_url, pc->asset->url)) {
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
