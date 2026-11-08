import Foundation
import ServiceManagement

/// Open at Login. A copy installed by mac/install.sh is started by its LaunchAgent (which also restarts it after a
/// crash); a downloaded copy uses the system's login items instead, switched on at its first launch from Applications.
enum LoginItem {
    private static var agentPlist: String {
        NSHomeDirectory() + "/Library/LaunchAgents/\(Bundle.main.bundleIdentifier ?? "xyz.iamkhalid.macindroid").plist"
    }

    static var managedByAgent: Bool { FileManager.default.fileExists(atPath: agentPlist) }
    static var enabled: Bool { managedByAgent || SMAppService.mainApp.status == .enabled }

    /// A login item remembers where the app was, so only a copy that lives in an Applications folder adds one.
    private static var installed: Bool {
        let path = Bundle.main.bundlePath
        return path.hasPrefix("/Applications/") || path.hasPrefix(NSHomeDirectory() + "/Applications/")
    }

    static func setUpOnFirstLaunch() {
        guard !managedByAgent, installed, !UserDefaults.standard.bool(forKey: "loginItemSetUp") else { return }
        UserDefaults.standard.set(true, forKey: "loginItemSetUp")
        set(true)
    }

    static func set(_ on: Bool) {
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            log("open at login \(on ? "on" : "off")")
        } catch {
            log("could not turn open at login \(on ? "on" : "off"): \(error)")
        }
    }
}
