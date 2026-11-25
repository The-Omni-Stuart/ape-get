#include "state.h"

#include "fs.h"
#include "json.h"
#include "manifest.h"
#include "path.h"
#include "platform.h"
#include "txn.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static long long epoch_millis(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void quarantine(const char *file, const char *label)
{
    char *q = path_fmt("%s.corrupt-%lld", file, epoch_millis());
    if (q == NULL) return;
    if (fs_rename(file, q))
        fprintf(stderr, "Warning: corrupt %s moved to %s\n", label, q);
    free(q);
}

// default config values, matching the original config file's
#define DEFAULT_CONFIG \
    "{\"kind\":\"grm.config\",\"schema_version\":1," \
    "\"strict_verification\":false,\"backup_count\":1,\"install_root\":null," \
    "\"require_signature\":false,\"providers\":{},\"search_all_providers\":true," \
    "\"update_channel\":\"stable\",\"auto_update\":\"off\"," \
    "\"update_interval_hours\":24,\"network_timeout_seconds\":30,\"proxy\":null}"

static bool config_is_valid(const char *text, size_t n)
{
    if (!json_valid(text, n)) return false;
    char *kind = NULL;
    long ver = 0, backups = 0;
    bool ok = json_get_string(text, "kind", &kind)
        && strcmp(kind, "grm.config") == 0
        && json_get_int(text, "schema_version", &ver) && ver == 1
        && json_get_int(text, "backup_count", &backups) && backups >= 0;
    free(kind);
    return ok;
}

static bool index_is_valid(const char *text, size_t n)
{
    if (!json_valid(text, n)) return false;
    char *kind = NULL, *gen = NULL;
    long ver = 0;
    bool ok = json_get_string(text, "kind", &kind)
        && strcmp(kind, "grm.index") == 0
        && json_get_int(text, "schema_version", &ver) && ver == 1
        && json_get_string(text, "generated_at", &gen)
        && json_top_array(text, "apps");
    free(kind);
    free(gen);
    return ok;
}

// Query mode is process-global because both state_open and the manifest
// scanner (state/manifest_scan.c) need it before any context flows.
static bool s_query;
void state_set_query(void) { s_query = true; }
bool state_is_query(void) { return s_query; }

// Forgiving policy read: an unknown key or value warns once naming it and
// falls back to the profile default; a bad policy never blocks a command.
// Precedence: CLI flag (call sites) > explicit policy key > profile >
// built-in. The legacy top-level strict_verification/require_signature keys
// keep working only while no policy object exists: the policy object is the
// new home and its presence activates the new precedence.
static void policy_apply(state_ctx *out, const char *cfg)
{
    const char *vs, *ve;
    if (!json_raw(cfg, "policy", &vs, &ve)) return; // absent = standard
    char *ptext = strndup(vs, (size_t)(ve - vs));
    jobject po = {0};
    if (ptext == NULL || !jobject_parse(ptext, &po)) {
        free(ptext);
        fprintf(stderr, "Warning: config policy is not an object;"
            " standard used\n");
        return;
    }
    // po's spans alias ptext: it must outlive every po access below
    bool hard = false;
    int f = jobject_find(&po, "profile");
    if (f >= 0) {
        char *pv = NULL;
        if (json_span_string(po.vals[f], po.vends[f], &pv) && pv != NULL) {
            if (!strcmp(pv, "hardened")) hard = true;
            else if (strcmp(pv, "standard") != 0)
                fprintf(stderr, "Warning: config policy: unknown profile"
                    " \"%s\"; using standard\n", pv);
        } else {
            fprintf(stderr, "Warning: config policy: unknown profile value;"
                " using standard\n");
        }
        free(pv);
    }
    out->policy_profile = hard ? 1 : 0;
    out->require_signature = hard;
    out->strict_verification = hard;
    out->signer_change = hard ? 1 : 0;
    out->desktop_policy = 0;
    static const char *known[] = { "profile", "require_signature",
        "checksum_strict", "signer_change", "desktop", NULL };
    for (int k = 0; k < po.n; k++) {
        const char *key = po.keys[k];
        bool ok = false;
        for (int q = 0; known[q] != NULL && !ok; q++)
            ok = !strcmp(key, known[q]);
        if (!ok) {
            fprintf(stderr, "Warning: config policy: unknown key \"%s\""
                " ignored\n", key);
            continue;
        }
        if (!strcmp(key, "profile")) continue;
        if (!strcmp(key, "require_signature")
            || !strcmp(key, "checksum_strict")) {
            bool b;
            if (json_span_bool(po.vals[k], po.vends[k], &b)) {
                if (key[0] == 'r') out->require_signature = b;
                else out->strict_verification = b;
            } else {
                fprintf(stderr, "Warning: config policy: unknown value for"
                    " \"%s\" ignored\n", key);
            }
            continue;
        }
        char *s = NULL;
        bool bad = false;
        if (json_span_string(po.vals[k], po.vends[k], &s) && s != NULL) {
            if (!strcmp(key, "signer_change")) {
                if (!strcmp(s, "confirm")) out->signer_change = 0;
                else if (!strcmp(s, "refuse")) out->signer_change = 1;
                else bad = true;
            } else { // desktop
                if (!strcmp(s, "auto")) out->desktop_policy = 0;
                else if (!strcmp(s, "always")) out->desktop_policy = 1;
                else if (!strcmp(s, "never")) out->desktop_policy = 2;
                else bad = true;
            }
        } else {
            bad = true;
        }
        if (bad)
            fprintf(stderr, "Warning: config policy: unknown value for"
                " \"%s\" ignored\n", key);
        free(s);
    }
    jobject_free(&po);
    free(ptext);
}

