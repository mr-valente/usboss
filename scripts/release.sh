#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  ./scripts/release.sh <version> [options]

Examples:
  ./scripts/release.sh v0.2.0 --notes-file docs/releases/v0.2.0.md
  ./scripts/release.sh 0.2.0 --publish --notes-file /path/to/notes.md
  ./scripts/release.sh v0.2.0 --skip-build --notes-file /path/to/notes.md
  ./scripts/release.sh v0.2.0 --assets-only

What it does:
  1. Builds USBoss with the Docker build helper unless --skip-build is passed
  2. Packages the known-good artifacts into release-assets/<version>/
  3. Generates default release notes unless --notes-file is provided
  4. Creates/pushes an annotated git tag
  5. Creates a GitHub release with downloadable assets through gh

Default behavior:
  - creates a draft GitHub release
  - uses the current checked-out commit
  - requires a clean git working tree

Options:
  --publish        Publish the GitHub release immediately instead of creating a draft
  --skip-build     Reuse existing build-artifacts/ outputs
  --notes-file     Use an existing markdown file for release notes
  --assets-only    Stop after packaging assets and writing notes/checksums
  --allow-dirty    Skip the clean-working-tree check
  -h, --help       Show this help text
EOF
}

die() {
  echo "release.sh: $*" >&2
  exit 1
}

release_user() {
  if [[ "$(id -u)" -eq 0 && -n "${SUDO_USER:-}" ]]; then
    printf '%s\n' "${SUDO_USER}"
  else
    id -un
  fi
}

release_group() {
  if [[ "$(id -u)" -eq 0 && -n "${SUDO_GID:-}" ]]; then
    printf '%s\n' "${SUDO_GID}"
  else
    id -g
  fi
}

release_uid() {
  if [[ "$(id -u)" -eq 0 && -n "${SUDO_UID:-}" ]]; then
    printf '%s\n' "${SUDO_UID}"
  else
    id -u
  fi
}

