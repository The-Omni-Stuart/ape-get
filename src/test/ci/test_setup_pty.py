#!/usr/bin/env python3
"""Setup's questions, answered on a real terminal.

setup asks which system it is installing on, where to install, whether PATH
may be touched, and then whether to go ahead. Only a terminal can answer
those, and the scenario suite drives the same command through pipes precisely
to pin that it stays silent there (decline.env). Here the child runs on a pty,
so the questions are real and the answers are typed: default accepted, a
directory named, PATH refused, the final answer withheld, and the system
denied - which is the one answer that must install nothing and instead say
what setup supports and how to do it by hand. The executable installed is the
real binary, so the bytes copied are checked rather than a fixture's.

usage: test_setup_pty.py <ape-get-exe>
"""
import os, pty, select, shutil, stat, sys, tempfile, time

PATH_LINE = 'export PATH="{dir}:$PATH"  # ape-get'


def run(exe, args, home, path, typed, extra=None):
    """One run on a pty with `typed` answered into it: (text, exit code)."""
    env = {"HOME": home, "USERPROFILE": home, "TERM": "dumb",
           "PATH": path, "NO_COLOUR": "1"}
    env.update(extra or {})
    pid, fd = pty.fork()
    if pid == 0:
        os.chdir(home)
        try:
            os.execve(exe, [exe] + args, env)
        except OSError:
            os._exit(127)
    os.write(fd, typed.encode())
    out = bytearray()
    end = time.time() + 30
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


def fresh_home(root, name):
    home = os.path.join(root, name)
    os.makedirs(home)
    return home


def mode_of(path):
    return stat.S_IMODE(os.stat(path).st_mode) if os.path.exists(path) else None


