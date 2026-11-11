# Distribution

Jmix CLI is distributed as self-contained application images through GitHub
Releases. GitHub Packages is not used because it targets package-manager
registries such as Maven, npm, NuGet, and containers; the CLI needs directly
downloadable platform archives for an unauthenticated bootstrap command.

Each application image contains the CLI, its libraries, and a reduced JDK 25
runtime produced with `jlink` and `jpackage`. Users do not need Java to launch
the CLI. JDK detection inside the wizard concerns the generated Jmix project,
not the CLI runtime.

The runtime holds only the modules listed in `releaseRuntimeModules` in
`build.gradle.kts`. Code that needs another module works in tests and source runs and
fails only in the installed CLI, so add the module to the list together with that code.
`jdk.httpserver` is there for `jmix login`, which receives the browser's sign-in
redirect on a loopback port (see [Account and trial](features/account-and-trial.md)).

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

In a terminal, the bootstrap installer highlights download, verification,
extraction, and CLI startup messages. On macOS/Linux, curl shows its native
download progress bar (or activity indicator when the size is unknown).
PowerShell shows native progress for installation phases and web downloads,
and closes its progress display before starting the CLI and on failure.
Redirected output and CI use plain status lines; `NO_COLOR` disables colored
status messages.

On macOS and Linux, the installer adds `~/.local/bin` to the detected Bash,
Zsh, or POSIX shell profile when the directory is not already in `PATH`. Since
the bootstrap script runs in a child shell, it also prints the `export` command
needed to use `jmix` immediately without restarting the current shell. Set
`JMIX_CLI_SKIP_PATH_UPDATE=1` to keep shell profiles unchanged.

On Windows, installation and updates write a Unicode-capable `jmix.cmd` for the
selected install and bin directories. On Windows 10 version 1903 or later, the
native launcher uses UTF-8 as its process code page to locate the bundled Java
runtime in Unicode paths. Rerun `install.ps1` to regenerate a
wrapper from an earlier installer if its launcher path was corrupted.

The stable asset names allow installers to use GitHub's
`releases/latest/download` URL while the release tag records the exact version.
Installations are stored by archive checksum, so installing the same release is
a no-op and an update does not overwrite the previous version.

## Self-update

Installed builds update themselves (`io.jmix.cli.update`). Startup compares the
running version's checksum directory with the published `.sha256` before the
command line is parsed, installs a differing release beside the current one,
repoints the managed command, and re-runs the typed command on the new version,
exiting with its code, so an update applies to the command that triggered it
rather than the next one. Within the interval below a release can still be up
to ten minutes old before a run picks it up. A check that cannot complete is
reported and the command continues. `jmix update` performs the same install on
demand and ignores the interval.

`update.lock` in the install root serializes manual updates, automatic updates,
and version cleanup. Manual updates wait for the lock. Automatic updates and
version cleanup skip that run when it is busy. The file also records the last
automatic check time, which throttles automatic checks to one per
`SelfUpdater.CHECK_INTERVAL` (ten minutes). The time is written before the check
runs, so an offline machine reports the failure once per interval. A stamp
dated in the future is treated as stale. The restarted command receives
`--no-update` as its first argument, which stops an update loop without an
environment variable that the IDE, file manager and Gradle processes would inherit.

The release check is skipped for `--help`, for `jmix update`, and for the
`--no-update` flag, which is read from the raw arguments in `main()` because
the update happens before parsing. Cache pruning and the in-use marker still
run. Version cleanup runs when it can acquire the lock.

Each complete version directory holds a `.jmix-installed` marker, written by
the installers and by self-update. Every run touches the marker of the version
it runs, so cleanup can remove versions that are neither running nor linked and
have gone unused for a week, while a second command sharing the install root
keeps its own version. A directory without a marker is an interrupted or
partially deleted install: it is reinstalled rather than reused, and pruned
after an hour. Archive timestamps are fixed by the reproducible build, so the
marker — not the directory mtime — is the only reliable install time.

The installers also record their `--bin-dir` choice in `<install-root>/bin-dir`
because the CLI cannot otherwise find a custom command location. Keep that
file, the `.jmix-installed` marker, and the wrapper marker in `install.ps1` in
sync with `SelfUpdater`. `install.sh` accepts the resolved symlink self-update
writes, so re-running the installer after an update is still a no-op.

Trust model: the archive and its checksum come from the same release endpoint,
so verification proves transfer integrity, not authorship — the same trust
model as the bootstrap installers, now applied automatically. Anyone able to
publish releases therefore reaches installed CLIs on their next run; keep
release credentials protected accordingly.

## Local verification

Build and test the current platform's bundle:

```shell
./gradlew clean build releaseBundle
tests/test-install.sh
```

On Windows, install the Windows SDK with its Manifest Tool (`mt.exe`). The
release build looks in the standard Windows Kits directory or on `PATH`.

```powershell
./gradlew clean build releaseBundle
tests/test-install.ps1
```

Besides the installer, update and checksum behavior, the install test starts the
installed CLI, checks that `login --help` and `trial --help` run, and checks that the
bundled runtime lists `jdk.httpserver`.

## Checking a Jmix release

After publishing a new stable Jmix version and its project templates, run
[Jmix Release Check](../.github/workflows/jmix-release-check.yml) from the CLI
repository's Actions tab and enter the exact `jmix_version`, such as `3.1.0`.
The workflow installs the latest published CLI, starts with an empty template
cache, and verifies that the default selection and `--jmix-version` both
produce the expected BOM version. It also builds the default project with its
Gradle wrapper.

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
tag-push event). Auto Release passes the commit SHA it selected. The release
workflow checks it against the tag and runs
the [full CI workflow](../.github/workflows/ci.yml) on that SHA, including the
platform test matrix and the generated-project build. It also builds and tests
every release image from the same commit. Publication requires both CI and
packaging to succeed. The publish job verifies the asset checksums and checks
that the tag still points to the tested commit.

Workflow actions are pinned to upstream commit SHAs. When updating an action,
verify the new SHA against its upstream release tag. When updating Gradle, set
`distributionSha256Sum` to the published checksum for the selected ZIP from
the [Gradle checksum reference](https://gradle.org/release-checksums/).

If the tag was pushed but dispatch failed, rerun the failed **Auto Release**
job. It reuses the existing `vX.Y.Z` tag for the original commit. If several
release tags point to that commit, dispatch **Release** manually for the
intended tag. To recover from a failed build, rerun **Release** for that tag.
Release runs for the same tag are serialized. A run stops after validation
if the release is already published.

Commit types decide releases: keep them accurate, and use `docs:`/`chore:`/`ci:`
for changes that must not publish a release.

Pushing a `vX.Y.Z` tag manually still triggers the same release workflow when a
hand-picked release point is needed.

Post-release checklist:

- [ ] Confirm the repository is public so the bootstrap commands need no token.
- [ ] Verify the release is marked immutable in GitHub.
- [ ] Run both README bootstrap commands on clean machines.

Do not upload release files manually.