run_as_release_user() {
  if [[ "$(id -u)" -eq 0 && -n "${SUDO_USER:-}" ]]; then
    sudo -H -u "${SUDO_USER}" env "PATH=${PATH}" "$@"
  else
    "$@"
  fi
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

normalize_version() {
  local raw="$1"
  if [[ "$raw" == v* ]]; then
    printf '%s\n' "$raw"
  else
    printf 'v%s\n' "$raw"
  fi
}

git_clean() {
  run_as_release_user git diff --quiet &&
    run_as_release_user git diff --cached --quiet &&
    [[ -z "$(run_as_release_user git ls-files --others --exclude-standard)" ]]
}

main() {
  if [[ $# -lt 1 ]]; then
    usage
    exit 1
  fi

  local raw_version="$1"
  shift

  local publish=0
  local skip_build=0
  local assets_only=0
  local allow_dirty=0
  local notes_file=""

  while [[ $# -gt 0 ]]; do
    case "$1" in
      --publish)
        publish=1
        ;;
      --skip-build)
        skip_build=1
        ;;
      --assets-only)
        assets_only=1
        ;;
      --allow-dirty)
        allow_dirty=1
        ;;
      --notes-file)
        shift
        [[ $# -gt 0 ]] || die "--notes-file requires a path"
        notes_file="$1"
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

  local version
  version="$(normalize_version "$raw_version")"

  local root_dir
  root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  cd "$root_dir"

  local actor_user
  actor_user="$(release_user)"

  require_cmd git
  require_cmd tar
  require_cmd sha256sum
  require_cmd docker

  if [[ "$assets_only" -eq 0 ]]; then
    run_as_release_user bash -lc 'command -v gh >/dev/null 2>&1' ||
      die "required command not found for release user ${actor_user}: gh"
  fi

  if [[ "$allow_dirty" -eq 0 ]] && ! git_clean; then
    die "git working tree is not clean. Commit or stash changes first, or rerun with --allow-dirty."
  fi

  local branch
  branch="$(run_as_release_user git rev-parse --abbrev-ref HEAD)"
  if [[ "$assets_only" -eq 0 && "$branch" == "HEAD" ]]; then
    die "detached HEAD detected. Check out the release branch/commit normally before creating a release."
  fi

  if [[ "$skip_build" -eq 0 ]]; then
    echo "==> Building USBoss artifacts with Docker"
    ./docker/run-build.sh
  else
    echo "==> Skipping Docker build and reusing existing build-artifacts/"
  fi

  local apk_src="$root_dir/build-artifacts/android/app-debug.apk"
  local linux_src="$root_dir/build-artifacts/linux/usboss-client"
  [[ -f "$apk_src" ]] || die "missing Android artifact: $apk_src"
  [[ -f "$linux_src" ]] || die "missing Linux artifact: $linux_src"

  local arch
  arch="$(uname -m)"
  local release_dir="$root_dir/release-assets/$version"
  local apk_out="$release_dir/usboss-${version}-android-debug.apk"
  local linux_out="$release_dir/usboss-client-${version}-linux-${arch}.tar.gz"
  local sums_out="$release_dir/SHA256SUMS.txt"
  local generated_notes="$release_dir/RELEASE_NOTES.md"

  rm -rf "$release_dir"
  mkdir -p "$release_dir"

  echo "==> Packaging release assets into $release_dir"
  cp "$apk_src" "$apk_out"
  tar -C "$(dirname "$linux_src")" -czf "$linux_out" "$(basename "$linux_src")"
  sha256sum "$apk_out" "$linux_out" > "$sums_out"

  if [[ -n "$notes_file" ]]; then
    [[ -f "$notes_file" ]] || die "notes file not found: $notes_file"
    cp "$notes_file" "$generated_notes"
  else
    cat > "$generated_notes" <<EOF
# USBoss ${version}

USB controller bridge release for Android and Linux.

Tested working with:
- Android host on NVIDIA Shield / Android TV
- Linux client
- 8BitDo Ultimate 2C Wireless Controller via 2.4G USB dongle in XInput mode

Assets:
- \`usboss-${version}-android-debug.apk\`: debug-signed Android APK for sideloading
- \`usboss-client-${version}-linux-${arch}.tar.gz\`: Linux client binary archive
- \`SHA256SUMS.txt\`: checksums for the downloadable binaries

Notes:
- The Android APK is debug-signed for sideload/testing convenience.
- Linux requires \`uinput\` for XInput devices and \`uhid\` for HID devices.
EOF
  fi

  if [[ "$(id -u)" -eq 0 && -n "${SUDO_UID:-}" && -n "${SUDO_GID:-}" ]]; then
    chown -R "$(release_uid):$(release_group)" "$release_dir"
  fi

  echo "==> Release assets ready:"
  ls -lh "$release_dir"

  if [[ "$assets_only" -eq 1 ]]; then
    echo
    echo "Assets-only mode complete."
    echo "Edit notes here if needed: $generated_notes"
    exit 0
  fi

  echo "==> Using release user: $actor_user"
  run_as_release_user gh auth status >/dev/null

  if run_as_release_user gh release view "$version" >/dev/null 2>&1; then
    die "GitHub release $version already exists. Delete/edit it first or choose a different version."
  fi

  if run_as_release_user git rev-parse --verify "refs/tags/$version" >/dev/null 2>&1; then
    local tagged_commit
    local head_commit
    tagged_commit="$(run_as_release_user git rev-list -n 1 "$version")"
    head_commit="$(run_as_release_user git rev-parse HEAD)"
    [[ "$tagged_commit" == "$head_commit" ]] || die "tag $version already exists but does not point at HEAD."
    echo "==> Reusing existing local tag $version"
  else
    echo "==> Creating annotated tag $version"
    run_as_release_user git tag -a "$version" -m "USBoss $version"
  fi

  echo "==> Pushing branch $branch"
  run_as_release_user git push origin "$branch"

  echo "==> Pushing tag $version"
  run_as_release_user git push origin "$version"

  local -a release_args=(
    release create "$version"
    "$apk_out#USBoss Android APK (debug, sideloadable)"
    "$linux_out#USBoss Linux client (${arch})"
    "$sums_out#SHA256 checksums"
    --verify-tag
    --latest
    --title "USBoss $version"
    --notes-file "$generated_notes"
  )

  if [[ "$publish" -eq 0 ]]; then
    release_args+=(--draft)
  fi

  echo "==> Creating GitHub release $version"
  run_as_release_user gh "${release_args[@]}"

  echo
  if [[ "$publish" -eq 0 ]]; then
    echo "Draft release created for $version."
    echo "Review/edit it on GitHub, then publish when ready."
  else
    echo "Published release $version."
  fi
  echo "Release notes file: $generated_notes"
}

main "$@"
