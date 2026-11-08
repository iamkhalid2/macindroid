#!/bin/bash
# Builds build/macindroid.apk with the plain Android SDK tools (no Gradle, no downloads).
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
JAR="$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -1)/android.jar"
B=build
rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex"

"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/base.apk" -I "$JAR" --manifest AndroidManifest.xml -R "$B/res.zip" --java "$B/gen" \
  --min-sdk-version 34 --target-sdk-version 34 --version-code "$(date +%s)" --version-name "$(cat ../VERSION)" --auto-add-overlay
javac -Xlint:-options --release 17 -classpath "$JAR" -d "$B/classes" $(find "$B/gen" src -name '*.java')
"$BT/d8" --release --lib "$JAR" --min-api 34 --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"

# The signing key: signing.env (git-ignored) names the keystore and its password. The first build makes both.
# Keep the keystore: an update signed with a different key won't install over the app.
if [ ! -f signing.env ]; then
  KS="$HOME/.android/macindroid.keystore"
  [ -f "$KS" ] && { echo "$KS exists but signing.env doesn't; write signing.env (KS, KS_ALIAS, KS_PASS)." >&2; exit 1; }
  PASS="$(openssl rand -hex 16)"
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -alias macindroid \
    -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Macindroid" >/dev/null
  (umask 077 && printf 'KS=%s\nKS_ALIAS=macindroid\nKS_PASS=%s\n' "$KS" "$PASS" > signing.env)
fi
set -a; source signing.env; set +a
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" --ks-pass env:KS_PASS --key-pass env:KS_PASS \
  --out "$B/macindroid.apk" "$B/aligned.apk"
echo "Built $(pwd)/$B/macindroid.apk"
