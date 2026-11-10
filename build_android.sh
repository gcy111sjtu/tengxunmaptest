#!/usr/bin/env bash
#
# Build TencentNaviDemo_Android with the repository's pinned Gradle wrapper.
#
# Requirements:
#   - JDK 8 (Gradle 5.6.4 / Android Gradle Plugin 3.6.3)
#   - Android SDK platform 29
#
# Usage:
#   JAVA_HOME=/path/to/jdk8 ANDROID_SDK_ROOT=/path/to/android-sdk ./build_android.sh
#   ./build_android.sh release
#   ./build_android.sh clean
#
set -euo pipefail

cd "$(dirname "$0")"
PROJECT_ROOT="$PWD"

usage() {
    cat <<'EOF'
Usage: ./build_android.sh [debug|release|clean] [extra Gradle arguments]

Environment:
  JAVA_HOME          JDK 8 directory (required if java is not on PATH)
  ANDROID_SDK_ROOT   Android SDK directory; ANDROID_HOME is also accepted

Examples:
  JAVA_HOME=/opt/jdk8 ANDROID_SDK_ROOT="$HOME/Android/Sdk" ./build_android.sh
  ./build_android.sh release --stacktrace
  ./build_android.sh clean
EOF
}

MODE="${1:-debug}"
case "$MODE" in
    debug|release|clean)
        if [ "$#" -gt 0 ]; then
            shift
        fi
        ;;
    -h|--help)
        usage
        exit 0
        ;;
    *)
        echo "error: unsupported build mode: $MODE" >&2
        usage >&2
        exit 2
        ;;
esac

# Resolve Java without changing the caller's environment. This old project is
# intentionally kept on JDK 8; modern Android Studio JBR 17 cannot run Gradle 5.6.4.
if [ -n "${JAVA_HOME:-}" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
else
    JAVA_CMD="$(command -v java || true)"
fi

if [ -z "$JAVA_CMD" ] || [ ! -x "$JAVA_CMD" ]; then
    echo "error: JDK 8 not found. Set JAVA_HOME to a JDK 8 directory." >&2
    exit 1
fi

JAVA_VERSION_OUTPUT="$("$JAVA_CMD" -version 2>&1)"
JAVA_VERSION="${JAVA_VERSION_OUTPUT%%$'\n'*}"
if [[ "$JAVA_VERSION" != *'"1.8.'* && "$JAVA_VERSION" != *'"8.'* ]]; then
    echo "error: this project requires JDK 8; detected: $JAVA_VERSION" >&2
    echo "Set JAVA_HOME to JDK 8 and retry." >&2
    exit 1
fi

# Prefer explicit SDK variables, then use the conventional Android Studio paths.
SDK_ROOT=""
SDK_CANDIDATES=(
    "${ANDROID_SDK_ROOT:-}"
    "${ANDROID_HOME:-}"
    "${HOME:-}/Android/Sdk"
    "${HOME:-}/Library/Android/sdk"
)
for candidate in "${SDK_CANDIDATES[@]}"; do
    if [ -n "$candidate" ] && [ -f "$candidate/platforms/android-29/android.jar" ]; then
        SDK_ROOT="$candidate"
        break
    fi
done

if [ -z "$SDK_ROOT" ]; then
    echo "error: Android SDK platform 29 was not found." >&2
    echo "Install 'Android SDK Platform 29', then set ANDROID_SDK_ROOT." >&2
    exit 1
fi

if [ ! -d "$SDK_ROOT/build-tools" ]; then
    echo "error: Android SDK Build-Tools are not installed under $SDK_ROOT." >&2
    exit 1
fi

export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"

GRADLE_MAIN="org.gradle.wrapper.GradleWrapperMain"
GRADLE_CLASSPATH="$PROJECT_ROOT/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$GRADLE_CLASSPATH" ]; then
    echo "error: Gradle wrapper jar not found: $GRADLE_CLASSPATH" >&2
    exit 1
fi

case "$MODE" in
    debug)
        TASK=":app:assembleDebug"
        APK="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
        ;;
    release)
        TASK=":app:assembleRelease"
        APK="$PROJECT_ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
        ;;
    clean)
        TASK=":app:clean"
        APK=""
        ;;
esac

echo "============================================"
echo "  TencentNaviDemo Android build"
echo "  mode: $MODE"
echo "  java: $JAVA_VERSION"
echo "  sdk:  $SDK_ROOT"
echo "============================================"

"$JAVA_CMD" -classpath "$GRADLE_CLASSPATH" "$GRADLE_MAIN" \
    --no-daemon "$TASK" "$@"

if [ -n "$APK" ]; then
    if [ ! -f "$APK" ]; then
        echo "error: Gradle completed but APK was not found: $APK" >&2
        exit 1
    fi
    echo
    echo "built: $APK"
    sha256sum "$APK" 2>/dev/null || true
fi
