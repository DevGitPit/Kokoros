# Kokoros on Android (Termux + Chrome Extension)

This is a fork of [Kokoros](https://github.com/lucasjinreal/Kokoros) adapted for Android using Termux and a custom Chrome Extension.

## Prerequisites
* **Termux** installed on your Android device.
* **PRoot distro** (Ubuntu recommended) installed within Termux.
* **Quetta Browser** (or any browser supporting extensions) if using the extension.

---

## 1. The Build Process (Fixes & Setup)

Getting this to compile on Android can be tricky. Here is the complete roadmap.

### Common Issues & Fixes

**Issue 1: OpenSSL Missing**
* *Error:* `couldn't find OpenSSL`
* *Fix:*
    ```bash
    apt install libssl-dev pkg-config
    ```

**Issue 2: ONNX Runtime Download Failed**
The build tries to download ONNX Runtime automatically but often fails in PRoot due to network restrictions.
* *Fix:* Manually download it in native Termux (which has network), then move it.
    ```bash
    # In Termux (OUTSIDE PRoot):
    curl -L -o onnxruntime.tgz [https://cdn.pyke.io/0/pyke:ort-rs/ms@1.22.0/aarch64-unknown-linux-gnu.tgz](https://cdn.pyke.io/0/pyke:ort-rs/ms@1.22.0/aarch64-unknown-linux-gnu.tgz)
    tar -xzf onnxruntime.tgz

    # Then IN PRoot:
    export ORT_LIB_LOCATION=/path/to/extracted/onnxruntime
    export ORT_SKIP_DOWNLOAD=1
    ```

**Issue 3: espeak-ng Bindings**
* *Error:* `libclang not found`
* *Fix:*
    ```bash
    apt install clang libclang-dev espeak-ng libespeak-ng-dev
    ```

### Full Setup Commands

**Step 1: Install System Packages**
```bash
apt update && apt upgrade -y
apt install git build-essential cmake \
    libssl-dev pkg-config \
    clang libclang-dev \
    espeak-ng libespeak-ng-dev \
    mpv \
    python3 python3-pip zip
```

**Step 2: Install Rust**
```bash
curl --proto '=https' --tlsv1.2 -sSf [https://sh.rustup.rs](https://sh.rustup.rs) | sh
source $HOME/.cargo/env
```

**Step 3: Build Kokoros**
```bash
git clone [https://github.com/DevGitPit/Kokoros](https://github.com/DevGitPit/Kokoros)
cd Kokoros

# If manual ONNX download was needed, export these first:
# export ORT_LIB_LOCATION=/path/to/onnxruntime
# export ORT_SKIP_DOWNLOAD=1

cargo build --release
```
The binary will be located at `target/release/koko`.

**Step 4: Python Dependencies**
```bash
pip install soundfile numpy ebooklib beautifulsoup4
```

---

## 2. Performance Optimization (Highly Recommended)

By default, the ONNX engine may use slow efficiency cores on Android. You can force it to use performance cores for a ~30% speedup.

**The Fix:**
1.  Open `kokoros/src/onn/ort_base.rs`.
2.  Find the `SessionBuilder` block.
3.  Add `.with_intra_threads(5)` (for Snapdragon 8 Gen 2/3 or 7+ Gen 3). Use `4` for older chips.

**Example Code Block:**
```rust
// Inside match SessionBuilder::new() { ...
let session = builder
    .with_execution_providers(providers)
    .map_err(|e| format!("Failed to build session: {}", e))?
    
    // --- ADD THIS BLOCK ---
    .with_intra_threads(5) 
    .map_err(|e| format!("Failed to set threads: {}", e))?
    // ----------------------

    .with_optimization_level(ort::session::builder::GraphOptimizationLevel::Level3)
    // ...
```
*After editing, run `cargo build --release` again.*

---

## 3. Installing the Chrome Extension

To use this with Quetta browser or others:

1.  **Zip the Extension:**
    Run this command in the repository root:
    ```bash
    zip -r chrome-extension.zip chrome-extension/
    ```
2.  **Install:**
    * Open your browser (e.g., Quetta).
    * Go to Extensions management.
    * Select "Load from Zip" (or Developer Mode -> Load Unpacked if supported).
    * Select the `chrome-extension.zip` file you just created.

---

## Usage

**Basic CLI Test:**
```bash
./target/release/koko --style af_heart t "Hello world" -o test.wav
mpv test.wav
```

**Performance Note:**
* **Snapdragon (Optimized):** RTF ~0.80s (5-min audio takes ~4 mins).
* **RTF (Real Time Factor):** Lower is better.
