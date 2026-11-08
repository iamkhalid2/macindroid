#!/bin/bash
# Builds Macindroid.app, installs it to ~/Applications and starts it at login (a LaunchAgent, which also
# restarts it if it ever crashes).
set -euo pipefail
cd "$(dirname "$0")"
./build.sh
LABEL="xyz.iamkhalid.macindroid"
APP="$HOME/Applications/Macindroid.app"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
UID_GUI="gui/$(id -u)"

launchctl bootout "$UID_GUI/$LABEL" 2>/dev/null || true
pkill -x Macindroid 2>/dev/null || true # e.g. a copy started from build/, which would hold the port
mkdir -p "$HOME/Applications"
rm -rf "$APP"
cp -R build/Macindroid.app "$APP"

# Starts at login; restarts after a crash, but stays quit when you choose Quit.
cat > "$PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>$APP/Contents/MacOS/Macindroid</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
  <key>ProcessType</key><string>Interactive</string>
</dict></plist>
PLIST
launchctl bootstrap "$UID_GUI" "$PLIST"
echo "Macindroid is running: look for its icon in the menu bar. Log: ~/Library/Logs/Macindroid.log"
