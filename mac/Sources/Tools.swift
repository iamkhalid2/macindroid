import Foundation

/// adb and scrcpy, bundled in Contents/Helpers (from scrcpy's own macOS build), so mirroring and the phone setup
/// need nothing installed. A Homebrew copy is used only if the bundled one is missing (a development build).
enum Tools {
    static let package = "xyz.iamkhalid.macindroid"
    private static let searchPaths = ["/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"]

    static func path(_ name: String) -> String? {
        let bundled = Bundle.main.bundleURL.appendingPathComponent("Contents/Helpers/\(name)").path
        if FileManager.default.isExecutableFile(atPath: bundled) { return bundled }
        return searchPaths.map { "\($0)/\(name)" }.first { FileManager.default.isExecutableFile(atPath: $0) }
    }

    static var adb: String? { path("adb") }
    static var scrcpy: String? { path("scrcpy") }
    static var scrcpyServer: String? { Bundle.main.path(forResource: "scrcpy-server", ofType: nil, inDirectory: "scrcpy") }

    /// The phone app this Mac app was built with, and its version code.
    static var apk: URL? { Bundle.main.url(forResource: "Macindroid", withExtension: "apk") }
    static var apkVersionCode: Int { Bundle.main.object(forInfoDictionaryKey: "MIPhoneVersionCode") as? Int ?? 0 }

    /// Environment for adb and scrcpy: the bundled tools, and mDNS discovery for Wireless debugging.
    static var environment: [String: String] {
        var env = ProcessInfo.processInfo.environment
        if let adb { env["ADB"] = adb }
        if let scrcpyServer { env["SCRCPY_SERVER_PATH"] = scrcpyServer }
        env["ADB_MDNS_OPENSCREEN"] = "1"
        env["PATH"] = searchPaths.joined(separator: ":") + ":" + (env["PATH"] ?? "")
        return env
    }

    /// Runs a tool and returns its exit status and combined output. Blocking; call off the main thread.
    @discardableResult
    static func run(_ path: String, _ args: [String], timeout: TimeInterval = 60) -> (status: Int32, output: String) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = args
        p.environment = environment
        let out = Pipe()
        p.standardOutput = out
        p.standardError = out
        p.standardInput = FileHandle.nullDevice
        do { try p.run() } catch { return (-1, error.localizedDescription) }
        let timer = DispatchWorkItem { if p.isRunning { p.terminate() } }
        DispatchQueue.global().asyncAfter(deadline: .now() + timeout, execute: timer)
        let data = out.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        timer.cancel()
        return (p.terminationStatus, String(decoding: data, as: UTF8.self))
    }

    static func adb(_ args: [String], timeout: TimeInterval = 60) -> (status: Int32, output: String) {
        guard let adb else { return (-1, "adb is missing") }
        return run(adb, args, timeout: timeout)
    }

    /// Serials of devices adb can use right now, USB first.
    static func readyDevices() -> [String] {
        let lines = adb(["devices"], timeout: 15).output.split(separator: "\n").dropFirst()
        let ready = lines.compactMap { line -> String? in
            let parts = line.split(separator: "\t")
            return parts.count == 2 && parts[1] == "device" ? String(parts[0]) : nil
        }
        return ready.filter { !$0.contains(":") && !$0.contains("._tcp") } + ready.filter { $0.contains(":") || $0.contains("._tcp") }
    }
}
