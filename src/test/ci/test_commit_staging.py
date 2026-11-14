#!/usr/bin/env python3
"""Manifest-write failure in the real install phase, entirely in fresh roots."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

try:
    import resource
except ImportError:  # Windows has no resource module or POSIX core dumps.
    resource = None

parser = argparse.ArgumentParser()
parser.add_argument("--driver", required=True)
parser.add_argument("--case", choices=["write-fail", "hash-fail", "control"])
args = parser.parse_args()
if resource is not None:
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
work = os.environ.get("APEGET_TEST_WORK_ROOT")
if work:
    Path(work).mkdir(parents=True, exist_ok=True)

for mode in [args.case] if args.case else ["write-fail", "hash-fail", "control"]:
    # Retain fixtures for review; no deletion paths run in this regression.
    root = Path(tempfile.mkdtemp(prefix=f"commit-{mode}-", dir=work)).resolve()
    state = root / "state"
    install = root / "install"
    app = state / "applications/fixture/owner/tool"
    vdir = app / "versions/v2"
    old_binary = app / "versions/v1/tool.com"
    target = vdir / "tool.com"
    stage = vdir / "tool.com.tmp-0123456789abcdef"
    mfile = app / "grm_manifest.json"
    mtmp = app / "grm_manifest.json.tmp-0123456789abcdef"
    current = app / "current"
    journal = state / "grm_transaction.json"
    sentinel = root / "foreign.txt"
    for directory in [install, vdir, old_binary.parent]:
        directory.mkdir(parents=True, exist_ok=True)
    old_binary.write_bytes(b"old binary sentinel\n")
    mfile.write_bytes(b"old canonical manifest sentinel\n")
    sentinel.write_bytes(b"foreign sentinel\n")
    current.symlink_to("versions/v1")
    if mode != "hash-fail":
        stage.write_bytes(b"new staged binary, never executed\n")
    env = dict(os.environ, APEGET_NO_SELF="1", PYTHONDONTWRITEBYTECODE="1")
    env.pop("APEGET_TEST_CRASH", None)
    for name, leaf in {"HOME": "home", "USERPROFILE": "home",
            "XDG_DATA_HOME": "data", "XDG_CONFIG_HOME": "config",
            "XDG_CACHE_HOME": "cache", "TMPDIR": "tmp", "TMP": "tmp",
            "TEMP": "tmp", "CCACHE_DIR": "cache/ccache",
            "CLANG_MODULE_CACHE_PATH": "cache/clang"}.items():
        path = root / leaf
        path.mkdir(parents=True, exist_ok=True)
        env[name] = str(path)
    # Validate all stage/journal/symlink/write paths before calling actual code.
    paths = [state, install, app, vdir, old_binary, target, stage, mfile,
             mtmp, current, journal, sentinel, *[Path(env[n]) for n in
             ["HOME", "USERPROFILE", "XDG_DATA_HOME", "XDG_CONFIG_HOME",
              "XDG_CACHE_HOME", "TMPDIR", "TMP", "TEMP", "CCACHE_DIR",
              "CLANG_MODULE_CACHE_PATH"]]]
    for path in paths:
        assert path.resolve().is_relative_to(root), path
    before = {p: p.read_bytes() for p in [old_binary, mfile, sentinel]}
    result = subprocess.run([args.driver, str(root), mode], env=env, cwd=root,
                            capture_output=True, text=True, timeout=30)
    print(result.stdout, end="", flush=True)
    print(result.stderr, end="", flush=True)
    assert result.returncode == 0, (mode, result.returncode, str(root))
    for path, contents in before.items():
        assert path.read_bytes() == contents, path
    assert not journal.exists()
    if mode == "control":
        assert not stage.exists() and target.read_bytes() == b"new staged binary, never executed\n"
        record = json.loads(mtmp.read_text())
        assert record["asset"]["sha256"] == hashlib.sha256(target.read_bytes()).hexdigest()
        assert record["version"] == "v2" and record["install"]["path"] == str(target)
        assert current.readlink() == Path("versions/v2")
    else:
        assert not target.exists() and not mtmp.exists()
        assert stage.exists() == (mode == "write-fail")
        assert current.readlink() == Path("versions/v1")
    print(f"{mode}: canonical manifest, old binary and foreign sentinel unchanged", flush=True)
