# Releasing USBoss

This guide assumes you are running on the Linux machine that already builds `USBoss` successfully with Docker.

The easiest path is the release helper script:

```bash
chmod +x scripts/release.sh
./scripts/release.sh v0.1.0
```

By default, that script will:

1. build both artifacts with [docker/run-build.sh](/Users/nick.valente/.dev/usboss/docker/run-build.sh)
2. package release assets into `release-assets/v0.1.0/`
3. generate default release notes
4. create and push the git tag
5. create a **draft** GitHub release with downloadable assets

That default is intentional. A draft release gives you one last chance to inspect the notes and assets before publishing.

## Prerequisites

Install and authenticate:

```bash
gh auth login
gh auth status
docker --version
git --version
```

Make sure the repo is clean before releasing:

```bash
git status
```

If the working tree is not clean, either commit the changes first or use `--allow-dirty` only if you understand the risk.

## Default release flow

Run:

```bash
./scripts/release.sh v0.1.0
```

The resulting release assets will be:

- `release-assets/v0.1.0/usboss-v0.1.0-android-debug.apk`
- `release-assets/v0.1.0/usboss-client-v0.1.0-linux-x86_64.tar.gz`
- `release-assets/v0.1.0/SHA256SUMS.txt`
- `release-assets/v0.1.0/RELEASE_NOTES.md`

The generated release notes are good enough for a first known-good release and can be edited later either:

- in the local `RELEASE_NOTES.md` before rerunning, or
- directly in the GitHub draft release page

## Useful options

### Publish immediately

```bash
./scripts/release.sh v0.1.0 --publish
```

### Reuse existing build artifacts

This is useful if you already built with Docker and only want to recreate the packaged assets or release:

```bash
./scripts/release.sh v0.1.0 --skip-build
```

### Use your own notes file

```bash
./scripts/release.sh v0.1.0 --notes-file /path/to/release-notes.md
```

### Package assets only

This is the safest dry run:

```bash
./scripts/release.sh v0.1.0 --assets-only
```

That stops before any `git push`, tag push, or GitHub release creation.

## Recommended workflow for v0.1.0

For your current state, I recommend:

```bash
./scripts/release.sh v0.1.0
```

Then:

1. open the draft release in GitHub
2. confirm the APK and Linux tarball uploaded correctly
3. skim the generated notes
4. click publish

This keeps the automation high while still giving you a safe final review step.

## Notes about the APK

For `v0.1.0`, the Android asset is intentionally packaged as a debug APK:

- filename: `usboss-v0.1.0-android-debug.apk`
- intended for sideloading and known-good testing

That is fine for the first release. For a more polished `v0.1.1`, you will probably want a proper signed release build.
