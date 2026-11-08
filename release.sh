#!/bin/bash
# Builds both apps and puts the release files in dist/: the Mac app as a disk image (with the phone app and the
# phone tools inside it), the phone app on its own, the old USB setup script and SHA256SUMS. The version comes
# from VERSION. The disk image needs dmgbuild, installed on first use into build/dmgenv.
#
#   ./release.sh            build only
#   ./release.sh --publish  also tag vVERSION and publish the GitHub release (needs gh, signed in)
#
# Publishing checks first that everything is committed and pushed and that the version is new, so a release is
# always exactly a commit on GitHub. Apps already installed find it within a day: the Mac offers Install, then
# offers the phone its new app over the link. The signing key never leaves this Mac.
set -euo pipefail
cd "$(dirname "$0")"
V="$(cat VERSION)"
REPO=iamkhalid2/macindroid
PUBLISH=0
[ "${1:-}" = --publish ] && PUBLISH=1

if [ $PUBLISH = 1 ]; then
  fail() { echo "release: $*" >&2; exit 1; }
  gh auth status >/dev/null 2>&1 || fail "sign in with gh auth login first"
  [ -z "$(git status --porcelain)" ] || fail "commit or stash your changes first"
  git fetch -q origin
  [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || fail "push main first (HEAD isn't origin/main)"
  git ls-remote --exit-code --tags origin "refs/tags/v$V" >/dev/null && fail "v$V is already released; raise VERSION"
  PREVIOUS="$(gh release view --repo "$REPO" --json tagName --jq .tagName 2>/dev/null || true)"
fi

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
[ $PUBLISH = 1 ] || exit 0

{
  echo "## What's new"
  echo
  if [ -n "$PREVIOUS" ]; then git log --reverse --format='- %s' "$PREVIOUS..HEAD"; else git log --reverse --format='- %s'; fi
  echo
  echo "## Install or update"
  echo
  echo "**Already using Macindroid?** Choose **Install Macindroid $V** in its menu. Once the Mac has updated, the phone"
  echo "shows **Update**; tap it, then **Install**."
  echo
  echo "**New to it?** Download **Macindroid-$V.dmg**, drag Macindroid into Applications and follow the"
  echo "[setup steps](https://github.com/$REPO#set-up). The Mac shows a QR code for the phone app."
  echo
  echo "Check a download with \`shasum -a 256 -c SHA256SUMS --ignore-missing\`."
} > build/release-notes.md

git tag -a "v$V" -m "Macindroid $V"
git push -q origin "v$V"
gh release create "v$V" --repo "$REPO" --title "Macindroid $V" --notes-file build/release-notes.md --latest \
  "dist/Macindroid-$V.dmg" "dist/Macindroid-$V.apk" dist/setup-phone.sh dist/SHA256SUMS

# What people download must be what was just built.
check="$(mktemp -d)"
gh release download "v$V" --repo "$REPO" --pattern SHA256SUMS --dir "$check"
cmp -s "$check/SHA256SUMS" dist/SHA256SUMS && echo "Published Macindroid $V: https://github.com/$REPO/releases/tag/v$V" \
  || { echo "release: the published SHA256SUMS differs from dist/" >&2; exit 1; }
rm -rf "$check"
