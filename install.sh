#!/usr/bin/env bash

set -euo pipefail

readonly DEFAULT_RELEASE_BASE_URL="https://github.com/jmix-framework/jmix-cli/releases/latest/download"
readonly RELEASE_BASE_URL="${JMIX_CLI_RELEASE_BASE_URL:-$DEFAULT_RELEASE_BASE_URL}"
readonly INSTALL_ROOT="${JMIX_CLI_INSTALL_ROOT:-${XDG_DATA_HOME:-$HOME/.local/share}/jmix-cli}"
readonly BIN_DIR="${JMIX_CLI_BIN_DIR:-$HOME/.local/bin}"

fail() {
    echo "Jmix CLI installer: $*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "'$1' is required."
}

case "$(uname -s)" in
    Darwin) platform="macos" ;;
    Linux) platform="linux" ;;
    *) fail "unsupported operating system: $(uname -s)" ;;
esac

case "$(uname -m)" in
    arm64 | aarch64) architecture="arm64" ;;
    x86_64 | amd64) architecture="x64" ;;
    *) fail "unsupported architecture: $(uname -m)" ;;
esac

require_command curl
require_command tar

archive_name="jmix-cli-$platform-$architecture.tar.gz"
checksum_name="$archive_name.sha256"
temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/jmix-cli-install.XXXXXX")"

cleanup() {
    rm -rf "$temp_dir"
}
trap cleanup EXIT HUP INT TERM

download_asset() {
    local name="$1"
    local destination="$2"
    local base="${RELEASE_BASE_URL%/}"

    echo "Downloading $name..."
    case "$base" in
        http://* | https://* | file://*)
            curl -fsSL "$base/$name" -o "$destination"
            ;;
        *)
            cp "$base/$name" "$destination"
            ;;
    esac
}

archive_file="$temp_dir/$archive_name"
checksum_file="$temp_dir/$checksum_name"
download_asset "$archive_name" "$archive_file"
download_asset "$checksum_name" "$checksum_file"

expected_checksum="$(awk 'NR == 1 { print tolower($1) }' "$checksum_file")"
[[ "$expected_checksum" =~ ^[[:xdigit:]]{64}$ ]] || fail "invalid checksum file for $archive_name."

if command -v sha256sum >/dev/null 2>&1; then
    actual_checksum="$(sha256sum "$archive_file" | awk '{ print tolower($1) }')"
elif command -v shasum >/dev/null 2>&1; then
    actual_checksum="$(shasum -a 256 "$archive_file" | awk '{ print tolower($1) }')"
else
    fail "'sha256sum' or 'shasum' is required to verify the download."
fi

[[ "$actual_checksum" == "$expected_checksum" ]] || fail "checksum verification failed for $archive_name."

versions_dir="$INSTALL_ROOT/versions"
version_dir="$versions_dir/$expected_checksum"
case "$platform" in
    macos)
        image_name="jmix.app"
        launcher_relative="Contents/MacOS/jmix"
        ;;
    linux)
        image_name="jmix"
        launcher_relative="bin/jmix"
        ;;
esac
launcher="$version_dir/$launcher_relative"

# Marks a complete installation; the CLI keeps and prunes versions by this file.
readonly INSTALL_MARKER=".jmix-installed"

installed=false
if [[ -x "$launcher" && -f "$version_dir/$INSTALL_MARKER" ]]; then
    echo "Jmix CLI is already up to date."
else
    [[ ! -e "$version_dir" ]] || fail "incomplete installation found at $version_dir."
    extract_dir="$temp_dir/extracted"
    mkdir -p "$extract_dir" "$versions_dir"
    tar -xzf "$archive_file" -C "$extract_dir"
    [[ -x "$extract_dir/$image_name/$launcher_relative" ]] || fail "release archive has an unexpected layout."
    : > "$extract_dir/$image_name/$INSTALL_MARKER"
    mv "$extract_dir/$image_name" "$version_dir"
    installed=true
fi
# Timestamps inside the archive are fixed, so record the install time here.
touch "$version_dir/$INSTALL_MARKER"

mkdir -p "$BIN_DIR"
command_path="$BIN_DIR/jmix"
versions_real="$(cd "$versions_dir" && pwd -P)"
if [[ -L "$command_path" ]]; then
    current_target="$(readlink "$command_path")"
    # Self-update writes the resolved path, so compare real locations too.
    current_real="$(cd "$(dirname "$current_target")" 2>/dev/null && pwd -P || true)"
    case "$current_target" in
        "$launcher") ;;
        "$versions_dir"/*) ln -sfn "$launcher" "$command_path" ;;
        *)
            case "${current_real:-}" in
                "$versions_real"/*) ln -sfn "$launcher" "$command_path" ;;
                *) fail "$command_path already exists and is not managed by this installer." ;;
            esac
            ;;
    esac
elif [[ -e "$command_path" ]]; then
    fail "$command_path already exists and is not managed by this installer."
else
    ln -s "$launcher" "$command_path"
fi

mkdir -p "$INSTALL_ROOT"
# Recorded after the command exists, so a rejected install leaves no metadata.
# The CLI cannot otherwise know a custom bin directory.
printf '%s\n' "$BIN_DIR" > "$INSTALL_ROOT/bin-dir"

if [[ "$installed" == true ]]; then
    echo "Installed Jmix CLI at $command_path"
fi
case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *) echo "Add $BIN_DIR to PATH to use 'jmix' in future shells." ;;
esac

if [[ "${JMIX_CLI_NO_RUN:-0}" == "1" ]]; then
    exit 0
fi

echo "Starting the Jmix project wizard..."
cleanup
trap - EXIT HUP INT TERM
if { exec 3</dev/tty; } 2>/dev/null; then
    exec "$command_path" "$@" <&3 3<&-
fi
exec "$command_path" "$@"
