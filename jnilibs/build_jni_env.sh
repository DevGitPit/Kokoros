#!/bin/bash
set -e

echo "Building Kokoros Android JNI Library with System Libraries..."

# Configuration
# Assuming user has installed libraries via pkg in Termux:
# pkg install espeak-ng libonnxruntime

# For ORT (Onnx Runtime)
export ORT_STRATEGY=system
# If pkg-config finds it, great. Otherwise:
# export ORT_LIB_LOCATION=/data/data/com.termux/files/usr/lib/libonnxruntime.so

# For Espeak-rs
# espeak-rs doesn't easily support system linking via env var in 0.1.9?
# It seems it always builds bundled.
# But we can try to fix the build error by ensuring env is clean.

# Navigate to crate
cd "$(dirname "$0")"
cd kokoros-android

# Build
cargo build --release

# Copy output
cd ..
mkdir -p output
TARGET_DIR="target/release"
LIB_NAME="libkokoros_android.so"

if [ -f "$TARGET_DIR/$LIB_NAME" ]; then
    cp "$TARGET_DIR/$LIB_NAME" "output/"
    echo "Success! Library copied to output/$LIB_NAME"
    ls -lh "output/$LIB_NAME"
else
    echo "Error: Could not find built library."
    exit 1
fi
