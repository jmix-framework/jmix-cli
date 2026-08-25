$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$releaseDir = Join-Path $repoRoot "build\release"
$tempDir = Join-Path ([IO.Path]::GetTempPath()) ("jmix-cli-install-test-" + [guid]::NewGuid().ToString("N"))
$installRoot = Join-Path $tempDir "install"
$binDir = Join-Path $tempDir "bin"

try {
    New-Item -ItemType Directory -Path $tempDir | Out-Null
    $archive = Get-ChildItem -LiteralPath $releaseDir -Filter "jmix-cli-windows-*.zip" -File -ErrorAction Stop |
        Select-Object -First 1
    if (-not $archive) {
        throw "Windows release archive was not built."
    }

    $firstOutput = & (Join-Path $repoRoot "install.ps1") `
        -ReleaseBaseUrl $releaseDir `
        -InstallRoot $installRoot `
        -BinDir $binDir `
        -NoRun `
        -SkipPathUpdate *>&1 | Out-String
    if ($firstOutput -notmatch "Installed Jmix CLI") {
        throw "Installer did not report a new installation."
    }

    $commandPath = Join-Path $binDir "jmix.cmd"
    if (-not (Test-Path -LiteralPath $commandPath -PathType Leaf)) {
        throw "Installer did not create jmix.cmd."
    }
    $helpOutput = & $commandPath --help | Out-String
    if ($helpOutput -notmatch "Jmix CLI") {
        throw "Installed CLI did not start."
    }

    $versionCountBefore = @(Get-ChildItem -LiteralPath (Join-Path $installRoot "versions") -Directory).Count
    $secondOutput = & (Join-Path $repoRoot "install.ps1") `
        -ReleaseBaseUrl $releaseDir `
        -InstallRoot $installRoot `
        -BinDir $binDir `
        -NoRun `
        -SkipPathUpdate *>&1 | Out-String
    $versionCountAfter = @(Get-ChildItem -LiteralPath (Join-Path $installRoot "versions") -Directory).Count
    if ($secondOutput -notmatch "already up to date" -or $versionCountBefore -ne $versionCountAfter) {
        throw "Repeated installation was not idempotent."
    }

    $startOutput = & (Join-Path $repoRoot "install.ps1") `
        -ReleaseBaseUrl $releaseDir `
        -InstallRoot $installRoot `
        -BinDir $binDir `
        -SkipPathUpdate `
        -CliArguments "--help" *>&1 | Out-String
    if ($startOutput -notmatch "Starting the Jmix project wizard" -or $startOutput -notmatch "Jmix CLI") {
        throw "Installer did not start the CLI."
    }

    $tamperedDir = Join-Path $tempDir "tampered-release"
    New-Item -ItemType Directory -Path $tamperedDir | Out-Null
    Copy-Item -LiteralPath $archive.FullName -Destination $tamperedDir
    Copy-Item -LiteralPath "$($archive.FullName).sha256" -Destination $tamperedDir
    Add-Content -LiteralPath (Join-Path $tamperedDir $archive.Name) -Value "tampered" -NoNewline

    $checksumRejected = $false
    try {
        & (Join-Path $repoRoot "install.ps1") `
            -ReleaseBaseUrl $tamperedDir `
            -InstallRoot (Join-Path $tempDir "tampered-install") `
            -BinDir (Join-Path $tempDir "tampered-bin") `
            -NoRun `
            -SkipPathUpdate
    } catch {
        $checksumRejected = $_.Exception.Message -match "checksum verification failed"
    }
    if (-not $checksumRejected) {
        throw "Installer accepted a release with an invalid checksum."
    }

    $unmanagedBinDir = Join-Path $tempDir "unmanaged-bin"
    $unmanagedCommand = Join-Path $unmanagedBinDir "jmix.cmd"
    New-Item -ItemType Directory -Path $unmanagedBinDir | Out-Null
    Set-Content -LiteralPath $unmanagedCommand -Value "@echo unmanaged" -Encoding Ascii -NoNewline
    $unmanagedRejected = $false
    try {
        & (Join-Path $repoRoot "install.ps1") `
            -ReleaseBaseUrl $releaseDir `
            -InstallRoot (Join-Path $tempDir "conflict-install") `
            -BinDir $unmanagedBinDir `
            -NoRun `
            -SkipPathUpdate
    } catch {
        $unmanagedRejected = $_.Exception.Message -match "not managed by this installer"
    }
    if (-not $unmanagedRejected -or (Get-Content -LiteralPath $unmanagedCommand -Raw) -ne "@echo unmanaged") {
        throw "Installer replaced an unmanaged command."
    }

    Write-Host "PowerShell installer tests passed."
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}
