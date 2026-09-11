# Jmix CLI

[![CI](https://github.com/jmix-framework/jmix-cli/actions/workflows/ci.yml/badge.svg)](https://github.com/jmix-framework/jmix-cli/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Create [Jmix](https://www.jmix.io/) projects with an interactive wizard or from
scripts and AI agents, using the same project templates as Jmix Studio.

<p align="center">
  <img src="docs/demo.gif" alt="Jmix CLI welcome, add-on selection, and project creation" width="800">
</p>

## Install

**macOS / Linux**

```shell
curl -fsSL https://github.com/jmix-framework/jmix-cli/releases/latest/download/install.sh | bash
```

**Windows (PowerShell)**

```powershell
irm https://github.com/jmix-framework/jmix-cli/releases/latest/download/install.ps1 | iex
```

The installer starts the wizard. The CLI bundles its own Java runtime;
building generated projects needs a compatible JDK, which the wizard can help install.

## Create a project

```shell
jmix
```

Choose a template, languages, add-ons, and project location. Follow the keyboard
hints at the bottom of each step. In text input, use **Ctrl+Q** to quit.

The progress bar tracks General, Localization, Add-ons, Location and setup, and Finishing up.
Downloads and other slow steps show a spinner naming the host being reached and, when the
size is known, a bar with byte counts.

The add-on picker groups compatible **free add-ons** by purpose. Press **/** to search,
**Space** to toggle, and **Enter** to confirm. Add-ons supplied by the template
are omitted; matching translations are preselected and can be unchecked.
In line-input consoles, use `/query` and comma-separated selection numbers.

For scripts and AI agents, pass `--non-interactive`:

```shell
jmix new demo --non-interactive \
    --template application \
    --locales en,de \
    --addons quartz,german-translation

cd demo
./gradlew bootRun
```

Non-interactive mode creates `./<name>` and installs no additional add-ons unless
`--addons` is supplied. Use `--path` for another location, `--no-git` to skip
Git initialization, and `--no-agents-toolkit` to skip the Agent Toolkit.
On Windows, run `gradlew.bat bootRun`.

Every run ends by printing the `jmix new ... --non-interactive` command that
recreates the project with the same settings, ready to paste into a script.

Generated projects include [Jmix Agent Toolkit](https://github.com/jmix-framework/jmix-agent-toolkit)
guidelines and skills for supported AI coding assistants. The wizard's setup
checklist can leave it out, as can `--no-agents-toolkit`.

## Options and updates

```shell
jmix new --help          # all project options
jmix update              # update the installed CLI
jmix --no-update new     # skip the startup update check
```

Project options apply to `jmix new [name]`. The name is required with
`--non-interactive`; otherwise the wizard asks for it. Defaults below describe
non-interactive generation; the wizard lets you choose values interactively.

| Option | Description | Default |
| --- | --- | --- |
| `--template <id>` | Project template, e.g. `application` or `application-kotlin`. | First template in the selected catalog |
| `--jmix-version <version>` | Jmix platform version. | Latest stable version |
| `--package <name>` | Base Java/Kotlin package. | Derived from the template prefix and project name |
| `--project-id <id>` | Prefix for entity, table and bean names; up to 7 characters. | Template default; required if the template demands it |
| `--theme <name>` | UI theme: `aura` or `lumo`, where supported by the template and Jmix version. | `aura` for Jmix 3; `lumo` for Jmix 2; omitted for templates without a theme |
| `--locales <codes>` | Comma-separated locale codes, e.g. `en,ru`. The first is the default locale. | `en` |
| `--addons <ids>` | Comma-separated compatible free add-on IDs, e.g. `quartz,reports`. | No additional add-ons |
| `--path <directory>` | Target directory. | `./<name>` |
| `--repository <url>` | Maven repository for templates and the generated project. | `https://global.repo.jmix.io/repository/public` |
| `--no-git` | Skip Git initialization and staging of generated files. | Git enabled when available |
| `--no-agents-toolkit` | Skip Agent Toolkit guidelines and local skills. | Toolkit enabled |
| `--include-unstable` | Include RC, beta and snapshot versions in version selection. | Stable versions only |
| `--force` | Allow generation into a non-empty directory without confirmation; existing files may be overwritten. | Confirmation required in the wizard; rejected in non-interactive mode |
| `--non-interactive` | Use options and defaults without prompting. | Interactive wizard |
| `--no-update` | Skip the automatic startup update check. Also accepted by `jmix` and `jmix update`; explicit updates still run. | Automatic checks enabled for installed builds outside CI |
| `-h`, `--help` | Show help and exit. Available for `jmix`, `jmix new` and `jmix update`. | — |

Installed builds check for updates automatically. Set `JMIX_CLI_NO_AUTO_UPDATE=1`
to disable checks; they are also skipped when `CI` is set or running from source.

Templates and the add-on catalog are cached under `~/.jmix/`. Offline use requires
cached templates and, when installing add-ons, the relevant Gradle dependencies.
Use `--repository` to select a custom template repository.

## Development

JDK 17+ is needed to launch Gradle; the build provisions its JDK 25 toolchain.

```shell
git clone https://github.com/jmix-framework/jmix-cli.git
cd jmix-cli
./run.sh                          # launch from source on macOS/Linux
./gradlew build                   # build and test
JMIX_CLI_IT=true ./gradlew test   # network-backed integration tests
```

- [Contributor guide](AGENTS.md) — conventions, feature docs, and verification.
- [Distribution guide](docs/DISTRIBUTION.md) — platform bundles and releases.
- [Demo recording](docs/demo.tape) — reproduce the README GIF with VHS.

## License

[Apache License 2.0](LICENSE).
