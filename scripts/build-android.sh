#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_ENV="$ROOT_DIR/.android-build.env"

# Keep machine-specific SDK/JDK paths out of Git. This local file can set:
#   XNOTES_ANDROID_SDK_ROOT=/path/to/Android/Sdk
#   XNOTES_JAVA_HOME=/path/to/a/full/JDK
if [[ -f "$LOCAL_ENV" ]]; then
    # shellcheck source=/dev/null
    source "$LOCAL_ENV"
fi

SDK_ROOT="${XNOTES_ANDROID_SDK_ROOT:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}}"
JDK_HOME="${XNOTES_JAVA_HOME:-${JAVA_HOME:-/opt/android-studio/jbr}}"

if [[ ! -x "$JDK_HOME/bin/java" || ! -x "$JDK_HOME/bin/javac" ]]; then
    printf 'A full JDK was not found at %s. Set XNOTES_JAVA_HOME in %s.\n' "$JDK_HOME" "$LOCAL_ENV" >&2
    exit 1
fi

JAVA_VERSION="$("$JDK_HOME/bin/java" -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p')"
JAVA_MAJOR="${JAVA_VERSION%%.*}"
if [[ "$JAVA_MAJOR" != "21" ]]; then
    printf 'This project build is pinned to JDK 21; found %s at %s.\n' "${JAVA_VERSION:-unknown}" "$JDK_HOME" >&2
    exit 1
fi

for sdk_component in platforms/android-37.0 ndk/27.0.12077973 cmake/3.22.1; do
    if [[ ! -d "$SDK_ROOT/$sdk_component" ]]; then
        printf 'Required Android SDK component is missing: %s/%s\n' "$SDK_ROOT" "$sdk_component" >&2
        exit 1
    fi
done

# Android Gradle Plugin reads sdk.dir from local.properties. Keep that ignored local
# file in sync with the selected SDK while preserving any other developer settings.
LOCAL_PROPERTIES="$ROOT_DIR/local.properties"
LOCAL_PROPERTIES_TMP="$(mktemp)"
if [[ -f "$LOCAL_PROPERTIES" ]]; then
    awk '!/^sdk\.dir=/' "$LOCAL_PROPERTIES" > "$LOCAL_PROPERTIES_TMP"
fi
printf 'sdk.dir=%s\n' "$SDK_ROOT" >> "$LOCAL_PROPERTIES_TMP"
mv "$LOCAL_PROPERTIES_TMP" "$LOCAL_PROPERTIES"

export JAVA_HOME="$JDK_HOME"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export PATH="$JDK_HOME/bin:$SDK_ROOT/platform-tools:$PATH"

if (($# == 0)); then
    set -- :app:assembleDebug
fi

printf 'Building with JDK %s (%s) and Android SDK %s\n' "$JAVA_VERSION" "$JAVA_HOME" "$ANDROID_SDK_ROOT"
# Shared memory and worker limits live in gradle.properties so IDE builds use
# the same defaults. Stop the command-line build VM when the build completes.
exec "$ROOT_DIR/gradlew" --no-daemon "$@"
