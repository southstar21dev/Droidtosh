#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
src_dir="$root/macos/DroidsyncKit/Sources/DroidsyncProtocol"
out_dir="$src_dir/Generated"

# Generated code must match the SwiftProtobuf runtime the package resolves to.
resolved="$root/macos/DroidsyncKit/Package.resolved"
want="$(grep -A6 '"identity" : "swift-protobuf"' "$resolved" | sed -n 's/.*"version" : "\(.*\)".*/\1/p')"
have="$(protoc-gen-swift --version | awk '{print $NF}')"
if [ -z "$want" ] || [ "$have" != "$want" ]; then
  echo "protoc-gen-swift $have does not match swift-protobuf ${want:-?} in Package.resolved" >&2
  exit 1
fi

# Start clean so a deleted .proto doesn't leave its .pb.swift behind.
rm -rf "$out_dir/droidsync"
mkdir -p "$out_dir/droidsync/v1"

protoc -I "$src_dir/Protos" \
  --swift_out="$out_dir" \
  --swift_opt=Visibility=Public \
  "$src_dir"/Protos/droidsync/v1/*.proto
