#!/bin/bash
# One-time phone setup over USB: installs Macindroid and grants the permissions only adb can give (automatic
# clipboard and on-demand wireless mirroring). Pairing happens in the app afterwards, with no cable.
#
#   ./setup-phone.sh                          uses a Macindroid-*.apk next to this script, or builds one from source
#   ./setup-phone.sh ~/Downloads/Macindroid-1.0.0.apk
set -euo pipefail
PKG=xyz.iamkhalid.macindroid
ADB="${ADB:-adb}"
HERE="$(cd "$(dirname "$0")" && pwd)"

APK="${1:-}"
if [ -z "$APK" ]; then
  APK="$(ls -t "$HERE"/Macindroid-*.apk 2>/dev/null | head -1 || true)"
  if [ -z "$APK" ] && [ -x "$HERE/android/build.sh" ]; then
    [ -f "$HERE/android/build/macindroid.apk" ] || "$HERE/android/build.sh"
    APK="$HERE/android/build/macindroid.apk"
  fi
fi
[ -f "$APK" ] || { echo "Couldn't find the Macindroid APK. Pass it: $0 path/to/Macindroid.apk" >&2; exit 1; }
command -v "$ADB" >/dev/null || { echo "adb isn't installed: brew install --cask android-platform-tools" >&2; exit 1; }

echo "Waiting for the phone (USB debugging on, cable connected, 'Allow' tapped on the phone)..."
"$ADB" wait-for-device
# -g grants READ_LOGS and WRITE_SECURE_SETTINGS at install time; vendor ROMs (ColorOS, MIUI) often refuse the
# separate pm grant calls below, so they are only a fallback.
# Android 15+ hides one-time codes from notification listeners. This app op exempts Macindroid so it can copy codes
# to the Mac. Android checks it when the listener binds, so set it before an update (rebinds) and again after.
"$ADB" shell appops set "$PKG" RECEIVE_SENSITIVE_NOTIFICATIONS allow >/dev/null 2>&1 || true
"$ADB" install -r -g "$APK"
"$ADB" shell appops set "$PKG" RECEIVE_SENSITIVE_NOTIFICATIONS allow >/dev/null 2>&1 || true
"$ADB" shell pm grant "$PKG" android.permission.READ_LOGS >/dev/null 2>&1 || true
"$ADB" shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true
"$ADB" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true
"$ADB" shell dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1 || true
"$ADB" shell am start -n "$PKG/.Launcher" >/dev/null
echo "Done. Follow the steps on the phone; on the Mac, choose Pair a Phone… in the Macindroid menu."
