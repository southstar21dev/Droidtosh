#!/usr/bin/env bash
# Build both apps and copy versioned artifacts into ./builds, named like release assets.
# The APK is the release build when the release key is set up (android/keystore.properties),
# otherwise the debug build.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
info="$("$root/scripts/version.sh" "${DROIDTOSH_VERSION:-}")"
read -r version build <<< "$info"
out="$root/builds"
mkdir -p "$out"

# Assign before exporting: `export X=$(cmd)` masks cmd's failure from set -e.
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
export JAVA_HOME
if [ -f "$root/android/keystore.properties" ] || [ -n "${ANDROID_KEYSTORE_FILE:-}" ]; then
  (cd "$root/android" && ./gradlew -q :app:assembleRelease)
  apk_src="$root/android/app/build/outputs/apk/release/app-release.apk"
else
  (cd "$root/android" && ./gradlew -q :app:assembleDebug)
  apk_src="$root/android/app/build/outputs/apk/debug/app-debug.apk"
fi
# File names drop the build metadata of a local version (0.1.0+268.c5b81ac → 0.1.0).
apk_dst="$out/Droidtosh-${version%%+*}-${build}.apk"
cp "$apk_src" "$apk_dst"

(cd "$root/macos" && xcodegen generate >/dev/null && \
  DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}" \
  xcodebuild -project Droidtosh.xcodeproj -scheme Droidtosh -configuration Release -destination 'generic/platform=macOS' \
    -derivedDataPath build/all MARKETING_VERSION="${version%%[-+]*}" CURRENT_PROJECT_VERSION="$build" \
    DROIDTOSH_VERSION="$version" build >/dev/null)
app_src="$root/macos/build/all/Build/Products/Release/Droidtosh.app"
zip_dst="$out/Droidtosh-${version%%+*}-${build}-mac.zip"
rm -f "$zip_dst"
ditto -c -k --keepParent "$app_src" "$zip_dst"

echo "APK: $apk_dst"
echo "MAC: $zip_dst"
