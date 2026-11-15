#!/usr/bin/env python3
"""Self refresh keeps raw policy/history and never recertifies different bytes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def inside(path, root):
    return Path(path).resolve().is_relative_to(root.resolve())


def run_case(driver, product, base, name):
    root = base / name
    state = root / "data/github-release-manager"
    install = root / "install"
    app = state / "applications/self" / ("~" + sha(b"self\0ape-get")
        if name.startswith("registration") else "ape-get")
    manifest = app / "grm_manifest.json"
    binary = install / "ape-get"
    for path in [root / "home", root / "config", root / "cache", root / "tmp", app, install]:
        path.mkdir(parents=True)
    (state / "grm_lock").touch()
    image = bytearray(128)
    image[80:87] = b".cosmo\n"
    image[106:110] = b"PK\x05\x06"
    binary.write_bytes(image)
    backup = app / "backups/retained"
    backup.mkdir(parents=True)
    backup_binary = backup / "old-binary"
    backup_binary.write_bytes(b"precious rollback bytes\x00\xff")
    backup_manifest = backup / "old-manifest.json"
    backup_manifest.write_text('{"history":"keep exactly"}\n')
    sentinel = root / "unrelated"
    sentinel.write_text("keep unrelated bytes\n")
    rich = {
        "kind": "grm.manifest", "schema_version": 1, "provider": "self", "repo": "ape-get",
        "version": "0.0.0", "locked": True, "release_id": 12345,
        "asset": {"name": "ape-get-aarch64.com", "url": "https://example.invalid/v1/tool",
                  "sha256": sha(image), "extension": {"keep": [1, None, {"value": "raw"}]}},
        "install": {"kind": "ape", "path": str(binary), "root": str(app), "command": "ape-get",
                    "uninstall": {"path": str(binary), "args": [], "product_code": None},
                    "extension": {"keep": True}},
        "verification": {"status": "verified", "method": "sha256", "checksum_asset": "SHA256SUMS",
                         "expected": sha(image), "actual": sha(image), "extension": ["keep"]},
        "signature": {"status": "verified", "mechanism": "gpg", "signer": "Pinned signer",
                      "identity": "1234ABCD", "notarized": False, "extension": {"policy": "keep"}},
        "backups": [{"path": str(backup), "version": "0.0.0-old", "binary": str(backup_binary),
                     "manifest": str(backup_manifest), "extension": "keep"}],
        "installed_at": "2020-01-02T03:04:05Z", "updated_at": "2020-02-03T04:05:06Z",
        "provider_type": "github", "provider_api": "https://api.example.invalid", "upstream_ref": "github:o/r",
        "extension": {"nested": [None, True, {"unicode": "žluťoučký", "escapes": "\n\t\\\""}]},
        "opaque\u0000tail": {"escaped key": "preserve raw spelling"},
    }
    mode = "normal"
    executable = binary
    expected_refresh = name in {"rich-refresh", "uppercase-digest", "missing-updated-at", "epoch-installed"}
    (state / "grm_config.json").write_text(json.dumps({
        "kind": "grm.config", "schema_version": 1, "backup_count": 1,
        "install_root": str(install), "providers": {}}))
    (state / "grm_index.json").write_text(json.dumps({
        "kind": "grm.index", "schema_version": 1, "generated_at": "2026-11-14T00:00:00Z", "apps": []}))
    if name.startswith("registration"):
        executable = root / "download.com"
        executable.write_bytes(binary.read_bytes())
        assert inside(binary, root)
        binary.unlink()
        if name.endswith("recovery"):
            mode = "stop-commit"
        elif name.endswith("hash-failure"):
            mode = "hash-failure"
    else:
        if name == "changed-bytes":
            rich["asset"]["sha256"] = sha(b"the previous artifact")
        elif name == "missing-digest":
            rich["asset"].pop("sha256")
        elif name == "null-digest":
            rich["asset"]["sha256"] = None
        elif name == "uppercase-digest":
            rich["asset"]["sha256"] = rich["asset"]["sha256"].upper()
        elif name == "missing-updated-at":
            rich.pop("updated_at")
        elif name == "epoch-installed":
            rich["installed_at"] = 1577934245000
        elif name == "unreadable":
            mode = "unreadable"  # deterministic EACCES even under elevated test users
        elif name in {"missing-target", "unreadable-missing-target"}:
            executable = root / "download.com"
            executable.write_bytes(binary.read_bytes())
            assert inside(binary, root)
            binary.unlink()
            if name.startswith("unreadable"):
                mode = "unreadable"
        elif name == "foreign-identity":
            rich["repo"] = "another-self"
        elif name == "future-schema":
            rich["schema_version"] = 2
        elif name == "newer-recorded-version":
            rich["version"] = "v999999999.0.0"
        elif name == "ambiguous-version":
            rich["version"] = "release-one"
        elif name == "suffix-difference":
            rich["version"] = "2.0.0+other-build"
        # Preserve unusual spacing, number spelling and escaping as raw spans.
        text = json.dumps(rich, ensure_ascii=True, indent=2).replace('"release_id": 12345', '"release_id": 1.2345e4')
        if name == "malformed":
            text = text[:-2]
        elif name == "trailing-garbage":
            text += " garbage"
        elif name == "nul-garbage":
            text += "\x00garbage"
        elif name == "duplicate-version":
            text = text.replace('"version":', '"version":"ambiguous", "version":', 1)
        manifest.write_text(text)
    env = dict(os.environ, HOME=str(root / "home"), USERPROFILE=str(root / "home"),
               XDG_DATA_HOME=str(root / "data"), XDG_CONFIG_HOME=str(root / "config"),
               XDG_CACHE_HOME=str(root / "cache"), TMPDIR=str(root / "tmp"),
               TEMP=str(root / "tmp"), TMP=str(root / "tmp"), APEGET_NO_SELF="0", NO_COLOR="1")

    def guard():
        for path in [state, install, app, manifest, binary, executable, backup, backup_binary,
                     backup_manifest, state / "grm_transaction.json", sentinel]:
            assert inside(path, root), ("unsafe fixture", path)
        for key in ["HOME", "USERPROFILE", "XDG_DATA_HOME", "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "TMPDIR", "TEMP", "TMP"]:
            assert inside(env[key], root), ("unsafe environment", key)
        for path in root.rglob("*"):
            assert inside(path, root), ("unsafe path or symlink", path)
        journal = state / "grm_transaction.json"
        if journal.exists():
            for side in ["old", "new"]:
                for value in json.loads(journal.read_text()).get(side, {}).values():
                    if isinstance(value, str) and value.startswith("/"):
                        assert inside(value, root), ("unsafe journal", value)

    def invoke():
        guard()
        p = subprocess.run([str(driver), str(root), mode, str(executable)],
                           env=env, cwd=root, capture_output=True, text=True, timeout=20)
        assert p.returncode == 0, (name, p.returncode, p.stdout, p.stderr)
        return json.loads(p.stdout), p.stderr

    before = manifest.read_bytes() if manifest.exists() else None
    files_before = {p: p.read_bytes() for p in [backup_binary, backup_manifest, sentinel]}
    result, stderr = invoke()
    version = result["version"]
    if expected_refresh:
        after = json.loads(manifest.read_text())
        assert after["version"] == version, (name, after)
        assert after["updated_at"] != rich.get("updated_at"), name
        original = json.loads(before)
        for key, value in original.items():
            if key in {"version", "updated_at"}:
                continue
            assert after[key] == value, (name, "lost field", key)
        # Each untouched raw value (including nested JSON whitespace) survives.
        decoder = json.JSONDecoder()
        for key in original:
            if key in {"version", "updated_at"}:
                continue
            marker = json.dumps(key) + ": "
            start = before.decode().index(marker) + len(marker)
            _, end = decoder.raw_decode(before.decode()[start:])
            assert before.decode()[start:start + end] in manifest.read_text(), (name, "raw value changed", key)
        assert not stderr, (name, stderr)
        # Optional v is the same identity: no timestamp, inode or byte rewrite.
        after["version"] = "v" + version.lstrip("v")
        manifest.write_text(json.dumps(after, indent=3))
        snapshot, stat = manifest.read_bytes(), manifest.stat()
        result, stderr = invoke()
        assert manifest.read_bytes() == snapshot and manifest.stat().st_ino == stat.st_ino, name
        assert manifest.stat().st_mtime_ns == stat.st_mtime_ns and not stderr, name
    elif name.startswith("registration"):
        if name.endswith("hash-failure"):
            assert result["checked_journals"] == 0 and not manifest.exists() and not binary.exists(), name
            assert not (state / "grm_transaction.json").exists(), name
            assert "cannot write the self manifest" in stderr, (name, stderr)
            assert not list(install.glob("*.tmp-*")) and not list(app.glob("*.tmp-*")), name
            assert files_before == {p: p.read_bytes() for p in files_before}, name
            print("PASS", name)
            return
        assert result["checked_journals"] == 1, (name, result)
        if name.endswith("recovery"):
            journal = state / "grm_transaction.json"
            assert journal.exists() and not binary.exists(), name
            guard()
            recovery_env = dict(env, APEGET_NO_SELF="1")
            p = subprocess.run([str(product), "lock", "self:ape-get"], env=recovery_env,
                               cwd=root, capture_output=True, text=True, timeout=20)
            assert p.returncode == 0, (name, p.stdout, p.stderr)
        record = json.loads(manifest.read_text())
        assert binary.read_bytes() == image and record["asset"]["sha256"] == sha(image), name
        assert not (state / "grm_transaction.json").exists(), name
    else:
        assert manifest.read_bytes() == before, (name, "existing record was rewritten")
        assert stderr.startswith("self-integration:"), (name, "missing refusal warning", stderr)
        if name.endswith("missing-target"):
            assert not binary.exists(), name
    assert files_before == {p: p.read_bytes() for p in files_before}, (name, "history bytes changed")
    assert not list(install.glob("*.tmp-*")) and not list(app.glob("*.tmp-*")), (name, "temporary files leaked")
    print("PASS", name)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--driver", required=True, type=Path)
    parser.add_argument("--bin", required=True, type=Path)
    parser.add_argument("--only", action="append")
    args = parser.parse_args()
    parent = Path(os.environ.get("APEGET_TEST_WORK_ROOT", tempfile.gettempdir())).resolve()
    parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="self-refresh-", dir=parent) as temp:
        base = Path(temp).resolve()
        cases = args.only or ["rich-refresh", "uppercase-digest", "missing-updated-at", "epoch-installed",
                "changed-bytes", "missing-digest", "null-digest", "malformed", "trailing-garbage", "nul-garbage",
                "duplicate-version", "foreign-identity", "future-schema", "newer-recorded-version",
                "ambiguous-version", "suffix-difference", "unreadable", "missing-target",
                "unreadable-missing-target", "registration", "registration-recovery", "registration-hash-failure"]
        for name in cases:
            run_case(args.driver.resolve(), args.bin.resolve(), base, name)
    print(f"{len(cases)} self refresh cases passed")


if __name__ == "__main__":
    main()
