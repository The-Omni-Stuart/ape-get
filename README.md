# GitHub Releases Manager

A multi-platform command-line tool for GitHub, Gitea/Forgejo/Codeberg, and GitLab releases.

## Features

- **Search** repositories across every configured provider
- **Install** the latest release, a specific tag, or a specific asset
- **Verify** SHA-256 checksums and publisher signatures before installing
- **Update** one release or every installed release (`update --all`)
- **Check** for newer releases without changing anything (`list --outdated`)
- **Roll back** to the previous version from a local backup
- **Uninstall** installed releases
- **List** and **inspect** installed releases
- **Diagnose** state, PATH, disk space, mounts, and verification tools with `doctor`

Supported formats: AppImage, Actually Portable Executable (APE), portable Windows exe, MSI, NSIS exe, and DMG.

---

## Prerequisites

For building from source: Java 21+ and Maven 3.6+. The prebuilt installers include a Java runtime.

---

## Installing a release

Tagged releases publish self-contained builds and a `SHA256SUMS` file:

| Platform | File |
| --- | --- |
| Linux x64 | `github-releases-manager-linux-x64.tar.gz` |
| Windows x64 | `github-releases-manager-windows-x64.msi` |
| macOS arm64 | `github-releases-manager-macos-arm64.dmg` |
| macOS x64 | `github-releases-manager-macos-x64.dmg` |

Extract the Linux archive and run `github-releases-manager/bin/github-releases-manager`, or install
the MSI/DMG. The macOS build is ad-hoc signed but not notarised, so the first launch needs
right-click → Open.

Verify downloads with `sha256sum -c SHA256SUMS` (`shasum -a 256 -c SHA256SUMS` on macOS).

## Building the application

To build the project:

```bash
mvn package
```

This will produce `target/github-releases-manager-1.9.10-jar-with-dependencies.jar`


To remove the generated files:

```bash
mvn clean
```

## Running the application

```bash
java -jar target/github-releases-manager-1.9.10-jar-with-dependencies.jar [command] [options]
```

Example:

```bash
java -jar target/github-releases-manager-1.9.10-jar-with-dependencies.jar list
```

## Supported commands

`<repo>` is `provider:owner/repo` or `provider:group/subgroup/project`. The provider is required.

| Command | Description |
| --- | --- |
| `search <query>` | Search all configured providers |
| `install <repo>` | Install the latest release (refuses an already-tracked repo; use `update`) |
| `install --version <tag> <repo>` | Install a specific release |
| `install --asset <name\|index> <repo>` | Choose an asset non-interactively |
| `update <repo>` | Update an installed release |
| `update --all` | Update every installed release |
| `list --outdated` | List installed releases with newer releases available |
| `uninstall <repo>` | Uninstall a previously installed release |
| `rollback <repo>` | Restore the latest local backup |
| `list` | List installed releases |
| `info <repo>` | Show version, path, checksums, signatures, and backups |
| `providers ...` | List, add, update, remove, or recover providers |
| `doctor` | Diagnose state and platform issues |
| `adopt <repo>` | Adopt an existing Linux install |
| `help` | Show usage instructions |

Example repositories:

- github:keepassxreboot/keepassxc
- github:ip7z/7zip
- github:obsproject/obs-studio

## Providers and common options

Built-in providers are `github`, `codeberg`, and `gitlab`. Repository arguments must name the
provider, for example `github:owner/repo`.

```bash
search neovim                    # queries every configured provider
search --provider github neovim  # one provider only
install codeberg:forgejo/forgejo
install gitlab:group/subgroup/project
install --version v1.2.3 github:owner/repo
install --dry-run --json github:owner/repo
install --name ff github:fastfetch-cli/fastfetch
install --kind portable-exe github:owner/tool
install --desktop --terminal github:owner/tool
update --all
list --outdated
```

Add a custom Gitea, Forgejo, or GitLab instance:

```bash
providers add work https://git.example.com --type auto
```

Provider tokens are read from the environment variable named by `token_env`; secrets are never
stored in the config.

Common flags: `--asset`, `--version`, `--kind`, `--name`, `--no-command`, `--all`,
`--outdated`, `--channel`, `--dry-run`, `--desktop`, `--terminal`, `--icon`,
`--assimilate`, `--json`, `--quiet`, `--yes`.
`--json` writes one document to stdout; progress, warnings, and prompts go to stderr.
`--yes` is required for non-interactive use; without it, confirmation prompts refuse when no TTY is
available.

Portable packages create a command in the user bin directory by default. `--name` changes it and
`--no-command` suppresses it. Plain `.exe` assets are treated as NSIS installers; use
`--kind portable-exe` for a portable Windows executable.

`--channel beta` follows prereleases where the provider exposes them. Automatic updates are
configured in `grm_config.json` with `auto_update` (`off`, `check`, or `apply`),
`update_channel` (`stable` or `beta`), and `update_interval_hours`. A `list` command performs a
due check or update and records it in `grm_updates.log`. `--desktop` creates a Linux application-menu entry; `--terminal`
marks it as terminal-based and `--icon` points at an icon file. `--assimilate` converts an APE to a
native binary using the cosmocc `assimilate` tool; it is never used unless requested.

## Running the tests

```bash
mvn test
```

## Documentation

To generate the full API documentation:

```bash
mvn javadoc:javadoc
```

The javadoc will be available at `target/reports/apidocs/index.html`
