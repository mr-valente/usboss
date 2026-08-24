#!/usr/bin/env bash
set -euo pipefail

# Sets the USBoss version everywhere it is declared, so the Linux client and the
# Android host cannot drift apart or fall behind the released tag.

usage() {
  cat <<'USAGE'
Usage:
  ./scripts/set-version.sh <version> [--version-code N]

Examples:
  ./scripts/set-version.sh v0.2.2
  ./scripts/set-version.sh 0.3.0 --version-code 6

Updates:
  - linux-client/Cargo.toml       package version
  - linux-client/Cargo.lock       usboss-client entry
  - android-host/app/build.gradle.kts  versionName and versionCode

The Android versionCode is incremented by one unless --version-code is given.
Commit the result before running scripts/release.sh.
USAGE
}

die() {
  echo "set-version.sh: $*" >&2
  exit 1
}

main() {
  if [[ $# -lt 1 ]]; then
    usage
    exit 1
  fi

  local raw_version="$1"
  shift
  local requested_code=""

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --version-code)
        shift
        [[ $# -gt 0 ]] || die "--version-code requires a number"
        requested_code="$1"
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        die "unknown option: $1"
        ;;
    esac
    shift
  done

  local version="${raw_version#v}"
  [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
    die "version must look like 0.2.2 or v0.2.2, got: $raw_version"

  local root_dir
  root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  cd "$root_dir"

  local cargo_toml="linux-client/Cargo.toml"
  local cargo_lock="linux-client/Cargo.lock"
  local gradle_file="android-host/app/build.gradle.kts"

  for file in "$cargo_toml" "$gradle_file"; do
    [[ -f "$file" ]] || die "missing file: $file"
  done

  local current_code
  current_code="$(grep -m1 -oP 'versionCode = \K[0-9]+' "$gradle_file")" ||
    die "could not read versionCode from $gradle_file"

  local version_code="${requested_code:-$((current_code + 1))}"
  [[ "$version_code" =~ ^[0-9]+$ ]] || die "version code must be a number, got: $version_code"
  if [[ "$version_code" -lt "$current_code" ]]; then
    die "version code $version_code is lower than the current $current_code; Android refuses downgrades"
  fi

  # Cargo package version: the first bare `version =` line, inside [package].
  sed -i "0,/^version = \".*\"/s//version = \"$version\"/" "$cargo_toml"

  if [[ -f "$cargo_lock" ]]; then
    awk -v version="$version" '
      $0 == "name = \"usboss-client\"" { seen = 1 }
      seen && /^version = "/ { print "version = \"" version "\""; seen = 0; next }
      { print }
    ' "$cargo_lock" > "$cargo_lock.tmp"
    mv "$cargo_lock.tmp" "$cargo_lock"
  fi

  sed -i "s/versionName = \".*\"/versionName = \"$version\"/" "$gradle_file"
  sed -i "s/versionCode = [0-9]\+/versionCode = $version_code/" "$gradle_file"

  echo "==> Version set to $version (Android versionCode $version_code)"
  grep -m1 '^version = ' "$cargo_toml"
  grep -m1 'versionCode = ' "$gradle_file"
  grep -m1 'versionName = ' "$gradle_file"
  echo
  echo "Commit these changes, then run: ./scripts/release.sh v$version"
}

main "$@"
