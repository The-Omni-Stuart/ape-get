#!/usr/bin/env python3
"""Uninstall recovery must remove only owned resources, including old journals.

Every process receives a disposable HOME/state/bin/temp. Journal paths and
symlink targets must resolve inside its case before the executable is invoked.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def inside(path, root):
    return Path(path).resolve().is_relative_to(root.resolve())


def run_case(exe, base, name):
    root = base / name
    state = root / "data/github-release-manager"
    install = root / "bin"
    app = state / "applications/github/owner__tool"
    payload = app / "versions/1.0.0/tool.com"
    launcher = install / "tool"
    manifest = app / "grm_manifest.json"
    for path in [root / "home", root / "tmp", root / "config", state, install,
                 payload.parent]:
        path.mkdir(parents=True, exist_ok=True)
    payload.write_text("owned artifact\n")
    (app / "current").symlink_to("versions/1.0.0", target_is_directory=True)
    launcher.symlink_to(app / "current/tool.com")
    sentinel = root / "unrelated"
    sentinel.write_text("unrelated data\n")
    (install / "unrelated-command").write_text("unrelated command\n")
    obj = {
        "kind": "grm.manifest", "schema_version": 1,
        "provider": "github", "repo": "owner/tool", "version": "1.0.0",
        "asset": {"name": "tool.com", "url": "https://example.invalid/tool.com"},
        "install": {"kind": "ape", "path": str(payload),
                    "command": "tool", "uninstall": {"path": str(launcher)}},
        "installed_at": "2026-11-13T00:00:00Z", "backups": [],
    }
    old = {"manifest_path": str(manifest), "binary": str(payload),
           "launcher": str(launcher), "generation": str(payload.parent)}
    phase = "prepared" if name == "prepared" else "committing"
    command = ["list", "--json"]
    reject = False
    retry = None
    if name in {"legacy-shared-root", "already-absent", "producer"}:
        launcher.unlink()
        payload = install / "adopted.com"
        payload.write_text("adopted artifact\n")
        launcher = payload
        obj["install"].update(path=str(payload), uninstall={"path": str(payload)})
        old.update(binary=str(payload), launcher=str(payload), generation=str(install))
    if name == "already-absent":
        shutil.rmtree(app)
    elif name == "dot-package":
        old["manifest_path"] = str(state / "applications/missing/../grm_manifest.json")
        reject = True
    elif name == "dot-provider":
        old["manifest_path"] = str(state / "applications/./missing/grm_manifest.json")
        reject = True
    elif name == "missing-install-root":
        launcher.unlink()
        (install / "unrelated-command").unlink()
        install.rmdir()
    elif name == "missing-overlap-alias":
        alias = root / "alias"
        alias.symlink_to(app, target_is_directory=True)
        install = alias / "missing-bin"
        reject = True
    elif name in {"app-symlink", "provider-symlink", "applications-symlink"}:
        target = {"app-symlink": app, "provider-symlink": app.parent,
                  "applications-symlink": app.parent.parent}[name]
        relocated = root / "foreign-tree"
        shutil.move(target, relocated)
        target.symlink_to(relocated, target_is_directory=True)
        reject = True
    elif name == "overlap-equal":
        install = app
        reject = True
    elif name == "overlap-parent":
        install = state
        reject = True
    elif name == "overlap-child":
        install = app / "shared-bin"
        install.mkdir()
        reject = True
    elif name in {"case-variant-alias", "case-variant-parent", "case-variant-missing"}:
        original = app.parent if name == "case-variant-parent" else app
        alias = original.with_name(original.name.upper())
        if not alias.exists() or not alias.samefile(original):
            return "SKIP case-sensitive filesystem"
        install = alias / "missing-bin" if name == "case-variant-missing" else alias
        reject = True
    elif name == "directory-unlink-retry":
        launcher.unlink()
        launcher.mkdir()
        reject = True
        retry = launcher.rmdir
    elif name == "tree-error-retry":
        if os.name == "nt" or (hasattr(os, "geteuid") and os.geteuid() == 0):
            return "SKIP permission-dependent tree error"
        reject = True
        retry = lambda: app.chmod(0o755)
    elif name == "journal-error-retry":
        if os.name == "nt" or (hasattr(os, "geteuid") and os.geteuid() == 0):
            return "SKIP permission-dependent journal error"
        (state / "grm_lock").write_text("")
        reject = True
        retry = lambda: state.chmod(0o755)
    elif name == "producer":
        blocker = root / "desktop-directory"
        blocker.mkdir()
        obj["install"]["desktop_entry"] = str(blocker)
        command = ["uninstall", "github:owner/tool", "--yes"]
    elif name == "normal-cleanup-retry":
        if os.name == "nt" or (hasattr(os, "geteuid") and os.geteuid() == 0):
            return "SKIP permission-dependent normal cleanup error"
        reject = True
        retry = lambda: app.parent.chmod(0o755)
        command = ["uninstall", "github:owner/tool", "--yes"]
    elif name == "dangling-launcher":
        launcher.unlink()
        launcher.symlink_to(root / "missing-owned-artifact")

    if name != "already-absent":
        # For symlink-rejection cases, the manifest remains in the relocated
        # synthetic tree. This write never points outside this case.
        assert inside(manifest, root)
        manifest.write_text(json.dumps(obj))
    config = {"kind": "grm.config", "schema_version": 1, "backup_count": 1,
              "install_root": str(install), "providers": {}}
    (state / "grm_config.json").write_text(json.dumps(config))
    (state / "grm_index.json").write_text(json.dumps({
        "kind": "grm.index", "schema_version": 1,
        "generated_at": "2026-11-13T00:00:00Z", "apps": [],
    }))
    journal = state / "grm_transaction.json"
    record = {"kind": "grm.transaction", "schema_version": 1,
              "id": "0123456789abcdef", "op": "uninstall",
              "ref": "github:owner/tool", "phase": phase,
              "created_at": "2026-11-13T00:00:00Z", "old": old}
    if name not in {"producer", "normal-cleanup-retry"}:
        journal.write_text(json.dumps(record))
    if name == "journal-error-retry":
        state.chmod(0o555)
    elif name == "tree-error-retry":
        app.chmod(0o555)
    elif name == "normal-cleanup-retry":
        app.parent.chmod(0o555)
    env = dict(os.environ, HOME=str(root / "home"), USERPROFILE=str(root / "home"),
               XDG_DATA_HOME=str(root / "data"), XDG_CONFIG_HOME=str(root / "config"),
               TMPDIR=str(root / "tmp"), TEMP=str(root / "tmp"),
               TMP=str(root / "tmp"), APEGET_NO_SELF="1", NO_COLOR="1")

    def invoke(args=command):
        assert inside(install, root) and inside(state, root)
        for value in old.values():
            assert inside(value, root), ("unsafe test path", value)
        for key in ["HOME", "USERPROFILE", "XDG_DATA_HOME", "XDG_CONFIG_HOME",
                    "TMPDIR", "TEMP", "TMP"]:
            assert inside(env[key], root), ("unsafe test environment", key)
        return subprocess.run([str(exe)] + args, env=env, cwd=root,
                              capture_output=True, text=True, timeout=15)

    try:
        p = invoke()
        assert sentinel.read_text() == "unrelated data\n"
        unrelated = root / "bin/unrelated-command"
        if name != "missing-install-root":
            assert unrelated.exists() and unrelated.read_text() == "unrelated command\n", \
                "uninstall recovery deleted an unrelated command"
        if name == "producer":
            assert p.returncode != 0, (p.stdout, p.stderr)
            produced = json.loads(journal.read_text())
            assert produced["old"].get("generation") is None, produced
            assert produced["old"]["binary"] == str(payload), produced
        elif reject:
            assert p.returncode != 0 and journal.exists(), (p.returncode, p.stdout, p.stderr)
            if name == "journal-error-retry":
                assert not app.exists()
            elif name == "normal-cleanup-retry":
                assert not manifest.exists() and app.exists()
            else:
                assert manifest.exists()
            assert sentinel.read_text() == "unrelated data\n"
            if retry is not None:
                retry()
                retry = None
                p = invoke(["list", "--json"])
                assert p.returncode == 0 and not journal.exists(), (p.stdout, p.stderr)
                assert not app.exists()
        elif name == "prepared":
            assert p.returncode == 0 and not journal.exists(), (p.stdout, p.stderr)
            assert payload.exists() and manifest.exists() and launcher.is_symlink()
        else:
            assert p.returncode == 0 and not journal.exists(), (p.stdout, p.stderr)
            assert not payload.exists() and not launcher.is_symlink() and not app.exists()
            # Replaying an already completed journal is harmless too.
            journal.write_text(json.dumps(record))
            p = invoke()
            assert p.returncode == 0 and not journal.exists(), (p.stdout, p.stderr)
        assert sentinel.read_text() == "unrelated data\n"
        unrelated = root / "bin/unrelated-command"
        if name != "missing-install-root":
            assert unrelated.read_text() == "unrelated command\n"
    finally:
        if retry is not None:
            retry()
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
    cases = ["legacy-shared-root", "versioned", "prepared", "already-absent",
             "missing-install-root", "dangling-launcher", "dot-package", "dot-provider", "app-symlink",
             "provider-symlink", "applications-symlink", "overlap-equal",
             "overlap-parent", "overlap-child", "case-variant-alias",
             "case-variant-parent", "case-variant-missing", "missing-overlap-alias",
             "directory-unlink-retry", "tree-error-retry", "journal-error-retry",
             "producer", "normal-cleanup-retry"]
    if args.case:
        assert args.case in cases, "Unknown case"
        cases = [args.case]
    with tempfile.TemporaryDirectory(prefix="apeget-uninstall-recovery-",
                                     dir=args.work_root) as td:
        base = Path(td).resolve()
        for name in cases:
            print(name, run_case(exe, base, name), flush=True)
    print(f"{len(cases)} uninstall recovery cases completed")


if __name__ == "__main__":
    main()
