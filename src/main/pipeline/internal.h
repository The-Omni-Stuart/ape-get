#ifndef APEGET_PIPELINE_INTERNAL_H
#define APEGET_PIPELINE_INTERNAL_H

// Pipeline-module internals: resolve_source_ref feeds the command entry
// points, the rest back run.c's phases. Public surface: pipeline.h.

#include <stdbool.h>
#include <stddef.h>
#include <string.h>
#include <strings.h>
#include <time.h>

#include "cmd.h"
#include "json.h"
#include "manifest.h"
#include "provider.h"
#include "state.h"
#include "verify.h"

// ---- checksum verification ----

enum vst { V_VERIFIED, V_MISSING, V_UNKNOWN_ASSET, V_INVALID, V_MISMATCH };

typedef struct {
    int status;
    char *expected, *actual, *checksum_asset;
} vresult;

// Everything the run_pipeline phases share; the single cleanup tail
// frees what the phases leave behind.
typedef struct {
    const cmd_opts *o;
    state_ctx *st;
    const char *ref;
    const manifest_entry *prev;
    bool report, ask;
    const char *version;
    char **to_out;
    bool updating;
    const char *op;
    char prov[128], path[512];
    // where to fetch, which for self-update differs from the manifest's own
    // coordinates: state keeps self:ape-get, the bytes come from upstream
    char fprov[128], fpath[512];
    int timeout_ms;
    provider_entry *plist;
    int pn;
    const provider_entry *pe;
    manifest_entry *ms;
    size_t mc;
    prov_release rel;
    prov_asset *pool;
    prov_asset *asset;
    int kind;
    bool compat_run;
    char *command, *launcher;
    char *wine_abs, *compat_tmp;
    vresult vr;
    char *tmp, *app_dir, *target, *entry, *native_name;
    char *mtmp;   // staged grm_manifest.json, renamed at the end
    sig_result sres;
    bool sres_used;
    // journal state (staged file: <target>.tmp-<id>)
    char j_id[17], j_created[40];
    char *j_staged, *j_vdir, *j_mfile;
    char base[512];   // decoded asset base name (transport envelope stripped)
    char sha_final[65];   // hash of the bytes being installed (manifest, journal)
    int delta_fd;     // delta source: installed file opened once, hashed (I4)
    bool delta_pass;  // selected asset is an eligible delta; its decode may re-select once
    bool delta_retry; // phase_decode asks for the single re-selection (I3)
    bool delta_fell_back;
    prov_asset fb_asset;  // smallest full variant of the delta's group (fallback)
    bool fb_ok;
    bool j_on, j_committed;
    char *detail;
    char *backups;
    bool failed;
    int rv;
} pipe_ctx;

// PIPE_GO: next phase; PIPE_DONE: unwind to the cleanup tail; PIPE_CLEANUP:
// as DONE, then drop the staged download and record the install unless failed.
enum { PIPE_DONE, PIPE_CLEANUP, PIPE_GO };

// pipeline/choose.c: interactive selection
long choose_index(const cmd_opts *o, const char *what, const char *none,
    size_t n);

// pipeline/arch.c: asset architecture preference
int arch_prefer(prov_asset *assets, int n);
int arch_line_of(const char *name);
int arch_line_current(void);
bool arch_strip_variant(char *name);

// pipeline/assimilate.c: cosmocc assimilate on a staged APE; returns a
// malloc'd <file>.native path, NULL with *err set on failure
char *assimilate_asset(const char *file, const char *disp_name, char **err);

// pipeline/commit.c: journal commit path: prepare, install, record
int phase_prepare(pipe_ctx *pc);
int phase_install(pipe_ctx *pc);
void phase_record(pipe_ctx *pc);

// pipeline/command_name.c: command name derivation from asset names
bool cn_valid(const char *v);
char *cn_from_asset(const char *repo_path, const char *asset_name);

// pipeline/desktop.c: Linux desktop entries
char *desktop_entry_path(const state_ctx *st, const char *command);
char *desktop_icon_copy(const char *requested, const char *app_dir);
void desktop_entry_write(const char *entry, const char *command,
    const char *launcher, const char *repo_path, const char *icon,
    bool terminal);
const char *desktop_recorded(const cmd_opts *o, const char *entry);

// pipeline/journal.c: one phase transition of the install journal
bool journal_phase_write(const state_ctx *st, const char *op,
    const char *ref, const char *id, const char *created,
    const char *phase, const char *vdir, const char *mfile,
    const char *mtmp, const char *staged, const char *sha,
    const char *backup, bool history, const manifest_entry *prev);

