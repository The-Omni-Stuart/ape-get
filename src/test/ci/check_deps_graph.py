#!/usr/bin/env python3
"""Validate the declared module graph.

Reads src/main/deps.mk, assigns levels by longest-path depth, and fails on
cycles, self-edges, or edges that do not point strictly downward.
src/test/ci/test_deps_graph.py feeds it synthetic violations. Exit 0 = graph
is legal.
"""
import sys

LEVEL_ORDER_HINT = [
    ["json", "repo", "cmd", "ui", "path", "semver", "redact", "clock", "crypto"],
    ["fs", "proc", "output", "prompt"],
    ["platform", "compat", "http", "txn", "keys"],
    ["provider", "state", "select", "verify"],
    ["probe", "doctor"],
    ["pipeline"],
    ["main"],
]


def parse(path):
    deps = {}
    for lineno, raw in enumerate(open(path), 1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        if ":" not in line:
            sys.exit(f"deps.mk:{lineno}: missing ':' in {line!r}")
        name, _, rest = line.partition(":")
        name, rest = name.strip(), rest.strip()
        deps[name] = [d.strip() for d in rest.split(",") if d.strip()]
    return deps


def levels_from_edges(deps):
    """Longest-path depth per node; -1 marks a cycle participant."""
    level, state = {}, {}

    def visit(n, stack):
        if n in state:
            return state[n]
        if n in stack:
            level[n] = -1
            state[n] = -1
            return -1
        for d in deps.get(n, []):
            if d not in deps:
                sys.exit(f"deps.mk: {n} declares unknown dependency {d!r}")
            if visit(d, stack | {n}) == -1:
                level[n] = -1
                state[n] = -1
                return -1
        lvl = 1 + max((level[d] for d in deps[n]), default=-1)
        level[n] = lvl
        state[n] = lvl
        return lvl

    for n in deps:
        visit(n, frozenset())
    return level


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "src/main/deps.mk"
    deps = parse(path)
    for n, ds in deps.items():
        if n in ds:
            sys.exit(f"deps.mk: {n} depends on itself")
    level = levels_from_edges(deps)
    bad = [n for n, l in level.items() if l == -1]
    if bad:
        sys.exit(f"cycle detected involving: {', '.join(sorted(bad))}")
    for n, ds in deps.items():
        for d in ds:
            if not level[d] < level[n]:
                sys.exit(f"edge {n}(L{level[n]}) -> {d}(L{level[d]}) "
                         "does not point strictly downward")
    hinted = {m: i for i, row in enumerate(LEVEL_ORDER_HINT) for m in row}
    for n, l in level.items():
        if n in hinted and hinted[n] != l:
            sys.exit(f"{n}: level by edges (L{l}) disagrees with documented "
                     f"table (L{hinted[n]})")
    print(f"graph ok: {len(deps)} modules, "
          f"{max(level.values()) + 1} levels (L0-L{max(level.values())})")


if __name__ == "__main__":
    main()
