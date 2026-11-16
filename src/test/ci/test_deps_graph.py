#!/usr/bin/env python3
"""Tests for the graph checker: the real graph passes, and synthetic
violations fail with the expected diagnosis. Registered in CTest as
deps_graph_selftest so a broken checker can never sit unexercised."""
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CHECK = ROOT / "src" / "test" / "ci" / "check_deps_graph.py"
DEPS_MK = ROOT / "src" / "main" / "deps.mk"

FAILURES = []


def check(name, cond, detail=""):
    print(f"{'ok' if cond else 'FAIL'}: {name}")
    if not cond:
        FAILURES.append(name + (f": {detail}" if detail else ""))


def run(tmp_path, text):
    f = Path(tmp_path) / "deps.mk"
    f.write_text(text)
    return subprocess.run(
        [sys.executable, str(CHECK), str(f)],
        capture_output=True, text=True, cwd=ROOT)


def test_real_graph_passes(tmp_path):
    r = subprocess.run(
        [sys.executable, str(CHECK), str(DEPS_MK)],
        capture_output=True, text=True, cwd=ROOT)
    assert r.returncode == 0, r.stdout + r.stderr
    assert "graph ok:" in r.stdout, r.stdout


def test_cycle_rejected(tmp_path):
    r = run(tmp_path, "a:b\nb:a\n")
    assert r.returncode != 0 and "cycle" in r.stderr + r.stdout


def test_upward_edge_rejected(tmp_path):
    r = run(tmp_path, "a:b\nb:\n")
    assert r.returncode == 0  # a(L1) -> b(L0) is legal
    r = run(tmp_path, "a:b\nc:a\nb:c\n")
    assert r.returncode != 0 and "cycle" in r.stderr + r.stdout


def test_unknown_dependency_rejected(tmp_path):
    r = run(tmp_path, "a:nope\n")
    assert r.returncode != 0 and "unknown dependency" in r.stderr + r.stdout


def test_self_edge_rejected(tmp_path):
    r = run(tmp_path, "a:a\n")
    assert r.returncode != 0 and "itself" in r.stderr + r.stdout


def main():
    tests = [test_real_graph_passes, test_cycle_rejected,
             test_upward_edge_rejected, test_unknown_dependency_rejected,
             test_self_edge_rejected]
    with tempfile.TemporaryDirectory() as tmp:
        for t in tests:
            try:
                t(tmp)
                check(t.__name__, True)
            except AssertionError as e:
                check(t.__name__, False, str(e))
            except Exception as e:  # noqa: BLE001 - any error fails the gate
                check(t.__name__, False, repr(e))
    if FAILURES:
        sys.exit("deps_graph self-test failed: " + "; ".join(FAILURES))


if __name__ == "__main__":
    main()
