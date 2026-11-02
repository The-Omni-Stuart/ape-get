#!/usr/bin/env python3
"""Completion check through real readline: press actual Tabs on a pty.

The function-level test (test_completions.sh) drove _ape_get by hand and
missed that -o default leaked filename completion; here every assertion
comes from keystrokes into a bash on a pty. The cwd holds only ZQJX_*
files, so that string anywhere in the transcript means Tab completed
filenames.

usage: test_completions_pty.py <ape-get-exe>
"""
import os, pty, select, subprocess, sys, tempfile, time

MARKER = "ZQJX"

def main():
    exe = sys.argv[1]
    work = tempfile.mkdtemp(prefix="apg-pty-")
    bindir, filedir = os.path.join(work, "bin"), os.path.join(work, "files")
    os.mkdir(bindir); os.mkdir(filedir)
    for name in ("alpha", "beta", "gamma"):
        open(os.path.join(filedir, f"{MARKER}_{name}"), "w").close()

    # stub for the data commands; list/providers emit one json object per
    # line, which is all the script's grep -o needs.
    stub = os.path.join(bindir, "ape-get")
    with open(stub, "w") as f:
        f.write("#!/bin/sh\n"
                'case "$*" in\n'
                '"list --json") printf \'%s\\n\' '
                "'{\"provider\":\"github\",\"repo\":\"neovim/neovim\"}' "
                "'{\"provider\":\"gitlab\",\"repo\":\"group/tool\"}' ;;\n"
                '"providers list --json") printf \'%s\\n\' '
                "'{\"name\":\"github\"}' '{\"name\":\"gitlab\"}' ;;\n"
                '*) exit 0 ;;\nesac\n')
    os.chmod(stub, 0o755)

    with open(os.path.join(work, "comp.sh"), "w") as f:
        f.write(subprocess.run([exe, "completions", "bash"],
                               check=True, capture_output=True).stdout.decode())
    rc = os.path.join(work, "rc")
    with open(rc, "w") as f:
        f.write("PS1='RT> '\n"
                "bind 'set show-all-if-ambiguous on'\n"
                f"source {os.path.join(work, 'comp.sh')}\n")

    pid, fd = pty.fork()
    if pid == 0:
        os.environ["PATH"] = bindir + os.pathsep + os.environ["PATH"]
        os.environ["TERM"] = "dumb"
        os.chdir(filedir)
        os.execvp("bash", ["bash", "--rcfile", rc, "-i"])
    out = bytearray()
    def drain(seconds):
        end = time.time() + seconds
        while time.time() < end:
            r, _, _ = select.select([fd], [], [], max(0.0, end - time.time()))
            if not r:
                break
            try:
                d = os.read(fd, 65536)
            except OSError:
                break
            if not d:
                break
            out.extend(d)
    time.sleep(1.0)
    cases = [b"ape-get i\t", b"ape-get \t\t", b"ape-get doctor\t\t",
             b"ape-get list\t\t", b"ape-get install gi\t", b"ape-get --col\t"]
    for keys in cases:
        os.write(fd, keys)
        drain(0.6)
        os.write(fd, b"\x03")   # abandon line between cases
        drain(0.2)
    os.write(fd, b"\nexit\n")
    drain(1.0)
    os.close(fd)
    os.waitpid(pid, 0)

    text = out.decode(errors="replace")
    fails = []
    if MARKER in text:
        fails.append(f"Tab leaked filename completion ({MARKER}* files shown)")
    for want, why in (("info", "command menu after 'ape-get i<TAB>'"),
                      ("uninstall", "command menu after 'ape-get <TAB>'"),
                      ("gitlab:", "provider words after 'install gi<TAB>'"),
                      ("--colour", "unique flag insert after '--col<TAB>'")):
        if want not in text:
            fails.append(f"missing {want!r} ({why})")
    if fails:
        print("PTY completion failures:\n  " + "\n  ".join(fails))
        print("--- transcript ---\n" + text)
        return 1
    print("pty completion checks passed")
    return 0

if __name__ == "__main__":
    sys.exit(main())
