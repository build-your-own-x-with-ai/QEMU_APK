#!/bin/bash
# Cross-compile qemu-system-arm for aarch64 and copy output
# Run inside the Docker build container or standalone
#
# Usage: ./build_qemu.sh [output_dir]
# Default output: ./output

set -e

OUTPUT_DIR="${1:-$(pwd)/output}"
mkdir -p "$OUTPUT_DIR"

echo "=== QEMU Build Script ==="
echo "Output directory: $OUTPUT_DIR"

# Check if running in Docker
if [ -f /opt/qemu/bin/qemu-system-arm ]; then
    echo "Found pre-built QEMU in /opt/qemu/bin/"
    QEMU_BIN="/opt/qemu/bin/qemu-system-arm"
else
    echo "Building QEMU from source..."

    QEMU_VERSION="9.2.0"
    QEMU_SRC="/tmp/qemu-src"

    mkdir -p "$QEMU_SRC"
    cd "$QEMU_SRC"

    if [ ! -d "qemu" ]; then
        git clone --depth 1 --branch "v${QEMU_VERSION}" \
            https://gitlab.com/qemu-project/qemu.git
    fi

    cd qemu
    ./configure \
        --cross-prefix="${CROSS_COMPILE:-aarch64-linux-gnu-}" \
        --target-list=arm-softmmu \
        --prefix="${QEMU_SRC}/install" \
        --static \
        --enable-slirp \
        --enable-vnc \
        --disable-docs \
        --disable-sdl \
        --disable-gtk \
        --disable-opengl \
        --disable-spice \
        --disable-usb-redir \
        --disable-libusb \
        --disable-smartcard \
        --disable-guest-agent \
        --disable-werror \
        --extra-cflags="-I/opt/cross/include" \
        --extra-ldflags="-L/opt/cross/lib"

    make -j$(nproc)
    make install

    QEMU_BIN="${QEMU_SRC}/install/bin/qemu-system-arm"
fi

# Verify the binary
echo ""
echo "=== Verifying binary ==="
file "$QEMU_BIN"
ls -lh "$QEMU_BIN"

# Copy to output
cp "$QEMU_BIN" "$OUTPUT_DIR/qemu-system-arm"
chmod +x "$OUTPUT_DIR/qemu-system-arm"

echo ""
echo "=== Build complete ==="
echo "Binary: $OUTPUT_DIR/qemu-system-arm"
echo ""
echo "To test on device:"
echo "  adb push $OUTPUT_DIR/qemu-system-arm /data/local/tmp/"
echo "  adb shell /data/local/tmp/qemu-system-arm --version"
