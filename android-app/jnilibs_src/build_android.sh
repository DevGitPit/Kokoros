#!/bin/bash
set -e

# Ensure we are in the script's directory
cd "$(dirname "$0")"

echo "=== Building Kokoros Android JNI Library ==="

# 1. Define Paths
# Resolve absolute paths
BASE_DIR="$(pwd)"
ONNX_PATH="$(readlink -f "$BASE_DIR/../onnxruntime_extracted/jni/arm64-v8a")"
LIB_NAME="libkokoros_android.so"
OUTPUT_DIR="$BASE_DIR/output"

echo "ONNX Runtime Path: $ONNX_PATH"
if [ ! -d "$ONNX_PATH" ]; then
    echo "Error: ONNX Runtime directory not found at $ONNX_PATH"
    exit 1
fi

# 2. Check Rust Target
if ! rustup target list --installed | grep -q "aarch64-linux-android"; then
    echo "Installing rust target: aarch64-linux-android..."
    rustup target add aarch64-linux-android
fi

# 3. Clean Previous Builds
echo "Cleaning cargo project..."
cargo clean

# 4. Configure Environment Variables for Build
# ORT_STRATEGY=system tells ort to use the system library instead of downloading
export ORT_STRATEGY=system
export ORT_LIB_LOCATION="$ONNX_PATH"

# NDK Configuration
export NDK_HOME="/home/pankaj/.gemini/tmp/9cc4c367faa6a0837cbd20970fab0676889bace9f16405c8326e978a6515bd6c/android-sdk/ndk/26.1.10909125"
export TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
export PATH="$TOOLCHAIN:$PATH"

# Set Compiler and Linker for aarch64-linux-android
export CC_aarch64_linux_android="aarch64-linux-android34-clang"
export CXX_aarch64_linux_android="aarch64-linux-android34-clang++"
export AR_aarch64_linux_android="llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="aarch64-linux-android34-clang"

# 5. Build
echo "Starting cargo build for aarch64-linux-android..."

# We need to explicitly link against onnxruntime. 
# The 'ort' crate with 'system' strategy should handle looking up the library if ORT_LIB_LOCATION is set.
# However, we often need to ensure the linker finds it.

# RUSTFLAGS env var to pass linker flags
# -L: Add search path for libraries
# -l: Link against onnxruntime
export RUSTFLAGS="-L $ONNX_PATH -l onnxruntime"

cargo build --release --target aarch64-linux-android

# 6. Copy Output
mkdir -p "$OUTPUT_DIR"
TARGET_ARTIFACT="$BASE_DIR/target/aarch64-linux-android/release/$LIB_NAME"

if [ -f "$TARGET_ARTIFACT" ]; then
    cp "$TARGET_ARTIFACT" "$OUTPUT_DIR/$LIB_NAME"
    echo "=== Build Successful ==="
    echo "Library copied to: $OUTPUT_DIR/$LIB_NAME"
    ls -lh "$OUTPUT_DIR/$LIB_NAME"
else
    echo "=== Build Failed ==="
    echo "Artifact not found at: $TARGET_ARTIFACT"
    exit 1
fi
