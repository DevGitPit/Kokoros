#!/bin/bash
set -e

# Ensure we are in the script's directory (jnilibs/)
cd "$(dirname "$0")"

echo "Building Kokoros Android JNI Library (Final Attempt)..."

# 1. Configure ONNX Runtime
export ORT_STRATEGY=system
export ORT_LIB_LOCATION="/data/data/com.termux/files/home/onnxruntime-android/jni/arm64-v8a"

# 2. Clean espeak-rs-sys to retry cmake
echo "Cleaning dependencies..."
# We need to run cargo clean from within the workspace member or root?
# kokoros-android is a member.
cd kokoros-android
cargo clean -p espeak-rs-sys || true
cargo clean -p ort-sys || true

# 3. Build
echo "Starting build..."
cargo build --release

# 4. Copy Output
# The build output is in the WORKSPACE target dir, which is ../target
cd ..
mkdir -p output
TARGET_DIR="target/release"
LIB_NAME="libkokoros_android.so"

if [ -f "$TARGET_DIR/$LIB_NAME" ]; then
    cp "$TARGET_DIR/$LIB_NAME" "output/"
    echo "Success! Library copied to output/$LIB_NAME"
    ls -lh "output/$LIB_NAME"
else
    echo "Error: Could not find built library at $TARGET_DIR/$LIB_NAME"
    ls -F "$TARGET_DIR" || echo "Target dir not found"
    exit 1
fi