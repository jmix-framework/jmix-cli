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

The release workflow is triggered by a semantic version tag such as `v0.1.0`.
It builds and tests every platform image, verifies all checksums, and creates the
GitHub release only after the complete matrix succeeds.

Release checklist:

- [ ] Confirm CI is green on the commit to release.
- [ ] Confirm the repository is public so the bootstrap commands need no token.
- [ ] Create and push the `vX.Y.Z` tag.
- [ ] Verify the release is marked immutable in GitHub.
- [ ] Run both README bootstrap commands on clean machines.

Do not upload release files manually or publish a tag before its commit has
passed CI.