// Detects corrupt records without touching them: repair happens later,
// by mutation commands only, once the state lock is held (S08).
bool state_open(state_ctx *out)
{
    memset(out, 0, sizeof(*out));
    out->search_all = true;
    out->timeout_seconds = 30;
    out->root = path_state_root();
    if (out->root == NULL) return false;
    out->config_file = path_join(out->root, "grm_config.json");
    out->index_file = path_join(out->root, "grm_index.json");
    out->install_root = platform_install_root_default();

    out->config_present = fs_exists(out->config_file);
    size_t n = 0;
    char *cfg = out->config_present ? fs_read_file(out->config_file, &n) : NULL;
    if (cfg != NULL && config_is_valid(cfg, n)) {
        char *ir = NULL;
        if (json_get_string(cfg, "install_root", &ir)
            && ir != NULL && ir[0] != '\0') {
            free(out->install_root);
            out->install_root = ir;
        } else {
            free(ir);
        }
        // Config normalization: search_all_providers is false only when
        // literally false; network_timeout_seconds <= 0 means 30.
        const char *vs, *ve;
        if (json_raw(cfg, "search_all_providers", &vs, &ve))
            out->search_all = !(ve - vs == 5 && memcmp(vs, "false", 5) == 0);
        long t = 30;
        if (json_get_int(cfg, "network_timeout_seconds", &t) && t > 0)
            out->timeout_seconds = (int)t;
        long bc = 0;
        if (json_get_int(cfg, "backup_count", &bc))
            out->backup_count = (int)bc;
        const char *bv, *bev;
        if (json_raw(cfg, "strict_verification", &bv, &bev))
            out->strict_verification = bev - bv == 4
                && !memcmp(bv, "true", 4);
        if (json_raw(cfg, "require_signature", &bv, &bev))
            out->require_signature = bev - bv == 4
                && !memcmp(bv, "true", 4);
        if (json_raw(cfg, "experimental_compat", &bv, &bev))
            out->experimental_compat = bev - bv == 4
                && !memcmp(bv, "true", 4);
        char *wp = NULL;
        if (json_get_string(cfg, "wine_path", &wp) && wp != NULL)
            out->wine_path = wp;
        else
            free(wp);
        char *px = NULL;
        if (json_get_string(cfg, "proxy", &px) && px != NULL)
            out->proxy = px;
        else
            free(px);
        char *ch = NULL;
        if (json_get_string(cfg, "update_channel", &ch) && ch != NULL)
            out->update_channel = ch;
        else
            free(ch);
        policy_apply(out, cfg);
    } else if (cfg != NULL) {
        out->config_corrupt = true;
    }
    free(cfg);

    char *idx = fs_read_file(out->index_file, &n);
    if (idx == NULL) out->index_ok = false;
    else if ((out->index_ok = index_is_valid(idx, n)) == false)
        out->index_corrupt = true;
    free(idx);

    out->manifests = fs_count_manifests(out->root);
    return true;
}

// Quarantine-and-replace corrupt records (the pre-lock state_open behaviour
// that mutation paths keep, just moved behind the lock).
void state_repair_corrupt(state_ctx *st)
{
    if (st->config_corrupt) {
        quarantine(st->config_file, "config");
        fs_write_file(st->config_file, DEFAULT_CONFIG);
        st->config_corrupt = false;
    }
    if (st->index_corrupt) {
        quarantine(st->index_file, "index");
        st->index_ok = false;
        st->index_corrupt = false;
    }
}

