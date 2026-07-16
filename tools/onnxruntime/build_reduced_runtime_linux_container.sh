#!/usr/bin/env bash
set -euo pipefail

IMAGE="${LINUX_RUNTIME_IMAGE:-eclipse-temurin:21-jdk-focal}"
HOST_UID="$(id -u)"
HOST_GID="$(id -g)"

docker run --rm \
  -e DEBIAN_FRONTEND=noninteractive \
  -e HOST_UID="$HOST_UID" \
  -e HOST_GID="$HOST_GID" \
  -e PARALLEL="${PARALLEL:-4}" \
  -e PYTHON=python3 \
  -v "$PWD:/workspace" \
  -w /workspace \
  "$IMAGE" \
  bash -lc '
    set -euo pipefail
    apt-get update
    apt-get install -y --no-install-recommends \
      binutils \
      build-essential \
      ca-certificates \
      curl \
      git \
      perl \
      python3 \
      python3-pip \
      unzip \
      zip
    python3 -m pip install --disable-pip-version-check "cmake<4" flatbuffers
    PARALLEL="${PARALLEL:-4}" PYTHON=python3 bash tools/onnxruntime/build_reduced_runtime_unix.sh
    chown -R "$HOST_UID:$HOST_GID" build runtime
  '
