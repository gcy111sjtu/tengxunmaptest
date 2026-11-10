#!/usr/bin/env bash
#
# Package an already-built, installable Android Release APK for delivery.
# This mirrors metartc-intercom/package_customer_delivery.sh: packaging never
# compiles the app, never overwrites an existing release, and emits checksums.
#
# Usage:
#   ./build_android.sh release
#   ./package_android_release.sh
#   ./package_android_release.sh path/to/app-release.apk /path/to/output
#
set -euo pipefail

cd "$(dirname "$0")"
PROJECT_ROOT="$PWD"
APK="${1:-$PROJECT_ROOT/app/build/outputs/apk/release/app-release.apk}"
DESTINATION="${2:-$PROJECT_ROOT/dist}"

if [ ! -f "$APK" ]; then
    echo "error: signed Release APK not found: $APK" >&2
    echo "Run ./build_android.sh release first." >&2
    exit 1
fi

VERSION_NAME="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*["'\'']\([^"'\'']*\)["'\''].*/\1/p' app/build.gradle | head -n 1)"
VERSION_CODE="$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*\([0-9][0-9]*\).*/\1/p' app/build.gradle | head -n 1)"
if [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ]; then
    echo "error: cannot read versionName/versionCode from app/build.gradle" >&2
    exit 1
fi

PRODUCT_NAME="TencentNaviSpeedLimit"
PACKAGE_NAME="$PRODUCT_NAME-$VERSION_NAME-android"
STAGE="$DESTINATION/$PACKAGE_NAME"
ARCHIVE="$DESTINATION/$PACKAGE_NAME.tar.gz"
ARCHIVE_CHECKSUM="$ARCHIVE.sha256"

mkdir -p "$DESTINATION"
if [ -e "$STAGE" ] || [ -e "$ARCHIVE" ] || [ -e "$ARCHIVE_CHECKSUM" ]; then
    echo "error: release output already exists; move it aside first:" >&2
    echo "  $STAGE" >&2
    echo "  $ARCHIVE" >&2
    exit 1
fi

mkdir -p "$STAGE"
cp "$APK" "$STAGE/$PRODUCT_NAME-$VERSION_NAME.apk"
cp README.md "$STAGE/README.md"

GIT_COMMIT="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
{
    echo "Product: Tencent Navi Speed Limit Demo"
    echo "Version: $VERSION_NAME"
    echo "Version code: $VERSION_CODE"
    echo "Application ID: com.example.tencentnavigation.tencentnavidemo"
    echo "Git commit: $GIT_COMMIT"
    echo "APK signing: debug keystore (internal testing only)"
    echo "Built artifact source: $APK"
    echo "Packaged at (UTC): $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
} > "$STAGE/DELIVERY_INFO.txt"

sha256_file() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1"
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1"
    else
        echo "error: sha256sum or shasum is required" >&2
        return 1
    fi
}

(
    cd "$STAGE"
    : > SHA256SUMS
    while IFS= read -r file; do
        sha256_file "$file" >> SHA256SUMS
    done < <(find . -type f ! -name SHA256SUMS | LC_ALL=C sort)
)

tar -C "$DESTINATION" -czf "$ARCHIVE" "$PACKAGE_NAME"
sha256_file "$ARCHIVE" > "$ARCHIVE_CHECKSUM"

echo "Android release delivery ready:"
echo "  directory: $STAGE"
echo "  APK:       $STAGE/$PRODUCT_NAME-$VERSION_NAME.apk"
echo "  archive:   $ARCHIVE"
echo "  checksum:  $ARCHIVE_CHECKSUM"
echo
echo "Warning: this Demo Release is signed with the debug keystore."
echo "Use a private release keystore before store or production distribution."
