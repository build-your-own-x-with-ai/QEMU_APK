#!/bin/bash
# Download Cuttlefish ARM32 Android images for use as guest system
#
# These are pre-built Android images from AOSP CI with full virtio support.
# Based on Cuttlefish reference virtual device.
#
# Usage: ./download_images.sh [output_dir]
# Default output: ./images

set -e

OUTPUT_DIR="${1:-$(dirname "$0")/../app/src/main/assets/guest}"
mkdir -p "$OUTPUT_DIR"

echo "=== Downloading Cuttlefish ARM32 Images ==="
echo "Output directory: $OUTPUT_DIR"
echo ""

# AOSP CI build artifacts URL
# Note: These URLs may change. Check https://ci.android.com/ for latest builds.
# Target: aosp_cf_arm_phone-userdebug (ARM32 Cuttlefish phone)

AOSP_BRANCH="android-11.0.0_r48"
BASE_URL="https://ci.android.com/builds/submitted"

# Alternative: Use pre-built images from community sources
# These are more reliable than AOSP CI direct links
CUTTLEFISH_RELEASE_URL="https://android.googlesource.com/device/google/cuttlefish/"

echo "NOTE: Automated downloading of AOSP images requires manual steps."
echo ""
echo "=== Option 1: Build from AOSP source ==="
echo "  1. Clone AOSP: repo init -u https://android.googlesource.com/platform/manifest -b android11-release"
echo "  2. Sync: repo sync -j8"
echo "  3. Build: source build/envsetup.sh && lunch aosp_cf_arm_phone-userdebug && make -j16"
echo "  4. Copy images from out/target/product/vsoc_arm/"
echo ""
echo "=== Option 2: Use pre-built Cuttlefish images ==="
echo "  1. Download from: https://ci.android.com/builds/branches/aosp-master/status"
echo "  2. Select 'aosp_cf_arm_phone-userdebug' target"
echo "  3. Download the artifacts"
echo ""
echo "=== Option 3: Use GSI (Generic System Image) ==="
echo "  1. Download ARM32 GSI from: https://developer.android.com/topic/generic-system-image/releases"
echo "  2. Select 'arm' variant"
echo "  3. Extract system.img"
echo ""
echo "=== Required files in $OUTPUT_DIR ==="
echo "  zImage          - ARM32 Linux kernel with virtio support"
echo "  ramdisk.img     - Boot ramdisk"
echo "  system.img      - Android system partition (ext4, raw)"
echo "  vendor.img      - Vendor partition (if separate)"
echo "  userdata.img    - User data partition template"
echo ""
echo "=== Kernel build instructions ==="
echo "  See kernel/build_kernel.sh for building the ARM32 kernel"
echo ""

# Create a helper script to extract images from a Cuttlefish build zip
cat > "$OUTPUT_DIR/extract_images.sh" << 'EXTRACT_EOF'
#!/bin/bash
# Extract images from a Cuttlefish build artifact zip
# Usage: ./extract_images.sh <zip_file> [output_dir]

ZIP_FILE="$1"
OUTPUT_DIR="${2:-.}"

if [ -z "$ZIP_FILE" ] || [ ! -f "$ZIP_FILE" ]; then
    echo "Usage: $0 <cuttlefish-build.zip> [output_dir]"
    exit 1
fi

mkdir -p "$OUTPUT_DIR"

echo "Extracting images from: $ZIP_FILE"

# Extract key images
unzip -o "$ZIP_FILE" -d "$OUTPUT_DIR/tmp_extract"

# Find and copy relevant images
find "$OUTPUT_DIR/tmp_extract" -name "*.img" | while read img; do
    name=$(basename "$img")
    case "$name" in
        boot.img|system.img|vendor.img|userdata.img|super.img|vendor_boot.img)
            cp "$img" "$OUTPUT_DIR/$name"
            echo "  Copied: $name"
            ;;
    esac
done

# Extract kernel from boot.img if needed
if [ -f "$OUTPUT_DIR/boot.img" ]; then
    echo ""
    echo "Boot image found. If you need separate kernel/ramdisk:"
    echo "  Use 'unpack_bootimg --boot_img $OUTPUT_DIR/boot.img --out $OUTPUT_DIR'"
    echo "  (from AOSP system/tools/mkbootimg)"
fi

# Cleanup
rm -rf "$OUTPUT_DIR/tmp_extract"

echo ""
echo "=== Extracted files ==="
ls -lh "$OUTPUT_DIR"/*.img 2>/dev/null || echo "No .img files found"
EXTRACT_EOF

chmod +x "$OUTPUT_DIR/extract_images.sh"

echo "Created extract helper at: $OUTPUT_DIR/extract_images.sh"
echo ""
echo "After obtaining images, run prepare_userdata.sh to create writable userdata."
