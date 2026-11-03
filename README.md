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

The installer opens the command menu. The CLI bundles its own Java runtime;
building generated projects needs a compatible JDK, which the wizard can help install.
On macOS and Linux, restart the shell after installation or run
`export PATH="$HOME/.local/bin:$PATH"` before invoking `jmix` in the current shell.

## Create a project

```shell
jmix
```

Select a command from the menu: **new** (create a project), **trial** (download the
Enterprise+BPM trial project), **login** or **logout** (depending on the saved
session), and **update**. Use arrow keys and Enter, or enter a number in a text
console. Press `q` to quit. To open the project wizard directly, run `jmix new`.
The welcome screen groups the saved account at the top right: region in the header,
name below it, and email on a third line when available. Signed-out and incomplete
profiles use two lines. After signing in or out from the menu, you return
to the menu with the updated account. Account details are cached locally, so it
works offline. The logo has a short, one-time reveal in interactive color terminals;
`NO_COLOR` also disables this animation.
The account name is bold green, email is muted, and region is cyan; signed-out
status is yellow. In the project wizard, Esc on the first editable step returns to
the command menu (`<` in text consoles). Returning to the menu discards the draft;
starting the wizard again begins a new one.

Choose a template, languages, add-ons, and project location. Follow the keyboard
hints at the bottom of each step. In text input, use **Ctrl+Q** to quit.

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

Commercial add-ons, marked with `[$]` in the wizard, require a
[Jmix subscription](https://docs.jmix.io/jmix/studio/subscription.html#enterprise-subscription).
Selecting one, such as `--addons business-calendars`, automatically adds the premium
Jmix repository. If credentials are not configured, run `jmix login`: the CLI uses
your account's commercial license key and saves premium credentials in your Gradle
user home, as Studio does. Conflicts offer a choice of keeping, replacing or saving
credentials separately; `--non-interactive` leaves conflicting settings untouched.
Successful saves explicitly name the properties file. You can also configure
`~/.gradle/gradle.properties` manually: use the part of your license key before the
dash for `premiumRepoUser`, and the part after the dash for `premiumRepoPass`.
For scripts and CI, these values can also be
supplied through the `ORG_GRADLE_PROJECT_premiumRepoUser` and
`ORG_GRADLE_PROJECT_premiumRepoPass` environment variables.
See [commercial add-on setup and troubleshooting](docs/features/add-ons.md#commercial-add-ons).

Every run ends by printing the `jmix new ... --non-interactive` command that
recreates the project with the same settings, ready to paste into a script.

Generated projects include [Jmix Agent Toolkit](https://github.com/jmix-framework/jmix-agent-toolkit)
guidelines and skills for supported AI coding assistants. The wizard's setup
checklist can leave it out, as can `--no-agents-toolkit`.
Skills live in `.agents/skills`, with links in `.claude/skills` for Claude Code.
A new `CLAUDE.md` imports `AGENTS.md` so the guidelines are shared.

## Jmix account and trial

Sign in to your Jmix account to download the Enterprise+BPM trial project: demo
applications that use the commercial add-ons and need no license key.

```shell
jmix login                 # sign in through your browser
jmix trial                 # choose where to download the trial project
jmix trial --path ~/trial  # or into another directory
jmix logout                # sign out on this computer
```

`jmix login [--region global|ru]` opens the Jmix sign-in page in your default browser and
prints its address in case the browser does not open. Finish within 10 minutes. Use
`--region ru` for an account on jmix.ru. Sign-in needs a browser on the same computer, so it
does not work over SSH yet. The session is saved in `~/.jmix/account/session.json` (readable
only by you on macOS and Linux), and the next sign-in replaces it. `jmix logout` deletes the file.

`jmix trial [--path <directory>] [--start-trial]` downloads the trial of your account.
The wizard offers the same location picker as project creation, including a custom path
with completion. `--path` skips that step; non-interactive runs use the current directory
by default. The archive's own project folder is preserved inside the selected directory.
At a terminal without a session it
signs you in first, in the global region; for jmix.ru run `jmix login --region ru` beforehand.
A trial that is already running, including one you started on the website, is downloaded as it
is. When there is none, the command asks before it starts one, and `--start-trial` answers yes.
With `--non-interactive` it never asks and never opens a browser, so scripts need `jmix login`
and `--start-trial` up front.

The project is a Gradle composite with the demos under `demo/` and the trial add-ons in
`trial_repository`; leave that layout as it is. The command prints the trial end date and
next steps with a general module run command, one real example and its URL.
Its `What's next?` checklist
can open the whole project in the detected IDE and run a selected demo module, using the
same JDK setup as project creation. These actions are skipped in non-interactive mode.
The demos stop starting when the
trial ends. If one stops earlier, download a fresh copy into another directory with `--path`.
See [account and trial](docs/features/account-and-trial.md) for the details and limits, and the
[Jmix guide](https://docs.jmix.io/jmix/account-management.html#enterprise-trial) for the trial itself.

## Options and updates

```shell
jmix new --help          # all project options
jmix update              # update the installed CLI
jmix --no-update new     # skip the startup update check
```

Project options apply to `jmix new [name]`. The name is required with
`--non-interactive`; otherwise the wizard asks for it. Defaults below describe
non-interactive generation; the wizard lets you choose values interactively.

| Option                     | Description                                                                                                                        | Default                                                                     |
|----------------------------|------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| `--template <id>`          | Project template, e.g. `application` or `application-kotlin`.                                                                      | First template in the selected catalog                                      |
| `--jmix-version <version>` | Jmix platform version.                                                                                                             | Latest stable version                                                       |
| `--package <name>`         | Base Java/Kotlin package.                                                                                                          | Derived from the template prefix and project name                           |
| `--project-id <id>`        | Prefix for entity, table and bean names; up to 7 characters.                                                                       | Template default; required if the template demands it                       |
| `--theme <name>`           | UI theme: `aura` or `lumo`, where supported by the template and Jmix version.                                                      | `aura` for Jmix 3; `lumo` for Jmix 2; omitted for templates without a theme |
| `--locales <codes>`        | Comma-separated locale codes, e.g. `en,ru`. The first is the default locale.                                                       | `en`                                                                        |
| `--addons <ids>`           | Comma-separated compatible add-on IDs, e.g. `quartz,bpm`. Commercial add-ons use configured credentials or the signed-in account's license. | No additional add-ons                                                       |
| `--path <directory>`       | Target directory.                                                                                                                  | `./<name>`                                                                  |
| `--repository <url>`       | Maven repository for templates and the generated project.                                                                          | `https://global.repo.jmix.io/repository/public`                             |
| `--no-git`                 | Skip Git initialization and staging of generated files.                                                                            | Git enabled when available                                                  |
| `--no-agents-toolkit`      | Skip Agent Toolkit guidelines and local skills.                                                                                    | Toolkit enabled                                                             |
| `--include-unstable`       | Include RC, beta and snapshot versions in version selection.                                                                       | Stable versions only                                                        |
| `--force`                  | Allow generation into a non-empty directory without confirmation; existing files may be overwritten.                               | Confirmation required in the wizard; rejected in non-interactive mode       |
| `--non-interactive`        | Use options and defaults without prompting.                                                                                        | Interactive wizard                                                          |
| `--no-update`              | Skip the automatic startup update check. Accepted by every command; explicit updates still run.                                    | Automatic checks enabled for installed builds outside CI                    |
| `-h`, `--help`             | Show help and exit. Available for every command.                                                                                   | —                                                                           |

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
