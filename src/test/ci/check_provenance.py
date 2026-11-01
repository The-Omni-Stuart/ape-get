#!/usr/bin/env python3
"""Pins the original author's provenance comments, verbatim.

Each entry is (repo-relative file, exact substring). These comments carry
the original project's own words at the code they document; they are
exempt from comment-style sweeps and must never be reworded, condensed
or deleted. Usage: check_provenance.py [root]"""

import sys
from pathlib import Path

PINS = [
    ("src/main/main.c", "central coordinator for parsing commands"),
    ("src/main/cmd.c", "The original CmdParser's grammar"),
    ("src/main/cmd.c", "CmdParser update arity"),
    ("src/main/provider/github.c", "interacting with the Github API"),
    ("src/main/provider/github.c", "GithubClient.searchRepoByName"),
    ("src/main/main/uninstall.c", "LinuxHandler.uninstall semantics"),
    ("src/main/pipeline/run.c",
     "downloading the asset and passing it to the platform handler"),
    ("src/main/pipeline/desktop.c",
     "responsible for handling the installation and uninstallation"),
    ("src/main/doctor.c", "under the home directory to avoid permission issues"),
    ("src/test/scenarios/run.py", "use reflection to set the private fields"),
]


def main() -> int:
    root = (Path(sys.argv[1]) if len(sys.argv) > 1
            else Path(__file__).resolve().parents[3])
    lost = 0
    for rel, needle in PINS:
        path = root / rel
        if not path.is_file() or needle not in path.read_text():
            lost += 1
            print(f"provenance comment lost: {rel}: {needle}")
    print(f"provenance: {len(PINS) - lost}/{len(PINS)} pins intact")
    return 1 if lost else 0


if __name__ == "__main__":
    sys.exit(main())
