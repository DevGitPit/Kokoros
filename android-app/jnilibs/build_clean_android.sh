#!/bin/bash
set -e

# Configuration
NDK_PATH="/home/pankaj/android-ndk-r26c"
API_LEVEL=34
TARGET="aarch64-linux-android"
TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64"
BIN_DIR="$TOOLCHAIN/bin"

# Export NDK Tools
export CC="$BIN_DIR/${TARGET}${API_LEVEL}-clang"
export CXX="$BIN_DIR/${TARGET}${API_LEVEL}-clang++"
export AR="$BIN_DIR/llvm-ar"
export LD="$BIN_DIR/ld"
export RANLIB="$BIN_DIR/llvm-ranlib"
export NM="$BIN_DIR/llvm-nm"

# Rust configuration
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR="$AR"

# ONNX Runtime dependency
export ORT_STRATEGY=system
export ORT_LIB_LOCATION="/home/pankaj/Kokoros/android-app/app/src/main/jniLibs/arm64-v8a"

echo "Using Linker: $CC"

# Ensure we are in jnilibs dir
cd "$(dirname "$0")"
JNILIBS_DIR=$(pwd)

# 1. Create Dummy Pcaudiolib (Static)
echo "Creating dummy static pcaudiolib..."
# Use the dummy C file we created in this dir
$CC -c -fPIC -o dummy_pcaudiolib.o dummy_pcaudiolib.c
$AR rcs libpcaudio.a dummy_pcaudiolib.o

# Update Rustflags to link against our dummy static lib
# We use -L $JNILIBS_DIR to find libpcaudio.a
export RUSTFLAGS="-L $JNILIBS_DIR -l static=pcaudio"

# 2. Build
echo "Starting clean build for $TARGET..."
# Build the specific crate using its manifest path to avoid workspace confusion
cargo build --manifest-path kokoros-android/Cargo.toml --target $TARGET --release

# 3. Success Check
# The output will be in jnilibs/target/ (not workspace root target)
LIB_OUT="target/$TARGET/release/libkokoros_android.so"

if [ -f "$LIB_OUT" ]; then
    echo "Build Successful: $LIB_OUT"
    mkdir -p output_clean
    cp "$LIB_OUT" output_clean/
    
    # Verify symbols
    echo "Verifying symbols in output_clean/libkokoros_android.so..."
    $NM -D --undefined-only output_clean/libkokoros_android.so | grep audio_object_flush && echo "Warning: audio_object_flush still undefined!" || echo "Success: audio_object_flush resolved."
else
    echo "Build Failed! Could not find $LIB_OUT"
    exit 1
fi
