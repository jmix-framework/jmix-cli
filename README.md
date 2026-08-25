# Jmix CLI

[![CI](https://github.com/jmix-framework/jmix-cli/actions/workflows/ci.yml/badge.svg)](https://github.com/jmix-framework/jmix-cli/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Jmix CLI is a command-line project generator for the
[Jmix](https://www.jmix.io/) framework. It provides a keyboard-driven wizard
for local development and a non-interactive mode for scripts and CI.

The CLI uses the same project template artifact and Groovy rendering model as
Jmix Studio, with the goal of producing equivalent project output outside the
IDE.

## Quick start

On macOS or Linux, install the latest release and open the wizard with one
command:

```shell
curl -fsSL https://github.com/jmix-framework/jmix-cli/releases/latest/download/install.sh | bash
```

On Windows x64, run in PowerShell:

```powershell
irm https://github.com/jmix-framework/jmix-cli/releases/latest/download/install.ps1 | iex
```

The installer downloads a checksummed, platform-specific application with its
own Java runtime, installs the `jmix` command, and starts the project wizard.
Run `jmix` later to open it again.

## Usage

### Interactive wizard

Run either `jmix` or `jmix new` to start the interactive wizard:

```shell
jmix
```

Selection lists display their controls at the bottom:

```text
───────────────────────────────────────
↑ up • ↓ down • space toggle • enter confirm • esc back
```

Press Enter to accept a default and Esc to return to the previous step. On the
project location step, Tab completes directory paths. In
consoles without raw terminal support, the wizard falls back to numbered lists
and line-based input; its navigation bar shows `< back` because Esc cannot be
captured there. When piped input runs out mid-wizard, the remaining steps use
their defaults; the wizard fails only when a required value has no default.

After generation the wizard offers to set up the
[Jmix Agent Toolkit](https://github.com/jmix-framework/jmix-agent-toolkit) —
skills and guidelines for AI coding agents — and to open the new project — in
IntelliJ IDEA when it is installed, otherwise in the system file manager — and
run it right away; once the application starts, it opens in the browser. When no compatible
JDK is found, the run downloads one automatically (Eclipse Temurin, verified
against its published checksum, into `~/.jmix/jdks`); declining the run offers
the same JDK installation as a separate step. The offer defaults to "no" and
is skipped in non-interactive runs.

### Non-interactive generation

For scripts and CI, provide a project name and use `--non-interactive`:

```shell
jmix new jmix-project \
    --non-interactive \
    --template application \
    --package com.company.jmixproject \
    --locales en,de \
    --no-git
```

### Options

| Option               | Description                                                    | Default                              |
|----------------------|----------------------------------------------------------------|--------------------------------------|
| `<name>`             | Project name                                                   | Required in non-interactive mode     |
| `--template`         | Template ID, such as `application` or `application-kotlin`     | First available project template     |
| `--jmix-version`     | Jmix platform version                                          | Latest stable version                |
| `--package`          | Base Java package                                              | `com.company.<project-name>`         |
| `--project-id`       | Prefix for entity, table, and bean names; maximum 7 characters | Template default                     |
| `--theme`            | UI theme: `aura` or `lumo`                                     | Depends on Jmix version and template |
| `--locales`          | Comma-separated locale codes                                   | `en`                                 |
| `--path`             | Target directory                                               | `./<project-name>` (the wizard suggests `~/IdeaProjects/<project-name>`) |
| `--repository`       | Maven repository containing Jmix templates                     | Jmix public repository               |
| `--no-git`           | Do not initialize a Git repository                             | Git initialization enabled           |
| `--include-unstable` | Include RC and snapshot versions in version selection          | Disabled                             |
| `--force`            | Generate into a non-empty directory without confirmation       | Disabled                             |
| `--non-interactive`  | Do not prompt; use arguments, options, and defaults            | Disabled                             |

Run `jmix new --help` for the authoritative command reference.

### Templates and offline use

Templates are downloaded from the
`io.jmix.templates.studio:jmix-studio-templates` Maven artifact. Metadata and
template JARs are cached per repository under `~/.jmix/templates/`.

After a version has been downloaded, it can be used while offline. Snapshot
templates are refreshed when the repository is reachable and fall back to the
cached copy otherwise.

## Development

Building from source requires JDK 17 or later to launch the Gradle wrapper. The
build provisions JDK 25 automatically.

### Run from source

Clone the repository and start the wizard:

```shell
git clone https://github.com/jmix-framework/jmix-cli.git
cd jmix-cli
./run.sh
```

`run.sh` is a macOS/Linux convenience script. The equivalent Gradle command is:

```shell
./gradlew run --console=plain
```

On Windows:

```powershell
gradlew.bat run --console=plain
```

Pass CLI arguments through either launcher:

```shell
./run.sh new jmix-project --no-git
./gradlew run --args="new jmix-project --no-git" --console=plain
```

### Build and test

Run the test suite:

```shell
./gradlew build
```

Run network-backed integration tests against the real template repository:

```shell
JMIX_CLI_IT=true ./gradlew test
```

### Build distributions

- `./gradlew installDist` creates a local distribution under
  `build/install/jmix-cli/`.
- `./gradlew releaseBundle` creates a self-contained platform archive and its
  SHA-256 checksum under `build/release/`.

See [docs/DISTRIBUTION.md](docs/DISTRIBUTION.md) for supported platforms and the
release process.

## Contributing

Issues and pull requests are welcome. Before opening a pull request:

1. Add or update tests for changed behavior.
2. Run the development checks above. Include the network-backed test for
   changes to template discovery, rendering, bindings, or project generation.
3. Update this README when changing user-visible commands or behavior.
4. Keep generated output compatible with Jmix Studio templates.

See [AGENTS.md](AGENTS.md) for architecture, invariants, and repository-specific
development guidance.

## License

Jmix CLI is available under the [Apache License 2.0](LICENSE).
