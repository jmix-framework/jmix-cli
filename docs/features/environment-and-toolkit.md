# Environment and Agent Toolkit

The CLI's bundled Java runtime starts the CLI. Building or running generated
projects requires a separate development JDK compatible with the chosen Jmix
version. Do not report the private bundled runtime as an installed development JDK.

## Behavior

- Discover JDKs from the environment and known installation locations, including
  CLI-managed installations. Keep version compatibility rules in `JdkVersions`.
  Resolve launcher shims (including macOS `/usr/bin/java`) to the actual JDK home
  reported by Java before setting `JAVA_HOME`. Java probes have a bounded timeout.
- Plain project generation warns when no compatible JDK is found. Installing
  additional add-ons requires one and fails before generation if it is missing.
- The interactive follow-up can install a compatible Temurin JDK. Downloads must
  be verified before installation; failures leave actionable installation hints.
- Launch generated projects through their Gradle wrapper with a compatible JDK.
  Offer the detected IDE and application run as separate choices. The file
  manager is the fallback when the IDE is not selected or cannot open the project.
  Non-interactive generation does not offer these actions.
- Trial projects use the same follow-up and JDK setup. The IDE opens the composite
  root; Gradle and browser opening use the selected `demo/<module>` directory and
  its own port. Non-interactive trial downloads do not open or run projects.
