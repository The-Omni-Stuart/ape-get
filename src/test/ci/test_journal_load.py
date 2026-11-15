#!/usr/bin/env python3
"""Existing unreadable journals must block startup without sweeping stages.

All process directories, journal paths and symlink targets are guarded inside
disposable cases. Only synthetic files are removed, repaired or chmod'ed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


JID = "0123456789abcdef"
OLD = b"old synthetic artifact\n"
NEW = b"pending synthetic artifact\n"


def run_case(exe, driver, base, name, fault=None):
    root = base / name
    state = root / "data/github-release-manager"
    app = state / "applications/github/owner__tool"  # valid legacy layout
    install = root / "install"
    journal = state / "grm_transaction.json"
    binary = install / "tool"
    manifest = app / "grm_manifest.json"
    bt = install / ("tool.tmp-" + JID)
    mt = app / ("grm_manifest.json.tmp-" + JID)
    target = root / "journal-target"
    sentinel = install / "unrelated-command"
    if name == "permission" and (os.name == "nt" or (
            hasattr(os, "geteuid") and os.geteuid() == 0)):
        return "SKIP permission-dependent case on Windows/root"
    for path in [root / "home", root / "config", root / "cache", root / "tmp",
                 app, install]:
        path.mkdir(parents=True, exist_ok=True)

    def obj(version):
        return {"kind": "grm.manifest", "schema_version": 1,
                "provider": "github", "repo": "owner/tool", "version": version,
                "asset": {"name": "tool", "url": "https://example.invalid/tool"},
                "install": {"kind": "ape", "path": str(binary),
                            "uninstall": {"path": str(binary)}},
                "installed_at": "2026-11-14T00:00:00Z", "backups": []}

    old_manifest = json.dumps(obj("1.0.0")).encode()
    new_manifest = json.dumps(obj("2.0.0")).encode()
    binary.write_bytes(OLD)
    manifest.write_bytes(old_manifest)
    bt.write_bytes(NEW)
    mt.write_bytes(new_manifest)
    sentinel.write_bytes(b"preserve unrelated command\n")
    (state / "grm_config.json").write_text(json.dumps({
        "kind": "grm.config", "schema_version": 1, "backup_count": 1,
        "install_root": str(install), "providers": {}}))
    (state / "grm_index.json").write_text(json.dumps({
        "kind": "grm.index", "schema_version": 1, "apps": [],
        "generated_at": "2026-11-14T00:00:00Z"}))
    record = {"kind": "grm.transaction", "schema_version": 1, "id": JID,
              "op": "reinstall", "ref": "github:owner/tool",
              "phase": "prepared" if name == "prepared" else "committing",
              "created_at": "2026-11-14T00:00:00Z", "new": {
                  "binary_tmp": str(bt), "asset_sha256": hashlib.sha256(NEW).hexdigest(),
                  "manifest_tmp": str(mt), "manifest": str(manifest),
                  "manifest_sha256": hashlib.sha256(new_manifest).hexdigest()}}
    if name == "committed":
        record["phase"] = "committed"
    journal_text = json.dumps(record)
    if name == "absent":
        pass
    elif name == "directory":
        journal.mkdir()
    elif name == "dangling":
        journal.symlink_to(target)
    elif name == "symlink-loop":
        journal.symlink_to(journal)
    elif name == "readable-link" or (fault and fault.startswith("target-")):
        target.write_text(journal_text)
        journal.symlink_to(target)
    else:
        journal.write_text("invalid json" if name == "invalid-json" else
                           "" if name == "empty" else journal_text)

    env = dict(os.environ, HOME=str(root / "home"), USERPROFILE=str(root / "home"),
               XDG_DATA_HOME=str(root / "data"), XDG_CONFIG_HOME=str(root / "config"),
               XDG_CACHE_HOME=str(root / "cache"), TMPDIR=str(root / "tmp"),
               TEMP=str(root / "tmp"), TMP=str(root / "tmp"), APEGET_NO_SELF="1")
    env.pop("APEGET_TEST_CRASH", None)

    def guard(path):
        # Check the entry lexically too: resolve() cannot handle the loop case.
        assert Path(os.path.abspath(path)).is_relative_to(root.resolve()), path
        if Path(path) != journal or name != "symlink-loop":
            assert Path(path).resolve().is_relative_to(root.resolve()), path

    def invoke(use_fault=None, repaired=False):
        for path in [state, app, install, journal, binary, manifest, bt, mt,
                     target, sentinel, *[Path(env[k]) for k in [
                         "HOME", "USERPROFILE", "XDG_DATA_HOME", "XDG_CONFIG_HOME",
                         "XDG_CACHE_HOME", "TMPDIR", "TEMP", "TMP"]]]:
            guard(path)
        for side in ["old", "new"]:
            for key, value in record.get(side, {}).items():
                if isinstance(value, str) and value.startswith("/"):
                    guard(value)
        if journal.is_symlink():
            guard(journal.parent / os.readlink(journal))
        if use_fault:
            args = [str(driver), use_fault, str(root)]
        elif blocked and not repaired:
            args = [str(exe), "list", "--json"]
        else:
            args = [str(exe), "unlock", "github:owner/tool"]
        return subprocess.run(args, cwd=root, env=env, capture_output=True,
                              text=True, timeout=15)

    blocked = fault is not None or name in {
        "permission", "directory", "dangling", "symlink-loop", "invalid-json", "empty"}
    entry_content = os.readlink(journal) if journal.is_symlink() else (
        None if name in {"absent", "directory"} else journal.read_bytes())
    if name == "permission":
        journal.chmod(0)
    entry_before = journal.lstat() if name != "absent" else None
    try:
        p = invoke(fault)
        if blocked:
            if fault:
                result = json.loads(p.stdout)
                assert p.returncode == 0 and result == {
                    "load": "UNREADABLE", "recovered": False, "history": False,
                    "unreadable_issue": True, "reads": 3 if fault.startswith("read-") else 0
                }, (name, result, p.stderr)
            else:
                assert p.returncode == 0, (name, p.stdout, p.stderr)
            assert "journal is unreadable" in p.stderr, (name, p.stderr)
            entry_after = journal.lstat()
            assert (entry_before.st_ino, entry_before.st_mode) == (
                entry_after.st_ino, entry_after.st_mode), name
            assert bt.read_bytes() == NEW and mt.read_bytes() == new_manifest, name
            assert binary.read_bytes() == OLD and manifest.read_bytes() == old_manifest, name
    finally:
        if name == "permission":
            guard(journal)
            journal.chmod(0o600)

    if blocked:
        if journal.is_symlink():
            assert os.readlink(journal) == entry_content, name
            if target.exists():
                assert target.read_text() == journal_text, name
        elif journal.is_dir():
            assert not list(journal.iterdir()), name
        else:
            assert journal.read_bytes() == entry_content, name
        # Repair just this fixture and recover the original staged contents.
        if journal.is_symlink():
            journal.unlink()
        elif journal.is_dir():
            journal.rmdir()
        journal.write_text(journal_text)
        p = invoke(None, True)
    assert p.returncode == 0 and not journal.exists(), (name, p.stdout, p.stderr)
    assert not mt.exists(), name
    if name in {"absent", "prepared"}:
        assert binary.read_bytes() == OLD and manifest.read_bytes() == old_manifest, name
    else:
        assert binary.read_bytes() == NEW and manifest.read_bytes() == new_manifest, name
    if name == "absent":
        assert bt.read_bytes() == NEW, name
    assert sentinel.read_bytes() == b"preserve unrelated command\n", name
    return "PASS"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bin", required=True)
    parser.add_argument("--driver", required=True)
    parser.add_argument("--work-root", default=os.environ.get("APEGET_TEST_WORK_ROOT"))
    args = parser.parse_args()
    exe = Path(args.bin).resolve(strict=True)
    driver = Path(args.driver).resolve(strict=True)
    results = []
    with tempfile.TemporaryDirectory(prefix="journal-load-", dir=args.work_root) as temp:
        base = Path(temp).resolve()
        for name in ["permission", "directory", "dangling", "symlink-loop",
                     "invalid-json", "empty", "absent", "prepared", "committing",
                     "committed", "readable-link"]:
            results.append({"case": name, "result": run_case(exe, driver, base, name)})
        for fault in ["path-oom", "lstat-eio", "lstat-enomem", "lstat-eacces",
                      "lstat-enotdir", "lstat-eloop", "entry-special", "target-special",
                      "target-eio", "target-enoent", "read-eio", "read-enomem",
                      "read-eacces", "read-enoent", "read-no-errno"]:
            results.append({"case": fault, "result": run_case(exe, driver, base, fault, fault)})
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
