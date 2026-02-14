# Kokoros Android JNI Build Success Report (Optimized)

**Date:** February 14, 2026
**Target Architecture:** `aarch64-linux-android` (Arm64-v8a)
**Build Environment:** Cross-compilation from Linux x86_64 using NDK 26.1.10909125.

## 1. Achievement
Successfully compiled an optimized version of `libkokoros_android.so` with **XNNPACK** support and high-performance threading for mobile processors. The library is fully integrated with the Android TTS service.

## 2. Key Challenges & Resolutions

| Challenge | Root Cause | Resolution |
| :--- | :--- | :--- |
| **Cortex-A53 Linker Error** | `ld.lld: error: --fix-cortex-a53-843419 is only supported on AArch64 targets`. | **Fixed `build.rs`:** Discovered that the root `kokoros/build.rs` was adding host library paths (`/usr/lib/x86_64-linux-gnu`) to the search path, causing the linker to think it was building for x86_64. Limited these paths to `target_arch == "x86_64"`. |
| **NDK 27 Incompatibility** | NDK 27 introduced stricter flag validation and path changes. | **Downgraded to NDK 26:** Used NDK `26.1.10909125` for more stable cross-compilation and predictable toolchain behavior. |
| **Threading Performance** | Default ONNX threading causes high context switching on mobile. | **Mobile Optimization:** Set `inter_threads(1)` and enabled dynamic `intra_threads` (passed from Kotlin) with `Level3` optimizations. |
| **CUDA Feature Overhead** | `cuda` feature was present in `Cargo.toml` but unused on Android. | **Cleaned Dependencies:** Removed `cuda` feature to reduce compile-time complexity. |
| **Bindgen Sysroot** | `espeak-rs-sys` failed to find `stdio.h` during cross-compile. | **Sysroot Configuration:** Added `--sysroot` to `BINDGEN_EXTRA_CLANG_ARGS` in the build script. |

## 3. Final Build Configuration

### Dependencies (`android-app/jnilibs_src/kokoros/Cargo.toml`)
*   **Features:** `default = ["cpu"]`, `xnnpack = ["ort/xnnpack"]`.
*   **ORT:** `version = "2.0.0-rc.11"` (Patched `ort-sys` for Android cache dir).

### Build Script (`build_android.sh`)
*   Uses **NDK 26**.
*   Sets `ORT_STRATEGY=system` and points to extracted AAR headers/libs.
*   Builds with `cargo build --release --target aarch64-linux-android --features xnnpack`.

## 4. Reproduction Steps

1.  **Environment:** Linux x86_64 with NDK 26 and Rust `aarch64-linux-android` target.
2.  **Setup:**
    ```bash
    cd android-app/jnilibs_src
    ./build_android.sh
    ```
3.  **Output:**
    *   `android-app/jnilibs_src/output/libkokoros_android.so`

## 5. Android Integration

1.  **Libraries:** Place `libkokoros_android.so` and `libonnxruntime.so` (from AAR) in `app/src/main/jniLibs/arm64-v8a/`.
2.  **Model:** Uses `kokoro-v1.0.fp16.onnx` for optimal mobile performance.
3.  **Threading:** `intra_threads` is dynamically calculated based on CPU cores (Prime + Performance cores recommended).
