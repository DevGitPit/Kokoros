#!/bin/bash

# Kokoros Build & Install Script with CUDA/XNNPACK/CPU support
# Auto-detects architecture and environment

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Variables
VOICES_JSON_SRC="data/voices-v1.0.bin"
VOICES_JSON_DEST="$HOME/.cache/kokoros/data.voices-v1.0.bin"
KOKO_BIN_SRC="target/release/koko"
KOKO_BIN_DEST="/usr/local/bin/koko"
ONNX_LIBS_SRC="target/release"

echo -e "${GREEN}=== Kokoros Build & Install Script ===${NC}"
echo ""

# Detect architecture
ARCH=$(uname -m)
echo -e "${BLUE}Detected architecture: $ARCH${NC}"

# Detect if running in Termux
IS_TERMUX=false
if [ -n "$TERMUX_VERSION" ] || [ -d "/data/data/com.termux" ]; then
    IS_TERMUX=true
    echo -e "${BLUE}Detected Termux environment (Android/mobile)${NC}"
fi

# Detect if running in PRoot
IS_PROOT=false
if [ -n "$PROOT_L2S_DIR" ] || grep -q "proot" /proc/1/cmdline 2>/dev/null; then
    IS_PROOT=true
    echo -e "${BLUE}Detected PRoot environment${NC}"
fi

# Special handling for Termux on ARM
if [ "$IS_TERMUX" = true ] && [[ "$ARCH" == "aarch64" || "$ARCH" == "armv"* ]]; then
    if [ "$IS_PROOT" = false ]; then
        echo ""
        echo -e "${YELLOW}╔════════════════════════════════════════════════════════════╗${NC}"
        echo -e "${YELLOW}║  WARNING: Building in Termux without PRoot               ║${NC}"
        echo -e "${YELLOW}╚════════════════════════════════════════════════════════════╝${NC}"
        echo ""
        echo -e "${YELLOW}For optimal XNNPACK builds on mobile devices, you should:${NC}"
        echo "1. Install PRoot: pkg install proot-distro"
        echo "2. Setup Ubuntu: proot-distro install ubuntu"
        echo "3. Login to PRoot: proot-distro login ubuntu"
        echo "4. Run this script inside PRoot"
        echo ""
        echo -e "${YELLOW}This ensures proper library access and better compatibility.${NC}"
        echo ""
        read -p "Continue anyway? [y/N]: " continue_anyway
        
        if [[ ! $continue_anyway =~ ^[Yy]$ ]]; then
            echo -e "${YELLOW}Exiting. Please setup PRoot and try again.${NC}"
            exit 0
        fi
    fi
fi

# Smart default selection based on architecture
echo ""
echo "Select build type:"

if [[ "$ARCH" == "x86_64" ]]; then
    # x86_64 - offer CUDA first, then CPU
    echo -e "1) CUDA (GPU acceleration - requires NVIDIA GPU) ${GREEN}[Recommended if you have GPU]${NC}"
    echo -e "2) CPU (default, no special features) ${GREEN}[Recommended otherwise]${NC}"
    echo -e "3) XNNPACK (CPU optimization - rarely needed on x86_64)"
    DEFAULT_CHOICE="2"
    
elif [[ "$ARCH" == "aarch64" || "$ARCH" == "armv"* ]]; then
    # ARM - offer XNNPACK first
    if [ "$IS_TERMUX" = true ]; then
        echo -e "1) XNNPACK (ARM CPU optimization) ${GREEN}[Recommended for mobile]${NC}"
    else
        echo -e "1) XNNPACK (ARM CPU optimization) ${GREEN}[Recommended for ARM devices]${NC}"
    fi
    echo -e "2) CPU (default, no special features)"
    DEFAULT_CHOICE="1"
    
else
    # Unknown architecture
    echo -e "1) CPU (default, no special features) ${GREEN}[Recommended]${NC}"
    echo -e "2) XNNPACK (CPU optimization)"
    DEFAULT_CHOICE="1"
fi

echo ""
read -p "Enter choice (default: $DEFAULT_CHOICE): " choice
choice=${choice:-$DEFAULT_CHOICE}

