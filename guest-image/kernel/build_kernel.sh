#!/bin/bash
# Build an ARM32 Linux kernel with Android and QEMU virt support
#
# Prerequisites:
#   - AOSP source tree or standalone kernel source
#   - ARM cross-compiler (arm-linux-gnueabi-gcc or NDK)
#   - bc, bison, flex, libssl-dev
#
# Usage: ./build_kernel.sh [kernel_source_dir] [output_dir]
#
# This builds a kernel suitable for booting Android inside QEMU's virt machine.

set -e

KERNEL_SRC="${1:-$(dirname "$0")/kernel-src}"
OUTPUT_DIR="${2:-$(dirname "$0")/../app/src/main/assets/guest}"
CROSS_COMPILE="${CROSS_COMPILE:-arm-linux-gnueabi-}"

mkdir -p "$OUTPUT_DIR"

echo "=== ARM32 Kernel Build for QEMU Virt ==="
echo "Kernel source: $KERNEL_SRC"
echo "Output: $OUTPUT_DIR"
echo "Cross compiler: $CROSS_COMPILE"
echo ""

# If kernel source doesn't exist, provide instructions
if [ ! -d "$KERNEL_SRC" ]; then
    echo "Kernel source not found at: $KERNEL_SRC"
    echo ""
    echo "=== Option 1: Clone Goldfish kernel ==="
    echo "  git clone --depth 1 \\"
    echo "    https://android.googlesource.com/kernel/goldfish \\"
    echo "    -b android-goldfish-4.14-gchips $KERNEL_SRC"
    echo ""
    echo "=== Option 2: Use AOSP common kernel ==="
    echo "  git clone --depth 1 \\"
    echo "    https://android.googlesource.com/kernel/common \\"
    echo "    -b android13-5.15 $KERNEL_SRC"
    echo ""
    echo "=== Option 3: Use mainline kernel ==="
    echo "  git clone --depth 1 \\"
    echo "    https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git \\"
    echo "    $KERNEL_SRC"
    echo ""
    exit 1
fi

cd "$KERNEL_SRC"

echo "=== Configuring kernel ==="

# Start with multi_v7_defconfig (multiplatform ARMv7 with virtio/PCI/DRM)
make ARCH=arm CROSS_COMPILE="$CROSS_COMPILE" multi_v7_defconfig

# Enable Android-specific options
cat >> .config << 'EOF'

# Android support
CONFIG_ANDROID=y
CONFIG_ANDROID_BINDER_IPC=y
CONFIG_ANDROID_BINDERFS=y
CONFIG_ANDROID_LOGGER=y
CONFIG_ANDROID_TIMED_OUTPUT_N=y
CONFIG_ANDROID_TIMED_GPIO=y
CONFIG_ANDROID_LOW_MEMORY_KILLER=y

# Binder IPC
CONFIG_ANDROID_BINDER_DEVICES="binder,hwbinder,vndbinder"

# Virtio devices (for QEMU)
CONFIG_VIRTIO=y
CONFIG_VIRTIO_PCI=y
CONFIG_VIRTIO_BLK=y
CONFIG_VIRTIO_NET=y
CONFIG_VIRTIO_CONSOLE=y
CONFIG_VIRTIO_BALLOON=y
CONFIG_VIRTIO_INPUT=y
CONFIG_VIRTIO_MMIO=y
CONFIG_VIRTIO_MMIO_CMDLINE_DEVICES=y

# GPU / Display
CONFIG_DRM=y
CONFIG_DRM_VIRTIO_GPU=y
CONFIG_FB=y
CONFIG_FB_SIMPLE=y
CONFIG_FRAMEBUFFER_CONSOLE=y
CONFIG_LOGO=y

# Filesystem support
CONFIG_EXT4_FS=y
CONFIG_FUSE_FS=y
CONFIG_9P_FS=y
CONFIG_TMPFS=y
CONFIG_DEVTMPFS=y
CONFIG_DEVTMPFS_MOUNT=y

# Network
CONFIG_NET=y
CONFIG_INET=y
CONFIG_IPV6=y
CONFIG_NETFILTER=y

# Required for Android
CONFIG_ASHMEM=y
CONFIG_DM_VERITY=y
CONFIG_KEYS=y
CONFIG_SECURITY=y
CONFIG_SECURITY_SELINUX=y
CONFIG_SECCOMP=y
CONFIG_CGROUPS=y
CONFIG_CGROUP_CPUACCT=y
CONFIG_CGROUP_FREEZER=y
CONFIG_CGROUP_SCHED=y
CONFIG_MEMCG=y

# Block devices
CONFIG_BLK_DEV_LOOP=y
CONFIG_BLK_DEV_DM=y

# Crypto
CONFIG_CRYPTO_AES=y
CONFIG_CRYPTO_SHA256=y
CONFIG_CRYPTO_SHA1=y
EOF

# Re-configure to resolve dependencies
make ARCH=arm CROSS_COMPILE="$CROSS_COMPILE" olddefconfig

echo ""
echo "=== Building kernel ==="
make ARCH=arm CROSS_COMPILE="$CROSS_COMPILE" -j$(nproc) zImage

# Copy output
KERNEL_IMAGE="arch/arm/boot/zImage"
if [ -f "$KERNEL_IMAGE" ]; then
    cp "$KERNEL_IMAGE" "$OUTPUT_DIR/zImage"
    echo ""
    echo "=== Kernel built successfully ==="
    echo "Output: $OUTPUT_DIR/zImage"
    ls -lh "$OUTPUT_DIR/zImage"
else
    echo "ERROR: Kernel build failed - zImage not found"
    exit 1
fi

# Build device tree blob (DTB) for virt machine
echo ""
echo "=== Building DTB ==="
make ARCH=arm CROSS_COMPILE="$CROSS_COMPILE" dtbs 2>/dev/null || true

DTB_FILE="arch/arm/boot/dts/vexpress-v2p-ca15-tc1.dtb"
if [ -f "$DTB_FILE" ]; then
    cp "$DTB_FILE" "$OUTPUT_DIR/virt.dtb"
    echo "DTB: $OUTPUT_DIR/virt.dtb"
fi
