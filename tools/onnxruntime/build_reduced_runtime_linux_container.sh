#!/usr/bin/env bash
set -euo pipefail

IMAGE="${LINUX_RUNTIME_IMAGE:-ubuntu:20.04}"
HOST_UID="$(id -u)"
HOST_GID="$(id -g)"
: "${JAVA_HOME:?JAVA_HOME must point to a host JDK 21 installation}"

docker run --rm \
  -e DEBIAN_FRONTEND=noninteractive \
  -e HOST_UID="$HOST_UID" \
  -e HOST_GID="$HOST_GID" \
  -e PARALLEL="${PARALLEL:-4}" \
  -e PYTHON=python3 \
  -v "$JAVA_HOME:/jdk:ro" \
  -v "$PWD:/workspace" \
  -w /workspace \
  "$IMAGE" \
  bash -lc '
    set -euo pipefail
    export JAVA_HOME=/jdk
    export PATH="$JAVA_HOME/bin:$PATH"
    apt-get update
    apt-get install -y --no-install-recommends \
      binutils \
      build-essential \
      ca-certificates \
      ca-certificates-java \
      curl \
      gcc-10 \
      git \
      g++-10 \
      perl \
      python3 \
      python3-pip \
      unzip \
      zip
    update-ca-certificates -f
    python3 -m pip install --disable-pip-version-check "cmake<4" flatbuffers
    export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts ${JAVA_TOOL_OPTIONS:-}"
    if [[ "$(uname -m)" == "aarch64" || "$(uname -m)" == "arm64" ]]; then
      export CC=gcc-10
      export CXX=g++-10
    fi
    PARALLEL="${PARALLEL:-4}" PYTHON=python3 ALLOW_RUNNING_AS_ROOT=1 bash tools/onnxruntime/build_reduced_runtime_unix.sh
    chown -R "$HOST_UID:$HOST_GID" build runtime
  '
