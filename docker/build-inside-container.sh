#!/usr/bin/env bash

set -euo pipefail

WORKSPACE_DIR="${WORKSPACE_DIR:-/workspace}"
ARTIFACT_ROOT="${ARTIFACT_ROOT:-/artifacts}"
ANDROID_ARTIFACT_DIR="${ANDROID_ARTIFACT_DIR:-${ARTIFACT_ROOT}/android}"
LINUX_ARTIFACT_DIR="${LINUX_ARTIFACT_DIR:-${ARTIFACT_ROOT}/linux}"
ANDROID_GRADLE_TASK="${ANDROID_GRADLE_TASK:-:app:assembleDebug}"

if [[ -z "${CARGO_BUILD_TARGET:-}" ]]; then
    unset CARGO_BUILD_TARGET || true
fi

BUILD_ANDROID=1
BUILD_LINUX=1

for arg in "$@"; do
    case "$arg" in
        --android-only)
            BUILD_LINUX=0
            ;;
        --linux-only)
            BUILD_ANDROID=0
            ;;
        *)
            echo "Unknown argument: $arg" >&2
            echo "Supported arguments: --android-only, --linux-only" >&2
            exit 1
            ;;
    esac
done

mkdir -p "${ANDROID_ARTIFACT_DIR}" "${LINUX_ARTIFACT_DIR}"

if [[ "${BUILD_LINUX}" == "1" ]]; then
    echo "==> Building Linux client"
    cargo_cmd=(cargo build --release --manifest-path "${WORKSPACE_DIR}/linux-client/Cargo.toml")
    linux_binary_path="${WORKSPACE_DIR}/linux-client/target/release/usboss-client"

    if [[ -n "${CARGO_BUILD_TARGET:-}" ]]; then
        cargo_cmd+=(--target "${CARGO_BUILD_TARGET}")
        linux_binary_path="${WORKSPACE_DIR}/linux-client/target/${CARGO_BUILD_TARGET}/release/usboss-client"
    fi

    "${cargo_cmd[@]}"

    install -Dm755 "${linux_binary_path}" "${LINUX_ARTIFACT_DIR}/usboss-client"
fi

if [[ "${BUILD_ANDROID}" == "1" ]]; then
    echo "==> Building Android host APK"
    gradle -p "${WORKSPACE_DIR}/android-host" "${ANDROID_GRADLE_TASK}"

    apk_path="$(
        find "${WORKSPACE_DIR}/android-host/app/build/outputs/apk" -type f -name '*.apk' -printf '%T@ %p\n' \
            | sort -n \
            | tail -n 1 \
            | cut -d' ' -f2-
    )"

    if [[ -z "${apk_path}" ]]; then
        echo "No APK was produced by Gradle task ${ANDROID_GRADLE_TASK}" >&2
        exit 1
    fi

    install -Dm644 "${apk_path}" "${ANDROID_ARTIFACT_DIR}/$(basename "${apk_path}")"
fi

if compgen -G "${ARTIFACT_ROOT}/android/*" >/dev/null || compgen -G "${ARTIFACT_ROOT}/linux/*" >/dev/null; then
    (
        cd "${ARTIFACT_ROOT}"
        find android linux -maxdepth 1 -type f -print0 \
            | sort -z \
            | xargs -0 sha256sum > SHA256SUMS
    )
fi

echo "==> Build complete"
echo "Android artifacts: ${ANDROID_ARTIFACT_DIR}"
echo "Linux artifacts: ${LINUX_ARTIFACT_DIR}"
