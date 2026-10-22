#!/usr/bin/env bash
# Build the Mac app and replace the installed copy in place: quit the running app, swap
# /Applications/Droidtosh.app (or $DROIDTOSH_APP) and reopen it. It is signed with the
# "Droidtosh Dev" identity like releases, so permissions and pairing carry over.
# --no-open replaces the app without reopening it.
set -euo pipefail

reopen=1
[ "${1:-}" = "--no-open" ] && reopen=0

root="$(cd "$(dirname "$0")/.." && pwd)"
target="${DROIDTOSH_APP:-/Applications/Droidtosh.app}"
info="$("$root/scripts/version.sh" "${DROIDTOSH_VERSION:-}")"
read -r version build <<< "$info"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"

cd "$root/macos"
xcodegen generate >/dev/null
xcodebuild -project Droidtosh.xcodeproj -scheme Droidtosh -configuration Release -destination 'generic/platform=macOS' \
  -derivedDataPath build/install \
  MARKETING_VERSION="${version%%[-+]*}" CURRENT_PROJECT_VERSION="$build" DROIDTOSH_VERSION="$version" build >/dev/null
built="$root/macos/build/install/Build/Products/Release/Droidtosh.app"

# Quit politely, then wait (up to 10 s) for the process to go. Only a running app is asked:
# AppleScript launches an app just to deliver a quit to it.
if pgrep -f "$target/Contents/MacOS/Droidtosh" >/dev/null; then
  osascript -e "tell application \"$target\" to quit" >/dev/null 2>&1 || true
  for _ in $(seq 50); do
    pgrep -f "$target/Contents/MacOS/Droidtosh" >/dev/null || break
    sleep 0.2
  done
  pkill -f "$target/Contents/MacOS/Droidtosh" 2>/dev/null || true
fi

rm -rf "$target"
ditto "$built" "$target"
[ "$reopen" = 1 ] && open "$target"
echo "Installed Droidtosh $version ($build) at $target"
