#include "doctor.h"
#include "compat.h"
#include "doctor/internal.h"
#include "fs.h"
#include "manifest.h"
#include "path.h"
#include "proc.h"
#include "state.h"
#include "txn.h"
#include "ui.h"

#include <dirent.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

// Doctor: header, checks in fixed order, issues last; --json replaces all of
// it with one compact object; exit 0 only when healthy.

#define MIN_FREE_BYTES (100ULL * 1024 * 1024)

void sv_add(strv *l, char *s)
{
    if (l->n == l->cap) {
        l->cap = l->cap ? l->cap * 2 : 8;
        l->v = realloc(l->v, (size_t)l->cap * sizeof(*l->v));
    }
    l->v[l->n++] = s;
}

// Re-point a tracked launcher that no longer resolves (dangling or gone);
// non-destructive, needs the manifest target, prefers current/<file>.
static bool heal_launcher(const state_ctx *st, manifest_entry *m, char **to_out)
{
    if (m->launcher == NULL || m->path == NULL) return false;
    if (fs_exists(m->launcher) || !fs_exists(m->path)) return false;
    char *ad = manifest_app_dir(st->root, m->provider, m->repo);
    const char *fn = strrchr(m->path, '/') != NULL
        ? strrchr(m->path, '/') + 1 : m->path;
    char *via = path_fmt("%s/current/%s", ad != NULL ? ad : "", fn);
    const char *to = via != NULL && fs_exists(via) ? via : m->path;
    bool ok = false;
    if (fs_mkdir_p(st->install_root)) {
        if (fs_is_symlink(m->launcher)) unlink(m->launcher);
        ok = symlink(to, m->launcher) == 0;
    }
    if (ok && to_out != NULL) *to_out = strdup(to);
    free(via);
    free(ad);
    return ok;
}

static void heal_apply(state_ctx *st, const cmd_opts *o, bool *index_fixed,
    strv *healed, strv *declined, int *n_heal, int *n_decl)
{
    // Every healed item confirms itself, index rebuild included (silent
    // only when declined); state_recover_gate ran the journal under the lock.
    if (!st->index_ok && state_rebuild_index(st)) {
        st->index_ok = true;
        *index_fixed = true;
        ui_msg(stderr, UI_OK, "Rebuilt state index.");
        heal_add(healed, "index", st->index_file, NULL);
        (*n_heal)++;
    }

    manifest_entry *ms = NULL;
    size_t mc = 0;
    if (manifests_scan(st->root, &ms, &mc) != 0) return;
    for (size_t k = 0; k < mc; k++) {
        char *to = NULL;
        if (!heal_launcher(st, &ms[k], &to)) continue;
        if (!o->json && !o->quiet)
            ui_msg(stderr, UI_OK, "Healed launcher: %s.", ms[k].launcher);
        heal_add(healed, "launcher", ms[k].launcher, to);
        free(to);
        (*n_heal)++;
    }

    /* confirmed tier: manifests referencing an unconfigured
       provider. Mount points (check 9) are macOS-only — the original
       used one "under the home directory to avoid permission issues" —
       and a Linux install is a plain file, so there is no mount fix to
       confirm. */
    provider_entry *list = NULL;
    int ln = providers_load(st, &list);
    strv want = {0};
    for (size_t k = 0; k < mc; k++) {
        // the self entry has no provider registry to recover
        if (ms[k].provider == NULL || !strcmp(ms[k].provider, "self")
            || providers_find(list, ln, ms[k].provider) != NULL) continue;
        sv_add_uniq(&want, ms[k].provider);
    }
    providers_free(list, ln);
    for (int k = 0; k < want.n; k++) {
        pv_recovered rec = {0};
        int rc = providers_recover_core(o, st, want.v[k], NULL, NULL, NULL,
            false, &rec);
        if (rc == 0) {
            heal_add(healed, "provider", want.v[k], rec.api);
            (*n_heal)++;
        } else if (rc == 1) {
            sv_add(declined, strdup(want.v[k]));
            (*n_decl)++;
        }
        free(rec.type);
        free(rec.url);
        free(rec.api);
        free(rec.token_env);
    }
    for (size_t k = 0; k < mc; k++) manifest_entry_free(&ms[k]);
    free(ms);
}

