#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
proto_dir="$root/protocol/proto"
fixture_dir="$root/protocol/fixtures"
out_dir="$fixture_dir/bin"
mkdir -p "$out_dir"

encode() {
  local name="$1"
  protoc -I "$proto_dir" --encode=droidsync.v1.Envelope \
    "$proto_dir"/droidsync/v1/*.proto \
    < "$fixture_dir/$name.txt" > "$out_dir/$name.bin"
}

encode hello
encode ping
encode notification_record
encode call_state
encode resume
encode device_status
