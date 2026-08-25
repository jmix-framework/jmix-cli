#!/usr/bin/env bash
# Build the CLI and run it: ./run.sh [args...]
# No args starts the wizard, e.g.: ./run.sh   or   ./run.sh new jmix-project --no-git
set -euo pipefail
cd "$(dirname "$0")"

./gradlew -q installDist

java_major() {
    "$1" -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -1
}

# The CLI targets Java 25. When the system java is older, reuse the JDK that
# Gradle's toolchain auto-provisioned under ~/.gradle/jdks.
current="$({ java_major "${JAVA_HOME:-/nonexistent}/bin/java" 2>/dev/null || java_major java 2>/dev/null || echo 0; })"
if [[ "${current:-0}" -lt 25 ]]; then
    for home in "$HOME"/.gradle/jdks/*/*/Contents/Home "$HOME"/.gradle/jdks/*/Contents/Home \
                "$HOME"/.gradle/jdks/*/* "$HOME"/.gradle/jdks/*; do
        if [[ -x "$home/bin/java" ]] && [[ "$(java_major "$home/bin/java")" -ge 25 ]]; then
            export JAVA_HOME="$home"
            break
        fi
    done
fi

exec build/install/jmix-cli/bin/jmix-cli "$@"
