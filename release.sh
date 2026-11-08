#!/bin/bash
# Builds both apps and puts the release files in dist/: the Mac app (zip), the phone app (APK), the phone setup
# script and SHA256SUMS. The version comes from VERSION.
set -euo pipefail
cd "$(dirname "$0")"
V="$(cat VERSION)"
mac/build.sh
android/build.sh
rm -rf dist && mkdir dist
ditto -c -k --sequesterRsrc --keepParent mac/build/Macindroid.app "dist/Macindroid-$V.zip"
cp android/build/macindroid.apk "dist/Macindroid-$V.apk"
cp setup-phone.sh dist/
(cd dist && shasum -a 256 Macindroid-* setup-phone.sh > SHA256SUMS)
ls -l dist && cat dist/SHA256SUMS
