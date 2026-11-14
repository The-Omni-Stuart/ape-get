# Spec: efficient delivery — `.xz` transport twins and binary deltas

Status: v4 — both heavy audits returned APPROVE-WITH-FIXES; all blockers
(audit #1 spec-vs-repo, audit #2 adversarial/security) incorporated;
audit trail in §12

Design law: be maximally efficient ALL THE TIME. No link-class detection,
no "mesh mode", no preferences per network — the fewest-bytes rule is
unconditional. LoRa-class radios (300 bps–9.6 kbps) are the motivating
worst case and the measuring stick, not a runtime concept.

Owner: ape-get c-port
Motivating numbers (measured 2026-11-02, x86_64 host, real artifacts):
fat APE 1,501,622 B raw → 587,952 B xz-9e (587,900 at 8 MiB dict);
x86_64 APE 842,448 → 387,090; point-release xdelta patch 16,482 B (1.95%);
no-op patch 323 B; direct delta 16,482 B < chained 16,805 B (address-shift
tax is paid per hop, so deltas are published per-old-version, never chained).

## 1. Goal

Make every supported artifact class as small as it can possibly be, for
every user, on every link, all the time — selecting strictly by fewest
bytes. Nothing about the network is detected or assumed. Two mechanisms:

* transport twins: publish `<artifact>.xz` next to `<artifact>`;
* binary deltas: publish patches to `<new>` from each of the previous K
  versions (direct jumps only; CI never chains).

Non-goals: no container repacking (no squashfs/PE surgery); no delta
*encoder* in the product (CI-side tool only); no new trust roots; no
changes outside asset resolution (commands like search/info/completions
are untouched, and no new flags or help-registry entries are added).

## 2. Supported artifact classes (unchanged set)

`APE (.com)`, `AppImage`, `Windows PE (.exe)` — each optionally followed
by `.xz` as pure transport encoding. `.xz` applies mechanically to *any*
class; CI publishes a twin only when it is meaningfully smaller (gate:
twin < 95% of raw, measured per asset at release time). Formats that
don't compress (self-compressed containers like squashfs AppImages)
simply never publish a twin — the manifest, not the code, decides.

## 3. Naming and parsing

Real CI asset names today are `ape-get-<ver>-fat.com`,
`ape-get-<ver>-x86_64.com` (c-build.yml:148-153). Extensions:

* twin:  `ape-get-2.0.1-fat.com.xz`      — raw name + `.xz`
* delta: `ape-get-2.0.1-x86_64.com-from-2.0.0.xdelta.xz`

Parser rule (the *decoded base name* helper, single shared
implementation): strip trailing `.xz`; then if the remainder ends
`.xdelta`, strip it plus the trailing `-from-<segment>` (last occurrence
wins). The `<segment>` is the source version and must match
`[A-Za-z0-9._+-]+` — anything else (a second `-from-`, path characters,
whitespace, URL escapes) makes the asset a plain unknown asset, never a
delta. Target version is the release version, never re-parsed from the
filename.

All version comparisons (`from` vs installed, freshness) go through one
shared comparator: strip exactly one leading `v`/`V`, then byte-for-byte
case-sensitive equality. `2.0.0-rc1` ≠ `2.0.0` (rc installs stay on the
full-asset path, correct); `V2.0.0` == `2.0.0`. This normalisation is
mandatory: the manifest records `tag_name` (`v2.0.0`) while asset names
use the v-stripped version (c-build.yml:149), and `list --outdated`'s
self freshness check is today a plain `strcmp` (update.c:310-312) that
reports self outdated unconditionally — the comparator fixes that bug
too (golden scenario required).

## 4. Client pipeline (single path for every class)

```
eligibility checks (§5.3/§5.4, incl. I4 hash gate — BEFORE any fetch)
  → select variant; stream download to ENVELOPE scratch (§ naming below),
    download capped at 2x the decode cap
  → if name ends .xz: liblzma stream-decode (limits per I6) → DECODED file
  → if base ends .xdelta: xdelta3 decode (limits per I6), source = the
    installed artifact file via its single already-open fd, output written
    through to DECODED file
  → verify sha256 of FINAL bytes (pin lookup per §4 lookups below)
  → existing staged commit (commit.c) + atomic swap + journal
```

Every file each pass writes (envelope scratch, decoded file) is created
or truncated fresh before its first write; the failed pass's files are
unlinked before any re-selection fetch (I3).

Decoded-name plumbing — the integrity layers today key every lookup on
the *selected asset name*, which is wrong for envelopes unless moved
(audit #1 B2/B3/B4):

* **Kind resolution**: `kind_from_asset_name` (package_kind.c:23-32)
  returns `K_UNKNOWN` for `.xz`; preflight treats that as a hard error.
  Kind, the `kind_matches` pool filter, and command-name derivation
  (command_name.c ext table gains `.xz` + delta stripping) all consume
  the §3 decoded base name helper.
* **Checksum/signature lookups**: `phase_checksum` builds candidates as
  `<asset-name>.sha256[.sum/.txt]/.digest` and matches the entry by exact
  asset name (integrity.c:35-53, :90); `phase_verify` finds `<asset>.sig`
  /`.asc` the same way (integrity.c:163-205); the APE-magic check reads
  the staged file (integrity.c:300-310). With an envelope selected, all
  candidate search and pin lookup MUST use the decoded base name, and
  `pc->tmp` MUST point at the decoded file before `phase_verify` — else
  the `.xz` magic kills native APE installs and honest pin files (which
  contain the raw artifact's entry) never match. A raw-asset signature
  therefore protects twin and delta output identically (audit #2).
* **Staging/journal naming**: the staged filename *is* the target name
  (`j_staged = <vdir>/<asset-basename>.tmp-<id>`, commit.c:152-155;
  recovery strips `.tmp-<id>` to find the final path, txn/recover.c:
  30-41). A twin must therefore journal as `<vdir>/<decoded-base>.tmp-<id>`
  so installs land as `foo.com`, never `foo.com.xz` — this keeps I4's
  "installed artifact file" unambiguous for the next delta. The envelope
  lives in a *separate* scratch file `<decoded-base>.xz.tmp-<id>`: the
  `.tmp-<16hex>` suffix form is load-bearing so the journal-absent crash
  sweep reaps it (recover.c:64-88 matches last `.tmp-` + 16 lowercase
  hex, txn.c:69-86, and each name derives its own final path — no
  cross-confusion). The envelope is unlinked right after decode
  succeeds, by the run.c cleanup tail (run.c:117-124) on failure paths,
  AND by the prepared-phase rollback (the journal names only the staged
  file; without this line a pre-commit crash orphans the envelope until
  the next journal-absent boot).
* **`--assimilate` and every filename derivation** (commit.c:209-210
  derives its base from the raw asset name) must consume the decoded
  base — a twin + `--assimilate` must not land `foo.com.xz.native`.

Invariants:

* **I1** — the pin always covers the DECODED artifact, never the envelope.
  Re-packaging must never look like a supply-chain attack.
* **I2** — every decode is streaming with a hard output cap: constant
  `APEGET_DECODE_CAP = 8 MiB` (CI size gates keep artifacts ≤ 1.5 MB, so
  no honest asset comes near it; there is no provider size metadata
  today — `prov_asset` is `{name,url}` — so a constant is the honest
  cap). The *envelope download* is capped at 16 MiB (2x): an honest xz
  envelope is ≈ its decoded size, so this only refuses attacker padding
  before airtime and disk are spent. Overflow, trailing bytes after the
  xz stream (decoder initialised with flags `0`, no `LZMA_CONCATENATED`
  — a concatenated second stream fails closed), a missing stream check
  field, or `unsupported feature` fail closed (no execution).
* **I3** — delta *eligibility* (version match, recorded hash, live hash)
  is evaluated at selection time, before any fetch — an ineligible
  install never spends radio on a patch. A *post-fetch* delta failure
  (corrupt patch, window errors, source changed mid-read) triggers
  exactly ONE re-selection of the smallest full variant (twin, else
  raw), re-running download→decode→I1 from scratch with fresh truncated
  files and no state carried (no `vr`/`sres`/`tmp` reuse). A full-variant
  failure after that is a hard error — never a third fetch, never a
  loop. The fallback is not an error path; it is the normal path taken
  differently.
* **I4** — a delta is eligible only when the manifest entry for the
  installed version records a sha256 (the field exists in manifest JSON
  but is unparsed today; self records null — §6) AND the installed
  file's live sha256 matches it. Mechanism, pinned here because the
  naive version is not implementable: open the installed file ONCE
  (`O_RDONLY|O_CLOEXEC`), hash to EOF through that fd, decide
  eligibility from that hash pre-fetch, then `lseek(0)` and hand the
  SAME fd to the decoder — no second path resolution, no reopen, so a
  local file swap between gate and apply cannot make the pin cover
  bytes the patcher never consumed. Gate failure ⇒ ineligible ⇒ silent
  full-variant selection, never a decode.
* **I5** — envelope integrity comes from the xz container's own check
  field (verified on decode; any non-none id accepted — liblzma verifies
  whichever of CRC32/CRC64/SHA256 is present; CI uses
  `--check=sha256`), surfacing transport corruption before the I1
  verification.
* **I6** — decoder memory is bounded *before* allocation, sized for
  router-class devices, because both formats let a hostile header ask
  for gigabytes:
  * xz: the first Block Header is pre-parsed (small parser) and the
    stream is rejected — falling back to the next variant — unless the
    filter chain is exactly [LZMA2], the stream check id is non-none,
    and the declared LZMA2 dictionary ≤ **8 MiB** (the honest maximum:
    CI compresses with dict=8MiB, measured equal-or-better than `-9e`
    defaults at our sizes). The decoder is initialised with liblzma
    `memlimit` = **16 MiB**; worst-case decoder RAM is therefore ~12 MiB
    whether or not liblzma re-checks per block (first block: high
    confidence the cap fires pre-allocation; per-block: ~70%, so a
    mandatory two-block escalation fixture — block 1 dict 8 MiB, block 2
    dict 64 MiB ⇒ `LZMA_MEMLIMIT_ERROR`, no allocation — is a pre-merge
    gate). If the fixture shows no per-block re-check, the client must
    enforce single-block streams itself (pre-parse rejects multi-block)
    — that contingency is decided by the fixture, not by faith.
  * xdelta: the first VCDIFF window header is pre-parsed as an
    early-exit ONLY, fail-soft (any parse anomaly ⇒ "not a delta", full
    variant; never a crash, never a skip of I1): declared source window
    ≤ installed source size, declared target window ≤ `APEGET_DECODE_CAP`.
    The binding bound is that the xdelta3 streaming decoder does not
    pre-allocate the target window and cumulative output is capped at
    I2 (every VCDIFF instruction emits ≥1 byte, so instructions ≤ cap).

## 5. Asset selection (preflight.c / arch.c)

1. **Group logical artifacts.** Group key: decoded base name (§3) minus
   the artifact extension, minus ONE trailing *variant* token, where
   variant tokens are the `arch_detect` tag set (arch.c:9-17 — which
   already maps universal-class tags like `universal2`/`all-arch`, and
   includes alias spellings such as `x86-64` ≡ `x86_64`) **plus the
   token `fat`**. Explicitly: `ape-get-2.0.1-fat.com` and
   `ape-get-2.0.1-x86_64.com` share the key `ape-get-2.0.1`;
   `aarch64`-tagged lines share it too and are arch-filtered next.
2. **Pool filter** (preflight.c:102-104, the compat `.exe` pool at
   :93-100, AND the `list --outdated` pool at update.c:292-296 — see
   §5.7) must admit names ending `.xz` and `.xdelta.xz` whose decoded
   base ends in an already-accepted extension — otherwise grouping never
   sees the twins at all (today they are silently dropped).
3. **Variant preference within a group: eligible delta > twin > raw.**
   Static, provably byte-minimal against honest-CI output (§8 publishes
   deltas only when smaller than the twin, twins only when smaller than
   raw), so **no size metadata and no probing is ever consulted** —
   provider size fields are ignored entirely (audit #1 N1: no adapter
   parses them; a cross-check would be dead code). For third-party
   repos outside the gates, a dishonestly large twin may cost bytes;
   security is unaffected because I1 pins decide acceptance. Delta
   eligibility per I3/I4, evaluated pre-fetch.
4. **Arch preference — explicit semantic change owned here** (audit #1
   B1, audit #2 fix 5). Within a group, drop members tagged for a
   non-current arch. If any current-arch-tagged *spelling* remains:
   auto-select that line (fewer bytes than fat) **only if exactly one
   distinct spelling survives** — two spellings of the same arch (e.g.
   `x86_64` and `x86-64`, both arch group 3, upload order attacker-
   controlled) is ambiguity and falls to today's picker prompt, which
   shows the user both names. With a single survivor line, prefer its
   variant per §5.3; if no current-arch line exists, the untagged/fat
   line wins. Today both fat and tagged survive `arch_prefer`
   (arch.c:80-89) and the "Multiple assets found" prompt fires
   (preflight.c:176-186); this rule resolves our own releases to one
   line silently — the ISSUES §65 fix, a user-visible change on fresh
   installs (changelog line; fat stays reachable via `--asset`).
   Whatever variant the rules pick, its full asset name is printed on
   the normal install path — grouping must not hide which bytes were
   chosen.
5. **`--asset <name|index>` selects the LOGICAL ARTIFACT (group), not a
   byte-variant** (audit #1 B6). A name matches if it equals the group's
   raw base or any member's full name (`foo.com`, `foo.com.xz` — same
   group; index numbers the group list in release order, a deliberate
   change from indexing raw assets). Variant preference (§5.3) still
   applies inside the selected group. The update path's "pick asset
   named `prev->asset`" (preflight.c:156-160) resolves through the group
   too, or updates would never see deltas.
6. Groups that remain ambiguous after all of the above (two genuinely
   different tools, or the §5.4 same-arch spelling tie) keep today's
   picker prompt verbatim.
7. **Up-to-date identity becomes (decoded base name, version), not
   asset URL** (audit #2 fix 4). Both freshness sites — the preflight
   up-to-date gate (preflight.c:294-295) and `list --outdated`
   (update.c:310-315, whose raw-only pool would otherwise never match a
   recorded twin/delta URL and report every twin install outdated
   forever, or make every post-delta `update` re-download the full
   twin) — compare decoded base + version. A same-version re-upload is
   not an "update"; `--reinstall` (exists, cmd.c) remains the forced
   refresh.

## 6. Self-update

`self:ape-get` rides the same pipeline — verified: `self_upstream()`
(update.c:41-51, self.c:477-483) feeds the same `run_pipeline_at` pool.
Gaps this spec closes rather than assumes: self's manifest writer records
`"sha256":null` (self.c:257), so commit 2 records the real hash at
install/update time (commit already computes it, commit.c:324) — until
one full (non-delta) update lands on an existing install, self deltas
are ineligible per I4, which is the honest fallback, not a bug. The
self-integration first-run writer (self.c:243-275, same manifest file)
gets the same hash recording for consistency. Windows replacement of
the running image stays out of scope (tracked separately).

## 7. Vendoring and provenance

* `vendor/liblzma` — decoder-capable subset of liblzma 5.8.x.
  `vendor/xdelta3` — upstream 3.x `xdelta3.{c,h}` compiled with
  `XD3_ENCODER=0` (library license Apache-2.0, GPL CLI main never
  included; Apache-2.0 is compatible with GPLv3).
* Mechanism stated accurately (audit #1 N2): `vendor/mbedtls/PIN` is a
  *convention*, not machine-checked (the only machine-checked PIN today
  is `toolchain/PIN`, c-build.yml:46-49). New vendors get: a `PIN` file
  (url + sha256 + size) in that convention, upstream URL, provenance
  comment block, AND a CI step running `sha256sum -c` on both new PIN
  files. CI additionally diffs the vendored file list against the
  pinned tarballs' selection (subset curation is machine-checked, not
  eyeballed).
* Build (audit #1 N3): hand-written config header per the
  `toolchain/mbedtls_apeget.h` precedent (upstream liblzma generates its
  `config.h` via CMake; we have no such mechanism); vendored sources
  compile with `-w` (mbedall pattern, CMakeLists.txt:39) — never the
  `-Wall -Wextra -Werror` regime — while the NEW glue (decode entry
  points) compiles with the full default flags. New pipeline modules
  get a `deps.mk` row (check_deps_graph.py).
* Decoder-only reality (audit #1 N4): `lzma_code` dispatches encode and
  decode in one entry, so gc-sections may retain encoder code; the size
  budget below is a target MEASURED in commit 1, not asserted.

## 8. Release CI changes

1. Twins: after `bin/package.sh`, `xz -9e --lzma2=preset=9e,dict=8MiB
   --check=sha256 --keep` per artifact; publish only if `< 95%` of raw.
   (`xz` ships on ubuntu-latest.) 8 MiB dict measured equal-or-better
   than `-9e` defaults at our file sizes and matches the I6 client cap —
   honest twins always pass pre-parse; anything declaring bigger is
   rejected client-side by design.
2. Delta matrix (commit 2): install `xdelta3` on the runner via
   `apt-get install` (not preinstalled; precedent c-fuzz.yml:44-45);
   fetch the last K=3 **stable** release assets via the GitHub API (the
   job's token already has contents:read; prerelease tags publish deltas
   against stables only); `xdelta3 -e -s old new patch`, xz each patch,
   publish if `< 95%` of the twin. The encoder never ships. CI emits no
   secondary-compressor patches; a fixture still proves the client
   refuses one cleanly.
3. Gates: twins/deltas added to the upload-artifact path and the release
   publish list — current globs (`dist/ape-get*.com`, c-build.yml:127,
   :160-162) do not match `.xz` files; the APE magic gate globs
   `ape-get*.com` so envelopes escape it (by glob shape, stated so it
   stays true); binary size gates (package.sh:31-38, absolutes
   1572864/1048576) are re-baselined in commit 1 to measured size +
   margin; budget targets: +90 KB per single-arch, +140 KB fat (liblzma
   only in commit 1; xdelta3 measured on top in commit 2).
4. Release notes list bytes saved — the feature is its own demo.

K=3, 95%, and the dict size are workflow knobs, not in the binary.

## 9. Verification & test plan (house law: failing test first)

* Scenario fixtures (offline, mock_provider.py serves byte-exact
  fixtures; binaries via `{b64:...}`): resolution table goldens for —
  twin chosen over raw; delta chosen when `from` == installed (v-prefix
  and no-prefix tags both, regression for audit #1 B7); no recorded
  manifest hash ⇒ no delta, and no patch fetched (I3, pre-fetch gate);
  corrupt twin header; twin declaring first-block dict > 8 MiB (rejected
  → raw, no ~20 MiB alloc); two-block stream with 64 MiB dict in block 2
  (expect `LZMA_MEMLIMIT_ERROR`, no allocation — decides the I6
  contingency; block 2 with delta/power2 filter chain variant); declared
  oversized xdelta window; patch source mismatch; delta success ⇒
  manifest version advances and installed file is `foo.com` not
  `foo.com.xz`; twin install then `update` at same version ⇒ "Already
  up to date" (no re-download; §5.7) and `list --outdated` agrees;
  post-delta `update` at latest ⇒ no full re-download; `--asset` group
  semantics (name = raw variant, name = twin, index); fat-vs-tagged
  arch preference AND the `x86_64`-vs-`x86-64` spelling tie ⇒ picker
  prompt (audit #2 fix 5b); `list --outdated` self freshness with v-tags
  (kills the existing bug); envelope download > 16 MiB ⇒ refused before
  decode; selected variant name printed on stdout. Every fallback silent
  on stderr-success paths, journalled.
* Round-trip ctest: two tiny fixture binaries; encode with vendored
  xdelta3 encoder **test-build target only**, decode through the product
  code path, compare sha256; liblzma round-trip against `xz` CLI output
  with `--check=sha256`; patch with secondary compression declared ⇒
  clean failure ⇒ full-variant fallback.
* Fuzz: new targets for the xz-decode and xdelta-decode entry points
  added to src/test/fuzz/build.sh module list; entry points must not
  link crypto/mbedall (the harness greps `nm`); corpus seeds: truncated
  streams, multi-block, huge declared dict/window, trailing garbage,
  varint-anomaly VCDIFF headers.
* pty gate: unchanged (it is completions-only and no flags are added);
  update-flow coverage lives in the scenario harness (mock: upstream,
  as self/update-basic.env does).
* CI prints actual artifact sizes per commit; gates move with facts.

## 10. Security notes

* liblzma/xdelta3 vendored + pinned + provenance (the xz-utils 2024
  circus is exactly why the PIN step is machine-checked here, §7).
* Bombs: I6 bounds allocator-visible memory before decode (first-block
  pre-parse with [LZMA2]-only filter gate + 16 MiB memlimit + fail-soft
  window pre-parse); I2 caps output AND envelope input; worst-case
  decoder RAM ~12 MiB — stated, sized for routers.
* TOCTOU on the delta source: single fd, hash-while-reading, eligibility
  decided pre-fetch (I4).
* Delta output is untrusted until I1 passes; nothing touches PATH or
  desktop records before the existing commit.c staging step completes.
* A delta cannot deliver bytes its own release's raw asset couldn't
  (attacker controls patch + raw + checksum file; ADD-only patches emit
  any target; garbage fails I1 ⇒ silent fallback) — verified from pin,
  signature, downgrade and source-tamper angles in audit #2.
* Downgrade/typosquat: a delta asset lives inside release `<new>` and
  targets `<new>` — it inherits the trust and freshness rules of the
  full asset, adding no new authority; eligibility requires the exact
  installed-version match.
* Crash matrix (audit #2 §12.5, K0–K7): no kill point produces an orphan
  that isn't eventually swept (envelope covered by the prepared-rollback
  line above) and none produces a half-swap (single same-fs renames).
* No new network calls of any kind; selection is stateless (no sizes, no
  probing); the network is never characterised.
* No new flags → no cmd.c help-registry entries, no completions drift.

## 11. Phasing (two squashed signed commits)

* **Commit 1** — liblzma vendor + §4 decode step with I2/I5/I6 + §3
  helper + §5 grouping/preference (incl. §5.4 arch rule = §65 fix, §5.7
  identity) + §8 twins/gates + the commit-1 scenarios. Ships standalone:
  installs shrink ~60%; picker friction gone for our releases.
* **Commit 2** — xdelta3 vendor + delta resolution/apply (I3/I4) +
  manifest sha256 parse + self.c hash record + normalised version
  comparator (+ `list --outdated` fix) + §8 delta matrix + round-trip
  and fuzz gates + delta scenarios. Nothing in commit 2 forces a
  commit-1 rework (envelope plumbing, scratch naming, decoded-base
  helper all landed there).

## 12. Audit trail

* Audit #1 — spec vs repo reality (heavy, qwen-27B): APPROVE-WITH-FIXES.
  7 blockers (pool filter, integrity lookups, staging/journal naming,
  kind resolution, unpinnable I4, `--asset` semantics, v-prefix
  mismatch), 10 improvements, 5 missed risks — all incorporated (v3).
* Audit #2 — adversarial/security (heavy, qwen-27B): APPROVE-WITH-FIXES.
  5 blockers (I6 sized wrong for routers + unverified multi-block
  escalation; I4 not single-open ⇒ unimplementable; I3 fallback
  contradiction + shared-scratch poisoning; URL-identity regression in
  `update`/`list --outdated`; group key contradiction (`fat` not an arch
  token) + same-arch spelling tie) — all incorporated (v4). Trust model
  survived every attack attempt (pin bypass, sig bypass via envelope,
  multi-window poison, delta-escalation, varint bombs, sweep confusion
  — see §10). Memlimit per-block re-check is a ~70%-confidence
  assumption converted into a mandatory fixture (§9).
