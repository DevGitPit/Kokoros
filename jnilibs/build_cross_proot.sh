#!/bin/bash
set -e

# Ensure we are in jnilibs directory
cd "$(dirname "$0")"

echo "Setting up Cross-Compilation for Android (in Proot)..."

# 0. Install Rustup if missing
if ! command -v rustup &> /dev/null; then
    echo "Rustup not found. Installing..."
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
    source "$HOME/.cargo/env"
fi

# Ensure cargo is available
source "$HOME/.cargo/env" || true

# 1. Install Rust Target
if ! rustup target list --installed | grep -q "aarch64-linux-android"; then
    echo "Installing target aarch64-linux-android..."
    rustup target add aarch64-linux-android
fi

# 2. Configure Environment
export ORT_STRATEGY=system
export ORT_LIB_LOCATION="/data/data/com.termux/files/home/onnxruntime-android/jni/arm64-v8a"

# 3. Clean
echo "Cleaning..."
cargo clean

# 4. Build
echo "Building for aarch64-linux-android..."
cargo build --release --target aarch64-linux-android

# 5. Copy
mkdir -p output
TARGET_DIR="target/aarch64-linux-android/release"
LIB_NAME="libkokoros_android.so"

if [ -f "$TARGET_DIR/$LIB_NAME" ]; then
    cp "$TARGET_DIR/$LIB_NAME" "output/"
    echo "Success! Library compiled for Android at output/$LIB_NAME"
    file "output/$LIB_NAME"
else
    echo "Error: Build failed."
    exit 1
fi