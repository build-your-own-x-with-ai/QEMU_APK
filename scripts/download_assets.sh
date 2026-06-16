#!/bin/bash
# Download pre-built QEMU, proot, and Alpine rootfs binaries
# No Docker or cross-compilation required!
#
# Usage: ./download_assets.sh [output_dir]
# Default output: ../app/src/main/assets/

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="${1:-${SCRIPT_DIR}/../app/src/main/assets}"
TEMP_DIR="${SCRIPT_DIR}/temp-downloads"

mkdir -p "$OUTPUT_DIR/qemu" "$OUTPUT_DIR/proot" "$OUTPUT_DIR/alpine" "$TEMP_DIR"

echo "=== Downloading pre-built binaries ==="
echo "Output: $OUTPUT_DIR"
echo ""

# ---- 1. proot (static aarch64 binary) ----
echo "[1/3] Downloading proot (static aarch64)..."
PROOT_URL="https://proot.gitlab.io/proot/bin/proot"
PROOT_STATIC_URL="https://sourceforge.net/projects/proot.mirror/files/v5.3.0/proot-v5.3.0-aarch64-static/download"

# Try SourceForge static build first
if [ ! -f "$OUTPUT_DIR/proot/proot" ]; then
    echo "  Downloading proot-static v5.3.0 (aarch64)..."
    curl -L -o "$TEMP_DIR/proot-aarch64" "$PROOT_STATIC_URL" 2>/dev/null || true

    if file "$TEMP_DIR/proot-aarch64" | grep -q "ELF"; then
        cp "$TEMP_DIR/proot-aarch64" "$OUTPUT_DIR/proot/proot"
        chmod +x "$OUTPUT_DIR/proot/proot"
        echo "  OK: proot-static downloaded"
    else
        echo "  WARN: SourceForge download may have failed, trying alternative..."
        # Alternative: download from GitHub releases
        PROOT_GH_URL="https://github.com/nicoulaj/proot-static-build/releases/download/v5.3.0/proot-aarch64"
        curl -L -o "$TEMP_DIR/proot-aarch64-alt" "$PROOT_GH_URL" 2>/dev/null || true
        if file "$TEMP_DIR/proot-aarch64-alt" | grep -q "ELF"; then
            cp "$TEMP_DIR/proot-aarch64-alt" "$OUTPUT_DIR/proot/proot"
            chmod +x "$OUTPUT_DIR/proot/proot"
            echo "  OK: proot downloaded from GitHub"
        else
            echo "  WARN: Could not download proot. You may need to build it manually."
            echo "  See: https://proot-me.github.io/"
        fi
    fi
else
    echo "  SKIP: proot already exists"
fi

# ---- 2. Alpine Linux minirootfs (aarch64) ----
echo ""
echo "[2/3] Downloading Alpine Linux minirootfs (aarch64)..."
ALPINE_VERSION="3.20.3"
ALPINE_URL="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION%.*}/releases/aarch64/alpine-minirootfs-${ALPINE_VERSION}-aarch64.tar.gz"
ALPINE_FILE="$OUTPUT_DIR/alpine/alpine-minirootfs.tar.gz"

if [ ! -f "$ALPINE_FILE" ]; then
    echo "  Downloading Alpine ${ALPINE_VERSION} minirootfs..."
    echo "  URL: $ALPINE_URL"
    curl -L -o "$ALPINE_FILE" "$ALPINE_URL"
    echo "  OK: $(ls -lh "$ALPINE_FILE" | awk '{print $5}')"
else
    echo "  SKIP: Alpine rootfs already exists"
fi

# ---- 3. QEMU system-arm (from Alpine package) ----
echo ""
echo "[3/3] Downloading QEMU system-arm (from Alpine package)..."

# We'll install qemu-system-arm inside the Alpine rootfs using apk
# This gives us a working QEMU without cross-compilation
QEMU_APK_DIR="$TEMP_DIR/qemu-apk"
mkdir -p "$QEMU_APK_DIR"

