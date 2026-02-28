#!/bin/bash
set -e

# Ensure we are in the script's directory
cd "$(dirname "$0")"

echo "=== Building Kokoros Android JNI Library (Simplified Fix) ==="

# 1. Define Paths
BASE_DIR="$(pwd)"
ONNX_PATH="$(readlink -f "$BASE_DIR/../onnxruntime_extracted/jni/arm64-v8a")"
LIB_NAME="libkokoros_android.so"
OUTPUT_DIR="$BASE_DIR/output"

# 2. Configure NDK 26
export NDK_HOME="/home/pankaj/android-build-tools/android-sdk/ndk/26.1.10909125"
export TOOLCHAIN="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
export PATH="$TOOLCHAIN:$PATH"

# Set Compiler and Linker
export CC_aarch64_linux_android="$TOOLCHAIN/aarch64-linux-android34-clang"
export CXX_aarch64_linux_android="$TOOLCHAIN/aarch64-linux-android34-clang++"
export AR_aarch64_linux_android="$TOOLCHAIN/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/aarch64-linux-android34-clang"

# 3. Bindgen and ORT config
export BINDGEN_EXTRA_CLANG_ARGS="--sysroot=$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot -I$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include/aarch64-linux-android"
export ORT_STRATEGY=system
export ORT_LIB_LOCATION="$ONNX_PATH"
export ORT_PREFER_DYNAMIC_LINK=1
export ORT_DYNAMIC_LOAD=0
export ORT_SKIP_DOWNLOAD=1

# 4. Build
echo "Starting cargo build..."

# Clean ort-sys to ensure environment variables are picked up
cargo clean -p ort-sys || true

# We use -Wl,--export-dynamic to ensure JNI and all referenced symbols are visible.
# We also link against standard Android libraries.
export RUSTFLAGS="-L $ONNX_PATH -l onnxruntime -l log -l atomic -l c++_shared -C link-arg=-Wl,--export-dynamic"

cargo build --release --target aarch64-linux-android

# 5. Copy Output and Strip
mkdir -p "$OUTPUT_DIR"
TARGET_ARTIFACT="$BASE_DIR/target/aarch64-linux-android/release/$LIB_NAME"

if [ -f "$TARGET_ARTIFACT" ]; then
    cp "$TARGET_ARTIFACT" "$OUTPUT_DIR/$LIB_NAME"
    
    # Strip binary for size
    STRIP="$TOOLCHAIN/llvm-strip"
    if [ -f "$STRIP" ]; then
        echo "Stripping binary..."
        "$STRIP" "$OUTPUT_DIR/$LIB_NAME"
    fi
    
    # Copy to android app jniLibs
    mkdir -p "$BASE_DIR/../app/src/main/jniLibs/arm64-v8a"
    cp "$OUTPUT_DIR/$LIB_NAME" "$BASE_DIR/../app/src/main/jniLibs/arm64-v8a/$LIB_NAME"
    
    echo "=== Build Successful ==="
else
    echo "=== Build Failed ==="
    exit 1
fi
