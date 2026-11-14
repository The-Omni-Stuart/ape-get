#!/usr/bin/env python3
"""Identity isolation through real native naming, adopt, rollback and uninstall.

No downloads or installed programs are executed. All marker artifacts, journal
paths, symlink targets and environment directories are disposable and guarded.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def key(provider, repo):
    return "~" + hashlib.sha256(provider.encode() + b"\0" + repo.encode()).hexdigest()


def sanitize(value):
    return re.sub("[^a-z0-9._-]+", "-", value.lower()).strip("._-") or "app"


def portable(value):
    base = value.split(".")[0]
    return (bool(value) and len(value) <= 200 and sanitize(value) == value
            and base not in {"con", "prn", "aux", "nul"}
            and not re.fullmatch(r"(?:com|lpt)[1-9]", base))


def install_name(provider, repo):
    name = provider + "__" + repo.replace("/", "__")
    return name if (portable(provider) and "__" not in provider and "__" not in repo
                    and all(portable(s) for s in repo.split("/")) and len(name) <= 200) else key(provider, repo)


def legacy_name(repo):
    name = "__".join(sanitize(s) for s in repo.split("/"))
    if "__" in repo:
        h = 0
        for c in repo.encode():
            h = (31 * h + c) & 0xffffffff
        name += "--" + format(h, "x")
    return name


class Case:
    def __init__(self, base, name, driver, product):
        self.root = base / name
        self.state = self.root / "data/github-release-manager"
        self.install = self.root / "install"
        self.driver, self.product = driver, product
        for path in [self.state, self.install, self.root / "home", self.root / "config",
                     self.root / "cache", self.root / "tmp"]:
            path.mkdir(parents=True)
        self.env = dict(os.environ, HOME=str(self.root / "home"), USERPROFILE=str(self.root / "home"),
                        XDG_DATA_HOME=str(self.root / "data"), XDG_CONFIG_HOME=str(self.root / "config"),
                        XDG_CACHE_HOME=str(self.root / "cache"), TMPDIR=str(self.root / "tmp"),
                        TEMP=str(self.root / "tmp"), TMP=str(self.root / "tmp"),
                        APEGET_NO_SELF="1", NO_COLOR="1")
        self.env.pop("APEGET_TEST_CRASH", None)
        (self.state / "grm_config.json").write_text(json.dumps({
            "kind": "grm.config", "schema_version": 1, "backup_count": 1,
            "install_root": str(self.install), "providers": {}}))
        (self.state / "grm_index.json").write_text(json.dumps({
            "kind": "grm.index", "schema_version": 1, "apps": [],
            "generated_at": "2026-11-14T00:00:00Z"}))

    def guard(self):
        for path in [self.state, self.install] + [Path(self.env[k]) for k in
                ["HOME", "USERPROFILE", "XDG_DATA_HOME", "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "TMPDIR", "TEMP", "TMP"]]:
            assert path.resolve().is_relative_to(self.root.resolve()), path
        for path in self.root.rglob("*"):
            assert path.resolve().is_relative_to(self.root.resolve()), ("unsafe path or symlink", path)
        journal = self.state / "grm_transaction.json"
        if journal.exists():
            for side in ["old", "new"]:
                for value in json.loads(journal.read_text()).get(side, {}).values():
                    if isinstance(value, str) and value.startswith("/"):
                        assert Path(value).resolve().is_relative_to(self.root.resolve()), value

    def invoke(self, exe, args):
        self.guard()
        return subprocess.run([str(exe)] + list(args), cwd=self.root, env=self.env,
                              capture_output=True, text=True, timeout=20)

    def paths(self, provider, repo, mode="paths", unreadable=None):
        args = [mode, str(self.root), provider, repo]
        if unreadable:
            args.append(str(unreadable))
        p = self.invoke(self.driver, args)
        assert p.returncode == 0, (args, p.returncode, p.stdout, p.stderr)
        return json.loads(p.stdout)

    def app(self, provider, repo):
        return self.state / "applications" / (provider if portable(provider) else key(provider, "")) / key(provider, repo)

    def legacy(self, provider, repo):
        return self.state / "applications" / sanitize(provider) / legacy_name(repo)

    def record(self, app, provider, repo, artifact=None, version="2.0.0"):
        artifact = artifact or self.install / install_name(provider, repo)
        app.mkdir(parents=True, exist_ok=True)
        artifact.parent.mkdir(parents=True, exist_ok=True)
        if not artifact.exists():
            artifact.write_text("synthetic marker: " + provider + ":" + repo + "\n")
        obj = {"kind": "grm.manifest", "schema_version": 1, "provider": provider,
               "repo": repo, "version": version, "installed_at": "2026-11-14T00:00:00Z",
               "asset": {"name": artifact.name, "url": "file://" + str(artifact)},
               "install": {"kind": "ape", "path": str(artifact), "root": str(app),
                           "uninstall": {"path": str(artifact)}}, "backups": []}
        (app / "grm_manifest.json").write_text(json.dumps(obj))
        return obj

    def command(self, *args, ok=True):
        p = self.invoke(self.product, args)
        assert (p.returncode == 0) == ok, (args, p.returncode, p.stdout, p.stderr)
        return p


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--driver", required=True, type=Path)
    parser.add_argument("--bin", required=True, type=Path)
    args = parser.parse_args()
    parent = Path(os.environ.get("APEGET_TEST_WORK_ROOT", tempfile.gettempdir())).resolve()
    parent.mkdir(parents=True, exist_ok=True)
    driver, product = args.driver.resolve(), args.bin.resolve()
    count = 0
    with tempfile.TemporaryDirectory(prefix="manifest-identity-", dir=parent) as temp:
        base = Path(temp).resolve()
        def case(name):
            return Case(base, name, driver, product)

        c = case("parser-boundary")
        exact = "o/" + "a" * 509
        for repo, accepted in [(exact, True), (exact + "a", False),
                               (exact + "x", False), (exact + "y", False)]:
            result = c.paths("github", repo, "parse")
            assert result["accepted"] == accepted, (len(repo), result)
            assert result["repo"] == (repo if accepted else None)
            count += 1
        print("PASS exact-fit parser boundary and oversized identities differing only after their shared prefix")

        refs = [("github", "owner/foo"), ("github", "owner/-foo"), ("github", "owner/foo."),
                ("github", "owner/_foo"), ("github", "owner/Foo"), ("forge", "owner/foo"),
                ("forge.", "owner/foo"), ("github", "owner/a__b"), ("github", "owner/a/b"),
                ("github", "owner/a b"), ("github", "owner/a-b"), ("a__b", "c/d"), ("a", "b/c/d"),
                ("con", "owner/foo"), ("con.txt", "owner/foo"), ("github", "owner/" + "a" * 240),
                ("self", "ape-get"), ("self", "ape-get.")]
        c = case("fresh-names")
        paths, artifacts = set(), set()
        for provider, repo in refs:
            result = c.paths(provider, repo)
            expected = c.app(provider, repo)
            assert result["app"] == str(expected), (provider, repo, result)
            assert result["manifest"] == str(expected / "grm_manifest.json")
            assert result["install"] == install_name(provider, repo), (provider, repo, result)
            assert result["app"].casefold() not in paths
            assert result["install"].casefold() not in artifacts
            paths.add(result["app"].casefold()); artifacts.add(result["install"].casefold())
        count += len(refs)
        print("PASS exact full hashes, reserved namespace, case aliases, provider boundaries, device names, long identities")

        for provider, repo in [("github", "owner/foo"), ("github", "owner/-foo"), ("forge.", "owner/foo")]:
            c = case("prepare-" + str(count))
            result = c.paths(provider, repo, "prepare")
            assert result["app"] == result["recorded"] == str(c.app(provider, repo))
            assert result["prepared"] == result["manifest"]
            count += 1
        print("PASS stable native prepare, directory creation and manifest rename")

        for status in ["matching", "foreign", "malformed", "missing", "unreadable", "future", "duplicate"]:
            c = case("legacy-" + status)
            app = c.legacy("github", "owner/-foo")
            obj = c.record(app, "github", "owner/-foo")
            mf = app / "grm_manifest.json"
            sentinel = app / "retained-backup"; sentinel.write_text("keep legacy contents\n")
            if status == "foreign": obj["repo"] = "owner/foo"
            if status == "future": obj["schema_version"] = 2
            if status in {"foreign", "future"}: mf.write_text(json.dumps(obj))
            if status == "malformed": mf.write_text('{"provider":')
            if status == "duplicate": mf.write_text(mf.read_text().replace('"repo":', '"repo":"other/ref", "repo":'))
            if status == "missing": mf.unlink()
            before = mf.read_bytes() if mf.exists() else None
            result = c.paths("github", "owner/-foo", unreadable=mf if status == "unreadable" else None)
            assert result["app"] == str(app if status == "matching" else c.app("github", "owner/-foo")), (status, result)
            assert (mf.read_bytes() if mf.exists() else None) == before
            if status == "matching":
                result = c.paths("github", "owner/-foo", "prepare")
                assert result["recorded"] == str(app)
            assert sentinel.read_text() == "keep legacy contents\n"
            count += 1
        print("PASS legacy exact ownership and non-mutating missing/malformed/unreadable/foreign lookup")

        for status in ["foreign", "malformed", "unreadable"]:
            c = case("canonical-" + status)
            app = c.app("github", "owner/foo")
            obj = c.record(app, "github", "owner/foo")
            mf = app / "grm_manifest.json"
            if status == "foreign": obj["repo"] = "owner/-foo"; mf.write_text(json.dumps(obj))
            if status == "malformed": mf.write_text("broken")
            before = mf.read_bytes()
            result = c.paths("github", "owner/foo", unreadable=mf if status == "unreadable" else None)
            assert result["app"] is None and result["manifest"] is None, (status, result)
            assert mf.read_bytes() == before
            count += 1
        print("PASS canonical foreign/malformed/unreadable conflicts fail closed")

        c = case("adopt-collisions")
        refs = [("github", "owner/foo"), ("github", "owner/-foo"), ("github", "owner/Foo"),
                ("forge", "owner/foo"), ("forge.", "owner/foo"), ("github", "owner/a__b"),
                ("github", "owner/a/b")]
        for provider, repo in refs:
            marker = c.install / install_name(provider, repo)
            marker.write_text("marker for " + provider + ":" + repo)
            c.command("adopt", provider + ":" + repo, "--yes")
            assert (c.app(provider, repo) / "grm_manifest.json").exists()
        listed = json.loads(c.command("list", "--json").stdout)["apps"]
        assert {(m["provider"], m["repo"]) for m in listed} == set(refs)
        for provider, repo in refs:
            c.command("uninstall", provider + ":" + repo, "--yes")
            assert not c.app(provider, repo).exists()
            for other_provider, other_repo in refs[refs.index((provider, repo)) + 1:]:
                assert (c.install / install_name(other_provider, other_repo)).read_text() == "marker for " + other_provider + ":" + other_repo
        count += len(refs)
        print("PASS real adopt/list/uninstall keeps all collision identities and artifacts separate")

        c = case("legacy-collision-adopt")
        legacy = c.legacy("github", "owner/-foo")
        legacy_artifact = c.install / "github__owner__-foo"
        c.record(legacy, "github", "owner/-foo", legacy_artifact)
        sentinel = legacy / "backups-retained"; sentinel.write_text("legacy rollback bytes\n")
        candidate = c.install / install_name("github", "owner/foo")
        candidate.write_text("new independent artifact\n")
        c.command("adopt", "github:owner/foo", "--yes")
        listed = json.loads(c.command("list", "--json").stdout)["apps"]
        assert {(m["provider"], m["repo"]) for m in listed} == {("github", "owner/foo"), ("github", "owner/-foo")}
        c.command("uninstall", "github:owner/foo", "--yes")
        assert sentinel.read_text() == "legacy rollback bytes\n" and legacy_artifact.exists()
        assert c.paths("github", "owner/-foo")["app"] == str(legacy)
        c.command("uninstall", "github:owner/-foo", "--yes")
        assert not legacy.exists() and not legacy_artifact.exists()
        count += 1
        print("PASS foreign legacy collision stays in place through fresh adoption and both uninstalls")

        for alias in ["same-path", "hardlink", "symlink"]:
            c = case("owned-artifact-" + alias)
            owned = c.install / ("github__owner__foo" if alias == "same-path" else "legacy-artifact")
            app = c.legacy("github", "owner/-foo")
            c.record(app, "github", "owner/-foo", owned)
            candidate = c.install / "github__owner__foo"
            if alias == "hardlink": candidate.hardlink_to(owned)
            if alias == "symlink": candidate.symlink_to(owned)
            before = owned.read_bytes()
            c.command("adopt", "github:owner/foo", "--yes", ok=False)
            assert not c.app("github", "owner/foo").exists() and owned.read_bytes() == before
            assert json.loads((app / "grm_manifest.json").read_text())["repo"] == "owner/-foo"
            count += 1
        print("PASS adoption refuses already owned artifacts and physical aliases")

        for layout in ["legacy", "canonical"]:
            c = case("rollback-uninstall-" + layout)
            app = c.legacy("github", "owner/-foo") if layout == "legacy" else c.app("github", "owner/-foo")
            old = app / "versions/1.0.0/old-marker"
            new = app / "versions/2.0.0/new-marker"
            obj = c.record(app, "github", "owner/-foo", new)
            old.parent.mkdir(parents=True); old.write_text("old synthetic bytes\n")
            backup = app / "backups/retained"; backup.mkdir(parents=True)
            artifact = backup / "old-marker"; artifact.write_bytes(old.read_bytes())
            old_obj = json.loads(json.dumps(obj)); old_obj["version"] = "1.0.0"
            old_obj["install"]["path"] = str(old)
            old_obj["install"]["uninstall"]["path"] = str(c.install / "distinct-command")
            backup_manifest = backup / "grm_backup_manifest.json"; backup_manifest.write_text(json.dumps(old_obj))
            obj["backups"] = [{"version": "1.0.0", "artifact": str(artifact), "install_path": str(old),
                               "manifest": str(backup_manifest), "created_at": "2026-11-14T00:00:00Z"}]
            (app / "grm_manifest.json").write_text(json.dumps(obj))
            (app / "current").symlink_to("versions/2.0.0", target_is_directory=True)
            foreign = c.app("github", "owner/foo")
            c.record(foreign, "github", "owner/foo")
            sentinel = foreign / "retained"; sentinel.write_text("foreign generation\n")
            assert c.paths("github", "owner/-foo")["app"] == str(app)
            c.command("rollback", "github:owner/-foo", "--yes")
            assert old.read_bytes() == artifact.read_bytes()
            assert json.loads((app / "grm_manifest.json").read_text())["version"] == "1.0.0"
            c.command("uninstall", "github:owner/-foo", "--yes")
            assert not app.exists() and sentinel.read_text() == "foreign generation\n"
            assert json.loads((foreign / "grm_manifest.json").read_text())["repo"] == "owner/foo"
            count += 1
        print("PASS legacy and canonical generation rollback/uninstall exact owned-tree cleanup")
    print(f"{count} identity cases passed")


if __name__ == "__main__":
    main()
