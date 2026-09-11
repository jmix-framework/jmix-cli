# Project generation

`jmix` and `jmix new` start the wizard. `jmix new <name> --non-interactive`
uses options and defaults without prompting. Run `jmix new --help` for the
complete option reference.

## Behavior to preserve

- Use `io.jmix.templates.studio:jmix-studio-templates`; do not maintain a second
  template format or forked templates. Template metadata controls available
  project types, visible parameters, and defaults.
- Resolve stable and unstable Jmix versions through the shared version helpers.
  The wizard offers the primary public repository, its backup, and a custom URL.
  Changing repositories invalidates the current template catalog.
- Cache templates under `~/.jmix/templates/`, isolated by repository URL.
  Refresh snapshots when online; use validated cached artifacts when offline.
  Reject corrupt or partial downloads.
- Evaluate `.globals` before rendering. Preserve Studio binding names and Groovy
  scriptlets in both paths and file contents. Copy excluded binary/static files
  unchanged, generate locale message files, and normalize the Gradle wrapper's
  line endings and executable permissions.
- Non-interactive output defaults to `./<name>`. An existing non-empty target
  requires interactive confirmation or `--force`; a file cannot be a target directory.
- Install selected [add-ons](add-ons.md) before optional `git init` and
  `git add --all`. Generation does not create a commit. `--no-git` skips Git setup.
- Install the [Agent Toolkit](environment-and-toolkit.md) after generation unless
  `--no-agents-toolkit` is passed or the wizard's setup entry is unchecked.
  Its failure produces a warning and does not discard the project.
- Report download progress through the callbacks in
  [Downloads.kt](../../src/main/kotlin/io/jmix/cli/util/Downloads.kt): the
  template jar reports bytes against Content-Length, generation reports its
  phases (rendering, add-on resolution, Git).
- Finish by printing the equivalent `jmix new <name> --non-interactive ...`
  command under `CLI command:` (`nonInteractiveCommand`): the Jmix version is
  pinned, defaults such as
  `./<name>` and the public repository are omitted. Arguments are quoted for
  PowerShell on Windows and POSIX shells on macOS/Linux, including paths with
  spaces or apostrophes.

## Code

- [NewCommand.kt](../../src/main/kotlin/io/jmix/cli/NewCommand.kt) — workflow and options.
- [template](../../src/main/kotlin/io/jmix/cli/template) and
  [TemplateRepository.kt](../../src/main/kotlin/io/jmix/cli/repo/TemplateRepository.kt) — discovery and cache.
- [generator](../../src/main/kotlin/io/jmix/cli/generator) — bindings and rendering.
- [JmixVersions.kt](../../src/main/kotlin/io/jmix/cli/util/JmixVersions.kt) — shared version logic.

## Verification

Run the generator, template, and repository tests relevant to the change, plus
the network-backed integration suite. Also build a generated project:

```shell
JMIX_CLI_IT=true ./gradlew test
SMOKE_DIR="$(mktemp -d)"
./run.sh new smoke-app --non-interactive --path "$SMOKE_DIR/smoke-app" --no-git
(cd "$SMOKE_DIR/smoke-app" && ./gradlew build -x test)
```

Use the generated wrapper, not a system Gradle installation. Include Java,
Kotlin, or add-on templates when the changed behavior affects them.
