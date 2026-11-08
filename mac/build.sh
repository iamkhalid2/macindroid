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
codesign --force --deep --sign - "$APP"
echo "Built $(pwd)/$APP"
