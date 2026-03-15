# Build USBoss With Docker

This document describes the fully containerized build path for `USBoss`.

The Docker setup does three things:

- creates a reproducible Android + Rust build environment
- mounts a host artifact directory
- builds the Linux client and Android APK and copies the final outputs into that mounted directory

## What gets built

By default, the container builds:

- the Linux client binary from [linux-client](/Users/nick.valente/.dev/usboss/linux-client)
- the Android debug APK from [android-host](/Users/nick.valente/.dev/usboss/android-host)

The copied artifacts end up under:

- `build-artifacts/linux/usboss-client`
- `build-artifacts/android/app-debug.apk`
- `build-artifacts/SHA256SUMS`

## Prerequisites

On your Linux machine, install:

- Docker Engine
- internet access for the first image build and first Gradle dependency resolution

Optional but useful:

- `adb` if you want to sideload the APK onto the Shield immediately after building

## Files involved

- [docker/Dockerfile](/Users/nick.valente/.dev/usboss/docker/Dockerfile): the build image
- [docker/build-inside-container.sh](/Users/nick.valente/.dev/usboss/docker/build-inside-container.sh): runs inside the container and performs the actual build
- [docker/run-build.sh](/Users/nick.valente/.dev/usboss/docker/run-build.sh): host-side helper that builds the image, mounts volumes, and runs the build

## Fast path

From the repository root:

```bash
chmod +x docker/run-build.sh
./docker/run-build.sh
```

That command will:

1. build the Docker image
2. create `build-artifacts/` and `.docker-cache/` if they do not already exist
3. mount the repo into `/workspace` inside the container
4. mount `build-artifacts/` into `/artifacts`
5. build both USBoss targets
6. copy the final outputs into the mounted artifact directory on your host

## Output locations

After a successful run, check:

```bash
ls -lah build-artifacts/android
ls -lah build-artifacts/linux
cat build-artifacts/SHA256SUMS
```

Expected files:

- `build-artifacts/android/app-debug.apk`
- `build-artifacts/linux/usboss-client`

## Build only one target

### Linux only

```bash
./docker/run-build.sh --linux-only
```

### Android only

```bash
./docker/run-build.sh --android-only
```

## Build a different Android task

The default is:

```bash
ANDROID_GRADLE_TASK=:app:assembleDebug
```

To build a release APK:

```bash
ANDROID_GRADLE_TASK=:app:assembleRelease ./docker/run-build.sh --android-only
```

Important:

- this repo does not currently define a release signing config
- `assembleRelease` will produce an unsigned release artifact unless you add signing settings first
- for sideload testing on the Shield, the debug APK is usually the easiest path

## Build for a different Linux Rust target

By default, the Rust binary is built for the architecture of the Docker host.

If your Linux server needs a different target:

```bash
CARGO_BUILD_TARGET=x86_64-unknown-linux-gnu ./docker/run-build.sh --linux-only
```

The copied artifact is still written to:

- `build-artifacts/linux/usboss-client`

## Manual Docker commands

If you do not want to use the helper script, you can run Docker directly.

### 1. Build the image

```bash
docker build \
  --build-arg USER_ID="$(id -u)" \
  --build-arg GROUP_ID="$(id -g)" \
  -t usboss-build:latest \
  -f docker/Dockerfile \
  .
```

### 2. Create host directories

```bash
mkdir -p build-artifacts/android build-artifacts/linux
mkdir -p .docker-cache/gradle .docker-cache/cargo/registry .docker-cache/cargo/git
```

### 3. Run the build container

```bash
docker run --rm \
  -v "$(pwd):/workspace" \
  -v "$(pwd)/build-artifacts:/artifacts" \
  -v "$(pwd)/.docker-cache/gradle:/home/builder/.gradle" \
  -v "$(pwd)/.docker-cache/cargo/registry:/home/builder/.cargo/registry" \
  -v "$(pwd)/.docker-cache/cargo/git:/home/builder/.cargo/git" \
  -e ANDROID_GRADLE_TASK=:app:assembleDebug \
  usboss-build:latest
```

To build only Linux:

```bash
docker run --rm \
  -v "$(pwd):/workspace" \
  -v "$(pwd)/build-artifacts:/artifacts" \
  -v "$(pwd)/.docker-cache/gradle:/home/builder/.gradle" \
  -v "$(pwd)/.docker-cache/cargo/registry:/home/builder/.cargo/registry" \
  -v "$(pwd)/.docker-cache/cargo/git:/home/builder/.cargo/git" \
  usboss-build:latest \
  --linux-only
```

To build only Android:

```bash
docker run --rm \
  -v "$(pwd):/workspace" \
  -v "$(pwd)/build-artifacts:/artifacts" \
  -v "$(pwd)/.docker-cache/gradle:/home/builder/.gradle" \
  -v "$(pwd)/.docker-cache/cargo/registry:/home/builder/.cargo/registry" \
  -v "$(pwd)/.docker-cache/cargo/git:/home/builder/.cargo/git" \
  usboss-build:latest \
  --android-only
```

## Sideload the built APK

Once the Android build is done:

```bash
adb connect SHIELD_IP:5555
adb install -r build-artifacts/android/app-debug.apk
```

## Common issues

### Docker permission denied

If Docker requires root on your Linux machine, run the helper with `sudo`:

```bash
sudo ./docker/run-build.sh
```

The helper script preserves the original caller’s UID and GID when it detects `sudo`, so the copied artifacts should still land with your normal user ownership. The cleaner long-term fix is usually to add your user to the `docker` group.

### Slow first build

The first run is slow because it needs to:

- build the Docker image
- fetch Android Maven dependencies
- warm Gradle and Cargo caches

Subsequent runs should be noticeably faster because `.docker-cache/` is mounted back into the container.

### No APK found

If the container says no APK was produced:

- check that Gradle completed successfully
- verify the task in `ANDROID_GRADLE_TASK`
- use `:app:assembleDebug` first before trying custom tasks

### Want cleaner host output

The final copied deliverables are always in `build-artifacts/`, but Gradle and Cargo still write their normal intermediate build directories under the repo:

- `android-host/app/build/`
- `linux-client/target/`

That is expected.
