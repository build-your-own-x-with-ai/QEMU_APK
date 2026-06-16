#!/bin/bash
# Quick launch script for testing QEMU VM on device via ADB
# Usage: ./launch_vm.sh
#
# This script pushes the necessary files to the device and starts QEMU
# for testing without the full Android app.

set -e

IMAGES_DIR="${1:-$(dirname "$0")/../guest-image/images}"
PACKAGE="com.qemuapk"

echo "=== QEMU APK - Quick Launch Script ==="
echo ""

# Check ADB connection
if ! adb devices | grep -q "device$"; then
    echo "ERROR: No ADB device connected"
    exit 1
fi

DEVICE_ARCH=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
echo "Device architecture: $DEVICE_ARCH"

if [ "$DEVICE_ARCH" != "arm64-v8a" ] && [ "$DEVICE_ARCH" != "aarch64" ]; then
    echo "WARNING: Device is not ARM64. QEMU ARM32 emulation requires ARM64 host."
fi

# Push images to device
echo ""
echo "=== Pushing images to device ==="
DEVICE_DIR="/data/local/tmp/qemu_apk"
adb shell "mkdir -p $DEVICE_DIR"

for img in zImage system.img userdata.qcow2 cache.img ramdisk.img; do
    if [ -f "$IMAGES_DIR/$img" ]; then
        echo "  Pushing $img..."
        adb push "$IMAGES_DIR/$img" "$DEVICE_DIR/$img"
    else
        echo "  SKIP: $img not found"
    fi
done

# Create shared directory
adb shell "mkdir -p $DEVICE_DIR/shared"

echo ""
echo "=== Launch command ==="
echo ""
echo "Connect to device via ADB shell, then run:"
echo ""
echo "  qemu-system-arm \\"
echo "    -M virt \\"
echo "    -cpu cortex-a15 \\"
echo "    -smp 2 \\"
echo "    -m 1024 \\"
echo "    -kernel $DEVICE_DIR/zImage \\"
echo "    -initrd $DEVICE_DIR/ramdisk.img \\"
echo "    -drive file=$DEVICE_DIR/system.img,format=raw,if=virtio,readonly=on \\"
echo "    -drive file=$DEVICE_DIR/userdata.qcow2,format=qcow2,if=virtio \\"
echo "    -device virtio-gpu-pci,xres=720,yres=1280 \\"
echo "    -display vnc=127.0.0.1:0 \\"
echo "    -device virtio-tablet-pci \\"
echo "    -device virtio-keyboard-pci \\"
echo "    -netdev user,id=net0,hostfwd=tcp::5555-:5555 \\"
echo "    -device virtio-net-pci,netdev=net0 \\"
echo "    -no-reboot \\"
echo "    -serial mon:stdio \\"
echo "    -append 'console=ttyAMA0 androidboot.hardware=cuttlefish androidboot.selinux=permissive'"
echo ""
echo "Then connect via VNC:"
echo "  adb forward tcp:5900 tcp:5900"
echo "  vncviewer 127.0.0.1:5900"
