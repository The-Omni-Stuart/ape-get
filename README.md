# ape-get

The Actually Portable Package Manager.

![Build status](https://github.com/The-Omni-Stuart/ape-get/actions/workflows/c-build.yml/badge.svg)
![License: GPL v3](https://img.shields.io/github/license/The-Omni-Stuart/ape-get)
![Latest release](https://img.shields.io/github/v/release/The-Omni-Stuart/ape-get?label=latest%20release&include_prereleases)

A command-line tool for managing GitHub, Gitea/Forgejo/Codeberg, GitLab, and rngit releases packaged as a Cosmopolitan Libc 'Actually Portable Executable'.

## Features

- **Search** for public repositories by name, across all configured providers
- **Install** the latest release of a repository
- **Update** installed releases if newer versions are available
- **Uninstall** installed release
- **List** all installed releases
- **Help** for usage guidance

Supported formats: appimage, ape, portable exe (incl. through wine)

---

## Quick Start

Download `ape-get.com` from the
[latest release](https://github.com/The-Omni-Stuart/ape-get/releases/latest).

On Linux and macOS:

```bash
chmod +x ape-get.com
./ape-get.com list
```

On Windows, run it from Command Prompt (`ape-get.com list`) or from PowerShell (`.\ape-get.com list`).

The first run registers `ape-get` (pun intended) as a command by installing a copy to `~/.local/bin/ape-get`; the downloaded file can then be deleted. From there `ape-get` updates itself like any release it manages:

```bash
ape-get update ape-get
```

---

## Supported commands

A repository is `owner/repo`, or `provider:owner/repo` to name one provider. `github`, `codeberg`, and `gitlab` are built in; `providers add` registers more. Experimental support for `rngit` repositories is also included.

| Command | Description |
| --- | --- |
| `search [name]` | Search for repositories by name |
| `install [owner/repo]` | Install the latest release of a repository |
| `install --version <tag> [owner/repo]` | Install a specific release |
| `uninstall [owner/repo]` | Uninstall a previously installed release |
| `update [owner/repo]` | Update an installed release if a newer version is available |
| `update --all` | Update every installed release |
| `rollback [owner/repo]` | Restore the latest local backup |
| `lock` / `unlock [owner/repo]` | Keep updates away from a release |
| `list` | List all installed releases |
| `info [owner/repo]` | Show version, path, checksums, and backups |
| `providers ...` | List, add, update, remove, or recover providers |
| `doctor` | Diagnose state and platform issues, `--heal` applies fixes |
| `adopt [owner/repo]` | Adopt an existing Linux install |
| `completions bash\|zsh` | Print a shell completion script |
| `help` | Show usage instructions |

Example repositories:

- github:keepassxreboot/keepassxc
- github:ip7z/7zip
- github:obsproject/obs-studio

---

## Advanced

### Prerequisites

- CMake 3.20+
- The pinned cosmocc toolchain (URL and SHA-256 in `toolchain/PIN`)

### Building the application

To build the project:

```bash
cmake -B build -DCMAKE_TOOLCHAIN_FILE=toolchain/cosmo.cmake
cmake --build build
```

This will produce `build/ape-get`, the host binary.

To package the portable binaries:

```bash
for a in x86_64 aarch64; do
  cmake -B build/$a -DCMAKE_TOOLCHAIN_FILE=toolchain/cosmo.cmake -DAPEGET_ARCH=$a
  cmake --build build/$a
done
bin/package.sh build/x86_64 build/aarch64 build/
```

This produces three portable binaries:

| File | Runs on | Size |
| --- | --- | --- |
| `ape-get.com` | everywhere (in theory) | ~1.5 MB |
| `ape-get-x86_64.com` | everywhere x86 (in theory) | < 1 MB |
| `ape-get-aarch64.com` | everywhere arm (in theory) | < 1 MB |

Unfortunately macOS has not yet been tested, but any and all reports are welcome.

Packagers can build with `-DAPEGET_SELF=OFF`: self-registration and self-update are compiled out entirely, leaving the package manager in charge of upgrades.

To remove the generated files:

```bash
rm -rf build
```

### Running the application

```bash
./build/ape-get.com [command] [options]
```

Example:

```bash
./build/ape-get.com list
```

### Running the tests

```bash
python3 src/test/scenarios/run.py --bin ./build/ape-get
```

### Documentation

To see usage instructions:

```bash
ape-get help
```
