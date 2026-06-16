#!/bin/bash
# Create a writable qcow2 userdata image for the guest Android VM
#
# Usage: ./prepare_userdata.sh [system_img_path] [output_path]
#
# Creates a qcow2 image that can be used as the userdata partition.
# The image starts empty and grows as data is written.

set -e

IMAGES_DIR="${1:-$(dirname "$0")/../app/src/main/assets/guest}"
OUTPUT_PATH="${2:-$IMAGES_DIR/userdata.qcow2}"
CACHE_PATH="${3:-$IMAGES_DIR/cache.img}"

# Default sizes
USERDATA_SIZE="2G"
CACHE_SIZE="256M"

echo "=== Preparing Guest Disk Images ==="
echo "Images directory: $IMAGES_DIR"
echo ""

# Check for qemu-img (needed to create qcow2 images)
if ! command -v qemu-img &> /dev/null; then
    echo "ERROR: qemu-img not found. Install QEMU tools:"
    echo "  macOS: brew install qemu"
    echo "  Ubuntu: apt-get install qemu-utils"
    echo "  Arch: pacman -S qemu-img"
    exit 1
fi

# Create userdata qcow2 image
echo "Creating userdata image ($USERDATA_SIZE)..."
qemu-img create -f qcow2 "$OUTPUT_PATH" "$USERDATA_SIZE"
echo "  Created: $OUTPUT_PATH"

# Create cache partition (raw ext4)
echo "Creating cache image ($CACHE_SIZE)..."
dd if=/dev/zero of="$CACHE_PATH" bs=1M count=256 2>/dev/null
mkfs.ext4 -F -L cache "$CACHE_PATH" 2>/dev/null || true
echo "  Created: $CACHE_PATH"

# Create shared folder mount point
SHARED_DIR="$IMAGES_DIR/shared"
mkdir -p "$SHARED_DIR"
echo "  Created shared folder: $SHARED_DIR"

echo ""
echo "=== Disk Images Ready ==="
ls -lh "$OUTPUT_PATH" "$CACHE_PATH"
echo ""
echo "Next steps:"
echo "  1. Ensure zImage (kernel) and system.img are in $IMAGES_DIR"
echo "  2. Launch the app and create a VM"
echo ""
echo "=== Verify with QEMU (optional) ==="
echo "  qemu-system-arm -M virt -cpu cortex-a15 -m 1024 \\"
echo "    -kernel $IMAGES_DIR/zImage \\"
echo "    -drive file=$IMAGES_DIR/system.img,format=raw,if=virtio,readonly=on \\"
echo "    -drive file=$OUTPUT_PATH,format=qcow2,if=virtio \\"
echo "    -display vnc=127.0.0.1:0 \\"
echo "    -serial mon:stdio \\"
echo "    -append 'console=ttyAMA0 androidboot.hardware=cuttlefish androidboot.selinux=permissive'"
