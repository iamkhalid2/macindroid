#!/bin/bash
# Runs the one-time-code detector's test set on the computer (plain Java, no phone or Android SDK needed).
set -euo pipefail
cd "$(dirname "$0")"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac -encoding UTF-8 --release 17 -d "$OUT" src/xyz/iamkhalid/macindroid/Otp.java test/OtpTest.java
java -cp "$OUT" xyz.iamkhalid.macindroid.OtpTest
