# Kokoros Android JNI Build Success Report

**Date:** December 17, 2025
**Target Architecture:** `aarch64-linux-android` (Arm64-v8a)
**Build Environment:** Native Termux (on Android Device)

## 1. Achievement
Successfully compiled `libkokoros_android.so`, a JNI-compatible shared library for Android that embeds the Kokoro TTS engine. The library is linked against Bionic libc and is ready for inclusion in an Android APK.

## 2. Key Challenges & Resolutions

| Challenge | Root Cause | Resolution |
| :--- | :--- | :--- |
| **`ort-sys` Compilation** | `ort-sys` (v2.0.0-rc.10) build script failed to detect `android` target for cache directory logic. | **Patched `ort-sys`:** Modified `src/internal/dirs.rs` in the cargo registry to treat `android` like `linux`. Used `patch_deps.sh`. |
| **`audiopus_sys` / `opus`** | `audiopus_sys` build script syntax error on Android. | **Removed Dependency:** Removed `opus` and `ogg` from `Cargo.toml`. The JNI lib returns raw PCM floats; Android handles encoding. |
| **`openssl-sys`** | Cross-compiling OpenSSL is difficult; pkg-config failed to find it for the target. | **Switched to Rustls:** Replaced `reqwest` default features (OpenSSL) with `rustls-tls` in `Cargo.toml`. |
| **`espeak-rs` / `espeak-ng`** | Build failed with "No such file or directory" for intonations. Caused by **path length buffer overflow** in `espeak-ng` C code. | **Moved Project:** Copied the project to a short path `~/k` in Termux to avoid the 160-char buffer limit in `espeak-ng`. |
| **ONNX Runtime** | `ort` requires prebuilt binaries or full compilation. | **Used Local Binary:** Configured `ORT_STRATEGY=system` and pointed `ORT_LIB_LOCATION` to the user's existing `libonnxruntime.so` in Termux. |

## 3. Final Build Configuration

### Dependencies (`jnilibs/kokoros/Cargo.toml`)
*   **Removed:** `opus`, `ogg`
*   **Modified:** `reqwest` uses `default-features = false, features = ["rustls-tls", "json"]`
*   **ORT:** `version = "2.0.0-rc.9"` (Patched locally via `patch_deps.sh`)

### Build Script (`build_jni_final.sh`)
*   Sets `ORT_STRATEGY=system`.
*   Sets `ORT_LIB_LOCATION` to the local Android `libonnxruntime.so`.
*   Runs `cargo clean` for dependencies to ensure clean environment.
*   Builds with `cargo build --release` (Native Termux defaults to Android host).

## 4. Reproduction Steps

1.  **Environment:** Native Termux on Android `aarch64`.
2.  **Prerequisites:**
    *   `pkg install rust cmake clang pkg-config`
    *   Existing `libonnxruntime.so` (Android build) at a known path.
3.  **Setup:**
    *   Copy `jnilibs` folder to a **short path** (e.g., `~/k`).
    *   Ensure `patch_deps.sh` has run at least once to fix `ort-sys` in `~/.cargo/registry`.
4.  **Build:**
    ```bash
    cd ~/k
    ./build_jni_final.sh
    ```
5.  **Output:**
    *   `~/k/output/libkokoros_android.so` (The JNI Lib)

## 5. Android Integration Guide

1.  **Copy Libraries:**
    *   Place `libkokoros_android.so` and `libonnxruntime.so` into your Android project at:
        `app/src/main/jniLibs/arm64-v8a/`
2.  **Load in Java/Kotlin:**
    ```kotlin
    companion object {
        init {
            System.loadLibrary("onnxruntime")
            System.loadLibrary("kokoros_android")
        }
    }
    ```
3.  **API:**
    *   `init(modelPath, voicesPath, threads)` -> Returns pointer (Long)
    *   `speak_raw(pointer, text, voice, speed)` -> Returns FloatArray
    *   `close(pointer)` -> Void
