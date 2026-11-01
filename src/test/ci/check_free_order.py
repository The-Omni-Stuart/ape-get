#!/usr/bin/env python3
"""Pin the free-before-use order the tools cannot observe at run time.

The binary links Cosmopolitan libc, so sanitiser and MALLOC_PERTURB_
tricks do not reach its allocator; a use-after-free here surfaces only
as a rare wrong message. This check flags any read of a pointer after a
standalone free(x); statement and before x is reassigned or the
enclosing block closes. Exit 0 = no violations.
"""
import pathlib
import re
import sys


def strip_noise(text):
    """Blank out comments and string/char literals (newlines kept)."""
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        two = text[i:i + 2]
        if two == "//":
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif two == "/*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join(ch if ch == "\n" else " " for ch in text[i:j]))
            i = j
        elif c in "\"'":
            j = i + 1
            while j < n and text[j] != c:
                j += 2 if text[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(c + " " * max(j - i - 2, 0) + (c if j - i >= 2 else ""))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def check_file(path):
    lines = strip_noise(path.read_text()).split("\n")
    depth_at = []          # block depth at the start of each line
    depth = 0
    for line in lines:
        depth_at.append(depth)
        depth += line.count("{") - line.count("}")
        depth = max(depth, 0)
    bad = []
    for i, line in enumerate(lines):
        m = re.match(r"^\s*free\((\w+)\);\s*$", line)
        if not m:
            continue
        var, floor = m.group(1), depth_at[i]
        for j in range(i + 1, len(lines)):
            later = lines[j]
            if depth_at[j] < floor or re.search(rf"\b{var}\b\s*=[^=]", later):
                break       # block closed or pointer reassigned
            if re.search(rf"\b{var}\b\s*(\+\+|--|\+=|-=)", later):
                break
            rest = re.sub(rf"\bfree\(\s*{var}\s*\)", "", later)
            if re.search(rf"\b{var}\b", rest):
                bad.append(f"{path}:{j + 1}: {var} used after free at line {i + 1}")
                break
    return bad


def main():
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "src/main")
    issues = []
    for f in sorted(root.rglob("*.c")):
        issues += check_file(f)
    for msg in issues:
        print(msg)
    sys.exit(1 if issues else 0)


if __name__ == "__main__":
    main()
