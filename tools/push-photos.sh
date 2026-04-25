#!/usr/bin/env bash
# Sync a folder of BP-monitor photos to the running Android emulator's
# Pictures gallery. Re-run any time you add new photos.
#
# Usage:
#   ./tools/push-photos.sh                      # uses ~/Desktop/silverbp-test-photos
#   ./tools/push-photos.sh /path/to/photos      # custom folder
#
# Supported inputs: .jpg / .jpeg / .png / .heic (HEIC auto-converted via macOS sips)

set -euo pipefail

SRC="${1:-$HOME/Desktop/silverbp-test-photos}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"

if [ ! -d "$SRC" ]; then
  echo "Source folder not found: $SRC"
  exit 1
fi

if ! "$ADB" devices | grep -q "device$"; then
  echo "No Android device/emulator detected. Start one first:"
  echo "  $HOME/Library/Android/sdk/emulator/emulator -avd Medium_Phone_API_35 &"
  exit 1
fi

count=0
shopt -s nullglob nocaseglob
for f in "$SRC"/*.{jpg,jpeg,png,heic}; do
  base="$(basename "$f")"
  ext="${base##*.}"
  ext_lc="$(echo "$ext" | tr '[:upper:]' '[:lower:]')"

  if [ "$ext_lc" = "heic" ]; then
    out="$SRC/${base%.*}.jpg"
    if [ ! -f "$out" ] || [ "$f" -nt "$out" ]; then
      echo "  Converting $base → ${base%.*}.jpg"
      sips -s format jpeg "$f" --out "$out" >/dev/null
    fi
    f="$out"
    base="${base%.*}.jpg"
  fi

  echo "→ pushing $base"
  "$ADB" push "$f" "/sdcard/Pictures/$base" >/dev/null
  "$ADB" shell am broadcast \
    -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
    -d "file:///sdcard/Pictures/$base" >/dev/null
  count=$((count+1))
done

if [ "$count" -eq 0 ]; then
  echo "No images found in $SRC. Drop .jpg/.png/.heic files there and re-run."
  exit 0
fi

echo
echo "✓ Pushed $count image(s) to /sdcard/Pictures/."
echo "  In the app: tap 拍攝 → 從相簿選 → photos appear in the picker."
