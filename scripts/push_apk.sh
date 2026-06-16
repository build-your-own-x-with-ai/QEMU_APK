#!/bin/bash
# Push and install an APK into the guest VM via ADB
# Usage: ./push_apk.sh <apk_file>
#
# Prerequisites:
#   - Guest VM is running with ADB port forwarded (5555)
#   - adb is available on host

set -e

APK_FILE="$1"

if [ -z "$APK_FILE" ] || [ ! -f "$APK_FILE" ]; then
    echo "Usage: $0 <path-to-apk>"
    echo ""
    echo "Pushes an APK to the guest Android VM and installs it."
    exit 1
fi

echo "=== Installing APK into guest VM ==="
echo "APK: $APK_FILE"
echo ""

# Check if guest ADB is accessible (forwarded via SLIRP)
echo "Checking guest ADB connection..."
if ! adb -s 127.0.0.1:5555 devices 2>/dev/null | grep -q "device"; then
    echo "ERROR: Guest ADB not accessible at 127.0.0.1:5555"
    echo ""
    echo "Make sure:"
    echo "  1. The guest VM is running"
    echo "  2. ADB port forwarding is configured (hostfwd=tcp::5555-:5555)"
    echo "  3. The guest has adbd running"
    exit 1
fi

# Install the APK
echo "Installing APK..."
adb -s 127.0.0.1:5555 install -r "$APK_FILE"

echo ""
echo "=== Installation complete ==="
echo ""
echo "To launch the app in the guest, use the guest's app launcher"
echo "or run: adb -s 127.0.0.1:5555 shell monkey -p <package_name> -c android.intent.category.LAUNCHER 1"
