#!/bin/bash
# Runs the one-time-code detector's test set on the computer (plain Java, no phone or Android SDK needed).
# --phone also runs it on a phone connected with adb, on Android's own runtime and regex engine, which differ from
# desktop Java's (a regex flag that Java accepts and Android rejects once crashed the app on every notification).
set -euo pipefail
cd "$(dirname "$0")"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac -encoding UTF-8 --release 17 -d "$OUT" src/xyz/iamkhalid/macindroid/Otp.java test/OtpTest.java
java -cp "$OUT" xyz.iamkhalid.macindroid.OtpTest
if [ "${1:-}" = "--phone" ]; then
  SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
  BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
  (cd "$OUT" && "$BT/d8" --release --min-api 34 --output . $(find . -name '*.class'))
  adb push "$OUT/classes.dex" /data/local/tmp/macindroid-otptest.dex >/dev/null
  echo -n "on the phone: "
  adb shell 'CLASSPATH=/data/local/tmp/macindroid-otptest.dex app_process / xyz.iamkhalid.macindroid.OtpTest'
  adb shell rm -f /data/local/tmp/macindroid-otptest.dex
fi
