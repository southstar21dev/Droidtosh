#!/usr/bin/env bash
# Create the Android release key once (docs/RELEASING.md): a PKCS#12 keystore with a random
# password at ~/.config/droidtosh/release.p12, and android/keystore.properties pointing at it.
# Back up both files: without the key, installed apps can never be updated again.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
dir="$HOME/.config/droidtosh"
store="$dir/release.p12"
properties="$root/android/keystore.properties"
alias="droidtosh"

if [ -e "$store" ]; then
  echo "$store already exists; not replacing it." >&2
  exit 1
fi
mkdir -p "$dir"
chmod 700 "$dir"
password="$(openssl rand -base64 30 | tr -d '/+=' | cut -c1-32)"
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
"$JAVA_HOME/bin/keytool" -genkeypair -v -storetype PKCS12 -keystore "$store" \
  -alias "$alias" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$password" -keypass "$password" \
  -dname "CN=Droidtosh, O=southstar21" >/dev/null
chmod 600 "$store"
umask 077
cat > "$properties" <<PROPS
storeFile=$store
storePassword=$password
keyAlias=$alias
PROPS
echo "Created $store and $properties."
echo "Back up both, and run scripts/upload-release-secrets.sh to give CI the key."
"$JAVA_HOME/bin/keytool" -list -keystore "$store" -storepass "$password" | grep "SHA-256"
