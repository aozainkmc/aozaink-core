#!/usr/bin/env bash
set -euo pipefail

ORT_TAG="v1.20.0"
ORT_VERSION="1.20.0"
EIGEN_COMMIT="e7248b26a1ed53fa030c5c459f7ea095dfd276ac"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BUILD_ROOT="$REPOSITORY_ROOT/build/onnxruntime-reduced"
ORT_SOURCE="$BUILD_ROOT/onnxruntime-$ORT_TAG"
EIGEN_SOURCE="$BUILD_ROOT/eigen-$EIGEN_COMMIT"
ORT_BUILD="$BUILD_ROOT/native"
CONFIG="$SCRIPT_DIR/required_operators_and_types.config"
SMOKE_SOURCE="$SCRIPT_DIR/RuntimeSmoke.java"
MODEL="$REPOSITORY_ROOT/src/main/resources/assets/aozaink_core/ocr/mix_flash_v1/unified_dynamic.onnx"
PARALLEL="${PARALLEL:-4}"
PYTHON="${PYTHON:-python3}"

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) PLATFORM="linux-x64" ;;
  Linux-aarch64|Linux-arm64) PLATFORM="linux-aarch64" ;;
  Darwin-x86_64) PLATFORM="osx-x64" ;;
  Darwin-arm64) PLATFORM="osx-aarch64" ;;
  *) echo "Unsupported build host: $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

RUNTIME_OUTPUT="$REPOSITORY_ROOT/runtime/reduced/$PLATFORM/onnxruntime-$ORT_VERSION-reduced.jar"

for required in "$CONFIG" "$SMOKE_SOURCE" "$MODEL"; do
  test -f "$required" || { echo "Required file is missing: $required" >&2; exit 1; }
done
command -v "$PYTHON" >/dev/null
command -v git >/dev/null
command -v cmake >/dev/null
command -v java >/dev/null
command -v javac >/dev/null
command -v jar >/dev/null

model_hash_before="$(shasum -a 256 "$MODEL" | awk '{print $1}')"
mkdir -p "$BUILD_ROOT"

if [[ ! -d "$ORT_SOURCE/.git" ]]; then
  git clone --branch "$ORT_TAG" --depth 1 --recurse-submodules \
    https://github.com/microsoft/onnxruntime.git "$ORT_SOURCE"
fi
actual_ort_tag="$(git -C "$ORT_SOURCE" describe --tags --exact-match)"
[[ "$actual_ort_tag" == "$ORT_TAG" ]] || {
  echo "Expected ONNX Runtime tag $ORT_TAG, found $actual_ort_tag" >&2
  exit 1
}

if [[ ! -d "$EIGEN_SOURCE/.git" ]]; then
  git clone --filter=blob:none --no-checkout \
    https://chromium.googlesource.com/external/gitlab.com/libeigen/eigen "$EIGEN_SOURCE"
  git -C "$EIGEN_SOURCE" checkout --detach "$EIGEN_COMMIT"
fi
actual_eigen_commit="$(git -C "$EIGEN_SOURCE" rev-parse HEAD)"
[[ "$actual_eigen_commit" == "$EIGEN_COMMIT" ]] || {
  echo "Expected Eigen $EIGEN_COMMIT, found $actual_eigen_commit" >&2
  exit 1
}

"$PYTHON" "$ORT_SOURCE/tools/ci_build/build.py" \
  --build_dir "$ORT_BUILD" \
  --config MinSizeRel \
  --update --build --build_java \
  --parallel "$PARALLEL" \
  --skip_tests --skip_submodule_sync \
  --include_ops_by_config "$CONFIG" \
  --enable_reduced_operator_type_support \
  --disable_ml_ops \
  --disable_rtti \
  --disable_types float8 \
  --enable_lto \
  --compile_no_warning_as_error \
  --cmake_extra_defines \
    "onnxruntime_BUILD_UNIT_TESTS=OFF" \
    "FETCHCONTENT_SOURCE_DIR_EIGEN=$EIGEN_SOURCE" \
    "CMAKE_POLICY_VERSION_MINIMUM=3.5"

built_jar="$ORT_SOURCE/java/build/libs/onnxruntime-$ORT_VERSION.jar"
test -f "$built_jar" || { echo "Built runtime JAR was not found: $built_jar" >&2; exit 1; }

case "$PLATFORM" in
  linux-*) extension="so" ;;
  osx-*) extension="dylib" ;;
esac
jar_entries="$(jar tf "$built_jar")"
grep -Fxq "ai/onnxruntime/native/$PLATFORM/libonnxruntime.$extension" <<<"$jar_entries"
grep -Fxq "ai/onnxruntime/native/$PLATFORM/libonnxruntime4j_jni.$extension" <<<"$jar_entries"

smoke_classes="$BUILD_ROOT/smoke-classes"
rm -rf "$smoke_classes"
mkdir -p "$smoke_classes"
javac -cp "$built_jar" -d "$smoke_classes" "$SMOKE_SOURCE"
java -cp "$smoke_classes:$built_jar" RuntimeSmoke "$MODEL"

mkdir -p "$(dirname "$RUNTIME_OUTPUT")"
cp "$built_jar" "$RUNTIME_OUTPUT"

model_hash_after="$(shasum -a 256 "$MODEL" | awk '{print $1}')"
[[ "$model_hash_before" == "$model_hash_after" ]] || {
  echo "The model changed during runtime compilation; refusing to accept the output." >&2
  exit 1
}

ls -lh "$RUNTIME_OUTPUT"