- Install Jmix Agent Toolkit from the branch matching the Jmix major version
  unless the user opts out (`--no-agents-toolkit` or the wizard's setup checklist).
  `jmix new` runs the skills, guidelines, and MCP steps with every MCP server
  and AI agent the toolkit offers, as `jmix ai-toolkit all` does. A missing
  `v<major>` branch fails the setup; there is no fallback to the default branch.
- Single source of truth: keep no copy of the toolkit's reference data in code,
  help texts, or guides. That covers server and agent ids, labels and defaults,
  per-agent config files, skill names, the skills folder, and the guidelines
  text. Read it at run time from the project's toolkit branch:
  - MCP servers and AI agents: `.studio/studio-meta-data.json`, step `mcp`,
    inputs `mcpServers` and `mcpAgents`.
  - Skill names, the project skills folder, and the content hash:
    `.studio/skills-manifest.json` (`skills`, `store.local`, `sha256`).
  - The current guidelines block: `content/guidelines-block.md`.
  - The paths a run changed: the installer's `@@JMIX_CHANGE@@` lines.
  - What to do after a run: the installer's own notes.

  The [toolkit's README](https://github.com/jmix-framework/jmix-agent-toolkit)
  describes what it installs; `jmix ai-toolkit --help` lists the current servers
  and agents.
- Only the installer's calling contract stays in code: the script names, the
  `skills`, `agents-md`, and `mcp` subcommands with their flags, the three file
  paths above, and the JSON keys read from them. Apply the same rule to any other
  third-party tool the CLI drives.
- Two guards must work without the network and keep their own toolkit knowledge:
  the CLI isolates Claude Desktop's user profile from the MCP step, and checks
  the project paths of the selected steps before the first one runs. A path the
  skills or guidelines step writes must not be a link that leads outside the
  project; an MCP config path must not be a link at all.
- Toolkit files are external input. Check ids and paths before they reach a path
  or an argument list, and remove control characters from any toolkit text that
  is printed.
- JDK and toolkit installation report their phases (lookup, download with byte
  counts, checksum, unpacking; installer download, metadata, skills, guidelines,
  MCP) through `onStatus`/`onProgress` callbacks so the CLI can show a progress
  indicator.
- Toolkit setup runs its downloaded installer as a file with a complete argument
  list, never through a shell string, so the installer never prompts. Steps run
  in the project, so the shell (`bash`, or Windows PowerShell) is started by its
  absolute path, and each step gets a PATH without empty or relative entries (a
  leading `~` becomes the user's home). Keep setup
  local to the project: never pass the global scope, a flag that turns an agent
  off, or a server or agent the toolkit metadata does not offer. `jmix new`
  reports installer failures as warnings that name `jmix ai-toolkit`.

## Toolkit command

`jmix ai-toolkit` adds or updates the toolkit in an existing project:

```text
jmix ai-toolkit [all|skills|guidelines|mcp]
                [--path DIR] [--jmix-version VERSION]
                [--servers CSV] [--agents CSV] [--non-interactive]
```

- One action runs per call; the command prints the result and exits. Without an
  action the interactive command opens the action menu, and `--non-interactive`
  runs `all`. See the [wizard guide](wizard.md) for the menu, Back, and the
  line-input fallback.
- Project: a directory with `build.gradle`, `build.gradle.kts`,
  `settings.gradle`, or `settings.gradle.kts`. `--path` names it; otherwise a
  scripted run uses the current directory, and the interactive command first
  asks which project to use. The list offers the current directory when it is a
  Gradle project, then the Jmix projects one level below it and in
  `~/IdeaProjects` (by name, hidden folders aside), then `Other...` for a typed
  directory with Tab completion. Started in the home directory, the list does
  not look into the home directory's own folders: on macOS that asks for access
  to Desktop, Documents, and Downloads. A scripted run, and any run whose explicit
  `--path` is not a project, fails
  with ``No Gradle project found in <dir>. Run `jmix ai-toolkit` from the project root or pass --path.``
- Jmix version: `--jmix-version`, else the first literal `bomVersion` in the root
  or `demo/*` build files, else the first `io.jmix` Gradle plugin version in
  the same files. A value that does not look like a version is
  skipped; a value without a numeric major counts as not detected.
  When none is found the interactive command asks for it; a
  scripted run fails with `Cannot detect the Jmix version in <dir>. Pass --jmix-version.`
- The toolkit branch is `v<major>`. A missing branch fails with
  `Agent Toolkit setup failed: the toolkit has no v<major> branch for Jmix <major> yet`.
- Choices: the MCP servers and AI agents come from the metadata of the project's
  branch, in the toolkit's order. A choice is offered when the toolkit enables it
  by default (`default: true`). A server is not offered when the `mcp` step has a
  text input for it: an input of type `userInput` whose id starts with the
  server's id, compared without case and without the characters of the server's
  id that are not letters or digits. Every offered choice is selected by default.
- A choice is ignored unless its id starts with a letter or digit and continues
  with letters, digits, `.`, `_`, or `-`. Metadata without the `mcp` step, without
  one of the two lists, or with a list that ends up empty is an error:
  `Unexpected Agent Toolkit metadata format.`
- `--servers` and `--agents` take offered ids, comma-separated and without case,
  and default to all of them. An unknown id fails and names the valid values; an
  empty list fails too. Both apply to `mcp` and `all` only; with `skills` or
  `guidelines` they fail. `all` never asks the MCP questions. `mcp` asks for each
  list that its option does not decide.
- Steps, in this order for `all`:

  | Action       | Toolkit call                               | Writes                                    |
  |--------------|--------------------------------------------|-------------------------------------------|
  | `skills`     | `skills --scope local`                     | The project skills folder and its links   |
  | `guidelines` | `agents-md`                                | The Jmix block in `AGENTS.md`             |
  | `mcp`        | `mcp --servers … --agents … --scope local` | The project config of each selected agent |

  The toolkit decides the files of each step, including the ones it adds for
  single agents and the backups of files it changes.
- The link guard and the metadata are checked before the first step runs, so a
  run never stops halfway for them. Every toolkit failure of a run (download,
  metadata, a step) is one line that starts with `Agent Toolkit setup failed:`.
  The first failed step stops the run: its line names the step and ends with
  the installer's error lines, or with the tail of its output when it printed
  none.
- Changes come from markers, not from log text: steps run with
  `JMIX_EMIT_CHANGE_MARKERS=1`, and each `@@JMIX_CHANGE@@` line gives an action
  and a path. Keep the first action per path, and print paths inside the project
  relative to it. The result is a green headline and one row per path with the
  toolkit's action word; an action this CLI version does not know is listed
  without color. Without changes the headline says `already up to date`.
- Notes: `Notes from the Agent Toolkit:` lists the installer's own guidance, one
  bullet each, whether or not files changed. Select notes by position and by
  the `Note:` prefix, never by wording: the lines marked `Note:` in every step,
  and in the MCP step also every line that starts at column 0 and is neither a
  `[…]` heading nor a marker. The CLI writes no guidance of its own. Notes are
  written for the toolkit's installer and can name its flags.
- Status hints: the interactive action menu appends `not installed`,
  `up to date`, or `update available` to `skills` and `guidelines`. Each hint
  needs one request with a 3 second limit and is omitted without an answer.
  The status is detected once for the project of a run: Back to the menu does
  not ask again.
  Skills compare the hash of the manifest's skills in its `store.local` folder
  with `sha256`. Guidelines compare `AGENTS.md` with the published block: it
  contains the whole block, else it has a line equal to the block's first line.
- Limits of the hints: `mcp` has none, because the toolkit publishes no list of
  the files it writes per agent. A checkout with converted line endings (Git
  `autocrlf`) hashes differently, so its skills hint stays `update available`.
  Scripted runs and runs with an action argument show no hints.
- Help: `jmix ai-toolkit --help` ends with the offered server and agent ids,
  loaded with a 3 second limit from the branch of the Jmix version detected in
  the current directory (`--path` is not used), or from the toolkit's default
  branch when none is detected. Without an answer it ends
  with `The current MCP servers and AI agents are listed when the Agent Toolkit is reachable.`
  Only printed help makes the request, a usage error does not, and option help
  texts name no values.
- Expected failures are one line without a stack trace or usage text: raise
  them as `CliktError`, not `UsageError`.
- Updating replaces the toolkit's skill folders, so local edits to them are
  lost. The command sets up project files only; the toolkit installer and Jmix
  Studio cover user-level setup and the choices the command does not offer.

## Code

- [EnvironmentCheck.kt](../../src/main/kotlin/io/jmix/cli/env/EnvironmentCheck.kt),
  [JdkDetector.kt](../../src/main/kotlin/io/jmix/cli/env/JdkDetector.kt), and
  [JdkVersions.kt](../../src/main/kotlin/io/jmix/cli/env/JdkVersions.kt) — detection and compatibility.
- [JdkInstaller.kt](../../src/main/kotlin/io/jmix/cli/env/JdkInstaller.kt) — managed JDK installation.
- [ProjectLauncher.kt](../../src/main/kotlin/io/jmix/cli/env/ProjectLauncher.kt) — IDE, file manager, and Gradle commands.
- [ProjectInteraction.kt](../../src/main/kotlin/io/jmix/cli/command/ProjectInteraction.kt) — shared follow-up prompts and JDK setup.
- [AgentToolkitInstaller.kt](../../src/main/kotlin/io/jmix/cli/env/AgentToolkitInstaller.kt) — toolkit steps, change markers, and notes.
- [AgentToolkitMetadata.kt](../../src/main/kotlin/io/jmix/cli/env/AgentToolkitMetadata.kt) — toolkit files and the offered MCP servers and AI agents.
- [AgentToolkitStatus.kt](../../src/main/kotlin/io/jmix/cli/env/AgentToolkitStatus.kt) — skills and guidelines status hints.
- [ToolkitCommand.kt](../../src/main/kotlin/io/jmix/cli/command/ToolkitCommand.kt) — the `ai-toolkit` command.

## Verification

Run `./gradlew test --tests 'io.jmix.cli.env.*'`. Network-backed JDK and toolkit
integration tests require `JMIX_CLI_IT=true`. For detection changes, also run a
self-contained CLI image so its bundled runtime cannot mask missing JDKs.
For toolkit changes, run
`./gradlew test --tests 'io.jmix.cli.env.AgentToolkit*' --tests 'io.jmix.cli.command.ToolkitCommandTest'`
and `JMIX_CLI_IT=true ./gradlew test --tests 'io.jmix.cli.env.AgentToolkitInstallerIntegrationTest'`.
The MCP integration tests read the user's Codex config (`$CODEX_HOME` or
`~/.codex/config.toml`), as the installer does: a global server with the same
name and another transport fails them.
Inspect the generated guidelines, skills, and MCP configs, and cover Windows
command construction. For command changes, also run `jmix ai-toolkit --help`,
a scripted run in a generated project, and the action menu in a raw terminal
and a line-input console, including Back and quit.
