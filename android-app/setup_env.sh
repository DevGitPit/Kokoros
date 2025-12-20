#!/bin/bash
set -e

TMP_DIR="/home/pankaj/.gemini/tmp/9cc4c367faa6a0837cbd20970fab0676889bace9f16405c8326e978a6515bd6c"
mkdir -p "$TMP_DIR/downloads"
mkdir -p "$TMP_DIR/jdk-17"
mkdir -p "$TMP_DIR/gradle-8.10"
mkdir -p "$TMP_DIR/android-sdk/cmdline-tools"

# URLs
JDK_URL="https://download.oracle.com/java/17/archive/jdk-17.0.12_linux-x64_bin.tar.gz"
GRADLE_URL="https://services.gradle.org/distributions/gradle-8.10.2-bin.zip"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip"

# Download and Extract JDK
if [ ! -d "$TMP_DIR/jdk-17/jdk-17.0.12" ]; then
    echo "Downloading JDK..."
    curl -L -o "$TMP_DIR/downloads/jdk.tar.gz" "$JDK_URL"
    echo "Extracting JDK..."
    tar -xzf "$TMP_DIR/downloads/jdk.tar.gz" -C "$TMP_DIR/jdk-17"
fi
export JAVA_HOME="$TMP_DIR/jdk-17/jdk-17.0.12"
export PATH="$JAVA_HOME/bin:$PATH"

# Download and Extract Gradle
if [ ! -d "$TMP_DIR/gradle-8.10/gradle-8.10.2" ]; then
    echo "Downloading Gradle..."
    curl -L -o "$TMP_DIR/downloads/gradle.zip" "$GRADLE_URL"
    echo "Extracting Gradle..."
    unzip -q "$TMP_DIR/downloads/gradle.zip" -d "$TMP_DIR/gradle-8.10"
fi
export GRADLE_HOME="$TMP_DIR/gradle-8.10/gradle-8.10.2"
export PATH="$GRADLE_HOME/bin:$PATH"

# Download and Extract Android Command Line Tools
if [ ! -d "$TMP_DIR/android-sdk/cmdline-tools/latest" ]; then
    echo "Downloading Android Command Line Tools..."
    curl -L -o "$TMP_DIR/downloads/cmdline-tools.zip" "$CMDLINE_TOOLS_URL"
    echo "Extracting Android Command Line Tools..."
    unzip -q "$TMP_DIR/downloads/cmdline-tools.zip" -d "$TMP_DIR/android-sdk/cmdline-tools"
    
    if [ -d "$TMP_DIR/android-sdk/cmdline-tools/cmdline-tools" ]; then
        mv "$TMP_DIR/android-sdk/cmdline-tools/cmdline-tools" "$TMP_DIR/android-sdk/cmdline-tools/latest"
    fi
fi

export ANDROID_HOME="$TMP_DIR/android-sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

echo "Tools setup complete."
echo "JAVA_HOME=$JAVA_HOME"
echo "GRADLE_HOME=$GRADLE_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"

# Accept Licenses and Install SDK Components
echo "Accepting licenses and installing SDK components..."
yes | sdkmanager --licenses > /dev/null
# Install Platform 34 (matches compileSdk 34) and Build Tools
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

echo "SDK setup complete."
