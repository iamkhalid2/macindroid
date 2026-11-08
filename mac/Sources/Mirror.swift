import AppKit

/// Phone mirroring through scrcpy (Homebrew), the open-source Android screen mirror. Over USB it starts at once;
/// otherwise the phone switches on Wireless debugging just for the session and switches it off afterwards.
final class Mirror {
    private var process: Process?
    private var phoneCanPrepare = true
    private let searchPaths = ["/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"]

    private func tool(_ name: String) -> String? {
        searchPaths.map { "\($0)/\(name)" }.first { FileManager.default.isExecutableFile(atPath: $0) }
    }

    var available: Bool { tool("scrcpy") != nil && tool("adb") != nil }
    var running: Bool { process?.isRunning == true }

    func start(quietly: Bool = false) {
        if let p = process, p.isRunning {
            NSRunningApplication(processIdentifier: p.processIdentifier)?.activate()
            return
        }
        guard let adb = tool("adb"), let scrcpy = tool("scrcpy") else {
            if !quietly { alert("Mirroring needs scrcpy", "Install it once in Terminal with:  brew install scrcpy") }
            return
        }
        phoneCanPrepare = true
        DispatchQueue.global(qos: .userInitiated).async {
            if let serial = self.findDevice(adb) {
                DispatchQueue.main.async { self.launch(scrcpy, adb: adb, serial: serial) }
                return
            }
            DispatchQueue.main.sync { link.sendToPhone(.mirrorPrep) }
            let deadline = Date().addingTimeInterval(12)
            while Date() < deadline {
                self.connectAdvertised(adb)
                if let serial = self.findDevice(adb) {
                    DispatchQueue.main.async { self.launch(scrcpy, adb: adb, serial: serial) }
                    return
                }
                Thread.sleep(forTimeInterval: 1)
            }
            DispatchQueue.main.async {
                link.sendToPhone(.mirrorDone)
                guard !quietly else { return }
                if self.phoneCanPrepare {
                    self.alert("Couldn't reach your phone",
                               "Make sure both are on the same Wi-Fi, or plug the phone in with USB debugging on, then try again.")
                } else {
                    self.alert("Turn on Wireless debugging",
                               "On the phone: Settings → Developer options → Wireless debugging. Or run setup-phone.sh once with the phone plugged in, so Macindroid can switch it on for you.")
                }
            }
        }
    }

    func phoneReady(_ info: [String: Any]) {
        phoneCanPrepare = info["ok"] as? Bool ?? false
    }

    private func launch(_ scrcpy: String, adb: String, serial: String) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: scrcpy)
        // Like iPhone Mirroring: the phone's own screen stays dark while you use it from the Mac.
        p.arguments = ["--serial", serial, "--window-title", link.phoneName, "--stay-awake", "--turn-screen-off",
                       "--power-off-on-close", "--keyboard=uhid"]
        var env = ProcessInfo.processInfo.environment
        env["ADB"] = adb
        env["PATH"] = searchPaths.joined(separator: ":") + ":" + (env["PATH"] ?? "")
        p.environment = env
        let errors = Pipe()
        p.standardError = errors
        p.standardOutput = FileHandle.nullDevice
        let started = Date()
        p.terminationHandler = { [weak self] ended in
            let output = String(decoding: errors.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
            DispatchQueue.main.async {
                self?.process = nil
                link.sendToPhone(.mirrorDone)
                if ended.terminationStatus != 0, Date().timeIntervalSince(started) < 5 {
                    let reason = output.split(separator: "\n").last(where: { $0.contains("ERROR") }).map(String.init) ?? output
                    log("mirroring failed: \(output)")
                    self?.alert("Mirroring stopped", reason.isEmpty ? "scrcpy exited early." : reason)
                }
            }
        }
        do {
            try p.run()
            process = p
            log("mirroring \(serial)")
        } catch {
            alert("Couldn't start mirroring", error.localizedDescription)
        }
    }

    private func findDevice(_ adb: String) -> String? {
        let lines = run(adb, ["devices"]).split(separator: "\n").dropFirst()
        let ready = lines.compactMap { line -> String? in
            let parts = line.split(separator: "\t")
            return parts.count == 2 && parts[1] == "device" ? String(parts[0]) : nil
        }
        return ready.first(where: { !$0.contains(":") && !$0.contains("._tcp") }) ?? ready.first // prefer USB
    }

    /// Wireless debugging advertises `_adb-tls-connect._tcp`; a Mac this phone has trusted over USB can connect.
    private func connectAdvertised(_ adb: String) {
        for line in run(adb, ["mdns", "services"]).split(separator: "\n") where line.contains("_adb-tls-connect._tcp") {
            if let address = line.split(whereSeparator: { $0 == " " || $0 == "\t" }).last, address.contains(":") {
                _ = run(adb, ["connect", String(address)])
            }
        }
    }

    private func run(_ path: String, _ args: [String]) -> String {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = args
        let out = Pipe()
        p.standardOutput = out
        p.standardError = out
        guard (try? p.run()) != nil else { return "" }
        let data = out.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return String(decoding: data, as: UTF8.self)
    }

    private func alert(_ title: String, _ message: String) {
        NSApp.activate(ignoringOtherApps: true)
        let a = NSAlert()
        a.messageText = title
        a.informativeText = message
        a.runModal()
    }
}
