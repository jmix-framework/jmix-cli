[CmdletBinding()]
param(
    [string]$ReleaseBaseUrl = $(
        if ($env:JMIX_CLI_RELEASE_BASE_URL) { $env:JMIX_CLI_RELEASE_BASE_URL }
        else { "https://github.com/jmix-framework/jmix-cli/releases/latest/download" }
    ),
    [string]$InstallRoot = $(
        if ($env:JMIX_CLI_INSTALL_ROOT) { $env:JMIX_CLI_INSTALL_ROOT }
        else { Join-Path $env:LOCALAPPDATA "Jmix\jmix-cli" }
    ),
    [string]$BinDir = $(
        if ($env:JMIX_CLI_BIN_DIR) { $env:JMIX_CLI_BIN_DIR }
        else { Join-Path $env:LOCALAPPDATA "Jmix\bin" }
    ),
    [switch]$NoRun,
    [switch]$SkipPathUpdate,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$CliArguments
)

$ErrorActionPreference = "Stop"

if ($env:OS -ne "Windows_NT") {
    throw "Jmix CLI installer: install.ps1 supports Windows only."
}

# [Runtime.InteropServices.RuntimeInformation]::OSArchitecture returns null in some
# Windows PowerShell 5.1 environments, so detect the architecture via environment variables.
$osArchitecture = if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }
$architecture = switch ($osArchitecture) {
    "AMD64" { "x64" }
    default { throw "Jmix CLI installer: only Windows x64 is currently supported (detected: $osArchitecture)." }
}

$archiveName = "jmix-cli-windows-$architecture.zip"
$checksumName = "$archiveName.sha256"
$tempDir = Join-Path ([IO.Path]::GetTempPath()) ("jmix-cli-install-" + [guid]::NewGuid().ToString("N"))
$archiveFile = Join-Path $tempDir $archiveName
$checksumFile = Join-Path $tempDir $checksumName
$launcher = $null
$installed = $false

function Copy-ReleaseAsset {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    if (Test-Path -LiteralPath $ReleaseBaseUrl -PathType Container) {
        Copy-Item -LiteralPath (Join-Path $ReleaseBaseUrl $Name) -Destination $Destination
    } else {
        $url = $ReleaseBaseUrl.TrimEnd("/") + "/" + $Name
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $Destination
    }
}

try {
    New-Item -ItemType Directory -Path $tempDir | Out-Null
    Copy-ReleaseAsset -Name $archiveName -Destination $archiveFile
    Copy-ReleaseAsset -Name $checksumName -Destination $checksumFile

    $checksumContent = Get-Content -LiteralPath $checksumFile -Raw
    if ([string]::IsNullOrWhiteSpace($checksumContent)) {
        throw "Jmix CLI installer: invalid checksum file for $archiveName."
    }
    $expectedChecksum = ($checksumContent.Trim() -split "\s+")[0].ToLowerInvariant()
    if ($expectedChecksum -notmatch "^[0-9a-f]{64}$") {
        throw "Jmix CLI installer: invalid checksum file for $archiveName."
    }
    $actualChecksum = (Get-FileHash -LiteralPath $archiveFile -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualChecksum -ne $expectedChecksum) {
        throw "Jmix CLI installer: checksum verification failed for $archiveName."
    }

    $versionsDir = Join-Path $InstallRoot "versions"
    $versionDir = Join-Path $versionsDir $expectedChecksum
    $launcher = Join-Path $versionDir "jmix.exe"

    # Marks a complete installation; the CLI keeps and prunes versions by this file.
    $installMarker = Join-Path $versionDir ".jmix-installed"

    if ((Test-Path -LiteralPath $launcher -PathType Leaf) -and (Test-Path -LiteralPath $installMarker -PathType Leaf)) {
        Write-Host "Jmix CLI is already up to date."
    } else {
        if (Test-Path -LiteralPath $versionDir) {
            throw "Jmix CLI installer: incomplete installation found at $versionDir."
        }
        $extractDir = Join-Path $tempDir "extracted"
        New-Item -ItemType Directory -Path $extractDir | Out-Null
        New-Item -ItemType Directory -Force -Path $versionsDir | Out-Null
        Expand-Archive -LiteralPath $archiveFile -DestinationPath $extractDir
        $imageDir = Join-Path $extractDir "jmix"
        if (-not (Test-Path -LiteralPath (Join-Path $imageDir "jmix.exe") -PathType Leaf)) {
            throw "Jmix CLI installer: release archive has an unexpected layout."
        }
        New-Item -ItemType File -Path (Join-Path $imageDir ".jmix-installed") | Out-Null
        Move-Item -LiteralPath $imageDir -Destination $versionDir
        $installed = $true
    }
    # Timestamps inside the archive are fixed, so record the install time here.
    (Get-Item -LiteralPath $installMarker).LastWriteTime = Get-Date

    New-Item -ItemType Directory -Force -Path $BinDir | Out-Null
    $commandPath = Join-Path $BinDir "jmix.cmd"
    $wrapperMarker = "@rem Managed by the Jmix CLI installer"
    $wrapper = "$wrapperMarker`r`n@echo off`r`n`"$launcher`" %*`r`n"
    $currentWrapper = if (Test-Path -LiteralPath $commandPath -PathType Leaf) {
        Get-Content -LiteralPath $commandPath -Raw
    } else {
        $null
    }
    if ($null -ne $currentWrapper -and -not $currentWrapper.StartsWith($wrapperMarker)) {
        throw "Jmix CLI installer: $commandPath already exists and is not managed by this installer."
    }
    if ($currentWrapper -cne $wrapper) {
        Set-Content -LiteralPath $commandPath -Value $wrapper -Encoding Ascii -NoNewline
    }

    New-Item -ItemType Directory -Force -Path $InstallRoot | Out-Null
    # Recorded after the command exists, so a rejected install leaves no metadata.
    # The CLI cannot otherwise know a custom bin directory; UTF-8 keeps non-ASCII
    # paths readable on both Windows PowerShell and PowerShell 7.
    Set-Content -LiteralPath (Join-Path $InstallRoot "bin-dir") -Value $BinDir -Encoding UTF8
    # This install just verified the latest release; start the CLI's own check
    # clock here so it does not immediately repeat the same request.
    Set-Content -LiteralPath (Join-Path $InstallRoot "update-check") `
        -Value ([DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()) -Encoding Ascii

    if ($installed) {
        Write-Host "Installed Jmix CLI at $commandPath"
    }

    if (-not $SkipPathUpdate -and $env:JMIX_CLI_SKIP_PATH_UPDATE -ne "1") {
        $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
        $pathEntries = @($userPath -split ";" | Where-Object { $_ })
        if ($pathEntries -notcontains $BinDir) {
            $updatedPath = (@($BinDir) + $pathEntries) -join ";"
            [Environment]::SetEnvironmentVariable("Path", $updatedPath, "User")
            Write-Host "Added $BinDir to your user PATH."
        }
    }
    if (($env:Path -split ";") -notcontains $BinDir) {
        $env:Path = "$BinDir;$env:Path"
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

if ($NoRun -or $env:JMIX_CLI_NO_RUN -eq "1") {
    return
}

Write-Host "Starting the Jmix project wizard..."
& $launcher @CliArguments
