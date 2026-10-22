#!/usr/bin/env bash
# Build the debug APK and install it over the app on the connected phone, keeping its data and
# pairing (adb install -r -d), then open it. Works over USB or wireless debugging; set
# ANDROID_SERIAL when more than one device is connected. Debug builds are signed with the release
# key when android/keystore.properties exists, so they replace released builds and vice versa.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_HOME:-$(sed -n 's/^sdk\.dir=//p' "$root/android/local.properties" 2>/dev/null)}"
sdk="${sdk:-$HOME/Library/Android/sdk}"
adb="$sdk/platform-tools/adb"
[ -x "$adb" ] || { echo "adb not found at $adb; set ANDROID_HOME" >&2; exit 1; }

devices="$("$adb" devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
if [ -z "$devices" ]; then
  echo "No phone connected. Plug it in, or use wireless debugging:" >&2
  echo "  Developer options › Wireless debugging › Pair device with pairing code," >&2
  echo "  then: $adb pair <ip:port> and $adb connect <ip:port>" >&2
  exit 1
fi

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
export JAVA_HOME
(cd "$root/android" && ./gradlew -q :app:assembleDebug)
apk="$root/android/app/build/outputs/apk/debug/app-debug.apk"

"$adb" install -r -d "$apk"
"$adb" shell monkey -p dev.southstar21.droidtosh -c android.intent.category.LAUNCHER 1 >/dev/null
echo "Installed $(basename "$apk") on $(echo "$devices" | head -1)"
