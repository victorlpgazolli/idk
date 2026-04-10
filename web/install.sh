#!/bin/bash

# IDK - Interactive Debug Kit Installer
# Supports: macOS (ARM64), Linux (x64, ARM64/WSL)

set -e

GITHUB_REPO="victorlpgazolli/idk"
INSTALL_DIR="$HOME/.idk"
BIN_DIR="$INSTALL_DIR/bin"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}==>${NC} Installing IDK: Interactive Debug Kit..."

# 1. Detect OS and Architecture
OS="$(uname -s)"
ARCH="$(uname -m)"

case "$OS" in
    Darwin)
        if [ "$ARCH" = "arm64" ]; then
            PLATFORM="macos-arm64"
        else
            echo -e "${RED}Error:${NC} Only Apple Silicon (ARM64) is currently supported on macOS."
            exit 1
        fi
        ;;
    Linux)
        if [ "$ARCH" = "x86_64" ]; then
            PLATFORM="linux-x64"
        elif [ "$ARCH" = "aarch64" ] || [ "$ARCH" = "arm64" ]; then
            PLATFORM="linux-arm64"
        else
            echo -e "${RED}Error:${NC} Unsupported Linux architecture: $ARCH"
            exit 1
        fi
        ;;
    *)
        echo -e "${RED}Error:${NC} Unsupported operating system: $OS"
        exit 1
        ;;
esac

# 2. Get Latest Release Version from GitHub
echo -e "${BLUE}==>${NC} Finding latest release..."
LATEST_RELEASE=$(curl -s "https://api.github.com/repos/$GITHUB_REPO/releases/latest" | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/')

if [ -z "$LATEST_RELEASE" ]; then
    echo -e "${RED}Error:${NC} Could not find latest release on GitHub."
    exit 1
fi

echo -e "${BLUE}==>${NC} Downloading IDK $LATEST_RELEASE for $PLATFORM..."
DOWNLOAD_URL="https://github.com/$GITHUB_REPO/releases/download/$LATEST_RELEASE/idk-$PLATFORM.zip"

# 3. Create install directory
mkdir -p "$BIN_DIR"

# 4. Download and Extract
TEMP_ZIP=$(mktemp)
curl -L -o "$TEMP_ZIP" "$DOWNLOAD_URL"

echo -e "${BLUE}==>${NC} Extracting to $INSTALL_DIR..."
unzip -o "$TEMP_ZIP" -d "$INSTALL_DIR/tmp_extract"
mv "$INSTALL_DIR/tmp_extract/dist/"* "$BIN_DIR/"
rm -rf "$INSTALL_DIR/tmp_extract"
rm "$TEMP_ZIP"

# 5. Handle macOS Quarantine
if [ "$OS" = "Darwin" ]; then
    echo -e "${BLUE}==>${NC} Removing macOS quarantine attributes..."
    xattr -dr com.apple.quarantine "$BIN_DIR" || true
fi

# 6. Ensure executability
chmod +x "$BIN_DIR/idk"
chmod +x "$BIN_DIR/idk-bridge"

# 7. Setup PATH instruction
SHELL_TYPE=$(basename "$SHELL")
RC_FILE=""

case "$SHELL_TYPE" in
    zsh) RC_FILE="$HOME/.zshrc" ;;
    bash) RC_FILE="$HOME/.bashrc" ;;
    *) RC_FILE="$HOME/.profile" ;;
esac

echo -e "\n${GREEN}Success! IDK has been installed to $BIN_DIR${NC}"
echo -e "\nTo start using idk, run:"
echo -e "  ${BLUE}export PATH=\"\$PATH:$BIN_DIR\"${NC}"
echo -e "  ${BLUE}export IDK_BRIDGE_PATH=\"$BIN_DIR/idk-bridge\"${NC}"

if ! grep -q "IDK_BRIDGE_PATH" "$RC_FILE"; then
    echo -e "\nTo make these changes permanent, add them to your $RC_FILE:"
    echo -e "  echo 'export PATH=\"\$PATH:$BIN_DIR\"' >> $RC_FILE"
    echo -e "  echo 'export IDK_BRIDGE_PATH=\"$BIN_DIR/idk-bridge\"' >> $RC_FILE"
fi

echo -e "\n${BLUE}==>${NC} Run 'idk' to start debugging!"
