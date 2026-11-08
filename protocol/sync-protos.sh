#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"

swift_protos="$root/macos/DroidsyncKit/Sources/DroidsyncProtocol/Protos"
android_protos="$root/android/app/src/main/proto"
swift_fixtures="$root/macos/DroidsyncKit/Tests/DroidsyncProtocolTests/Fixtures"
android_fixtures="$root/android/app/src/test/resources/fixtures"

mkdir -p "$swift_protos/droidsync/v1" "$android_protos/droidsync/v1"
mkdir -p "$swift_fixtures" "$android_fixtures" "$root/protocol/fixtures/bin"
touch "$root/protocol/fixtures/bin/.gitkeep"

rsync -a --delete "$root/protocol/proto/droidsync/v1/" "$swift_protos/droidsync/v1/"
rsync -a --delete "$root/protocol/proto/droidsync/v1/" "$android_protos/droidsync/v1/"
# Only mirror the generated .bin fixtures; other files in the test fixture dirs are left alone.
rsync -a --delete --include='*.bin' --exclude='*' "$root/protocol/fixtures/bin/" "$swift_fixtures/"
rsync -a --delete --include='*.bin' --exclude='*' "$root/protocol/fixtures/bin/" "$android_fixtures/"
