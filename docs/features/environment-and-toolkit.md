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
- Install Jmix Agent Toolkit from the branch matching the Jmix major version
  unless the user opts out (`--no-agents-toolkit` or the wizard's setup checklist).
  Shared guidelines go into `AGENTS.md`, and project-local skills are real folders
  in `.agents/skills`, read by Codex, OpenCode, Junie and other agents. Claude Code
  is enabled by default: `.claude/skills` links each skill to the shared store,
  and a new `CLAUDE.md` imports `@AGENTS.md`.
- JDK and toolkit installation report their phases (lookup, download with byte
  counts, checksum, unpacking; installer download, skills, guidelines) through
  `onStatus`/`onProgress` callbacks so the CLI can show a progress indicator.
- Toolkit setup runs its downloaded installer with fixed argument lists. Keep
  setup local to the generated project; global MCP/browser configuration belongs
  to the toolkit's own wizard. Report installer failures as warnings with a retry link.

## Code

- [EnvironmentCheck.kt](../../src/main/kotlin/io/jmix/cli/env/EnvironmentCheck.kt),
  [JdkDetector.kt](../../src/main/kotlin/io/jmix/cli/env/JdkDetector.kt), and
  [JdkVersions.kt](../../src/main/kotlin/io/jmix/cli/env/JdkVersions.kt) — detection and compatibility.
- [JdkInstaller.kt](../../src/main/kotlin/io/jmix/cli/env/JdkInstaller.kt) — managed JDK installation.
- [ProjectLauncher.kt](../../src/main/kotlin/io/jmix/cli/env/ProjectLauncher.kt) — IDE, file manager, and Gradle commands.
- [AgentToolkitInstaller.kt](../../src/main/kotlin/io/jmix/cli/env/AgentToolkitInstaller.kt) — toolkit setup.

## Verification

Run `./gradlew test --tests 'io.jmix.cli.env.*'`. Network-backed JDK and toolkit
integration tests require `JMIX_CLI_IT=true`. For detection changes, also run a
self-contained CLI image so its bundled runtime cannot mask missing JDKs.
For toolkit changes, inspect generated guideline and skill locations, including
Junie's shared files, and cover Windows command construction.
