#!/usr/bin/env python3
"""Pending recovery owns the journal across main, self hooks and direct writes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def inside(path, root):
    return Path(path).resolve().is_relative_to(root.resolve())


def snapshot(paths):
    result = {}
    for parent in paths:
        for p in [parent, *parent.rglob("*")]:
            if not p.exists() and not p.is_symlink():
                continue
            st = p.lstat()
            value = os.readlink(p) if p.is_symlink() else p.read_bytes() if p.is_file() else None
            result[str(p)] = (st.st_mode, st.st_ino, st.st_mtime_ns if value is not None else None, value)
    return result


def run_case(driver, product, base, name):
    root = base / name
    state = root / "data/github-release-manager"
    install = root / "install"
    app = state / "applications/github/owner__tool"
    self_app = state / "applications/self" / ("~" + sha(b"self\0ape-get"))
    self_manifest = self_app / "grm_manifest.json"
    target = install / "ape-get"
    for p in [root / "home", root / "tmp", root / "config", root / "cache", app, install]:
        p.mkdir(parents=True, exist_ok=True)
    (state / "grm_lock").touch()
    sentinel = install / "unrelated-command"
    sentinel.write_bytes(b"unrelated bytes\x00\xff")
    binary_stage = install / "tool.tmp-0123456789abcdef"
    manifest_stage = app / "grm_manifest.json.tmp-0123456789abcdef"
    binary_stage.write_bytes(b"retained package binary\x00\xff")
    manifest_stage.write_bytes(b'{"retained":"package manifest"}\n')
    journal = state / "grm_transaction.json"
    journal_tmp = state / "grm_transaction.json.tmp"
    journal_tmp.write_bytes(b"pre-existing write temporary; keep on refusal\n")
    record = {"kind": "grm.transaction", "schema_version": 1, "id": "0123456789abcdef",
              "op": "install", "ref": "github:owner/tool", "phase": "committing",
              "created_at": "2026-11-14T00:00:00Z", "new": {
                  "binary_tmp": str(binary_stage), "manifest_tmp": str(manifest_stage),
                  "manifest": str(app / "grm_manifest.json"),
                  "asset_sha256": sha(binary_stage.read_bytes()),
                  "manifest_sha256": sha(manifest_stage.read_bytes())}}
    image = bytearray(128)
    image[80:87] = b".cosmo\n"
    image[106:110] = b"PK\x05\x06"
    executable = root / "download.com"
    executable.write_bytes(image)
    mode = "normal"
    main_case = name.startswith("main-")
    writer = name.startswith("writer-")
    refresh = name.startswith("pending-refresh-")
    kind = name.rsplit("-", 1)[1]
    if refresh:
        self_app.mkdir(parents=True)
        target.write_bytes(image)
        executable = target
        self_manifest.write_text(json.dumps({
            "kind": "grm.manifest", "schema_version": 1, "provider": "self", "repo": "ape-get",
            "version": "0.0.0", "locked": True,
            "asset": {"name": "ape-get", "url": "https://example.invalid/ape-get", "sha256": sha(image)},
            "install": {"kind": "ape", "path": str(target), "root": str(self_app)},
            "installed_at": "2020-01-02T03:04:05Z", "updated_at": "2020-02-03T04:05:06Z",
            "extension": {"preserve": [1, True, None]}}))
    if kind in {"prepared", "committing", "committed"}:
        record["phase"] = kind
    elif name == "writer-same":
        record["phase"] = "prepared"
    if kind == "directory":
        journal.mkdir()
        (journal / "sentinel").write_bytes(b"unreadable directory journal\n")
    elif kind == "dangling":
        journal.symlink_to(root / "missing-journal-target")
    elif kind == "malformed":
        journal.write_bytes(b'{"kind":"truncated journal"')
    elif kind != "absent" and not main_case:
        journal.write_text(json.dumps(record))
    if name.endswith("dry-run") or name.startswith("dry-run-"):
        mode = "dry-run"
    if writer:
        mode = "writer"
        incoming = dict(record, id="fedcba9876543210", phase="committed")
        if kind in {"same", "op", "ref", "invalid", "emptyid", "emptyop", "emptyref"}:
            incoming["id"] = record["id"]
        if kind == "same":
            incoming["phase"] = "committing"
        if kind.startswith("empty"):
            incoming[kind[5:]] = ""
        if kind == "op":
            incoming["op"] = "self-update"
        elif kind == "ref":
            incoming["ref"] = "github:another/tool"
        executable = root / "incoming.json"
        executable.write_text("invalid JSON" if kind == "invalid" else json.dumps(incoming))
    command = []
    if main_case:
        # CMake's native-macOS entry is a shell wrapper around the APE image.
        # Copy that image so self resolution can only read a disposable file.
        source = product
        if product.read_bytes().startswith(b"#!/bin/sh\nexec /bin/sh "):
            words = shlex.split(product.read_text().splitlines()[1])
            assert words[:2] == ["exec", "/bin/sh"] and words[3:] == ["$@"], words
            source = Path(words[2]).resolve(strict=True)
        executable = root / ("download.com" if source.suffix == ".com" else "download")
        shutil.copy2(source, executable)
        command = (["/bin/sh"] if executable.suffix == ".com" else []) + [str(executable)]
        # Startup may sweep orphans; these belong to the failed package and
        # are retained ordinary package data, not startup orphan candidates.
        binary_stage.rename(app / "retained-binary-stage")
        manifest_stage.rename(app / "retained-manifest-stage")
        journal_tmp.unlink()
        (state / "grm_config.json").write_text(json.dumps({
            "kind": "grm.config", "schema_version": 1, "backup_count": 1,
            "install_root": str(install), "providers": {}}))
        (state / "grm_index.json").write_text(json.dumps({
            "kind": "grm.index", "schema_version": 1, "generated_at": "2026-11-14T00:00:00Z", "apps": []}))
        if kind == "uninstall":
            blocker = root / "desktop-directory"
            blocker.mkdir()
            payload = app / "tool.com"
            payload.write_bytes(b"owned package bytes\n")
            launcher = install / "tool"
            launcher.symlink_to(payload)
            (app / "grm_manifest.json").write_text(json.dumps({
                "kind": "grm.manifest", "schema_version": 1, "provider": "github", "repo": "owner/tool",
                "version": "1.0.0", "asset": {"name": "tool.com", "url": "https://example.invalid/tool.com"},
                "install": {"kind": "ape", "path": str(payload), "command": "tool",
                            "uninstall": {"path": str(launcher)}, "desktop_entry": str(blocker)},
                "installed_at": "2026-11-14T00:00:00Z", "backups": []}))
            command += ["uninstall", "github:owner/tool", "--yes"]
        elif kind == "lock":
            command += ["lock", "github:missing/tool"]
        else:
            command += ["list", "--json"]
    env = dict(os.environ, HOME=str(root / "home"), USERPROFILE=str(root / "home"),
               XDG_DATA_HOME=str(root / "data"), XDG_CONFIG_HOME=str(root / "config"),
               XDG_CACHE_HOME=str(root / "cache"), TMPDIR=str(root / "tmp"),
               TEMP=str(root / "tmp"), TMP=str(root / "tmp"), APEGET_NO_SELF="0", NO_COLOR="1")
    for p in root.rglob("*"):
        assert inside(p, root), ("unsafe fixture or symlink", p)
    for key in ["HOME", "USERPROFILE", "XDG_DATA_HOME", "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "TMPDIR", "TEMP", "TMP"]:
        assert inside(env[key], root), ("unsafe environment", key)
    for value in record["new"].values():
        if value.startswith("/"):
            assert inside(value, root), ("unsafe journal path", value)
    before = snapshot([state, install])
    if kind == "permission":
        journal.chmod(0)
        if os.name == "posix" and os.geteuid() != 0:
            try:
                journal.read_bytes()
            except PermissionError:
                pass
            else:
                raise AssertionError("permission fixture must actually be unreadable")
    try:
        p = subprocess.run(command if main_case else [str(driver), str(root), mode, str(executable)],
                           env=env, cwd=root, capture_output=True, text=True, timeout=20)
    finally:
        if kind == "permission":
            journal.chmod(0o644)
    assert p.returncode == (1 if kind == "lock" else 0), (name, p.returncode, p.stdout, p.stderr)
    if main_case:
        if kind == "list":
            assert target.read_bytes() == executable.read_bytes(), name
            assert json.loads(self_manifest.read_text())["asset"]["sha256"] == sha(target.read_bytes()), name
        elif kind == "uninstall":
            assert not journal.exists(), (name, p.stderr)
            assert target.exists() and self_manifest.exists(), (name, p.stderr)
        else:
            assert not target.exists() and not self_manifest.exists(), (name, p.stderr)
            assert not journal.exists(), name
    elif writer and kind in {"same", "absent"}:
        assert json.loads(p.stdout)["written"], (name, p.stdout, p.stderr)
        assert journal.read_text() == executable.read_text(), name
        if kind == "same":
            incoming["phase"] = "committed"
            executable.write_text(json.dumps(incoming))
            p = subprocess.run([str(driver), str(root), mode, str(executable)], env=env, cwd=root,
                               capture_output=True, text=True, timeout=20)
            assert p.returncode == 0 and json.loads(p.stdout)["written"], (name, p.stdout, p.stderr)
            assert journal.read_text() == executable.read_text(), name
        after = snapshot([state, install])
        for path, value in before.items():
            if path not in {str(journal), str(journal_tmp)}:
                assert after[path] == value, (name, "stage changed", path)
    else:
        assert snapshot([state, install]) == before, (name, "journal, stage or self state changed", p.stderr)
        if writer:
            assert not json.loads(p.stdout)["written"], (name, p.stdout)
        elif mode != "dry-run":
            assert "transaction journal" in p.stderr, (name, p.stderr)
    assert sentinel.read_bytes() == b"unrelated bytes\x00\xff", name
    print("PASS", name, flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--driver", required=True, type=Path)
    parser.add_argument("--bin", required=True, type=Path)
    parser.add_argument("--only", action="append")
    args = parser.parse_args()
    cases = args.only or [
        *[f"pending-{action}-{kind}" for action in ["registration", "refresh"]
          for kind in ["prepared", "committing", "committed", "malformed", "directory", "dangling", "permission"]],
        "pending-registration-dry-run", "pending-refresh-dry-run", "dry-run-absent",
        *[f"writer-{kind}" for kind in ["prepared", "committing", "committed", "malformed", "directory",
                                        "dangling", "permission", "same", "op", "ref", "invalid",
                                        "emptyid", "emptyop", "emptyref", "absent"]],
        "main-failed-uninstall", "main-failed-lock", "main-success-list"]
    parent = Path(os.environ.get("APEGET_TEST_WORK_ROOT", tempfile.gettempdir())).resolve()
    parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="self-pending-", dir=parent) as temp:
        for name in cases:
            run_case(args.driver.resolve(), args.bin.resolve(), Path(temp).resolve(), name)
    print(f"{len(cases)} journal ownership cases passed")


if __name__ == "__main__":
    main()
