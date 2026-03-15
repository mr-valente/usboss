FROM eclipse-temurin:17-jdk-jammy

ARG USER_ID=1000
ARG GROUP_ID=1000
ARG GRADLE_VERSION=8.7
ARG ANDROID_CMDLINE_TOOLS_VERSION=11076708

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV ANDROID_HOME=/opt/android-sdk
ENV GRADLE_HOME=/opt/gradle
ENV PATH=/opt/gradle/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/home/builder/.cargo/bin:${PATH}

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash \
    ca-certificates \
    curl \
    git \
    g++ \
    lib32stdc++6 \
    lib32z1 \
    make \
    pkg-config \
    unzip \
    xz-utils \
    zip \
    && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && mv "/opt/gradle-${GRADLE_VERSION}" "${GRADLE_HOME}" \
    && rm /tmp/gradle.zip

RUN mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip" -o /tmp/android-cmdline-tools.zip \
    && unzip -q /tmp/android-cmdline-tools.zip -d "${ANDROID_SDK_ROOT}/cmdline-tools" \
    && mv "${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools" "${ANDROID_SDK_ROOT}/cmdline-tools/latest" \
    && rm /tmp/android-cmdline-tools.zip

RUN set +o pipefail \
    && yes | sdkmanager --sdk_root="${ANDROID_SDK_ROOT}" --licenses >/dev/null \
    && set -o pipefail \
    && sdkmanager --sdk_root="${ANDROID_SDK_ROOT}" \
        "build-tools;34.0.0" \
        "build-tools;35.0.0" \
        "platform-tools" \
        "platforms;android-34" \
        "platforms;android-35"

RUN if ! getent group "${GROUP_ID}" >/dev/null; then groupadd --gid "${GROUP_ID}" builder; fi \
    && useradd --uid "${USER_ID}" --gid "${GROUP_ID}" --create-home --shell /bin/bash builder \
    && chown -R "${USER_ID}:${GROUP_ID}" "${ANDROID_SDK_ROOT}"

COPY docker/build-inside-container.sh /usr/local/bin/build-usboss

RUN chmod +x /usr/local/bin/build-usboss \
    && chown "${USER_ID}:${GROUP_ID}" /usr/local/bin/build-usboss

USER builder
WORKDIR /workspace

ENV GRADLE_USER_HOME=/home/builder/.gradle
ENV CARGO_HOME=/home/builder/.cargo
ENV RUSTUP_HOME=/home/builder/.rustup

RUN curl https://sh.rustup.rs -sSf | bash -s -- -y --profile minimal --default-toolchain stable

ENTRYPOINT ["/usr/local/bin/build-usboss"]