// pipeline/integrity.c: checksum and signature verification of the
// staged download
int phase_checksum(pipe_ctx *pc);
int phase_verify(pipe_ctx *pc);

// pipeline/package_kind.c: package kind tables; the phases carry the
// values in ints (K_UNKNOWN == -1)
enum kind {
    K_APPIMAGE, K_PORTABLE_EXE, K_APE, K_MSI, K_EXE, K_DMG,
    K_UNKNOWN = -1
};

const char *kind_manifest_name(int k);
int kind_from_asset_name(const char *name);
size_t artifact_base_name(const char *name, char *out, size_t cap);
bool artifact_delta_from(const char *name, char *from, size_t cap);
bool kind_is_portable(int k);
bool kind_supported_on_linux(int k);
int kind_from_option(const char *v);
int kind_from_manifest_kind(const char *v);
bool kind_matches(int requested, int actual, bool allow_unknown);
bool has_ape_magic(const char *path);

// pipeline/paths.c: path/file helpers for the phases
bool mkdir_p(const char *path);
char *sanitize_version(const char *v);
bool path_is_dir(const char *p);
bool path_exists(const char *p);
bool dir_on_path(const char *dir);
// owned by another ref when the target equals or sits under its path
bool owned_by_other(const manifest_entry *ms, size_t mc,
    const char *prov, const char *path, const char *target);

// pipeline/preflight.c: all pre-download selection and gating
int phase_preflight(pipe_ctx *pc);

// pipeline/progress.c: download progress UI; dl_line_buf backs
// dl_progress's line and must outlive the provider_download call
typedef struct {
    const char *asset;
    long long total;
    time_t tprev;
    size_t bprev;
    size_t seen;
    double ewma;
    char *line;
} dl_ctx;
extern char dl_line_buf[320];
void dl_progress(size_t done, long long total, void *ud);

// pipeline/signer.c: signer-conflict handling; resolve_pin returns
// false when the caller must fail the pipeline
char *describe_manifest_sig(const manifest_entry *m);
bool resolve_pin(const cmd_opts *o, state_ctx *st, const char *op,
    const char *ref, const char *repo, const char *reason,
    const char *deny_hint, const char *question, const char *warning);

// pipeline/run.c: the whole install/update pipeline.
#define RES_OK 0
#define RES_UP_TO_DATE 1
#define RES_PLANNED 2
#define RES_FAILED 3

int run_pipeline(const cmd_opts *o, state_ctx *st, const char *prov_in,
    const char *path_in, const char *ref, const manifest_entry *prev,
    bool report, bool ask, const char *version, char **to_out);
// as run_pipeline, fetching from fetch_prov:fetch_path while committing
// under prov_in:path_in (self-update: state stays self:ape-get)
int run_pipeline_at(const cmd_opts *o, state_ctx *st, const char *prov_in,
    const char *path_in, const char *ref, const char *fetch_prov,
    const char *fetch_path, const manifest_entry *prev,
    bool report, bool ask, const char *version, char **to_out);

// pipeline/source.c: owner/repo or bare name -> provider:path
bool resolve_source_ref(const cmd_opts *o, state_ctx *st,
    const char *argument, char *out, size_t cap);

static inline bool blank(const char *s) { return s == NULL || *s == '\0'; }

// The one version comparison: strip exactly one leading v/V, then exact
// byte equality. 2.0.0-rc1 != 2.0.0 (rc installs stay on full assets);
// V2.0.0 == 2.0.0. Mandatory because manifests record tag_name (v2.0.0)
// while asset names carry the v-stripped version.
static inline bool ver_eq(const char *a, const char *b)
{
    if (a == NULL || b == NULL) return false;
    if (*a == 'v' || *a == 'V') a++;
    if (*b == 'v' || *b == 'V') b++;
    return !strcmp(a, b);
}

static inline int ci_endswith(const char *name, const char *suffix)
{
    size_t l = strlen(name), sl = strlen(suffix);
    return l >= sl && !strncasecmp(name + l - sl, suffix, sl);
}

// decode.c: streaming .xz envelope decode with hostile-input caps
// (spec §4/I2/I5/I6). 0 = out_path holds the bytes; failure deletes it.
int xz_decode_file(const char *in_path, const char *out_path);

// wire cap for .xz envelope downloads (I2); must stay >= XZ_IN_CAP in
// decode.c so honest envelopes never trip the transfer gate first
#define XFER_ENVELOPE_MAX (16ll << 20)
// decode.c: apply a .xdelta patch to the bytes behind src_fd (hashed
// once by the caller, I4). 0 = out_path holds the target; failure deletes it.
int xdelta_apply_fd(int src_fd, const char *patch_path, const char *out_path);

#endif