def main():
    exe = os.path.abspath(sys.argv[1])
    root = tempfile.mkdtemp(prefix="apg-setup-pty-")
    plain = "/usr/bin:/bin"
    fails = []

    def check(what, ok, detail=""):
        if not ok:
            fails.append(f"{what}: {detail}")

    # Every answer accepted: the system, the default directory, PATH, and the
    # confirmation.
    home = fresh_home(root, "default")
    out, code = run(exe, ["setup"], home, plain, "\n\n\ny\n")
    target = os.path.join(home, ".local", "bin", "ape-get")
    check("defaults accepted exit", code == 0, f"exit {code}\n{out}")
    check("defaults accepted installed", mode_of(target) == 0o755,
        f"mode {mode_of(target)}")
    if os.path.exists(target):
        check("defaults accepted bytes copied",
            os.path.getsize(target) == os.path.getsize(exe),
            "installed size differs from the executable")
    profile = os.path.join(home, ".profile")
    want = PATH_LINE.format(dir=os.path.join(home, ".local", "bin"))
    check("defaults accepted PATH written",
        os.path.exists(profile) and want in open(profile).read(),
        f"no {want!r}")
    for asked in ("Is that the system", "Installation directory",
                  "Add it to PATH"):
        check(f"asked {asked!r}", asked in out, f"absent from:\n{out}")

    # The directory question spells out what Enter does: a bracketed path on
    # its own leaves a person guessing whether Enter installs there, asks
    # again, or declines.
    default_dir = os.path.join(home, ".local", "bin")
    check("directory question shows the directory Enter takes",
        f"[default: {default_dir}]" in out, f"absent from:\n{out}")
    check("directory question says Enter and an alternative",
        "Press Enter" in out and "or type" in out,
        f"the question does not say what Enter does:\n{out}")

    # A directory named at the question, rather than the one suggested.
    home = fresh_home(root, "custom")
    chosen = os.path.join(home, "opt", "apg")
    out, code = run(exe, ["setup"], home, plain, f"\n{chosen}\n\ny\n")
    check("custom directory exit", code == 0, f"exit {code}\n{out}")
    check("custom directory installed",
        mode_of(os.path.join(chosen, "ape-get")) == 0o755,
        f"mode {mode_of(os.path.join(chosen, 'ape-get'))}")
    check("custom directory default untouched",
        mode_of(os.path.join(home, ".local", "bin", "ape-get")) is None,
        "the default directory was used after all")
    profile = os.path.join(home, ".profile")
    check("custom directory PATH written",
        os.path.exists(profile)
        and PATH_LINE.format(dir=chosen) in open(profile).read(),
        f"no entry for {chosen}")

    # PATH refused at the question: the command is installed, the shell's
    # own files are left alone.
    home = fresh_home(root, "no-path")
    out, code = run(exe, ["setup"], home, plain, "\n\nn\ny\n")
    check("PATH refused exit", code == 0, f"exit {code}\n{out}")
    check("PATH refused installed",
        mode_of(os.path.join(home, ".local", "bin", "ape-get")) == 0o755,
        "not installed")
    check("PATH refused shell untouched",
        not os.path.exists(os.path.join(home, ".profile")),
        ".profile was written despite the refusal")

    # The last answer withheld: nothing is created.
    home = fresh_home(root, "declined")
    out, code = run(exe, ["setup"], home, plain, "\n\n\nn\n")
    check("declined exit", code != 0, "declining reported success")
    check("declined nothing installed",
        mode_of(os.path.join(home, ".local", "bin", "ape-get")) is None,
        "installed after a refusal")

    # The system it found is denied: nothing is installed, and what setup
    # supports is offered as a choice rather than guessed at. A number out of
    # range stops it, because installing for a system nobody chose is worse
    # than installing nothing.
    home = fresh_home(root, "menu-refused")
    out, code = run(exe, ["setup"], home, plain, "n\n9\n")
    check("menu refused exit", code != 0,
        "reported success having installed nothing")
    check("menu refused nothing installed",
        mode_of(os.path.join(home, ".local", "bin", "ape-get")) is None,
        "installed after an answer outside the list")
    check("menu refused shell untouched",
        not os.path.exists(os.path.join(home, ".profile")),
        ".profile was written after an answer outside the list")
    for said in ("1  Linux", "2  macOS", "3  Windows", "4  other",
                 "~/.profile", "~/.zshrc", "ape-get.com",
                 "Not one of those"):
        check(f"menu says {said!r}", said in out, f"absent from:\n{out}")

    # 4 is the system setup has not been told about: the manual steps, for
    # both families, since nothing narrows the choice.
    home = fresh_home(root, "menu-manual")
    out, code = run(exe, ["setup"], home, plain, "n\n4\n")
    check("menu manual exit", code != 0,
        "reported success having installed nothing")
    check("menu manual nothing installed",
        mode_of(os.path.join(home, ".local", "bin", "ape-get")) is None,
        "installed after being told to show steps instead")
    for said in ("mkdir -p", "chmod 755", ".local/bin/ape-get", "~/.zshrc",
                 "ape-get.com", "Programs\\ape-get"):
        check(f"manual steps show {said!r}", said in out, f"absent from:\n{out}")
    check("manual steps name the file", exe in out,
        f"the file to copy is not named in:\n{out}")

    # 3 is a system this is not: setup lays out for the system it was told,
    # naming the Windows command in a Windows directory, and its PATH step
    # says plainly that it could not be done rather than claiming it was or
    # quietly editing the shell files of the system it happens to be on.
    home = fresh_home(root, "menu-windows")
    win_dir = "C:\\apg"
    out, code = run(exe, ["setup", "--prefix", win_dir], home, plain,
        "n\n3\n\ny\n")
    check("menu windows says chosen", "chosen by you" in out,
        f"the plan does not say the system was chosen:\n{out}")
    check("menu windows installed where told",
        mode_of(os.path.join(home, win_dir, "ape-get.com")) == 0o755,
        f"no ape-get.com under {win_dir}:\n{out}")
    check("menu windows left the unix layout alone",
        mode_of(os.path.join(home, ".local", "bin", "ape-get")) is None,
        "installed into the Linux layout after being told it was Windows")
    check("menu windows shell untouched",
        not os.path.exists(os.path.join(home, ".profile")),
        "the Linux shell's files were edited for a Windows install")
    check("menu windows PATH refused honestly", "Could not add" in out,
        f"the PATH step neither worked nor said so:\n{out}")
    check("menu windows did not claim PATH success", "Added " not in out,
        "claimed to have added a Windows directory to PATH")
    check("menu windows exit", code != 0,
        "reported success with the PATH step unfinished")

    # A directory given in the "/C:" shape the runtime reports a Windows drive
    # path as: setup installs where Windows says that directory is, rather than
    # refusing the path its own prompt had just offered, and it does not create
    # a directory called C at the root of the disk it happens to run on.
    home = fresh_home(root, "menu-windows-slash")
    out, code = run(exe, ["setup", "--prefix", "/C/apg-slash"], home, plain,
        "n\n3\n\ny\n")
    native = os.path.join(home, "C:\\apg-slash")
    check("menu windows slash names the drive in the plan",
        "Install directory: C:\\apg-slash" in out,
        f"the plan does not say where Windows calls the directory:\n{out}")
    check("menu windows slash installed where Windows names it",
        mode_of(os.path.join(native, "ape-get.com")) == 0o755,
        f"no ape-get.com under {native}:\n{out}")
    check("menu windows slash made no directory called C",
        not os.path.exists("/C/apg-slash"),
        "a directory named C was made at the root instead")

    # 1 is the system it already had: the menu is a detour, not a restart, and
    # the install goes ahead as if the question had never been asked.
    home = fresh_home(root, "menu-linux")
    out, code = run(exe, ["setup"], home, plain, "n\n1\n\n\ny\n")
    check("menu linux exit", code == 0, f"exit {code}\n{out}")
    check("menu linux installed",
        mode_of(os.path.join(home, ".local", "bin", "ape-get")) == 0o755,
        "not installed")
    profile = os.path.join(home, ".profile")
    check("menu linux PATH written",
        os.path.exists(profile)
        and PATH_LINE.format(dir=os.path.join(home, ".local", "bin"))
        in open(profile).read(),
        "no PATH entry after choosing the system from the menu")

    if fails:
        print("setup pty failures:\n  " + "\n  ".join(fails))
        return 1
    print("setup pty checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