case $choice in
    1)
        if [[ "$ARCH" == "x86_64" ]]; then
            BUILD_TYPE="cuda"
            FEATURES="--features cuda"
            echo -e "${GREEN}Building with CUDA support...${NC}"
        else
            BUILD_TYPE="xnnpack"
            FEATURES="--features xnnpack"
            echo -e "${GREEN}Building with XNNPACK support...${NC}"
        fi
        ;;
    2)
        if [[ "$ARCH" == "x86_64" ]]; then
            BUILD_TYPE="cpu"
            FEATURES=""
            echo -e "${GREEN}Building with CPU support (default)...${NC}"
        else
            BUILD_TYPE="cpu"
            FEATURES=""
            echo -e "${GREEN}Building with CPU support (default)...${NC}"
        fi
        ;;
    3)
        if [[ "$ARCH" == "x86_64" ]]; then
            BUILD_TYPE="xnnpack"
            FEATURES="--features xnnpack"
            echo -e "${YELLOW}Building with XNNPACK on x86_64 (uncommon choice)...${NC}"
        else
            # Not offered on ARM
            echo -e "${RED}Invalid choice. Exiting.${NC}"
            exit 1
        fi
        ;;
    *)
        echo -e "${RED}Invalid choice. Exiting.${NC}"
        exit 1
        ;;
esac

