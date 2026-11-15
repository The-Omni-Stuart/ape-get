#!/usr/bin/env python3
"""Query commands leave the whole home tree byte-for-byte unchanged (S08).

Each case seeds a state directory in a given condition (clean, corrupt
config/index/journal, pending journal), runs a query command, and asserts the
tree digest is identical before and after. On the pre-fix binary the query
path quarantined corrupt files, logged recovery lines, and rolled pending
journals forward, so every corrupt/pending case mutates the tree here.
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path


def sha(data):
    return hashlib.sha256(data).hexdigest()


def snapshot(root):
    out = {}
    for p in sorted(root.rglob("*")):
        key = p.relative_to(root).as_posix()
        if p.is_symlink():
            out[key] = ("L", os.readlink(p))
        elif p.is_file():
            out[key] = ("F", p.stat().st_mode & 0o7777, p.read_bytes())
        else:
            out[key] = ("D",)
    return out


def digest_of(snap):
    lines = []
    for key, val in sorted(snap.items()):
        kind = val[0]
        if kind == "L":
            lines.append(f"L {key} {val[1]}")
        elif kind == "F":
            lines.append(f"F {key} {val[1]:o} {sha(val[2])}")
        else:
            lines.append(f"D {key}")
    return sha("\n".join(lines).encode())


STATE = ".local/share/github-release-manager"


def seed(home, binary):
    state = home / STATE
    app = state / "applications/forge/owner__tool"
    self_app = state / f"applications/self/~{sha(b'self\0ape-get')}"
    app.mkdir(parents=True)
    self_app.mkdir(parents=True)
    install = home / ".local/bin"
    install.mkdir(parents=True)
    target = install / "ape-get"
    shutil.copyfile(binary, target)
    target.chmod(0o755)
    (state / "grm_config.json").write_text(json.dumps({
        "kind": "grm.config", "schema_version": 1, "strict_verification": False,
        "backup_count": 3, "install_root": str(install), "require_signature": False,
        "providers": {"forge": {"type": "gitea", "url": "https://127.0.0.1:18081",
                                "api": None, "token_env": None}},
        "search_all_providers": True, "update_channel": "stable", "auto_update": "off",
        "update_interval_hours": 24, "network_timeout_seconds": 30, "proxy": None}))
    (state / "grm_index.json").write_text(json.dumps({
        "kind": "grm.index", "schema_version": 1,
        "generated_at": "2026-01-02T03:04:05Z", "apps": []}))
    (app / "grm_manifest.json").write_text(json.dumps({
        "kind": "grm.manifest", "schema_version": 1, "provider": "forge",
        "repo": "owner/tool", "version": "1.0.0",
        "asset": {"name": "tool-1.0.0.tar.gz",
                  "url": "https://forge.example/owner/tool/releases/download/"
                         "v1.0.0/tool-1.0.0.tar.gz"},
        "install": {"kind": "appimage", "path": f"{install}/tool"},
        "installed_at": "2020-01-02T03:04:05Z"}))
    (state / "grm_lock").write_text("")
    (self_app / "grm_manifest.json").write_text(json.dumps({
        "kind": "grm.manifest", "schema_version": 1, "provider": "self", "repo": "ape-get",
        "version": "2.0.0", "locked": True,
        "asset": {"name": "ape-get", "url": "https://example.invalid/ape-get",
                  "sha256": sha(target.read_bytes())},
        "install": {"kind": "ape", "path": str(target), "root": str(self_app)},
        "installed_at": "2020-01-02T03:04:05Z", "updated_at": "2020-02-03T04:05:06Z"}))
    return state


def add_pending_journal(home, state):
    cfg = json.loads((state / "grm_config.json").read_text())
    cfg["providers"]["mock"] = {"type": "gitea", "url": "https://127.0.0.1:18081",
                                "api": None, "token_env": None}
    (state / "grm_config.json").write_text(json.dumps(cfg))
    staged = state / "applications/mock/owner__tool/versions/1.1.0"
    staged.mkdir(parents=True)
    (state / "applications/mock/owner__tool/grm_manifest.json").write_text(json.dumps({
        "kind": "grm.manifest", "schema_version": 1, "provider": "mock",
        "repo": "owner/tool", "version": "1.0.0",
        "asset": {"name": "tool-1.0.0.AppImage",
                  "url": "https://127.0.0.1:18081/assets/tool-1.0.0.AppImage"},
        "install": {"kind": "appimage", "path": f"{home}/.local/bin/tool",
                    "command": "tool"},
        "installed_at": "2020-01-02T03:04:05Z"}))
    (staged / "tool-1.1.0.AppImage.tmp-0123456789abcdef").write_text("binary")
    (state / "grm_transaction.json").write_text(json.dumps({
        "kind": "grm.transaction", "schema_version": 1, "id": "0123456789abcdef",
        "op": "update", "ref": "mock:owner/tool", "phase": "prepared",
        "created_at": "2026-01-02T03:04:05Z",
        "new": {"binary_tmp": str(staged / "tool-1.1.0.AppImage.tmp-0123456789abcdef")}}))


def build_seed(home, binary, condition):
    state = seed(home, binary)
    if condition == "clean":
        return
    if condition == "corrupt-config":
        (state / "grm_config.json").write_text("{ broken")
    elif condition == "corrupt-index":
        (state / "grm_index.json").write_text("{ broken")
    elif condition == "corrupt-journal":
        (state / "grm_transaction.json").write_text("not json at all")
    elif condition == "pending-journal":
        add_pending_journal(home, state)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bin", required=True)
    args = parser.parse_args()
    binary = Path(args.bin).resolve()
    # includes the exact subprocess calls the bash completion script makes
    # on Tab (list --json, providers list --json)
    commands = [["list"], ["list", "--json"], ["info", "forge:owner/tool"],
                ["providers", "list"], ["providers", "list", "--json"],
                ["providers", "show", "forge"], ["completions", "bash"],
                ["doctor"]]
    conditions = ["clean", "corrupt-config", "corrupt-index", "corrupt-journal",
                  "pending-journal"]
    ran = 0
    with tempfile.TemporaryDirectory(prefix="query-purity-") as temp:
        for condition in conditions:
            for argv in commands:
                name = f"{condition}-{'-'.join(a.replace('/', '-') for a in argv)}"
                home = Path(temp) / name
                home.mkdir()
                build_seed(home, binary, condition)
                env = dict(os.environ, HOME=str(home), USERPROFILE=str(home))
                for key in ("APEGET_NO_SELF", "APEGET_STATE_ROOT", "APEGET_INSTALL_ROOT"):
                    env.pop(key, None)
                before = snapshot(home)
                subprocess.run([str(home / ".local/bin/ape-get"), *argv], env=env,
                               capture_output=True, timeout=60)
                after = snapshot(home)
                if digest_of(before) != digest_of(after):
                    moved = [k for k in set(before) | set(after)
                             if before.get(k) != after.get(k)]
                    raise SystemExit(
                        f"query {argv} on {condition} state mutated the tree: "
                        f"{sorted(moved)[:8]}")
                ran += 1
    print(f"{ran} query purity cases passed")


if __name__ == "__main__":
    main()
