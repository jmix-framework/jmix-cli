# AGENTS.md

This file contains repository-specific guidance for coding agents and
contributors working on Jmix CLI.

## Project mission

Jmix CLI creates Jmix projects from the command line through `jmix new`. It
supports both a keyboard-driven wizard and deterministic non-interactive use in
scripts and CI.

Project generation should remain compatible with the templates and behavior
used by Jmix Studio.

## Read first

- [README.md](README.md) is the public source of truth for supported commands,
  requirements, and contributor-facing workflows.
- `src/main/kotlin/io/jmix/cli/` contains the production code.
- `src/test/kotlin/io/jmix/cli/` contains unit and integration coverage.
- `.github/workflows/ci.yml` defines the required cross-platform checks.

Verify behavior against the current code and tests. Do not rely on historical
implementation notes when they disagree with the implementation.

## Architecture

| Area                | Location                                                  | Responsibility                                                                        |
|---------------------|-----------------------------------------------------------|---------------------------------------------------------------------------------------|
| CLI entry point     | `Main.kt`                                                 | Root command and `new` subcommand registration                                        |
| Dependency catalog  | `gradle/libs.versions.toml`                               | Plugin and library coordinates and versions                                           |
| Command workflow    | `NewCommand.kt`                                           | Options, wizard state, environment checks, and generation orchestration               |
| Wizard UI           | `wizard/Prompts.kt`                                       | Text input, selection lists, navigation, and terminal fallbacks                       |
| Validation          | `wizard/Validation.kt`                                    | Project name, package, project ID, and derived-name rules                             |
| Template repository | `repo/TemplateRepository.kt`                              | Maven metadata, downloads, caching, and offline fallback                              |
| Template catalog    | `template/`                                               | Template metadata and artifact discovery                                              |
| Project generation  | `generator/`                                              | Studio-compatible bindings, Groovy rendering, file generation, and Git initialization |
| Environment         | `env/`                                                    | JDK and Git discovery and Jmix/JDK compatibility checks                               |
| Version logic       | `util/JmixVersions.kt`                                    | Jmix version comparison and picker grouping                                           |
| Distribution        | `install.*`, `buildSrc/`, `.github/workflows/release.yml` | Self-contained images, installers, checksums, and release publishing                  |

## Core invariants

- Keep the implementation on Kotlin/JVM. Project templates use Groovy template
  engines, including scriptlets, and both file paths and file contents are
  rendered.
- Use the existing
  `io.jmix.templates.studio:jmix-studio-templates` artifact. Do not introduce a
  second template format or maintain a forked built-in template set.
- Preserve Studio-compatible generation behavior: `.globals` evaluation,
  binding names, binary-file handling, localized message generation, Gradle
  wrapper permissions and line endings, and optional `git init`.
- Keep every wizard choice usable in non-interactive mode through an argument,
  option, or deterministic default.
- Preserve both terminal paths: enhanced raw-terminal interaction and the
  numbered/line-input fallback used by IDE consoles and pipes.
- Keep template caches isolated by repository URL. A custom repository must
  never receive metadata or artifacts cached for another repository.
- Preserve offline fallback without accepting corrupt or partial downloads.
- Validate the selected Jmix version against the supported JDK versions before
  generation. Generated projects use their bundled Gradle wrapper.
- Keep release archives self-contained. The bundled runtime starts the CLI;
  environment detection must continue to report JDKs available to the generated
  project rather than treating that private runtime as a development JDK.
- User-facing failures should be concise and actionable; avoid exposing Java
  stack traces for expected network, validation, or template errors.

## Build and verification

The Gradle build provisions its JDK 25 toolchain automatically.

Run the standard checks:

```shell
./gradlew build
```

Run a focused test while developing:

```shell
./gradlew test --tests 'io.jmix.cli.generator.ProjectGeneratorTest'
```

Run the real-template integration test for changes to repository access,
template metadata, rendering, bindings, or generation:

```shell
JMIX_CLI_IT=true ./gradlew test
```

For generator changes, also create a project and build it:

```shell
SMOKE_DIR="$(mktemp -d)"
./run.sh new smoke-app --non-interactive --path "$SMOKE_DIR/smoke-app" --no-git
cd "$SMOKE_DIR/smoke-app"
./gradlew build -x test
```

For wizard UI changes, exercise both:

1. A real terminal with arrow keys, Space, Enter, Esc, and backward navigation.
2. A console without raw terminal support to verify numbered and line-input fallbacks.

For distribution changes, build and exercise the installer for the current
platform:

```shell
./gradlew releaseBundle
tests/test-install.sh
```

Use `tests/test-install.ps1` on Windows.

## Change guidelines

- Use English for code, comments, documentation, tests, and commit messages.
- Follow the existing Kotlin style and package structure.
- Declare project plugin and library versions in `gradle/libs.versions.toml`.
  Settings plugins are the Gradle-supported exception because catalog aliases
  are unavailable in settings scripts.
- Prefer focused changes and existing abstractions over parallel implementations.
- Add or update tests for behavior changes and edge cases.
- Keep public documentation synchronized with commands and defaults.
- Preserve unrelated working-tree and staged changes.
- Do not commit generated projects, build output, IDE metadata, or local caches.
- Do not commit, push, rewrite history, or publish artifacts unless explicitly requested.
- Do not create or push release tags while testing the distribution workflow.
- Pushing to `main` publishes a release automatically when it contains new
  `feat`/`fix`/`perf` commits (see docs/DISTRIBUTION.md) — keep commit types
  accurate.

## Completion checklist

Before handing off a change:

1. Inspect the final diff for accidental or unrelated edits.
2. Run the smallest relevant tests, then `./gradlew build` for implementation changes.
3. Run the network-backed integration test when template behavior is affected.
4. Confirm `jmix --help` and `jmix new --help` after command-line changes.
5. Update README.md and tests when user-visible behavior changes.
6. Report what was verified and any environment-dependent checks not run.
