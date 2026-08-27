# Jmix CLI

[![CI](https://github.com/jmix-framework/jmix-cli/actions/workflows/ci.yml/badge.svg)](https://github.com/jmix-framework/jmix-cli/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Jmix CLI creates [Jmix](https://www.jmix.io/) projects from the command line:
a keyboard-driven wizard for local use, a non-interactive mode for AI agents, scripts and
CI. It uses the same templates and rendering model as Jmix Studio and produces
equivalent projects.

## Quick start

macOS / Linux:

```shell
curl -fsSL https://github.com/jmix-framework/jmix-cli/releases/latest/download/install.sh | bash
```

Windows (PowerShell):

```powershell
irm https://github.com/jmix-framework/jmix-cli/releases/latest/download/install.ps1 | iex
```

The installer downloads a checksummed, self-contained build with its own Java
runtime, installs the `jmix` command, and starts the wizard. Run `jmix` to
open it again.

## Usage

### Interactive wizard

Run `jmix` or `jmix new`. Selection lists show their controls at the bottom:

```text
───────────────────────────────────────
↑ up • ↓ down • space toggle • enter confirm • esc back
```

### Non-interactive generation

For AI agents, scripts and CI, pass a project name and `--non-interactive`:

```shell
jmix new jmix-project \
    --non-interactive \
    --template application \
    --package com.company.jmixproject \
    --locales en,de \
    --no-git
```

### Options

| Option               | Description                                                    | Default                                                                      |
|----------------------|----------------------------------------------------------------|------------------------------------------------------------------------------|
| `<name>`             | Project name                                                   | Required in non-interactive mode                                             |
| `--template`         | Template ID, such as `application` or `application-kotlin`     | First available project template                                             |
| `--jmix-version`     | Jmix platform version                                          | Latest stable version                                                        |
| `--package`          | Base Java package                                              | `com.company.<project-name>`                                                 |
| `--project-id`       | Prefix for entity, table, and bean names; maximum 7 characters | Template default                                                             |
| `--theme`            | UI theme: `aura` or `lumo`                                     | Depends on Jmix version and template                                         |
| `--locales`          | Comma-separated locale codes                                   | `en`                                                                         |
| `--path`             | Target directory                                               | `./<project-name>`; the wizard also offers `~/IdeaProjects` or a custom path |
| `--repository`       | Maven repository containing Jmix templates                     | Jmix public repository                                                       |
| `--no-git`           | Do not initialize a Git repository                             | Git initialization enabled                                                   |
| `--include-unstable` | Include RC and snapshot versions in version selection          | Disabled                                                                     |
| `--force`            | Generate into a non-empty directory without confirmation       | Disabled                                                                     |
| `--non-interactive`  | Do not prompt; use arguments, options, and defaults            | Disabled                                                                     |

`jmix new --help` shows the authoritative reference.

### Updates and cleanup

Installed builds check for a new release once a day at startup and install it;
the update takes effect on the next run. Superseded versions and unused
template caches are removed automatically.

```shell
jmix update    # update immediately and remove old versions
```

### Templates and offline use

Templates come from the `io.jmix.templates.studio:jmix-studio-templates` Maven
artifact — the Jmix public repository by default, its backup at
`nexus.jmix.io`, or a custom repository chosen in the wizard or via
`--repository`. Downloads are cached per
repository under `~/.jmix/templates/` and work offline afterwards; snapshots
are refreshed whenever the repository is reachable.

## Development

Building from source needs JDK 17+ to launch the Gradle wrapper; the build
provisions JDK 25 itself.

### Run from source

```shell
git clone https://github.com/jmix-framework/jmix-cli.git
cd jmix-cli
./run.sh
```

`run.sh` is a macOS/Linux shortcut for `./gradlew run --console=plain`
(Windows: `gradlew.bat`). Arguments pass through either launcher:

```shell
./run.sh new jmix-project --no-git
./gradlew run --args="new jmix-project --no-git" --console=plain
```

### Build and test

Standard checks:

```shell
./gradlew build
```

Integration tests against the real template repository:

```shell
JMIX_CLI_IT=true ./gradlew test
```

### Build distributions

- `./gradlew installDist` — local distribution under `build/install/jmix-cli/`.
- `./gradlew releaseBundle` — self-contained platform archive with SHA-256
  checksum under `build/release/`.

See [docs/DISTRIBUTION.md](docs/DISTRIBUTION.md) for supported platforms and
the release process.

## Contributing

Issues and pull requests are welcome. Before opening a pull request:

1. Add or update tests for changed behavior.
2. Run the checks above; include the integration test for changes to template discovery, rendering, bindings, or generation.
3. Update this README for user-visible changes.
4. Keep generated output compatible with Jmix Studio templates.

See [AGENTS.md](AGENTS.md) for architecture, invariants, and development
guidance.

## License

Jmix CLI is available under the [Apache License 2.0](LICENSE).
