#!/usr/bin/env bash
# Store the signing keys as GitHub Actions secrets for .github/workflows/release.yml
# (docs/RELEASING.md). Uses `gh`; asks before uploading each key. Pass the repo as $1 to target
# another repo than this checkout's.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
repo=(${1:+--repo "$1"})
confirm() { read -r -p "$1 [y/N] " answer; [ "$answer" = y ] || [ "$answer" = Y ]; }

properties="$root/android/keystore.properties"
if [ -f "$properties" ] && confirm "Upload the Android release key from $properties?"; then
  store="$(sed -n 's/^storeFile=//p' "$properties")"
  base64 -i "$store" | gh secret set ANDROID_KEYSTORE_BASE64 ${repo[@]+"${repo[@]}"}
  sed -n 's/^storePassword=//p' "$properties" | tr -d '\n' | gh secret set ANDROID_KEYSTORE_PASSWORD ${repo[@]+"${repo[@]}"}
  sed -n 's/^keyAlias=//p' "$properties" | tr -d '\n' | gh secret set ANDROID_KEY_ALIAS ${repo[@]+"${repo[@]}"}
fi

if confirm 'Export the "Droidtosh Dev" signing identity from your login keychain and upload it?'; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  password="$(openssl rand -hex 24)"
  # No -v: that lists only trusted identities, and this certificate is self-signed.
  security find-identity -p codesigning | grep -q '"Droidtosh Dev"' || { echo 'No "Droidtosh Dev" identity found.' >&2; exit 1; }
  # Exports only this identity, so macOS asks once, for this key ("swift" wants to export a key:
  # enter your login password and choose Allow).
  echo 'macOS will ask once to allow exporting the "Droidtosh Dev" key.'
  swift -suppress-warnings "$root/scripts/export-signing-identity.swift" "Droidtosh Dev" "$tmp/dev-export.p12" "$password"
  openssl pkcs12 -in "$tmp/dev-export.p12" -passin "pass:$password" -nodes -out "$tmp/dev.pem" 2>/dev/null \
    || openssl pkcs12 -legacy -in "$tmp/dev-export.p12" -passin "pass:$password" -nodes -out "$tmp/dev.pem"
  # The certificate and the key must belong together before anything is uploaded.
  cert_key="$(openssl x509 -in "$tmp/dev.pem" -noout -pubkey 2>/dev/null)"
  key_key="$(openssl pkey -in "$tmp/dev.pem" -pubout 2>/dev/null)"
  if [ -z "$cert_key" ] || [ "$cert_key" != "$key_key" ]; then
    echo 'The exported "Droidtosh Dev" certificate and key don'"'"'t match; nothing was uploaded.' >&2
    exit 1
  fi
  openssl pkcs12 -export -legacy -in "$tmp/dev.pem" -name "Droidtosh Dev" -passout "pass:$password" -out "$tmp/dev.p12" 2>/dev/null \
    || openssl pkcs12 -export -in "$tmp/dev.pem" -name "Droidtosh Dev" -passout "pass:$password" -out "$tmp/dev.p12"
  echo "Uploading the \"Droidtosh Dev\" identity ($(openssl x509 -in "$tmp/dev.pem" -noout -fingerprint -sha1 | cut -d= -f2 | tr -d :))."
  base64 -i "$tmp/dev.p12" | gh secret set MAC_SIGNING_P12_BASE64 ${repo[@]+"${repo[@]}"}
  printf '%s' "$password" | gh secret set MAC_SIGNING_P12_PASSWORD ${repo[@]+"${repo[@]}"}
fi
echo "Done."
