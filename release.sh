#!/bin/bash
# Builds both apps and puts the release files in dist/: the Mac app as a disk image (with the phone app and the
# phone tools inside it), the phone app on its own, the old USB setup script and SHA256SUMS. The version comes
# from VERSION. The disk image needs dmgbuild, installed on first use into build/dmgenv.
set -euo pipefail
cd "$(dirname "$0")"
V="$(cat VERSION)"
android/build.sh   # first: the Mac app bundles this APK
mac/build.sh
rm -rf dist && mkdir -p dist build
[ -x build/dmgenv/bin/dmgbuild ] || { python3 -m venv build/dmgenv && build/dmgenv/bin/pip install -q dmgbuild==1.6.7; }
swift mac/dmg/background.swift build/dmg-bg.png build/dmg-bg@2x.png
tiffutil -cathidpicheck build/dmg-bg.png build/dmg-bg@2x.png -out build/dmg-bg.tiff >/dev/null
build/dmgenv/bin/dmgbuild -s mac/dmg/settings.py -D app=mac/build/Macindroid.app -D background=build/dmg-bg.tiff \
  "Macindroid" "dist/Macindroid-$V.dmg"
cp android/build/macindroid.apk "dist/Macindroid-$V.apk"
cp setup-phone.sh dist/
(cd dist && shasum -a 256 Macindroid-* setup-phone.sh > SHA256SUMS)
ls -l dist && cat dist/SHA256SUMS