int doctor_run(const cmd_opts *o)
{
    state_ctx st;
    if (!state_open(&st)) return 2;
    strv issues = {0}, warnings = {0};

    // doctor --heal is stateful: lock, then run recovery before reporting.
    // Bare doctor stays read-only and lock-free.
    if (o->heal) {
        if (!state_lock(st.root)) return 1;
        if (!state_recover_gate(&st, o->quiet, o->json)) return 1;
    }

    if (!st.config_present)
        sv_add(&issues, path_fmt("Config: missing at %s", st.config_file));
    if (!st.index_ok)
        sv_add(&issues, strdup(
            "Index: missing or corrupt (run a stateful command to rebuild)"));

    bool gpg_ok = proc_have_executable("gpg");
    if (!gpg_ok)
        sv_add(&issues, strdup("Signature tool missing: gpg"));

    const char *probe = st.install_root ? st.install_root : st.root;
    unsigned long long fb = 0;
    long long free_bytes = fs_free_bytes(probe, &fb) ? (long long)fb : -1;
    if (free_bytes < 0 || (unsigned long long)free_bytes < MIN_FREE_BYTES) {
        char num[24];
        if (free_bytes < 0) strcpy(num, "unknown");
        else snprintf(num, sizeof(num), "%lld MB", free_bytes / (1024 * 1024));
        sv_add(&issues, path_fmt("Low disk space at %s: %s free, need 100 MB",
                                 probe, num));
    }

    if (st.manifests > 0 && st.install_root != NULL
        && !proc_dir_on_path(st.install_root))
        sv_add(&warnings, path_fmt("PATH: %s is not on PATH", st.install_root));

    // compat wrapper check: warnings only, no state writes
    {
        char *apps = path_join(st.root, "applications");
        if (apps != NULL) {
            if (fs_is_dir(apps)) compat_walk(apps, st.wine_path, &warnings);
            free(apps);
        }
    }

    // Orphan scan: only ape-get's own links into the state root count (shared
    // install root); it runs whatever the manifest count, or a stray hides.
    if (fs_is_dir(st.install_root)) {
        // a link candidate is ours when the target lies inside the state
        // root; dangling links are judged on their recorded text
        char root_resolved[PATH_MAX];
        bool root_ok = realpath(st.root, root_resolved) != NULL;
        manifest_entry *oms = NULL;
        size_t omc = 0;
        if (manifests_scan(st.root, &oms, &omc) != 0) { oms = NULL; omc = 0; }
        DIR *d = opendir(st.install_root);
        if (d != NULL) {
            struct dirent *e;
            while ((e = readdir(d)) != NULL) {
                if (e->d_name[0] == '.') continue;
                char *p = path_join(st.install_root, e->d_name);
                if (!fs_is_symlink(p)) { free(p); continue; }
                if (!strcmp(e->d_name, "ape-get")) { free(p); continue; }
                bool ours = false;
                char resolved[PATH_MAX];
                if (realpath(p, resolved) != NULL && root_ok)
                    ours = !strncmp(resolved, root_resolved,
                               strlen(root_resolved))
                        && resolved[strlen(root_resolved)] == '/';
                else {
                    char tgt[PATH_MAX];
                    ssize_t n = readlink(p, tgt, sizeof(tgt) - 1);
                    if (n > 0) {
                        tgt[n] = '\0';
                        char *abs = tgt[0] == '/'
                            ? strdup(tgt)
                            : path_join(st.install_root, tgt);
                        char *nabs = path_norm_dup(abs);
                        char *nroot = path_norm_dup(st.root);
                        ours = nabs != NULL && nroot != NULL
                            && !strncmp(nabs, nroot, strlen(nroot))
                            && nabs[strlen(nroot)] == '/';
                        free(nroot);
                        free(nabs);
                        free(abs);
                    }
                }
                bool tracked = false;
                for (size_t k = 0; ours && k < omc; k++) {
                    if (oms[k].launcher != NULL
                        && !strcmp(oms[k].launcher, p))
                        tracked = true;
                    if (!tracked && oms[k].command != NULL) {
                        char *via = path_fmt("%s/%s",
                            st.install_root, oms[k].command);
                        tracked = via != NULL && !strcmp(via, p);
                        free(via);
                    }
                }
                if (ours && !tracked)
                    sv_add(&issues,
                        path_fmt("Orphan candidate: %s", p));
                free(p);
            }
            closedir(d);
        }
        for (size_t k = 0; k < omc; k++) manifest_entry_free(&oms[k]);
        free(oms);
    }

    // pending transaction journal: only C-created state can have one;
    // reported without taking the lock
    {
        char *pend = txn_doctor_issue(st.root);
        if (pend != NULL) sv_add(&issues, pend);
    }

    // bare doctor ends here read-only; --heal prints the full diagnosis first,
    // then acts; --json runs the heal first so its arrays join the document
    bool index_fixed = false;
    strv healed = {0}, declined = {0};
    int n_heal = 0, n_decl = 0;

    // availability beyond gpg (wine, tor, reticulum): human report only,
    // probed once
    tool_line tools[3];
    int nt = 0;
    char wbuf[512], tbuf[128], rbuf[160];
    char *wine_err = NULL;
    char *wine = NULL;
    if (!o->json) {
        wine = compat_detect_wine(st.wine_path, &wine_err);
        if (wine != NULL) {
            snprintf(wbuf, sizeof wbuf, ": wine available");
            tools[nt] = (tool_line){ "Compatibility tools", wbuf, UI_OK };
            nt++;
        } else if (st.wine_path != NULL) {
            snprintf(wbuf, sizeof wbuf, ": wine unusable: %s",
                wine_err != NULL ? wine_err : "check wine_path");
            tools[nt] = (tool_line){ "Compatibility tools", wbuf, UI_ERROR };
            nt++;
        } else if (compat_flatpak_wine()) {
            snprintf(wbuf, sizeof wbuf, ": wine available (flatpak)");
            tools[nt] = (tool_line){ "Compatibility tools", wbuf, UI_OK };
            nt++;
        } else {
            snprintf(wbuf, sizeof wbuf, ": wine not found (optional)");
            tools[nt] = (tool_line){ "Compatibility tools", wbuf, UI_WARN };
            nt++;
        }
        bool tor = tcp_probe_local(9050) || tcp_probe_local(9150);
        bool tor_want = st.proxy != NULL
            && (strstr(st.proxy, "tor") != NULL
                || strstr(st.proxy, "9050") != NULL
                || strstr(st.proxy, "9150") != NULL);
        if (tor && tor_want)
            snprintf(tbuf, sizeof tbuf, ": detected, in use");
        else if (tor)
            snprintf(tbuf, sizeof tbuf,
                ": detected, unused (configure in grm_config.json)");
        else if (tor_want)
            snprintf(tbuf, sizeof tbuf, ": configured but not detected");
        else
            snprintf(tbuf, sizeof tbuf, ": not detected (optional)");
        tools[nt] = (tool_line){ "Tor status", tbuf,
            tor ? (tor_want ? UI_OK : UI_WARN)
                : (tor_want ? UI_ERROR : UI_WARN) };
        nt++;
        bool rns = rnsd_running();
        bool rns_cfg = !rns && rns_configured();
        bool rns_want = false;
        provider_entry *pl = NULL;
        int pn = providers_load(&st, &pl);
        for (int k = 0; k < pn; k++)
            if (pl[k].type != NULL && !strcmp(pl[k].type, "rngit"))
                rns_want = true;
        providers_free(pl, pn);
        if (rns)
            snprintf(rbuf, sizeof rbuf, ": connected");
        else if (rns_cfg && rns_want)
            snprintf(rbuf, sizeof rbuf,
                ": configured, not running (a provider needs it)");
        else if (rns_cfg)
            snprintf(rbuf, sizeof rbuf, ": configured, not running (start rnsd)");
        else if (rns_want)
            snprintf(rbuf, sizeof rbuf,
                ": not connected (a provider needs it)");
        else
            snprintf(rbuf, sizeof rbuf, ": not connected (optional)");
        tools[nt] = (tool_line){ "Reticulum status", rbuf,
            rns ? UI_OK : (rns_want ? UI_ERROR : UI_WARN) };
        nt++;
    }

    if (o->heal && !o->json)
        report_lines(&st, &issues, &warnings, gpg_ok, free_bytes, false,
                     issues.n == 0, NULL, NULL, tools, nt);
    if (o->heal)
        heal_apply(&st, o, &index_fixed, &healed, &declined, &n_heal, &n_decl);
    bool healthy = (issues.n - (index_fixed ? 1 : 0)) == 0 && n_decl == 0;
    if (!o->heal || o->json)
        report_lines(&st, &issues, &warnings, gpg_ok, free_bytes, o->json,
                     healthy, o->heal ? &healed : NULL,
                     o->heal ? &declined : NULL, tools, nt);
    free(wine);
    free(wine_err);

    // verdict last: judgement first, then what --heal did
    if (!o->json && !o->quiet) {
        if (healthy)
            ui_msg(stdout, UI_OK, "Diagnosis: no issues found.");
        else {
            ui_msg(stdout, UI_ERROR, "Diagnosis: %d issue(s) remain.",
                (int)issues.n - (index_fixed ? 1 : 0));
            if (!o->heal)
                ui_msg(stdout, UI_ITALIC,
                    "Run 'ape-get doctor --heal' to attempt repairs.");
        }
        if (o->heal) {
            if (n_heal == 0 && n_decl == 0)
                ui_msg(stdout, UI_OK, "Heal: nothing to repair.");
            else
                ui_msg(stdout, UI_OK, "Heal: %d repaired, %d declined.",
                    n_heal, n_decl);
        }
    }
    if (o->heal) {
        char cnt[24];
        snprintf(cnt, sizeof cnt, "%d/%d", n_heal, n_decl);
        oplog_append(&st, "doctor", "-", cnt, NULL);
    }
    return healthy ? 0 : 1;
}
