#!/usr/bin/env bash
# Release notes and the CHANGELOG for semantic-release (.releaserc.json, docs/RELEASING.md).
#
#   scripts/release-changelog.sh notes [LAST_TAG]
#       prints the release notes: the Unreleased section of CHANGELOG.md, or, when it is empty,
#       the feat/fix/perf commit subjects since LAST_TAG
#   scripts/release-changelog.sh prepare VERSION [LAST_TAG]
#       writes VERSION and moves those notes under "## VERSION – <today>" in CHANGELOG.md
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
changelog="$root/CHANGELOG.md"

unreleased() {
  # Lines between "## Unreleased" and the next "## " heading, without leading/trailing blanks.
  awk '/^## Unreleased[[:space:]]*$/ {on=1; next} on && /^## / {exit} on' "$changelog" |
    sed -e :a -e '/^\n*$/{$d;N;ba' -e '}' | sed '/./,$!d'
}

notes() {
  local last="${1:-}" body
  body="$(unreleased)"
  if [ -z "$body" ]; then
    local range="HEAD"
    [ -n "$last" ] && range="$last..HEAD"
    body="$(git -C "$root" log --no-merges --format='%s' "$range" |
      grep -E '^(feat|fix|perf)(\([^)]*\))?!?: ' |
      sed -E 's/^[a-z]+(\([^)]*\))?!?: //' |
      awk '{ print "- " toupper(substr($0, 1, 1)) substr($0, 2) "." }')" || true
  fi
  printf '%s\n' "$body"
}

case "${1:-}" in
  notes)
    notes "${2:-}"
    ;;
  prepare)
    version="${2:?usage: $0 prepare VERSION [LAST_TAG]}"
    body="$(notes "${3:-}")"
    printf '%s\n' "$version" > "$root/VERSION"
    tmp="$(mktemp)"
    HEADING="## $version – $(date -u +%Y-%m-%d)" BODY="$body" awk '
      /^## Unreleased[[:space:]]*$/ {
        print; print ""; print ENVIRON["HEADING"]; print ""
        if (ENVIRON["BODY"] != "") { print ENVIRON["BODY"]; print "" }
        skip=1; next
      }
      skip && /^## / { skip=0 }
      !skip
    ' "$changelog" > "$tmp"
    cat "$tmp" > "$changelog"
    rm -f "$tmp"
    ;;
  *)
    echo "usage: $0 notes [LAST_TAG] | prepare VERSION [LAST_TAG]" >&2
    exit 1
    ;;
esac
