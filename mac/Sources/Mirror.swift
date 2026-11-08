import AppKit

/// Phone mirroring through scrcpy (bundled), the open-source Android screen mirror. Over USB it starts at once;
/// otherwise the phone switches on Wireless debugging just for the session and switches it off afterwards.
final class Mirror {
    private var process: Process?
    private var phoneCanPrepare = true
    private func tool(_ name: String) -> String? { Tools.path(name) }

    var available: Bool { tool("scrcpy") != nil && tool("adb") != nil }
    var running: Bool { process?.isRunning == true }

    func start(quietly: Bool = false) {
        if let p = process, p.isRunning {
            NSRunningApplication(processIdentifier: p.processIdentifier)?.activate()
            return
        }
        guard let adb = tool("adb"), let scrcpy = tool("scrcpy") else {
            if !quietly { alert("Mirroring isn't included in this build", "Download Macindroid again from its GitHub page.") }
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
                               "On the phone: Settings → Developer options → Wireless debugging. Or choose Settings → Set Up Power Features… once, so Macindroid can switch it on for you.")
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
        var env = Tools.environment
        env["ADB"] = adb
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

    private func findDevice(_ adb: String) -> String? { Tools.readyDevices().first } // USB first

    /// Wireless debugging advertises `_adb-tls-connect._tcp`; a Mac this phone has trusted over USB can connect.
    private func connectAdvertised(_ adb: String) {
        for line in run(adb, ["mdns", "services"]).split(separator: "\n") where line.contains("_adb-tls-connect._tcp") {
            if let address = line.split(whereSeparator: { $0 == " " || $0 == "\t" }).last, address.contains(":") {
                _ = run(adb, ["connect", String(address)])
            }
        }
    }

    private func run(_ path: String, _ args: [String]) -> String { Tools.run(path, args, timeout: 20).output }

    private func alert(_ title: String, _ message: String) {
        NSApp.activate(ignoringOtherApps: true)
        let a = NSAlert()
        a.messageText = title
        a.informativeText = message
        a.runModal()
    }
}