void state_report_query(const state_ctx *st, bool quiet)
{
    if (quiet) return;
    if (st->config_corrupt)
        fprintf(stderr, "Warning: config at %s is corrupt; defaults used, "
            "file left in place\n", st->config_file);
    if (st->index_corrupt)
        fprintf(stderr, "Warning: index at %s is corrupt; left in place\n",
            st->index_file);
    char *pend = txn_doctor_issue(st->root);
    if (pend != NULL) {
        fprintf(stderr, "%s\n", pend);
        free(pend);
    }
}

// --- lock ---

static int s_lock_fd = -1;   // held until exit on purpose

bool state_lock_held(void) { return s_lock_fd >= 0; }

bool state_lock(const char *root)
{
    if (!fs_mkdir_p(root)) {
        fprintf(stderr, "Cannot acquire state lock at %s/grm_lock: %s\n",
            root, strerror(errno));
        return false;
    }
    char *p = path_join(root, "grm_lock");
    if (p == NULL) return false;
    int fd = open(p, O_CREAT | O_RDWR, 0644);
    if (fd >= 0 && flock(fd, LOCK_EX | LOCK_NB) == 0) {
        s_lock_fd = fd;
        return true;
    }
    int e = errno;
    fprintf(stderr, "Cannot acquire state lock at %s: %s\n", p, strerror(e));
    if (fd >= 0) close(fd);
    return false;
}

// Recovery under the lock before the command's own work; a roll-forward
// that never wrote its history line appends it now. False blocks the command.
bool state_recover_gate(const state_ctx *st, bool quiet, bool json)
{
    bool nh = false;
    const char *no = NULL, *nr = NULL;
    char ob[64], rb[768];
    if (!txn_recover_startup(st->root, st->install_root, quiet, json,
            &nh, &no, &nr, ob, sizeof(ob), rb, sizeof(rb)))
        return false;
    if (nh && no != NULL && nr != NULL) oplog_append(st, no, nr, "-", NULL);
    return true;
}

// --- index write (entries land with the manifest module) ---

// Index rebuild scans the manifests; the foreign/corrupt warnings are its
// user-visible part.
void state_scan_manifests_quiet(const char *root)
{
    manifest_entry *ms = NULL;
    size_t n = 0;
    if (manifests_scan(root, &ms, &n) != 0) return;
    for (size_t i = 0; i < n; i++) manifest_entry_free(&ms[i]);
    free(ms);
}

static bool write_index(const state_ctx *st);

bool state_rebuild_index(const state_ctx *st)
{
    return write_index(st);
}

static bool write_index(const state_ctx *st)
{
    state_scan_manifests_quiet(st->root);
    char ts[40];
    struct timespec tp;
    clock_gettime(CLOCK_REALTIME, &tp);
    struct tm tm;
    gmtime_r(&tp.tv_sec, &tm);
    snprintf(ts, sizeof(ts), "%04d-%02d-%02dT%02d:%02d:%02dZ",
        tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday, tm.tm_hour,
        tm.tm_min, tm.tm_sec);
    char *text = path_fmt(
        "{\"kind\":\"grm.index\",\"schema_version\":1,\"generated_at\":\"%s\","
        "\"apps\":[]}", ts);
    bool ok = text != NULL && fs_write_file(st->index_file, text);
    free(text);
    return ok;
}

void state_ensure(state_ctx *st, bool quiet, bool json)
{
    fs_mkdir_p(st->root);
    if (!st->config_present) {
        fs_write_file(st->config_file, DEFAULT_CONFIG);
        st->config_created = true;
    }
    char *apps = path_join(st->root, "applications");
    fs_mkdir_p(apps);
    free(apps);

    // Scan before touching the index so foreign/corrupt warnings precede
    // setup and rebuild lines.
    state_scan_manifests_quiet(st->root);

    bool rebuilt = false;
    if (!st->index_ok) {
        rebuilt = write_index(st);
        st->index_ok = rebuilt;
    }
    if (quiet || json) return;
    if (st->config_created) {
        fprintf(stderr, "First-time setup complete.\n");
        fprintf(stderr, "  config: %s\n", st->config_file);
        fprintf(stderr, "  index:  %s\n", st->index_file);
    } else if (rebuilt) {
        fprintf(stderr, "Rebuilt index at %s\n", st->index_file);
    }
}
