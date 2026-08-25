#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/jmix-cli-install-test.XXXXXX")"

cleanup() {
    rm -rf "$temp_dir"
}
trap cleanup EXIT HUP INT TERM

case "$(uname -s)" in
    Darwin) platform="macos" ;;
    Linux) platform="linux" ;;
    *) echo "Unsupported test platform" >&2; exit 1 ;;
esac
case "$(uname -m)" in
    arm64 | aarch64) architecture="arm64" ;;
    x86_64 | amd64) architecture="x64" ;;
    *) echo "Unsupported test architecture" >&2; exit 1 ;;
esac

archive_name="jmix-cli-$platform-$architecture.tar.gz"
release_dir="$repo_root/build/release"
[[ -f "$release_dir/$archive_name" ]]
[[ -f "$release_dir/$archive_name.sha256" ]]

run_installer() {
    JMIX_CLI_RELEASE_BASE_URL="$1" \
    JMIX_CLI_INSTALL_ROOT="$temp_dir/install" \
    JMIX_CLI_BIN_DIR="$temp_dir/bin" \
    JMIX_CLI_NO_RUN=1 \
        "$repo_root/install.sh"
}

first_output="$(run_installer "$release_dir")"
[[ "$first_output" == *"Installed Jmix CLI"* ]]
[[ -x "$temp_dir/bin/jmix" ]]
"$temp_dir/bin/jmix" --help | grep -q "Jmix CLI"

version_count_before="$(find "$temp_dir/install/versions" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
second_output="$(run_installer "$release_dir")"
version_count_after="$(find "$temp_dir/install/versions" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
[[ "$second_output" == *"already up to date"* ]]
[[ "$version_count_before" == "$version_count_after" ]]

start_output="$(
    JMIX_CLI_RELEASE_BASE_URL="$release_dir" \
    JMIX_CLI_INSTALL_ROOT="$temp_dir/install" \
    JMIX_CLI_BIN_DIR="$temp_dir/bin" \
        "$repo_root/install.sh" --help
)"
[[ "$start_output" == *"Starting the Jmix project wizard"* ]]
[[ "$start_output" == *"Jmix CLI"* ]]

tampered_dir="$temp_dir/tampered-release"
mkdir -p "$tampered_dir"
cp "$release_dir/$archive_name" "$release_dir/$archive_name.sha256" "$tampered_dir/"
printf 'tampered' >>"$tampered_dir/$archive_name"
if run_installer "$tampered_dir" >"$temp_dir/tampered.log" 2>&1; then
    echo "Installer accepted a release with an invalid checksum" >&2
    exit 1
fi
grep -q "checksum verification failed" "$temp_dir/tampered.log"

unmanaged_bin_dir="$temp_dir/unmanaged-bin"
unmanaged_target="$temp_dir/unmanaged-jmix"
mkdir -p "$unmanaged_bin_dir"
touch "$unmanaged_target"
ln -s "$unmanaged_target" "$unmanaged_bin_dir/jmix"
if JMIX_CLI_RELEASE_BASE_URL="$release_dir" \
    JMIX_CLI_INSTALL_ROOT="$temp_dir/conflict-install" \
    JMIX_CLI_BIN_DIR="$unmanaged_bin_dir" \
    JMIX_CLI_NO_RUN=1 \
        "$repo_root/install.sh" >"$temp_dir/conflict.log" 2>&1; then
    echo "Installer replaced an unmanaged command" >&2
    exit 1
fi
grep -q "not managed by this installer" "$temp_dir/conflict.log"
[[ "$(readlink "$unmanaged_bin_dir/jmix")" == "$unmanaged_target" ]]

echo "Shell installer tests passed."
