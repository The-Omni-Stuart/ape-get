#!/usr/bin/env python3
"""Recovery must prove staged/already-applied artifact and manifest identity.

All commands use disposable state, explicit install_root and isolated process
directories. No downloads, installed executables, mounts or shell setup.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


OLD = b"#!/bin/sh\nprintf 'old:%s\\n' \"$*\"\n"
NEW = b"#!/bin/sh\nprintf 'new:%s\\n' \"$*\"\n"
JID = "0123456789abcdef"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def inside(path, root):
    return Path(path).resolve().is_relative_to(root.resolve())


def run_case(exe, base, name):
    root = base / name
    state = root / "data/github-release-manager"
    install = root / "bin"
    app = state / "applications/github/owner__tool"
    binary = install / "tool"
    manifest = app / "grm_manifest.json"
    bt = Path(str(binary) + ".tmp-" + JID)
    mt = Path(str(manifest) + ".tmp-" + JID)
    for path in [root / "home", root / "config", root / "cache", root / "tmp",
                 state, install, app]:
        path.mkdir(parents=True, exist_ok=True)

    def obj(version):
        return {"kind": "grm.manifest", "schema_version": 1,
                "provider": "github", "repo": "owner/tool", "version": version,
                "asset": {"name": "tool", "url": "https://example.invalid/tool"},
                "install": {"kind": "ape", "path": str(binary),
                            "uninstall": {"path": str(binary)}},
                "installed_at": "2026-11-13T00:00:00Z", "backups": []}

    old_manifest = json.dumps(obj("1.0.0")).encode()
    new_manifest = json.dumps(obj("2.0.0")).encode()
    binary.write_bytes(OLD)
    binary.chmod(0o755)
    bt.write_bytes(NEW)
    bt.chmod(0o755)
    manifest.write_bytes(old_manifest)
    mt.write_bytes(new_manifest)
    sentinel = install / "unrelated-command"
    sentinel.write_text("preserve unrelated command\n")
    (state / "grm_config.json").write_text(json.dumps({
        "kind": "grm.config", "schema_version": 1, "backup_count": 1,
        "install_root": str(install), "providers": {}}))
    (state / "grm_index.json").write_text(json.dumps({
        "kind": "grm.index", "schema_version": 1, "apps": []}))
    journal = state / "grm_transaction.json"
    record = {"kind": "grm.transaction", "schema_version": 1, "id": JID,
              "op": "rollback" if name.startswith("rollback-") else "reinstall",
              "ref": "github:owner/tool", "phase": "committing",
              "created_at": "2026-11-13T00:00:00Z", "new": {
                  "binary_tmp": str(bt), "asset_sha256": digest(NEW),
                  "manifest_tmp": str(mt), "manifest": str(manifest),
                  "manifest_sha256": digest(new_manifest)}}
    fields = record["new"]
    reject = False
    repair = None
    binary_rejected = False
    if name in {"stale-final", "rollback-stale-final"}:
        bt.unlink()
        reject = binary_rejected = True
        repair = lambda: bt.write_bytes(NEW)
    elif name == "matching-final":
        bt.unlink()
        binary.write_bytes(NEW)
    elif name == "missing-both":
        bt.unlink()
        binary.unlink()
        reject = binary_rejected = True
        repair = lambda: bt.write_bytes(NEW)
    elif name == "staged-mismatch":
        bt.write_bytes(b"unexpected staged bytes")
        reject = binary_rejected = True
        repair = lambda: bt.write_bytes(NEW)
    elif name in {"invalid-digest", "short-digest"}:
        fields["asset_sha256"] = "g" * 64 if name == "invalid-digest" else digest(NEW)[:-1]
        reject = binary_rejected = True
        repair = lambda: fields.update(asset_sha256=digest(NEW))
    elif name == "uppercase-digests":
        fields["asset_sha256"] = fields["asset_sha256"].upper()
        fields["manifest_sha256"] = fields["manifest_sha256"].upper()
    elif name in {"typed-asset-digest", "typed-manifest-digest"}:
        field = "asset_sha256" if name == "typed-asset-digest" else "manifest_sha256"
        fields[field] = {"unexpected": "object"} if name == "typed-asset-digest" else 42
        reject = binary_rejected = True
        repair = lambda: fields.update({field: digest(NEW if field == "asset_sha256" else new_manifest)})
    elif name.startswith("legacy-"):
        fields.pop("asset_sha256")
        fields.pop("manifest_sha256")
        if name == "legacy-final":
            bt.unlink()
            reject = binary_rejected = True
            repair = lambda: bt.write_bytes(NEW)
        elif name == "legacy-manifest-final":
            mt.unlink()
            reject = True
            # Once binary rename has happened, this legacy journal also
            # needs the genuine stage restored to prove the binary step.
            def repair():
                bt.write_bytes(NEW)
                mt.write_bytes(new_manifest)
    elif name == "stale-manifest-final":
        bt.unlink()
        binary.write_bytes(NEW)
        mt.unlink()
        reject = True
        repair = lambda: mt.write_bytes(new_manifest)
    elif name == "matching-finals":
        bt.unlink()
        binary.write_bytes(NEW)
        mt.unlink()
        manifest.write_bytes(new_manifest)
    elif name == "staged-manifest-mismatch":
        mt.write_bytes(old_manifest)
        reject = True
        repair = lambda: mt.write_bytes(new_manifest)
    elif name == "missing-manifest":
        mt.unlink()
        manifest.unlink()
        reject = True
        repair = lambda: mt.write_bytes(new_manifest)
    elif name in {"partial-rename-retry"}:
        manifest.unlink()
        manifest.mkdir()
        reject = True
        repair = manifest.rmdir
    elif name == "committed":
        record["phase"] = "committed"
    elif name.startswith("committed-no-stage-"):
        # Older producer shape retained the manifest final path alone.
        record["phase"] = "committed"
        for field in ["binary_tmp", "asset_sha256", "manifest_tmp"]:
            fields.pop(field)
        bt.unlink()
        mt.unlink()
        binary.write_bytes(NEW)
        if name.endswith("matching"):
            manifest.write_bytes(new_manifest)
        else:
            reject = True
            if name.endswith("legacy"):
                fields.pop("manifest_sha256")
            def repair():
                manifest.write_bytes(new_manifest)
                fields["manifest_sha256"] = digest(new_manifest)

    env = dict(os.environ, HOME=str(root / "home"), USERPROFILE=str(root / "home"),
               XDG_DATA_HOME=str(root / "data"), XDG_CONFIG_HOME=str(root / "config"),
               XDG_CACHE_HOME=str(root / "cache"), TMPDIR=str(root / "tmp"),
               TEMP=str(root / "tmp"), TMP=str(root / "tmp"), APEGET_NO_SELF="1", NO_COLOR="1")

    def invoke(args=None, crash=None):
        for path in [state, install, binary, bt, manifest, mt, sentinel, journal]:
            assert inside(path, root), ("unsafe fixture", path)
        for key in ["HOME", "USERPROFILE", "XDG_DATA_HOME", "XDG_CONFIG_HOME",
                    "XDG_CACHE_HOME", "TMPDIR", "TEMP", "TMP"]:
            assert inside(env[key], root), ("unsafe environment", key)
        for path in root.rglob("*"):
            if path.is_symlink():
                assert inside(path, root), ("unsafe symlink", path)
        if journal.exists():
            for side in ["old", "new"]:
                for key, value in json.loads(journal.read_text()).get(side, {}).items():
                    if isinstance(value, str) and value.startswith("/"):
                        assert inside(value, root), ("unsafe journal", key, value)
        return subprocess.run([str(exe)] + (args or ["unlock", "github:owner/tool"]),
                              cwd=root, env=dict(env, **({"APEGET_TEST_CRASH": crash} if crash else {})),
                              capture_output=True, text=True, timeout=15)

    if name == "rollback-producer":
        bt.unlink()
        mt.unlink()
        backup = app / "backups/previous"
        backup.mkdir(parents=True)
        artifact = backup / "tool"
        artifact.write_bytes(NEW)
        artifact.chmod(0o755)
        backup_manifest = backup / "grm_backup_manifest.json"
        backup_manifest.write_bytes(new_manifest)
        current = obj("1.0.0")
        current["backups"] = [{"version": "2.0.0", "artifact": str(artifact),
                               "install_path": str(binary), "manifest": str(backup_manifest),
                               "created_at": "2026-11-13T00:01:00Z"}]
        manifest.write_text(json.dumps(current))
        p = invoke(["rollback", "github:owner/tool", "--yes"], "rollback")
        assert p.returncode == 70 and journal.exists(), (p.returncode, p.stdout, p.stderr)
        record = json.loads(journal.read_text())
        fields = record["new"]
        assert fields["asset_sha256"] == digest(NEW)
        assert fields["manifest_sha256"] == digest(Path(fields["manifest_tmp"]).read_bytes())
        # Same-name stale target must not pass even for a produced journal.
        Path(fields["binary_tmp"]).unlink()
        p = invoke()
        assert p.returncode != 0 and journal.exists() and binary.read_bytes() == OLD
        Path(fields["binary_tmp"]).write_bytes(NEW)
        p = invoke()
        assert p.returncode == 0 and not journal.exists(), (p.stdout, p.stderr)
        assert binary.read_bytes() == NEW
    else:
        journal.write_text(json.dumps(record))
        p = invoke()
        if reject:
            assert p.returncode != 0 and journal.exists(), (name, p.returncode, p.stdout, p.stderr)
            if binary_rejected:
                assert not binary.exists() or binary.read_bytes() == OLD
                assert manifest.read_bytes() == old_manifest
                assert mt.is_file(), "manifest committed after rejecting the binary"
            if name in {"partial-rename-retry"}:
                assert binary.read_bytes() == NEW and not bt.exists(), "binary step did not complete"
            repair()
            journal.write_text(json.dumps(record))
            p = invoke()
        assert p.returncode == 0 and not journal.exists(), (name, p.returncode, p.stdout, p.stderr)
        assert binary.read_bytes() == NEW and manifest.read_bytes() == new_manifest
        if not name.startswith("legacy-"):
            # Replaying after every rename has already completed must prove
            # both finals, then complete without requiring the vanished stages.
            journal.write_text(json.dumps(record))
            p = invoke()
            assert p.returncode == 0 and not journal.exists(), (p.stdout, p.stderr)
    assert sentinel.read_text() == "preserve unrelated command\n"
    return "PASS"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bin", required=True)
    parser.add_argument("--work-root", default=os.environ.get("APEGET_TEST_WORK_ROOT"))
    parser.add_argument("--case")
    args = parser.parse_args()
    exe = Path(args.bin).resolve(strict=True)
    if args.work_root:
        Path(args.work_root).mkdir(parents=True, exist_ok=True)
    cases = ["stale-final", "matching-final", "missing-both", "staged-mismatch",
             "invalid-digest", "short-digest", "uppercase-digests", "legacy-staged",
             "typed-asset-digest", "typed-manifest-digest",
             "legacy-final", "legacy-manifest-final", "stale-manifest-final", "matching-finals",
             "staged-manifest-mismatch", "missing-manifest", "partial-rename-retry",
             "rollback-stale-final", "rollback-producer", "committed",
             "committed-no-stage-stale", "committed-no-stage-matching", "committed-no-stage-legacy"]
    if args.case:
        assert args.case in cases
        cases = [args.case]
    with tempfile.TemporaryDirectory(prefix="apeget-recovery-identity-", dir=args.work_root) as td:
        for name in cases:
            print(name, run_case(exe, Path(td).resolve(), name), flush=True)
    print(f"{len(cases)} recovery identity cases completed")


if __name__ == "__main__":
    main()