# Check if we need to download QEMU
if [ ! -f "$OUTPUT_DIR/qemu/qemu-system-arm" ]; then
    echo "  Fetching qemu-system-arm from Alpine packages..."

    # Alpine qemu-system-arm package URL (aarch64)
    # Check Alpine package index for the correct URL
    ALPINE_REPO="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION%.*}/community/aarch64"

    # Get package listing to find qemu-system-arm
    echo "  Querying Alpine package index..."
    PACKAGES_HTML=$(curl -sL "${ALPINE_REPO}/" 2>/dev/null || echo "")

    QEMU_PKG=$(echo "$PACKAGES_HTML" | grep -o 'qemu-system-arm-[0-9][^"]*\.apk' | head -1)

    if [ -n "$QEMU_PKG" ]; then
        echo "  Found: $QEMU_PKG"
        curl -L -o "$TEMP_DIR/$QEMU_PKG" "${ALPINE_REPO}/$QEMU_PKG"
        echo "  Downloaded: $(ls -lh "$TEMP_DIR/$QEMU_PKG" | awk '{print $5}')"

        # Extract the binary from the APK (it's a tar.gz)
        echo "  Extracting qemu-system-arm..."
        mkdir -p "$QEMU_APK_DIR/extracted"
        tar xzf "$TEMP_DIR/$QEMU_PKG" -C "$QEMU_APK_DIR/extracted" 2>/dev/null || \
        tar xf "$TEMP_DIR/$QEMU_PKG" -C "$QEMU_APK_DIR/extracted" 2>/dev/null || true

        # Find the qemu-system-arm binary
        QEMU_BIN=$(find "$QEMU_APK_DIR/extracted" -name "qemu-system-arm" -type f 2>/dev/null | head -1)
        if [ -n "$QEMU_BIN" ]; then
            cp "$QEMU_BIN" "$OUTPUT_DIR/qemu/qemu-system-arm"
            chmod +x "$OUTPUT_DIR/qemu/qemu-system-arm"
            echo "  OK: qemu-system-arm extracted"
        else
            echo "  WARN: Could not extract qemu-system-arm from package"
            echo "  The APK package may have dependencies that need to be installed"
        fi

        # Also get required shared libs
        echo "  Downloading QEMU dependencies..."
        for dep in qemu-common glib libpixman libslirp; do
            DEP_PKG=$(echo "$PACKAGES_HTML" | grep -o "${dep}-[0-9][^\"]*\.apk" | head -1)
            if [ -z "$DEP_PKG" ]; then
                # Check main repo
                MAIN_REPO="https://dl-cdn.alpinelinux.org/alpine/v${ALPINE_VERSION%.*}/main/aarch64"
                MAIN_HTML=$(curl -sL "${MAIN_REPO}/" 2>/dev/null || echo "")
                DEP_PKG=$(echo "$MAIN_HTML" | grep -o "${dep}-[0-9][^\"]*\.apk" | head -1)
                DEP_URL="${MAIN_REPO}/${DEP_PKG}"
            else
                DEP_URL="${ALPINE_REPO}/${DEP_PKG}"
            fi

            if [ -n "$DEP_PKG" ]; then
                echo "    - $DEP_PKG"
                curl -sL -o "$TEMP_DIR/$DEP_PKG" "$DEP_URL" 2>/dev/null || true
            fi
        done
    else
        echo "  WARN: qemu-system-arm not found in Alpine community repo"
        echo "  You may need to:"
        echo "    1. Enable the community repo in Alpine, or"
        echo "    2. Build QEMU via Docker (see Dockerfile.build), or"
        echo "    3. Download from Termux: pkg install qemu-system-arm-headless"
    fi
else
    echo "  SKIP: qemu-system-arm already exists"
fi

# ---- Summary ----
echo ""
echo "============================================"
echo "  Download Summary"
echo "============================================"
echo ""
echo "Assets directory: $OUTPUT_DIR"
echo ""

for dir in proot alpine qemu; do
    if [ -d "$OUTPUT_DIR/$dir" ]; then
        count=$(find "$OUTPUT_DIR/$dir" -type f ! -name ".gitkeep" | wc -l | tr -d ' ')
        size=$(du -sh "$OUTPUT_DIR/$dir" 2>/dev/null | awk '{print $1}')
        echo "  $dir/: $count files ($size)"
    fi
done

echo ""
echo "============================================"
echo "  Alternative: Use Alpine rootfs + apk"
echo "============================================"
echo ""
echo "If QEMU download failed, you can install it"
echo "inside the Alpine rootfs at runtime:"
echo ""
echo "  # On the Android device, after proot setup:"
echo "  echo 'https://dl-cdn.alpinelinux.org/alpine/v3.20/community' >> /etc/apk/repositories"
echo "  apk add qemu-system-arm"
echo ""
echo "This installs QEMU with all dependencies via Alpine's package manager."

# Cleanup temp
rm -rf "$TEMP_DIR"

echo ""
echo "Done!"
