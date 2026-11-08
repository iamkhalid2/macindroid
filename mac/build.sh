#!/bin/bash
# Builds build/Macindroid.app: a menu-bar app for macOS 14 and later, Apple silicon and Intel, ad-hoc signed.
# install.sh copies it to ~/Applications. ARCHS="arm64" builds one architecture, for a quicker build while working.
set -euo pipefail
cd "$(dirname "$0")"
APP=build/Macindroid.app
rm -rf "$APP" build/AppIcon.iconset
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
VERSION="$(cat ../VERSION)"
for arch in ${ARCHS:-arm64 x86_64}; do
  swiftc -swift-version 5 -O -target "$arch-apple-macos14.0" -o "build/Macindroid-$arch" Sources/*.swift
done
lipo -create -output "$APP/Contents/MacOS/Macindroid" build/Macindroid-*
rm build/Macindroid-*
# The app draws its own icon (Sources/Icon.swift); render it into an .icns.
"$APP/Contents/MacOS/Macindroid" --make-iconset build/AppIcon.iconset
iconutil -c icns -o "$APP/Contents/Resources/AppIcon.icns" build/AppIcon.iconset
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleIdentifier</key><string>xyz.iamkhalid.macindroid</string>
  <key>CFBundleName</key><string>Macindroid</string>
  <key>CFBundleDisplayName</key><string>Macindroid</string>
  <key>CFBundleExecutable</key><string>Macindroid</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>CFBundleVersion</key><string>$(date +%s)</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSUIElement</key><true/>
  <key>NSLocalNetworkUsageDescription</key><string>Macindroid connects to your Android phone over your Wi-Fi.</string>
  <key>NSBonjourServices</key><array><string>_macindroid._tcp</string></array>
  <key>CFBundleURLTypes</key><array><dict>
    <key>CFBundleURLName</key><string>Macindroid actions</string>
    <key>CFBundleURLSchemes</key><array><string>macindroid</string></array>
  </dict></array>
  <key>CFBundleDocumentTypes</key><array><dict>
    <key>CFBundleTypeName</key><string>File</string>
    <key>CFBundleTypeRole</key><string>Viewer</string>
    <key>LSHandlerRank</key><string>Alternate</string>
    <key>LSItemContentTypes</key><array><string>public.data</string><string>public.content</string></array>
  </dict></array>
  <key>NSAppleEventsUsageDescription</key><string>Macindroid reads the address of your browser's front tab when you choose to open it on your phone.</string>
</dict></plist>
PLIST
# Mirroring and the power-features setup use scrcpy and adb (scrcpy's own macOS build, which includes adb),
# bundled so nobody needs Homebrew or Terminal. Pinned and checked against the release's published SHA-256.
SCRCPY=v5.0.1
mkdir -p build/cache "$APP/Contents/Helpers" "$APP/Contents/Resources/scrcpy"
thin=() src=""
for arch in ${ARCHS:-arm64 x86_64}; do
  a=$([ "$arch" = arm64 ] && echo aarch64 || echo x86_64)
  sum=$([ "$a" = aarch64 ] && echo 33611e51977a8289e2e124b220f727895181ef2eb9060e0987a4dc2db9cf0d6b \
                           || echo 31a5467a9e907f162b093267cbbab1276b15c6e709c2ea8159566a8d165beb50)
  tgz="build/cache/scrcpy-macos-$a-$SCRCPY.tar.gz"
  [ -f "$tgz" ] || curl -fsSL -o "$tgz" "https://github.com/Genymobile/scrcpy/releases/download/$SCRCPY/scrcpy-macos-$a-$SCRCPY.tar.gz"
  echo "$sum  $tgz" | shasum -a 256 -c --quiet - || { rm -f "$tgz"; echo "scrcpy download didn't match its checksum" >&2; exit 1; }
  tar xzf "$tgz" -C build/cache
  src="build/cache/scrcpy-macos-$a-$SCRCPY"
  thin+=("$src/scrcpy")
done
lipo -create -output "$APP/Contents/Helpers/scrcpy" "${thin[@]}"
cp "$src/adb" "$APP/Contents/Helpers/adb"  # already universal
cp "$src/scrcpy-server" "$src/LICENSE" "$APP/Contents/Resources/scrcpy/"
# The phone app, so the Mac can hand it to the phone over Wi-Fi (scan a QR code) or install it over adb.
if [ -f ../android/build/macindroid.apk ]; then
  cp ../android/build/macindroid.apk "$APP/Contents/Resources/Macindroid.apk"
  /usr/libexec/PlistBuddy -c "Add :MIPhoneVersionCode integer $(cat ../android/build/version-code)" "$APP/Contents/Info.plist"
else
  echo "note: no android/build/macindroid.apk; build the phone app first to bundle it" >&2
fi
for tool in "$APP"/Contents/Helpers/*; do codesign --force --sign - "$tool"; done
codesign --force --deep --sign - "$APP"
echo "Built $(pwd)/$APP"
