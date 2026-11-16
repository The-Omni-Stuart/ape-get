#!/usr/bin/env python3
"""The portable-mode notice through a real terminal.

isatty on stderr decides part of whether the notice is said, so a pipe cannot
exercise it -- and the pipe contract is exactly what the notice must leave
alone, which is why the scenario suite runs everything through pipes. Here the
child's standard streams are a pty, with TERM=dumb so the styling gate stays
off and the bytes compared are the plain ones.

usage: test_portable_notice_pty.py <ape-get-exe>
"""
import os, pty, select, shutil, sys, tempfile, time

NOTICE = "ape-get is running in portable mode."
HINT = "Run 'ape-get setup' to install it."
DEADLINE_SECONDS = 30


def run_on_pty(exe, args, home, path):
    """One run with its standard streams on a pty: (text, exit code)."""
    env = {"HOME": home, "USERPROFILE": home, "TERM": "dumb", "PATH": path}
    pid, fd = pty.fork()
    if pid == 0:
        os.chdir(home)
        try:
            os.execve(exe, [exe] + args, env)
        except OSError:
            os._exit(127)
    out = bytearray()
    end = time.time() + DEADLINE_SECONDS
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
    os.close(fd)
    _, status = os.waitpid(pid, 0)
    return out.decode(errors="replace"), os.waitstatus_to_exitcode(status)


def main():
    exe = os.path.abspath(sys.argv[1])
    root = tempfile.mkdtemp(prefix="apg-portable-")
    home = os.path.join(root, "home")
    os.makedirs(home)
    plain = "/usr/bin:/bin"
    fails = []

    def check(what, out, shown):
        got = NOTICE in out
        if got != shown:
            fails.append(f"{what}: notice {'said' if got else 'silent'}, "
                         f"wanted {'said' if shown else 'silent'}")

    # A download nobody has installed, run by a person at a terminal: the
    # notice, the way out of it, and the command's own outcome untouched.
    out, code = run_on_pty(exe, ["about"], home, plain)
    check("download at a terminal", out, True)
    if HINT not in out:
        fails.append("the notice does not say how to install")
    if code != 0:
        fails.append(f"the notice changed the exit code ({code})")

    # The readers that must not be interrupted: a machine parsing stdout, a
    # scripted run, and a shell eval'ing the completion script it is asked for.
    for args, why in ((["about", "--json"], "--json"),
                      (["about", "--quiet"], "--quiet"),
                      (["completions", "bash"], "completions")):
        out, _ = run_on_pty(exe, args, home, plain)
        check(why, out, False)

    # The other half of the rule: the same program with nothing wrong about
    # where it lives. A host that reaches the binary through a wrapper script
    # names a different file to the kernel, so there the two cases would test
    # the loader rather than the tool.
    with open(exe, "rb") as f:
        wrapper = f.read(2) == b"#!"
    if wrapper:
        print("note: wrapper entry point; installed-file cases skipped")
    else:
        placed_home = os.path.join(root, "placed")
        bindir = os.path.join(placed_home, ".local", "bin")
        os.makedirs(bindir)
        placed = os.path.join(bindir, "ape-get")
        shutil.copy2(exe, placed)
        out, _ = run_on_pty(placed, ["about"], placed_home, plain)
        check("run from the install root", out, False)

        elsewhere = os.path.join(root, "elsewhere")
        os.makedirs(elsewhere)
        on_path = os.path.join(elsewhere, "ape-get")
        shutil.copy2(exe, on_path)
        out, _ = run_on_pty(on_path, ["about"], home, elsewhere + ":" + plain)
        check("directory already on PATH", out, False)

    if fails:
        print("portable notice failures:\n  " + "\n  ".join(fails))
        return 1
    print("portable notice checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
