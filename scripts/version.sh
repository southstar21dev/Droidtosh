#!/usr/bin/env bash
# The app version and its build number, the one place both are computed (docs/RELEASING.md).
#
#   scripts/version.sh [VERSION]   prints "<version> <build>"
#
# VERSION is a semantic version, X.Y.Z or X.Y.Z-(alpha|beta|rc).N, optionally with a leading "v"
# (a tag). Without an argument it is read from the VERSION file, and the local build gets the
# build metadata "+<commit count>.<short sha>", which semver ignores when ordering versions.
#
# The build number is Android's versionCode and the Mac's CFBundleVersion. It rises with every
# version in semver order: X*10000000 + Y*100000 + Z*1000 + a pre-release rank, where alpha.N is N,
# beta.N is 300+N, rc.N is 600+N and a final release is 999. So Y and Z go up to 99, N up to 299.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
local_build=0
if [ $# -ge 1 ] && [ -n "$1" ]; then
  version="${1#v}"
else
  version="$(tr -d '[:space:]' < "$root/VERSION")"
  local_build=1
fi

pattern='^([0-9]+)\.([0-9]+)\.([0-9]+)(-(alpha|beta|rc)\.([0-9]+))?$'
if [[ ! "$version" =~ $pattern ]]; then
  echo "version.sh: \"$version\" is not X.Y.Z or X.Y.Z-(alpha|beta|rc).N" >&2
  exit 1
fi
major=$((10#${BASH_REMATCH[1]}))
minor=$((10#${BASH_REMATCH[2]}))
patch=$((10#${BASH_REMATCH[3]}))
stage="${BASH_REMATCH[5]}"
number="${BASH_REMATCH[6]}"

if [ "$major" -gt 213 ] || [ "$minor" -gt 99 ] || [ "$patch" -gt 99 ]; then
  echo "version.sh: $version is out of range (major ≤ 213, minor and patch ≤ 99)" >&2
  exit 1
fi
case "$stage" in
  "") rank=999 ;;
  alpha) rank=$((10#$number)) ;;
  beta) rank=$((300 + 10#$number)) ;;
  rc) rank=$((600 + 10#$number)) ;;
esac
if [ -n "$stage" ] && { [ "$((10#$number))" -lt 1 ] || [ "$((10#$number))" -gt 299 ]; }; then
  echo "version.sh: the pre-release number in $version must be 1–299" >&2
  exit 1
fi
build=$((major * 10000000 + minor * 100000 + patch * 1000 + rank))

if [ "$local_build" = 1 ] && git -C "$root" rev-parse HEAD >/dev/null 2>&1; then
  version="$version+$(git -C "$root" rev-list --count HEAD).$(git -C "$root" rev-parse --short HEAD)"
fi
echo "$version $build"