# CUDA-specific setup (only on x86_64)
if [ "$BUILD_TYPE" == "cuda" ]; then
    echo ""
    echo -e "${YELLOW}Checking for required CUDA libraries...${NC}"
    
    MISSING_LIBS=()
    
    # Check for CUDA runtime libraries
    if ! ldconfig -p 2>/dev/null | grep -q "libcublas.so"; then
        MISSING_LIBS+=("libcublas12")
    fi
    if ! ldconfig -p 2>/dev/null | grep -q "libcudart.so"; then
        MISSING_LIBS+=("libcudart12")
    fi
    if ! ldconfig -p 2>/dev/null | grep -q "libcurand.so"; then
        MISSING_LIBS+=("libcurand10")
    fi
    if ! ldconfig -p 2>/dev/null | grep -q "libcudnn.so"; then
        MISSING_LIBS+=("libcudnn9-cuda-12")
    fi
    if ! ldconfig -p 2>/dev/null | grep -q "libcufft.so"; then
        MISSING_LIBS+=("libcufft11")
    fi
    
    if [ ${#MISSING_LIBS[@]} -gt 0 ]; then
        echo -e "${YELLOW}Missing CUDA libraries detected: ${MISSING_LIBS[*]}${NC}"
        echo ""
        read -p "Install missing CUDA libraries? This requires sudo. [y/N]: " install_cuda
        
        if [[ $install_cuda =~ ^[Yy]$ ]]; then
            echo -e "${GREEN}Installing CUDA libraries...${NC}"
            
            # Add NVIDIA repository if not already added
            if ! apt-cache policy 2>/dev/null | grep -q "developer.download.nvidia.com"; then
                echo "Adding NVIDIA CUDA repository..."
                
                # Detect Ubuntu version
                UBUNTU_VERSION=$(lsb_release -rs 2>/dev/null || echo "24.04")
                if [[ "$UBUNTU_VERSION" == "24.04" ]]; then
                    UBUNTU_CODENAME="ubuntu2404"
                elif [[ "$UBUNTU_VERSION" == "22.04" ]]; then
                    UBUNTU_CODENAME="ubuntu2204"
                else
                    echo -e "${YELLOW}Warning: Ubuntu version $UBUNTU_VERSION may not be officially supported${NC}"
                    UBUNTU_CODENAME="ubuntu2404"  # Default to latest
                fi
                
                wget https://developer.download.nvidia.com/compute/cuda/repos/${UBUNTU_CODENAME}/x86_64/cuda-keyring_1.1-1_all.deb
                sudo dpkg -i cuda-keyring_1.1-1_all.deb
                sudo apt update
                rm cuda-keyring_1.1-1_all.deb
            fi
            
            # Install missing libraries
            sudo apt install -y "${MISSING_LIBS[@]}"
            echo -e "${GREEN}CUDA libraries installed successfully!${NC}"
        else
            echo -e "${YELLOW}Proceeding without installing CUDA libraries.${NC}"
            echo -e "${YELLOW}Build may fail or runtime may not work without them.${NC}"
        fi
    else
        echo -e "${GREEN}All required CUDA libraries found!${NC}"
    fi
fi

# Build with rpath
echo ""
echo -e "${GREEN}Building Kokoros with $BUILD_TYPE support...${NC}"

# Set RUSTFLAGS to include rpath for ONNX Runtime libraries
# For WSL2, also add /usr/lib/wsl/lib to find WSL CUDA drivers
if grep -qi "microsoft\|wsl" /proc/version 2>/dev/null; then
    export RUSTFLAGS='-C link-args=-Wl,--disable-new-dtags,-rpath,$ORIGIN,-rpath,/usr/lib/wsl/lib'
    echo -e "${BLUE}WSL2 detected - adding /usr/lib/wsl/lib to RPATH${NC}"
else
    export RUSTFLAGS='-C link-args=-Wl,--disable-new-dtags,-rpath,$ORIGIN'
fi

# Build
cargo build --release $FEATURES

# Verify build
if [ ! -f "$KOKO_BIN_SRC" ]; then
    echo -e "${RED}Build failed: $KOKO_BIN_SRC not found${NC}"
    exit 1
fi

# Verify rpath was set
echo ""
echo -e "${YELLOW}Verifying rpath configuration...${NC}"
if readelf -d "$KOKO_BIN_SRC" 2>/dev/null | grep -qE "(RPATH|RUNPATH).*ORIGIN"; then
    echo -e "${GREEN}✓ RPATH/RUNPATH correctly set to \$ORIGIN${NC}"
else
    echo -e "${YELLOW}⚠ Warning: RPATH/RUNPATH not found (may be expected for CPU build)${NC}"
fi

# Check for ONNX Runtime libraries
if [ "$BUILD_TYPE" == "cuda" ] || [ "$BUILD_TYPE" == "xnnpack" ]; then
    echo ""
    echo -e "${YELLOW}Checking for ONNX Runtime libraries...${NC}"
    
    ONNX_LIBS=(
        "libonnxruntime_providers_shared.so"
    )
    
    if [ "$BUILD_TYPE" == "cuda" ]; then
        ONNX_LIBS+=("libonnxruntime_providers_cuda.so")
        ONNX_LIBS+=("libonnxruntime_providers_tensorrt.so")
    fi
    
    for lib in "${ONNX_LIBS[@]}"; do
        if [ -f "$ONNX_LIBS_SRC/$lib" ]; then
            echo -e "${GREEN}✓ Found: $lib${NC}"
        fi
    done
fi

# Create cache directory
echo ""
if [ ! -d "$(dirname "$VOICES_JSON_DEST")" ]; then
    echo "Creating directory: $(dirname "$VOICES_JSON_DEST")"
    mkdir -p "$(dirname "$VOICES_JSON_DEST")"
fi

# Copy voices data
if [ -f "$VOICES_JSON_SRC" ]; then
    echo "Copying voices data to $VOICES_JSON_DEST"
    cp "$VOICES_JSON_SRC" "$VOICES_JSON_DEST"
else
    echo -e "${RED}Error: $VOICES_JSON_SRC not found${NC}"
    exit 1
fi

# Install binary
echo ""

# Adjust installation based on environment
WSL_WRAPPER_CREATED=false

if [ "$IS_TERMUX" = true ]; then
    # In Termux, use $PREFIX/bin
    KOKO_BIN_DEST="$PREFIX/bin/koko"
    echo "Installing to Termux location: $KOKO_BIN_DEST"
    cp "$KOKO_BIN_SRC" "$KOKO_BIN_DEST"
    
    # Copy ONNX libraries
    if [ "$BUILD_TYPE" == "cuda" ] || [ "$BUILD_TYPE" == "xnnpack" ]; then
        echo "Copying ONNX Runtime libraries..."
        for lib in "$ONNX_LIBS_SRC"/libonnxruntime*.so; do
            if [ -f "$lib" ]; then
                cp "$lib" "$PREFIX/bin/"
                echo "  ✓ Copied $(basename "$lib")"
            fi
        done
    fi
else
    # Regular Linux (PC - both x86_64 and ARM)
    read -p "Install koko to $KOKO_BIN_DEST? This requires sudo. [Y/n]: " install_bin
    
    if [[ ! $install_bin =~ ^[Nn]$ ]]; then
        echo "Installing koko binary..."
        sudo cp "$KOKO_BIN_SRC" "$KOKO_BIN_DEST"
        
        # For CUDA/XNNPACK builds, also copy ONNX libraries to the same directory
        if [ "$BUILD_TYPE" == "cuda" ] || [ "$BUILD_TYPE" == "xnnpack" ]; then
            echo "Copying ONNX Runtime libraries..."
            for lib in "$ONNX_LIBS_SRC"/libonnxruntime*.so; do
                if [ -f "$lib" ]; then
                    sudo cp "$lib" "/usr/local/bin/"
                    echo "  ✓ Copied $(basename "$lib")"
                fi
            done
        fi
        
        # WSL2-specific: Create wrapper script for CUDA
        if [ "$BUILD_TYPE" == "cuda" ] && grep -qi "microsoft\|wsl" /proc/version 2>/dev/null; then
            echo ""
            echo -e "${BLUE}WSL2 detected - creating wrapper script for CUDA support...${NC}"
            
            sudo tee /usr/local/bin/koko-wsl > /dev/null << 'EOF'
#!/bin/bash
# WSL2 wrapper for koko - ensures correct CUDA library is loaded
export LD_PRELOAD=/usr/lib/wsl/lib/libcuda.so.1
exec /usr/local/bin/koko "$@"
EOF
            
            sudo chmod +x /usr/local/bin/koko-wsl
            echo -e "${GREEN}✓ Created koko-wsl wrapper${NC}"
            WSL_WRAPPER_CREATED=true
        fi
        
        echo -e "${GREEN}✓ Installation completed successfully!${NC}"
    else
        echo -e "${YELLOW}Skipping installation. Binary available at: $KOKO_BIN_SRC${NC}"
        
        # Even if skipping install, create a local WSL wrapper for testing
        if [ "$BUILD_TYPE" == "cuda" ] && grep -qi "microsoft\|wsl" /proc/version 2>/dev/null; then
            echo ""
            echo -e "${BLUE}WSL2 detected - creating local wrapper script...${NC}"
            
            cat > koko-wsl.sh << 'EOF'
#!/bin/bash
# WSL2 wrapper for koko - ensures correct CUDA library is loaded
export LD_PRELOAD=/usr/lib/wsl/lib/libcuda.so.1
exec "$(dirname "$0")/target/release/koko" "$@"
EOF
            
            chmod +x koko-wsl.sh
            echo -e "${GREEN}✓ Created ./koko-wsl.sh wrapper${NC}"
            KOKO_BIN_DEST="./koko-wsl.sh"
            WSL_WRAPPER_CREATED=true
        else
            KOKO_BIN_DEST="$KOKO_BIN_SRC"
        fi
    fi
fi

# Final summary
echo ""
echo -e "${GREEN}╔════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║     Installation Summary                  ║${NC}"
echo -e "${GREEN}╚════════════════════════════════════════════╝${NC}"
echo "Architecture:        $ARCH"
if [ "$IS_TERMUX" = true ]; then
    echo "Environment:         Termux (mobile)"
elif [[ "$ARCH" == "aarch64" || "$ARCH" == "armv"* ]]; then
    echo "Environment:         ARM PC/Server"
else
    echo "Environment:         Desktop/Server"
fi
echo "Build type:          $BUILD_TYPE"
echo "Voices data:         $VOICES_JSON_DEST"
echo "Executable:          $KOKO_BIN_DEST"
echo ""

# Test the binary
echo -e "${YELLOW}Testing binary...${NC}"
if command -v koko &> /dev/null; then
    koko --version 2>/dev/null && echo -e "${GREEN}✓ Binary works!${NC}" || echo -e "${YELLOW}⚠ Binary test inconclusive${NC}"
elif [ -f "$KOKO_BIN_DEST" ]; then
    "$KOKO_BIN_DEST" --version 2>/dev/null && echo -e "${GREEN}✓ Binary works!${NC}" || echo -e "${YELLOW}⚠ Binary test inconclusive${NC}"
fi

echo ""
echo -e "${GREEN}🎉 Setup complete!${NC}"

# Build-specific advice
if [ "$BUILD_TYPE" == "cuda" ]; then
    echo ""
    echo -e "${YELLOW}CUDA Notes:${NC}"
    echo "- Ensure your NVIDIA drivers are installed and up to date"
    echo "- Run 'nvidia-smi' to verify GPU is accessible"
    
    if [ "$WSL_WRAPPER_CREATED" = true ]; then
        echo ""
        echo -e "${GREEN}WSL2 Setup Complete:${NC}"
        if [[ "$KOKO_BIN_DEST" == *"koko-wsl.sh"* ]]; then
            echo -e "- Use the local wrapper: ${GREEN}./koko-wsl.sh openai${NC}"
            echo -e "- To install system-wide, re-run this script and choose 'Y' for installation"
        else
            echo -e "- Use the ${GREEN}koko-wsl${NC} command for CUDA support"
            echo -e "- Example: ${GREEN}koko-wsl openai${NC}"
        fi
    else
        echo "- The binary will automatically use CUDA if available"
        echo -e "- Try running: ${GREEN}koko openai${NC}"
    fi
fi

if [ "$BUILD_TYPE" == "xnnpack" ]; then
    echo ""
    echo -e "${YELLOW}XNNPACK Notes:${NC}"
    echo "- Optimized for ARM CPU operations"
    echo "- Should provide better performance than default CPU build"
    if [ "$IS_TERMUX" = true ]; then
        echo "- Works best in PRoot environment on mobile devices"
    fi
fi
