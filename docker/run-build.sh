#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE_TAG="${IMAGE_TAG:-usboss-build:latest}"
ARTIFACT_ROOT="${ARTIFACT_ROOT:-${ROOT_DIR}/build-artifacts}"
CACHE_ROOT="${CACHE_ROOT:-${ROOT_DIR}/.docker-cache}"
HOST_UID="${SUDO_UID:-$(id -u)}"
HOST_GID="${SUDO_GID:-$(id -g)}"

mkdir -p \
    "${ARTIFACT_ROOT}/android" \
    "${ARTIFACT_ROOT}/linux" \
    "${CACHE_ROOT}/gradle" \
    "${CACHE_ROOT}/cargo/registry" \
    "${CACHE_ROOT}/cargo/git"

if [[ -n "${SUDO_UID:-}" && -n "${SUDO_GID:-}" ]]; then
    chown -R "${SUDO_UID}:${SUDO_GID}" "${ARTIFACT_ROOT}" "${CACHE_ROOT}"
fi

docker build \
    --build-arg "USER_ID=${HOST_UID}" \
    --build-arg "GROUP_ID=${HOST_GID}" \
    --tag "${IMAGE_TAG}" \
    --file "${ROOT_DIR}/docker/Dockerfile" \
    "${ROOT_DIR}"

docker_args=(
    run
    --rm
    --volume "${ROOT_DIR}:/workspace"
    --volume "${ARTIFACT_ROOT}:/artifacts"
    --volume "${CACHE_ROOT}/gradle:/home/builder/.gradle"
    --volume "${CACHE_ROOT}/cargo/registry:/home/builder/.cargo/registry"
    --volume "${CACHE_ROOT}/cargo/git:/home/builder/.cargo/git"
    --env "ANDROID_GRADLE_TASK=${ANDROID_GRADLE_TASK:-:app:assembleDebug}"
)

if [[ -n "${CARGO_BUILD_TARGET:-}" ]]; then
    docker_args+=(--env "CARGO_BUILD_TARGET=${CARGO_BUILD_TARGET}")
fi

docker_args+=("${IMAGE_TAG}")
docker_args+=("$@")

docker "${docker_args[@]}"
