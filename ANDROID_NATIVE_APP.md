# Kokoros on Android

This project supports two ways to run Kokoro on Android:
1. **Termux + Chrome Extension**: (Legacy/CLI focused) - See sections below.
2. **Native Android Application**: (Recommended/User friendly) - See [android-app/README.md](./android-app/README.md).

---

## 1. Native Android Application (New!)

We now support building a standalone Android APK that provides a system-wide TTS service. This is the most optimized way to run Kokoros on mobile.

- **Fastest Inference**: Uses XNNPACK and FP16 models.
- **System Integration**: Works as a standard Android Text-To-Speech engine.
- **Easy Setup**: Just install the APK and select it in Android settings.

For build and installation instructions, please refer to the **[Native App Documentation](./android-app/README.md)**.

---

## 2. Termux + Chrome Extension (Legacy)


We now provide an automated build script that handles most setup!

### Step 1: Install PRoot Ubuntu in Termux
```bash
# In Termux (outside PRoot):
pkg install proot-distro
proot-distro install ubuntu
proot-distro login ubuntu
```

### Step 2: Clone and Run Install Script
```bash
# Inside PRoot Ubuntu:
git clone https://github.com/DevGitPit/Kokoros
cd Kokoros
chmod +x install.sh
./install.sh
```

The script will:
- Auto-detect ARM architecture
- Recommend XNNPACK for optimal mobile performance
- Ask for thread count optimization (use 5 for SD 7+ Gen 3, 4 for others)
- Install all required dependencies
- Build the optimized binary

---

## 2. Manual Installation (Advanced)

If you prefer manual setup or the script fails:

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
    curl -L -o onnxruntime.tgz https://cdn.pyke.io/0/pyke:ort-rs/ms@1.22.0/aarch64-unknown-linux-gnu.tgz
    tar -xzf onnxruntime.tgz
    
    # Then IN PRoot:
    export ORT_LIB_LOCATION=/path/to/extracted/onnxruntime
    export ORT_SKIP_DOWNLOAD=1
```

**Issue 3: espeak-ng Dependencies**
* *Error:* `libclang not found` or linking errors
* *Fix:*
```bash
    apt install clang libclang-dev espeak-ng libespeak-ng-dev \
                libsonic-dev libpcaudio-dev
```

### Full Manual Setup Commands

**Step 1: Install System Packages**
```bash
apt update && apt upgrade -y
apt install git build-essential cmake \
    libssl-dev pkg-config \
    clang libclang-dev \
    espeak-ng libespeak-ng-dev \
    libsonic-dev libpcaudio-dev \
    mpv \
    python3 python3-pip zip
```

**Step 2: Install Rust**
```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
```

**Step 3: Build Kokoros**
```bash
git clone https://github.com/DevGitPit/Kokoros
cd Kokoros

# Update dependencies (important after pulling updates):
cargo update

# If manual ONNX download was needed, export these first:
# export ORT_LIB_LOCATION=/path/to/onnxruntime
# export ORT_SKIP_DOWNLOAD=1

# Build with XNNPACK optimization for ARM:
RUSTFLAGS='-C link-args=-Wl,-rpath,$ORIGIN' cargo build --release --features xnnpack
```

The binary will be located at `target/release/koko`.

**Step 4: Python Dependencies (Optional - for ebook2audiobook integration)**
```bash
pip install soundfile numpy ebooklib beautifulsoup4
```

---

## 3. Performance Optimization

### Thread Count Optimization

The script automatically asks for thread count during XNNPACK builds. If you built manually or want to adjust:

**Create an optimized wrapper:**
```bash
cat > koko-optimized.sh << 'EOF'
#!/bin/bash
# For Snapdragon 7+ Gen 3 (1 Prime + 4 Performance cores):
export KOKOROS_INTRA_THREADS=5

# For standard 4-big-core setups, use:
# export KOKOROS_INTRA_THREADS=4

exec "$(dirname "$0")/target/release/koko" "$@"
EOF

chmod +x koko-optimized.sh
```

**Usage:**
```bash
./koko-optimized.sh --style af_heart t "Hello world" -o test.wav
```

### Recommended Settings by Device

- **Snapdragon 8 Gen 2/3 or 7+ Gen 3**: `KOKOROS_INTRA_THREADS=5`
- **Snapdragon 8 Gen 1 or older flagship**: `KOKOROS_INTRA_THREADS=4`
- **Mid-range SoCs**: `KOKOROS_INTRA_THREADS=4`

---

## 4. Installing the Chrome Extension

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
# With optimized wrapper:
./koko-optimized.sh --style af_heart t "Hello world" -o test.wav
mpv test.wav

# Or directly (without optimization):
./target/release/koko --style af_heart t "Hello world" -o test.wav
```

**Performance Notes:**
* **XNNPACK + Thread Optimization:** RTF ~0.80s (5-min audio takes ~4 mins on SD 7+ Gen 3)
* **Without Optimization:** RTF ~1.2s (noticeably slower)
* **RTF (Real Time Factor):** Lower is better

---

## Troubleshooting

**Build fails with "couldn't find libsonic":**
```bash
apt install libsonic-dev libpcaudio-dev
```

**ONNX Runtime not found:**
Use the manual download method described in Issue 2 above.

**Slow performance:**
Make sure you:
1. Built with `--features xnnpack`
2. Set `KOKOROS_INTRA_THREADS` appropriately for your device
3. Are running inside PRoot Ubuntu (not native Termux)

---

## Updates

When pulling new changes from upstream:
```bash
cd Kokoros
git pull
cargo update  # Important: regenerates Cargo.lock
./install.sh  # Rebuild with the script
```
