#!/usr/bin/env python3
"""CLI scenario runner: drives the C binary against transcripts and
compares normalised output with reviewed expected files.

  <group>/<name>.env    key=value: argv (repeat), stdin, env, tags
  <group>/<name>.out    expected stdout after normalization
  <group>/<name>.err    expected stderr after normalization
  <group>/<name>.code   expected exit code
  (optional) file=<path> lines snapshot home-relative paths after the run
  into <name>.files, modes included
  (optional) <name>.out.flaky / <name>.err.flaky are accepted matches for
  output the host's network decides (e.g. rate-limited lookups)

Expected files are written by --update from reviewed output, never
hand-typed. Normalization maps volatile paths, ids and host facts to
<PLACEHOLDERS>. C reads $HOME; every scenario gets a throwaway home.

  src/test/scenarios/run.py --bin ./build/ape-get [group [group/case ...]]
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

SCEN = Path(__file__).resolve().parent
PASS = FAIL = 0


def normalize(text: str, state: str, install: str, home: str = "") -> str:
    t = text.replace(state, "<STATE_ROOT>").replace(install, "<INSTALL_ROOT>")
    if home:
        t = t.replace(home, "<HOME>")
    t = re.sub(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z", "<INSTANT>", t)
    t = re.sub(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
               r"-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b", "<UUID>", t)
    t = re.sub(r"\b\d{13}-", "<EPOCH_MILLIS>-", t)   # backup dir names
    t = re.sub(r"\.corrupt-\d+", ".corrupt-<EPOCH_MILLIS>", t)
    # transaction ids are random hex; journals and staged names pin
    # them as <ID> so a crash-window fixture stays deterministic
    t = re.sub(r"\.tmp-[0-9a-f]{16}", ".tmp-<ID>", t)
    t = re.sub(r'"id"\s*:\s*"[0-9a-f]{16}"', '"id": "<ID>"', t)
    t = re.sub(r"\b\d+ bytes? free\b", "<FREE_BYTES> free", t)
    t = re.sub(r"Free space: (?:\d+(?:\.\d+)? [KMGT]B|unknown)",
               "Free space: <FREE>", t)
    t = re.sub(r'"free_bytes":\d+', '"free_bytes":<FREE_BYTES>', t)
    t = re.sub(r"Low disk space at \S+: (?:\S+ )?free, need \d+ MB",
               "Low disk space at <INSTALL_ROOT>: <FREE> free, need 100 MB", t)
    t = re.sub(r"Signature tool: (\S+) (available|missing)",
               r"Signature tool: \1 <TOOL_STATE>", t)
    # availability lines depend on the host: probe results
    # (tor/rnsd/wine) vary per machine, so pin only the label
    t = re.sub(r"Compatibility tools: .*", "Compatibility tools: <WINE>", t)
    t = re.sub(r"Tor status: .*", "Tor status: <TOR>", t)
    t = re.sub(r"Reticulum status: .*", "Reticulum status: <RETICULUM>", t)
    t = re.sub(r'"available":(?:true|false)', '"available":<TOOL_AVAILABLE>', t)
    t = t.replace("ape-get.com", "<ENTRYPOINT>")
    return t


def load(path: Path):
    env = {"argv": [], "stdin": "", "env": {}, "tags": "any"}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        k, _, v = line.partition("=")
        if k == "argv":
            env["argv"].append(v)
        elif k == "stdin":
            env["stdin"] = v
        elif k == "env":
            # test-only env hooks mirror the original tests' approach:
            # "use reflection to set the private fields - replace with
            # dependency injection framework"
            key, _, val = v.partition("=")
            env["env"][key] = val
        elif k == "file":
            env.setdefault("file", []).append(v)
        elif k:
            env[k] = v
    return env


import re as _re_glob
_GLOB_ID_RE = _re_glob.compile(r"\.tmp-[0-9a-f]{16}")


def dump_files(home: str, rels, state: str, install: str) -> str:
    """Post-run snapshot of listed paths (relative to the fake home) for
    the .files fixtures; absent paths are pinned as such so deletions
    (e.g. --no-desktop) are observable. An entry containing * is a glob
    (crash-window fixtures stage under random ids; the header's id is
    normalised so the snapshot stays stable — literal names are
    printed as written)."""
    import glob as _glob
    import stat as st
    out = []
    for rel in rels:
        if "*" in rel:
            names = [str(Path(home) / n).removeprefix(str(home) + "/")
                     for n in sorted(_glob.glob(str(Path(home) / rel)))]
        else:
            names = [rel]
        for one in names:
            p = Path(one) if one.startswith("/") else Path(home) / one
            head = _GLOB_ID_RE.sub(".tmp-<ID>", one) if "*" in rel else one
            if p.is_file():
                mode = st.S_IMODE(p.stat().st_mode)
                body = normalize(p.read_text(errors="replace"),
                                 state, install, home)
                out.append(f"--- {head} ({mode:04o}) ---\n{body}")
            else:
                out.append(f"--- {head} (absent) ---\n")
    return "".join(out)


def seed(home: str, case_dir: Path, name: str):
    """Copy the scenario's setup/ tree into the fake home, expanding
    {home}. Fixtures are inputs, so hand-writing them is
    fine — the binary's behaviour against them is what the check pins.
    Conventions: *.exec lands chmod-0755; a whole-file "{b64:...}" is
    base64-decoded (binary fixtures stay ASCII in the repo); a
    whole-file "{symlink:target}" becomes a symlink (target may be
    absolute or relative to the link's directory); a
    <dir>/<file>.modeNNN sentinel is not copied — when <file> exists
    beside it, the copied file is chmod'ed NNN, otherwise the sentinel's
    directory becomes 0700 (gpg demands that).
    """
    setup = case_dir / f"{name}.setup"
    if not setup.is_dir():
        return
    import re as _re
    pending = []   # (target Path, mode) applied after the copy pass
    for f in sorted(p for p in setup.rglob("*") if p.is_file()):
        rel = f.relative_to(setup)
        m = _re.match(r"^(.*)\.mode([0-7]{3})$", f.name)
        if m:
            stem = m.group(1)
            if (f.parent / stem).is_file():
                pending.append(
                    (Path(home) / rel.parent / stem, int(m.group(2), 8)))
            else:
                d = Path(home) / rel.parent
                d.mkdir(parents=True, exist_ok=True)
                d.chmod(0o700)
            continue
        if f.name.endswith(".exec"):
            rel = rel.with_name(f.name[:-len(".exec")])
        dst = Path(home) / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        text = f.read_text().replace("{home}", home)
        import base64 as _b64
        if text.startswith("{symlink:"):
            dst.parent.mkdir(parents=True, exist_ok=True)
            if dst.is_symlink() or dst.exists():
                dst.unlink()
            dst.symlink_to(text.rstrip("\n")[len("{symlink:"):-1])
            continue
        if text.startswith("{b64:") and text.endswith("}"):
            dst.write_bytes(_b64.b64decode(text[5:-1]))
        else:
            dst.write_text(text)
        if f.name.endswith(".exec"):
            dst.chmod(0o755)
    for p, mode in pending:
        p.chmod(mode)


def start_mock(fixture_dir: Path):
    """Boot the HTTPS mock provider on the pinned port and wait for it."""
    import socket
    import time
    fixture_dir.mkdir(exist_ok=True)
    p = subprocess.Popen([sys.executable, str(SCEN / "mock_provider.py"),
                          str(fixture_dir)],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(100):
        try:
            with socket.create_connection(("127.0.0.1", 18081), timeout=0.2):
                return p
        except OSError:
            time.sleep(0.05)
    p.terminate()
    raise RuntimeError("mock provider did not come up")


def run_case(case_dir: Path, name: str, cmd, update: bool) -> bool:
    spec = load(case_dir / f"{name}.env")
    # fake home on the shared disk (host and distrobox share statvfs),
    # so free-space warnings stay stable; values normalise anyway.
    tdp = Path(tempfile.gettempdir()) / "ape-get-scen"
    tdp.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="grm-scen-", dir=str(tdp)) as td:
        home = str(Path(td).resolve())
        # state root is the XDG data dir; install root comes from config
        # (fixtures point it at <home>/.local/bin).
        state = home + "/.local/share/github-release-manager"
        install = home + "/.local/bin"
        seed(home, case_dir, name)
        env = dict(os.environ)
        env.update({"HOME": home, "USERPROFILE": home,
                    "TZ": "UTC", "TERM": "dumb", "NO_COLOUR": "1"})
        # self-integration stays off for every scenario run; self
        # scenarios opt back in.
        env["APEGET_NO_SELF"] = "1"
        # Desktop-entry lookups follow XDG_DATA_HOME; pin it out unless a
        # scenario sets it explicitly.
        env.pop("XDG_CONFIG_HOME", None)
        env.pop("XDG_DATA_HOME", None)
        env.update({k: v.replace("{home}", home)
                    for k, v in spec["env"].items()})
        full = list(cmd)
        argv = [a.replace("{home}", home) for a in spec["argv"]]
        mock = None
        if spec.get("mock"):
            mock = start_mock(case_dir / f"{name}.mock")
            env["APEGET_CA_BUNDLE"] = str(SCEN / "tls" / "cert.pem")
        try:
            r = subprocess.run(full + argv, input=spec["stdin"],
                               capture_output=True, text=True, env=env, timeout=60)
        finally:
            if mock:
                mock.terminate()
                mock.wait(timeout=10)
        out = normalize(r.stdout, state, install, home)
        err = normalize(r.stderr, state, install, home)
        e_out, e_err, e_code = (".out", ".err", ".code")
        files_key = [f.replace("{home}", home)
                     for f in spec.get("file", [])]
        if update:
            if files_key:
                (case_dir / f"{name}.files").write_text(
                    dump_files(home, files_key, state, install))
            (case_dir / f"{name}{e_out}").write_text(out)
            (case_dir / f"{name}{e_err}").write_text(err)
            (case_dir / f"{name}{e_code}").write_text(str(r.returncode))
            print(f"  updated: {case_dir.name}/{name}")
            return True
        ok = True
        for fname, got in ((f"{name}{e_out}", out), (f"{name}{e_err}", err)):
            f = case_dir / fname
            want = f.read_text() if f.exists() else ""
            if got != want:
                # an accepted alternate for output the host's network
                # decides (e.g. rate-limited lookups on shared IPs)
                alt = case_dir / f"{fname}.flaky"
                if alt.exists() and got == alt.read_text():
                    want = got
            if got != want:
                ok = False
                print(f"FAIL {case_dir.name}/{name} [{fname}]\n"
                      f"--- want ---\n{want}--- got ----\n{got}")
        code_file = case_dir / f"{name}{e_code}"
        want_code = int(code_file.read_text()) if code_file.exists() else 0
        if r.returncode != want_code:
            ok = False
            print(f"FAIL {case_dir.name}/{name} [exit] "
                  f"want {want_code}, got {r.returncode}")
        # operation history: <case>.log expectations are hand-written
        # (never --update'd) and diffed against the run.
        log_expected = case_dir / f"{name}.log"
        if log_expected.exists():
            got_log = ""
            p = Path(state) / "grm_updates.log"
            if p.exists():
                got_log = normalize(p.read_text(), state, install)
            if got_log != log_expected.read_text():
                ok = False
                print(f"FAIL {case_dir.name}/{name} [{name}.log]\n"
                      f"--- want ---\n{log_expected.read_text()}"
                      f"--- got ----\n{got_log}")
        # file-content snapshots (.files), e.g. .desktop entries where
        # C adds hardened keys
        if files_key:
            gf = case_dir / f"{name}.files"
            got_files = dump_files(home, files_key, state, install)
            want = gf.read_text() if gf.exists() else ""
            if got_files != want:
                ok = False
                print(f"FAIL {case_dir.name}/{name} [.files]\n"
                      f"--- want ---\n{want}--- got ----\n{got_files}")
        return ok


def main():
    global PASS, FAIL
    ap = argparse.ArgumentParser()
    ap.add_argument("--bin", required=True, help="C binary path")
    ap.add_argument("--update", action="store_true",
                  help="rewrite expected files from the run")
    ap.add_argument("select", nargs="*", help="groups or group/case")
    a = ap.parse_args()
    cmd = [a.bin]

    cases = [(d, f.stem) for d in sorted(p for p in SCEN.iterdir() if p.is_dir())
             for f in sorted(d.glob("*.env"))]
    if a.select:
        cases = [c for c in cases if any(
            s == c[0].name or s == f"{c[0].name}/{c[1]}" for s in a.select)]

    for case_dir, name in cases:
        if run_case(case_dir, name, cmd, a.update):
            PASS += 1
        else:
            FAIL += 1
    print(f"{PASS} passed, {FAIL} failed")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
