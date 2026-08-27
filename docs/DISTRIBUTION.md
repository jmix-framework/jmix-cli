# Distribution

Jmix CLI is distributed as self-contained application images through GitHub
Releases. GitHub Packages is not used because it targets package-manager
registries such as Maven, npm, NuGet, and containers; the CLI needs directly
downloadable platform archives for an unauthenticated bootstrap command.

Each application image contains the CLI, its libraries, and a reduced JDK 25
runtime produced with `jlink` and `jpackage`. Users do not need Java to launch
the wizard. JDK detection inside the wizard concerns the generated Jmix project,
not the CLI runtime.

## Release assets

Every release contains an archive and SHA-256 checksum for each supported
platform:

| Platform       | Archive                              |
|----------------|--------------------------------------|
| Linux x64      | `jmix-cli-linux-x64.tar.gz`          |
| Linux ARM64    | `jmix-cli-linux-arm64.tar.gz`        |
| macOS x64      | `jmix-cli-macos-x64.tar.gz`          |
| macOS ARM64    | `jmix-cli-macos-arm64.tar.gz`        |
| Windows x64    | `jmix-cli-windows-x64.zip`           |

The release also includes `install.sh` and `install.ps1`. README downloads the
installer from the same release as the application archives, keeping the
bootstrap script and archive layout in sync.

The stable asset names allow installers to use GitHub's
`releases/latest/download` URL while the release tag records the exact version.
Installations are stored by archive checksum, so installing the same release is
a no-op and an update does not overwrite the previous version.

## Self-update

Installed builds update themselves (`io.jmix.cli.update`). Once every 24 hours
a startup check compares the running version's checksum directory with the
published `.sha256`, installs a differing release beside the current one, and
repoints the managed command; the running process keeps its image. `jmix
update` does the same on demand.

Each complete version directory holds a `.jmix-installed` marker, written by
the installers and by self-update. Every run touches the marker of the version
it runs, so cleanup can remove versions that are neither running nor linked and
have gone unused for a week, while a second command sharing the install root
keeps its own version. A directory without a marker is an interrupted or
partially deleted install: it is reinstalled rather than reused, and pruned
after an hour. Archive timestamps are fixed by the reproducible build, so the
marker — not the directory mtime — is the only reliable install time.

The installers also record their `--bin-dir` choice in `<install-root>/bin-dir`
because the CLI cannot otherwise find a custom command location, and seed
`<install-root>/update-check` so a fresh install does not immediately repeat
the release request. Keep those files, the `.jmix-installed` marker, and the
wrapper marker in `install.ps1` in sync with `SelfUpdater`. `install.sh`
accepts the resolved symlink self-update writes, so re-running the installer
after an update is still a no-op.

Trust model: the archive and its checksum come from the same release endpoint,
so verification proves transfer integrity, not authorship — the same trust
model as the bootstrap installers, now applied automatically. Anyone able to
publish releases can therefore reach installed CLIs within a day; keep release
credentials protected accordingly.

## Local verification

Build and test the current platform's bundle:

```shell
./gradlew clean build releaseBundle
tests/test-install.sh
```

On Windows:

```powershell
./gradlew clean build releaseBundle
tests/test-install.ps1
```

## Publishing a release

Releases are fully automatic. On every push to `main`, `auto-release.yml`
derives the next semantic version from the Conventional Commit types since the
last `v*` tag:

| Commits since the last tag                | Result                          |
|-------------------------------------------|---------------------------------|
| `type!:` subject or `BREAKING CHANGE:`    | major (minor while pre-1.0)     |
| `feat:`                                   | minor                           |
| `fix:` or `perf:`                         | patch                           |
| only `docs:`, `chore:`, `ci:`, `test:`, … | no release                      |

When a bump applies, the workflow pushes the `vX.Y.Z` tag and dispatches the
release workflow on it (tags pushed with the workflow token do not fire the
tag-push event). The release workflow builds and tests every platform image,
verifies all checksums, and creates the GitHub release only after the complete
matrix succeeds — a broken build therefore fails before anything is published.

Commit types decide releases: keep them accurate, and use `docs:`/`chore:`/`ci:`
for changes that must not publish a release.

Pushing a `vX.Y.Z` tag manually still triggers the same release workflow when a
hand-picked release point is needed.

Post-release checklist:

- [ ] Confirm the repository is public so the bootstrap commands need no token.
- [ ] Verify the release is marked immutable in GitHub.
- [ ] Run both README bootstrap commands on clean machines.

Do not upload release files manually.
