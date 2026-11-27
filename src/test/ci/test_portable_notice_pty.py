#!/usr/bin/env python3
"""The portable notice, through a real tty.

isatty gates it, so no piped scenario can ever see it: here the child's
stdout and stderr are a pty slave and every assertion reads the transcript.
It pins both halves of the promise — a portable run says so, and an installed
one never does.

usage: test_portable_notice_pty.py <ape-get-exe>
"""
import os, pty, select, subprocess, sys, tempfile, time

NOTICE = "portable mode"
fails = 0
cases = 0


def note(msg):
    global fails
    print(f"FAIL: {msg}")
    fails += 1


def run_tty(exe, args, home):
    """Run with stdout AND stderr on a pty; return (transcript, rc)."""
    master, slave = pty.openpty()
    p = subprocess.Popen([exe] + args, stdout=slave, stderr=slave, cwd=home,
                         env={"HOME": home, "PATH": "/usr/bin:/bin",
                              "TERM": "xterm", "NO_COLOUR": "1"})
    os.close(slave)
    out = b""
    deadline = time.time() + 60
    while time.time() < deadline:
        ready, _, _ = select.select([master], [], [], 0.5)
        if not ready:
            if p.poll() is not None:
                break
            continue
        try:
            chunk = os.read(master, 4096)
        except OSError:
            break
        if not chunk:
            break
        out += chunk
    os.close(master)
    p.wait(timeout=10)
    return out.decode(errors="replace"), p.returncode


def check(label, cond):
    global cases
    cases += 1
    if not cond:
        note(label)


def main():
    exe = os.path.abspath(sys.argv[1])
    home = tempfile.mkdtemp(prefix="apg-notice-")

    text, rc = run_tty(exe, ["about"], home)
    check("portable run exits 0", rc == 0)
    check("a tty run mentions portable mode", NOTICE in text)
    check("and names the remedy", "ape-get setup" in text)

    text, _ = run_tty(exe, ["completions", "bash"], home)
    check("setup's own command stays quiet", NOTICE not in text)

    text, _ = run_tty(exe, ["list", "--json"], home)
    check("--json output stays clean", NOTICE not in text)

    # a piped stderr is not a tty: scripts get nothing extra
    p = subprocess.run([exe, "about"], cwd=home, capture_output=True,
                       env={"HOME": home, "PATH": "/usr/bin:/bin",
                            "NO_COLOUR": "1"})
    check("a piped run stays quiet", NOTICE not in p.stderr.decode())

    # integrate, and the nag ends — this is the whole point of setup
    p = subprocess.run([exe, "setup", "-y"], cwd=home, capture_output=True,
                       env={"HOME": home, "PATH": "/usr/bin:/bin",
                            "NO_COLOUR": "1", "APEGET_NO_SELF": ""})
    check("setup -y succeeds", p.returncode == 0)
    text, _ = run_tty(exe, ["about"], home)
    check("an installed run is silent about it", NOTICE not in text)

    print(f"{cases - fails} of {cases} portable-notice cases passed")
    return fails != 0


if __name__ == "__main__":
    sys.exit(main())
