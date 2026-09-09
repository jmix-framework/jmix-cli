# Jmix CLI contributor guide

Jmix CLI is a Kotlin/JVM project generator using the same templates as Jmix
Studio. Start with [README.md](README.md) for usage and check the current code
and tests before changing behavior.

## Feature guides

Read the guide relevant to the change; update it when behavior changes.

| Feature                                                                   | Covers                                                               |
|---------------------------------------------------------------------------|----------------------------------------------------------------------|
| [Project generation](docs/features/project-generation.md)                 | Templates, repository cache, rendering, locales, Git                 |
| [Wizard](docs/features/wizard.md)                                         | Steps, navigation, terminal fallbacks, demo recording                |
| [Add-ons](docs/features/add-ons.md)                                       | Studio catalog, compatibility, selection, translations, installation |
| [Environment and Agent Toolkit](docs/features/environment-and-toolkit.md) | Development JDKs, project launching, AI agent setup                  |
| [Updates](docs/features/updates.md)                                       | Startup checks, self-update, cache cleanup                           |

Production code is in [src/main/kotlin/io/jmix/cli](src/main/kotlin/io/jmix/cli);
tests mirror it under [src/test/kotlin/io/jmix/cli](src/test/kotlin/io/jmix/cli).
[Main.kt](src/main/kotlin/io/jmix/cli/Main.kt) registers commands;
[NewCommand.kt](src/main/kotlin/io/jmix/cli/NewCommand.kt) coordinates generation.

## Working rules

- Preserve Studio-compatible templates, bindings, and generated projects.
- Keep wizard choices available through options or deterministic defaults.
  Support both raw terminals and numbered/line-input consoles.
- Keep caches isolated by source URL and validate downloads before using them.
- Use English and the existing Kotlin style. Prefer existing helpers and focused
  changes. Put dependency versions in [gradle/libs.versions.toml](gradle/libs.versions.toml);
  settings plugins are the exception.
- Keep expected failures concise and actionable, without Java stack traces.
- Update tests and the relevant feature guide for behavior changes; update
  README.md when usage changes.
- Preserve unrelated working-tree and staged changes. Keep generated projects,
  build output, IDE metadata, and local caches out of commits.
- Do not commit, push, rewrite history, publish artifacts, or create release tags
  unless requested. Never create release tags to test distribution.
  Pushing release-worthy commits to `main` can publish a release automatically;
  see [Distribution](docs/DISTRIBUTION.md).

## Verification

JDK 17+ launches Gradle; the build provisions its JDK 25 toolchain.

Run focused tests while developing, then the standard build for implementation
changes:

```shell
./gradlew test --tests 'io.jmix.cli.generator.ProjectGeneratorTest'
./gradlew build
```

Additional checks depend on the change:

| Change                                 | Required checks                                                                                   |
|----------------------------------------|---------------------------------------------------------------------------------------------------|
| Commands/options                       | `jmix --help`, `jmix new --help`, and non-interactive use                                         |
| Templates, repository access, or generation | `JMIX_CLI_IT=true ./gradlew test` |
| Generation | Also generate and build a project as described in the feature guide |
| Wizard                                 | Real terminal and numbered/line-input fallback, including back and quit                           |
| Distribution/installers                | `./gradlew releaseBundle`, then `tests/test-install.sh` or `tests/test-install.ps1` on Windows    |
| Documentation/demo                     | Check links and examples; validate and visually inspect the recording                             |

[CI](.github/workflows/ci.yml) runs on Linux, macOS, and Windows and builds a
generated project on Linux. Inspect the final diff and report what passed,
failed, or could not be checked.
