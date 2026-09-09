# Updates

Installed CLI builds check for updates at startup, at most once every ten
minutes. A successful update restarts the requested command using the new image.
Source builds do not self-update. `jmix update` requests an immediate update.

## Behavior to preserve

- Skip startup updates with `--no-update`, `JMIX_CLI_NO_AUTO_UPDATE=1`, or a
  non-empty `CI` environment variable.
- Failed automatic checks report the problem and continue with the current
  version. Diagnostics go to stderr so piped stdout remains usable.
- Verify archive checksums and extraction paths, serialize updates, and switch
  the launcher only to a complete installation. Preserve arguments and terminal
  input when restarting into an updated image.
- Clean superseded CLI versions and unused template caches without deleting the
  active installation or artifacts still in use. Keep custom-repository caches
  isolated during cleanup as well as during download.
- Release archives are self-contained. Distribution layout, installer commands,
  release triggers, and platform checks are documented in
  [Distribution](../DISTRIBUTION.md).

## Code and verification

- [Main.kt](../../src/main/kotlin/io/jmix/cli/Main.kt) and
  [UpdateCommand.kt](../../src/main/kotlin/io/jmix/cli/UpdateCommand.kt) — startup and explicit updates.
- [update](../../src/main/kotlin/io/jmix/cli/update) — installation discovery, updates, and cleanup.

Run `./gradlew test --tests 'io.jmix.cli.update.*'`. For image or installer changes,
follow the local distribution checks. Use local test assets and scratch install
directories; do not publish releases or create release tags during verification.
