#!/bin/bash
set -e

# Define paths
CRATE_DIR="kokoros-android"
OUTPUT_DIR="output" # Relative to jnilibs/
LIB_NAME="libkokoros_android.so"

echo "Building Kokoros Android JNI Library..."

# Ensure we are in the jnilibs directory
cd "$(dirname "$0")"

# Build the crate
# Note: In native Termux, this builds for the host (Bionic/aarch64) by default.
# In PRoot Ubuntu, this builds for glibc/aarch64.
cd "$CRATE_DIR"
cargo build --release

# Go back to jnilibs root
cd .. 

# Create output directory relative to jnilibs/
mkdir -p "$OUTPUT_DIR"

# Copy the resulting .so file
# Workspace target directory is in jnilibs/target
TARGET_DIR="target/release"

if [ -f "$TARGET_DIR/$LIB_NAME" ]; then
    cp "$TARGET_DIR/$LIB_NAME" "$OUTPUT_DIR/"
    echo "Success! Library copied to $OUTPUT_DIR/$LIB_NAME"
    ls -lh "$OUTPUT_DIR/$LIB_NAME"
else
    echo "Error: Could not find built library at $TARGET_DIR/$LIB_NAME"
    echo "Current directory contents of target/release:"
    ls -F "$TARGET_DIR" || echo "Directory not found"
    exit 1
fi
