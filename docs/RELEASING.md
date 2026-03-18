# Releasing USBoss

This guide assumes you are running on the Linux machine that already builds `USBoss` successfully with Docker.

The easiest path is the release helper script:

```bash
chmod +x scripts/release.sh
./scripts/release.sh v0.1.1 --notes-file docs/releases/v0.1.1.md
```

By default, that script will:

1. build both artifacts with [docker/run-build.sh](/Users/nick.valente/.dev/usboss/docker/run-build.sh)
2. package release assets into `release-assets/<version>/`
3. generate default release notes
4. create and push the git tag
5. create a **draft** GitHub release with downloadable assets

That default is intentional. A draft release gives you one last chance to inspect the notes and assets before publishing.

## Running under sudo

If your Docker setup requires `sudo`, the helper now supports that cleanly:

```bash
sudo ./scripts/release.sh v0.1.1 --notes-file docs/releases/v0.1.1.md
```

When run this way, the script will:

- run the Docker build as root
- package the release assets
- hand ownership of `release-assets/<version>/` back to your original user
- run `git` and `gh` as the original invoking user from `SUDO_USER`

That means your GitHub login should remain the normal one you already configured as `nicholas`.

Important:

- make sure you already ran `gh auth login` as your normal user before using `sudo`
- the script expects `sudo` to preserve `SUDO_USER`, which is the normal behavior
- if possible, adding your user to the `docker` group is still the cleaner long-term setup

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
./scripts/release.sh v0.1.1 --notes-file docs/releases/v0.1.1.md
```

The resulting release assets will be:

- `release-assets/v0.1.1/usboss-v0.1.1-android-debug.apk`
- `release-assets/v0.1.1/usboss-client-v0.1.1-linux-x86_64.tar.gz`
- `release-assets/v0.1.1/SHA256SUMS.txt`
- `release-assets/v0.1.1/RELEASE_NOTES.md`

If you pass `--notes-file`, the helper copies that file into the release bundle and uses it for the GitHub release body.

If you do not pass `--notes-file`, the helper generates a simple fallback notes file that can still be edited later either:

- in the local `RELEASE_NOTES.md` before rerunning, or
- directly in the GitHub draft release page

## Useful options

### Publish immediately

```bash
./scripts/release.sh v0.1.1 --notes-file docs/releases/v0.1.1.md --publish
```

### Reuse existing build artifacts

This is useful if you already built with Docker and only want to recreate the packaged assets or release:

```bash
./scripts/release.sh v0.1.1 --skip-build --notes-file docs/releases/v0.1.1.md
```

### Use your own notes file

```bash
./scripts/release.sh v0.1.1 --notes-file /path/to/release-notes.md
```

### Package assets only

This is the safest dry run:

```bash
./scripts/release.sh v0.1.1 --assets-only --notes-file docs/releases/v0.1.1.md
```

That stops before any `git push`, tag push, or GitHub release creation.

## Recommended workflow for v0.1.1

For your current state, I recommend:

```bash
sudo ./scripts/release.sh v0.1.1 --notes-file docs/releases/v0.1.1.md
```

Then:

1. open the draft release in GitHub
2. confirm the APK and Linux tarball uploaded correctly
3. skim the generated notes
4. click publish

This keeps the automation high while still giving you a safe final review step.

## Notes about the APK

For `v0.1.1`, the Android asset is intentionally packaged as a debug APK:

- filename: `usboss-v0.1.1-android-debug.apk`
- intended for sideloading and known-good testing

That is still acceptable for the current release. A later release can switch to a proper signed release APK if you want a more polished Android distribution story.
