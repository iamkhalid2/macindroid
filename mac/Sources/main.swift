// Macindroid for Mac: a menu-bar app that links this Mac with an Android phone.
import AppKit

if let index = CommandLine.arguments.firstIndex(of: "--make-iconset"), index + 1 < CommandLine.arguments.count {
    Icon.writeIconset(to: URL(fileURLWithPath: CommandLine.arguments[index + 1]))
    exit(0)
}

// One Macindroid at a time: a second copy (say, launched by a macindroid:// link while the login copy was still
// starting) would add a second menu-bar icon. The younger copy leaves.
let me = NSRunningApplication.current
if let id = Bundle.main.bundleIdentifier, NSRunningApplication.runningApplications(withBundleIdentifier: id).contains(where: {
    $0 != me && ($0.launchDate ?? .distantPast, $0.processIdentifier) < (me.launchDate ?? .distantFuture, me.processIdentifier)
}) {
    exit(0)
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
