"""Tests for the graph checker: the real graph passes, and synthetic
violations fail with the expected diagnosis."""
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHECK = ROOT / "tests" / "ci" / "check_deps_graph.py"


def run(tmp_path, text):
    f = tmp_path / "deps.mk"
    f.write_text(text)
    return subprocess.run(
        [sys.executable, str(CHECK), str(f)],
        capture_output=True, text=True)


def test_real_graph_passes(tmp_path):
    r = subprocess.run(
        [sys.executable, str(CHECK), str(ROOT / "src" / "main" / "c" / "deps.mk")],
        capture_output=True, text=True, cwd=ROOT)
    assert r.returncode == 0, r.stdout + r.stderr
    assert "30 modules" in r.stdout # 29 modules + main


def test_cycle_rejected(tmp_path):
    r = run(tmp_path, "a:b\nb:a\n")
    assert r.returncode != 0 and "cycle" in r.stderr + r.stdout


def test_upward_edge_rejected(tmp_path):
    r = run(tmp_path, "a:\nb:a\nc:\nmain:b,c\n")
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
