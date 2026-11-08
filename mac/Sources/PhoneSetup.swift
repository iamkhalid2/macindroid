import AppKit

/// A small floating window like the pairing panel: icon, heading, text, then whatever the setup needs.
private func setupWindow(width: CGFloat, delegate: NSWindowDelegate, views: [NSView]) -> NSPanel {
    let panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: width, height: 300),
                        styleMask: [.titled, .closable, .fullSizeContentView], backing: .buffered, defer: false)
    panel.titlebarAppearsTransparent = true
    panel.titleVisibility = .hidden
    panel.isReleasedWhenClosed = false
    panel.level = .floating
    panel.delegate = delegate
    let stack = NSStackView(views: views)
    stack.orientation = .vertical
    stack.alignment = .centerX
    stack.spacing = 12
    stack.edgeInsets = NSEdgeInsets(top: 36, left: 28, bottom: 24, right: 28)
    stack.translatesAutoresizingMaskIntoConstraints = false
    let content = NSView()
    content.addSubview(stack)
    NSLayoutConstraint.activate([
        stack.leadingAnchor.constraint(equalTo: content.leadingAnchor),
        stack.trailingAnchor.constraint(equalTo: content.trailingAnchor),
        stack.topAnchor.constraint(equalTo: content.topAnchor),
        stack.bottomAnchor.constraint(equalTo: content.bottomAnchor),
        stack.widthAnchor.constraint(equalToConstant: width),
    ])
    panel.contentView = content
    return panel
}

private func label(_ text: String, size: CGFloat, weight: NSFont.Weight = .regular, secondary: Bool = false,
                   width: CGFloat) -> NSTextField {
    let field = NSTextField(wrappingLabelWithString: text)
    field.font = .systemFont(ofSize: size, weight: weight)
    field.textColor = secondary ? .secondaryLabelColor : .labelColor
    field.alignment = .center
    field.preferredMaxLayoutWidth = width
    return field
}

// MARK: - the phone app

/// "Get the Phone App": the QR code for the phone app's download page, for a new phone or an update.
final class PhoneAppPanel: NSObject, NSWindowDelegate {
    private var panel: NSPanel?
    private let heading = label("", size: 17, weight: .semibold, width: 300)
    private let message = label("", size: 13, secondary: true, width: 300)
    private let qrBox = NSStackView()

    func show(update: Bool) {
        if panel == nil {
            qrBox.orientation = .vertical
            qrBox.spacing = 8
            let done = NSButton(title: "Done", target: self, action: #selector(close))
            done.keyEquivalent = "\r"
            panel = setupWindow(width: 360, delegate: self,
                                views: [NSImageView(image: Icon.appIconImage(points: 64)), heading, message, qrBox, done])
        }
        heading.stringValue = update ? "Update the Phone App" : "Get the Phone App"
        message.stringValue = (update ? "This Mac has a newer Macindroid for your phone. " : "")
            + "Scan this with the phone's camera, open the download and tap Install. If Android asks, allow installing "
            + "apps from your browser."
        if qrBox.arrangedSubviews.isEmpty { fillQR() }
        NSApp.activate(ignoringOtherApps: true)
        panel?.center()
        panel?.makeKeyAndOrderFront(nil)
    }

    private func fillQR() {
        qrBox.addArrangedSubview(QRCode.view(PhoneAppLink.url.absoluteString, points: 168))
        qrBox.addArrangedSubview(label("Macindroid \(PhoneAppLink.version) for Android, from GitHub", size: 11, secondary: true, width: 300))
    }

    @objc private func close() { panel?.close() }
}

// MARK: - power features

/// "Turn On Power Features": the one-time step that used to need Terminal, Homebrew and a cable. The phone pairs
/// with the Mac's bundled adb through Android's own Wireless debugging QR code (or over USB, if it's plugged in with
/// USB debugging on); the Mac then installs or updates the phone app and grants what only a computer can:
/// reading the clipboard log (so copies reach the Mac on their own) and switching Wireless debugging on just for
/// mirroring, plus the permissions Android would otherwise ask for one by one. Wireless debugging is switched off
/// again afterwards if it was switched on for this.
final class PowerSetupPanel: NSObject, NSWindowDelegate {
    private var panel: NSPanel?
    private let heading = label("Turn On Power Features", size: 17, weight: .semibold, width: 340)
    private let intro = label("Copies on your phone reach this Mac by themselves, and mirroring works over Wi-Fi. "
        + "It's a one-time step and needs no cable.", size: 13, secondary: true, width: 340)
    private let steps = NSStackView()
    private let qrBox = NSStackView()
    private let status = label("", size: 13, weight: .medium, width: 340)
    private let detail = label("", size: 12, secondary: true, width: 340)
    private let spinner = NSProgressIndicator()
    private let button = NSButton(title: "Cancel", target: nil, action: nil)
    private var session = UUID()

    func show() {
        if panel == nil { build() }
        start()
        NSApp.activate(ignoringOtherApps: true)
        panel?.center()
        panel?.makeKeyAndOrderFront(nil)
    }

    private func build() {
        steps.orientation = .vertical
        steps.alignment = .leading
        steps.spacing = 8
        for (n, text) in [
            "On the phone, turn on Developer options: tap Build number seven times. It's in Settings → About phone "
                + "(on some phones under Software information or Version). Macindroid on the phone opens it for you.",
            "Open Settings → Developer options → Wireless debugging. Turn it on, then tap Pair device with QR code.",
            "Scan this code with that screen.",
        ].enumerated() {
            let number = NSTextField(labelWithString: "\(n + 1)")
            number.font = .monospacedDigitSystemFont(ofSize: 12, weight: .bold)
            number.alignment = .center
            number.wantsLayer = true
            number.textColor = .white
            number.drawsBackground = true
            number.backgroundColor = .controlAccentColor
            number.layer?.cornerRadius = 9
            number.layer?.masksToBounds = true
            number.translatesAutoresizingMaskIntoConstraints = false
            number.widthAnchor.constraint(equalToConstant: 18).isActive = true
            let body = NSTextField(wrappingLabelWithString: text)
            body.font = .systemFont(ofSize: 13)
            body.preferredMaxLayoutWidth = 300
            let row = NSStackView(views: [number, body])
            row.alignment = .top
            row.spacing = 10
            steps.addArrangedSubview(row)
        }
        qrBox.orientation = .vertical
        spinner.style = .spinning
        spinner.controlSize = .small
        button.target = self
        button.action = #selector(close)
        button.keyEquivalent = "\u{1b}"
        let statusRow = NSStackView(views: [spinner, status])
        statusRow.spacing = 8
        panel = setupWindow(width: 400, delegate: self, views: [
            NSImageView(image: Icon.appIconImage(points: 64)), heading, intro, steps, qrBox, statusRow, detail, button,
        ])
    }

    private func start() {
        let token = UUID()
        session = token
        let name = "studio-" + Self.random(10, lowercase: true)
        let password = Self.random(12, lowercase: false)
        qrBox.arrangedSubviews.forEach { $0.removeFromSuperview() }
        qrBox.addArrangedSubview(QRCode.view("WIFI:T:ADB;S:\(name);P:\(password);;", points: 160))
        steps.isHidden = false
        qrBox.isHidden = false
        detail.stringValue = "Plugged in with USB debugging on? That works too: it starts by itself."
        button.title = "Cancel"
        button.keyEquivalent = "\u{1b}"
        setStatus("Waiting for your phone…", busy: true)
        guard Tools.adb != nil else {
            setStatus("This build of Macindroid is missing its phone tools.", busy: false)
            detail.stringValue = "Download Macindroid again from its GitHub page."
            return
        }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.waitForPhone(token: token, name: name, password: password)
        }
    }

    private func live(_ token: UUID) -> Bool {
        var current = false
        DispatchQueue.main.sync { current = self.session == token && self.panel?.isVisible == true }
        return current
    }

    private func ui(_ token: UUID, _ change: @escaping (PowerSetupPanel) -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.session == token else { return }
            change(self)
        }
    }

    private func setStatus(_ text: String, busy: Bool) {
        status.stringValue = text
        spinner.isHidden = !busy
        if busy { spinner.startAnimation(nil) } else { spinner.stopAnimation(nil) }
    }

    // MARK: work, off the main thread

    private func waitForPhone(token: UUID, name: String, password: String) {
        Tools.adb(["start-server"], timeout: 20)
        var serial: String?
        var pairedOverWiFi = false
        while serial == nil {
            guard live(token) else { return }
            if let ready = Tools.readyDevices().first {
                serial = ready
                break
            }
            if let address = Self.service(named: name, type: "_adb-tls-pairing._tcp") {
                ui(token) { $0.setStatus("Pairing…", busy: true) }
                let result = Tools.adb(["pair", address, password], timeout: 30)
                log("adb pair \(address): \(result.output.trimmingCharacters(in: .whitespacesAndNewlines))")
                guard result.output.contains("Successfully paired") else {
                    ui(token) {
                        $0.setStatus("Pairing didn't work. Try scanning again.", busy: false)
                        $0.button.title = "Try Again"
                        $0.button.keyEquivalent = "\r"
                    }
                    return
                }
                let host = String(address.split(separator: ":").first ?? "")
                ui(token) { $0.setStatus("Connecting…", busy: true) }
                serial = Self.connect(host: host, token: token, live: live)
                pairedOverWiFi = true
                if serial == nil {
                    ui(token) { $0.setStatus("Paired, but the phone didn't connect. Try again.", busy: false) }
                    return
                }
                break
            }
            Thread.sleep(forTimeInterval: 1)
        }
        guard let serial, live(token) else { return }
        ui(token) {
            $0.steps.isHidden = true
            $0.qrBox.isHidden = true
            $0.detail.stringValue = ""
            $0.setStatus("Setting up your phone…", busy: true)
        }
        let outcome = Self.setUp(serial: serial, progress: { text in self.ui(token) { $0.setStatus(text, busy: true) } })
        if pairedOverWiFi {
            // Switched on just for this; Macindroid switches it on again by itself when you mirror.
            Tools.adb(["-s", serial, "shell", "settings", "put", "global", "adb_wifi_enabled", "0"], timeout: 10)
            Tools.adb(["disconnect", serial], timeout: 10)
        }
        ui(token) { panel in
            panel.button.title = "Done"
            panel.button.keyEquivalent = "\r"
            if let problem = outcome {
                panel.setStatus("Something didn't work", busy: false)
                panel.detail.stringValue = problem
            } else {
                panel.setStatus("Power features are on", busy: false)
                panel.detail.stringValue = "Android may ask once whether Macindroid can access device logs: tap Allow. "
                    + (pairedOverWiFi ? "Wireless debugging is off again; Macindroid switches it on just while you mirror." : "")
            }
        }
    }

    /// The address of a Wireless debugging service the phone advertises on the network.
    private static func service(named name: String?, type: String) -> String? {
        for line in Tools.adb(["mdns", "services"], timeout: 10).output.split(separator: "\n") where line.contains(type) {
            if let name, !line.contains(name) { continue }
            if let address = line.split(whereSeparator: { $0 == " " || $0 == "\t" }).last, address.contains(":") {
                return String(address)
            }
        }
        return nil
    }

    /// After pairing, the phone advertises its debugging port; connect to it.
    private static func connect(host: String, token: UUID, live: (UUID) -> Bool) -> String? {
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline, live(token) {
            if let ready = Tools.readyDevices().first(where: { $0.hasPrefix(host + ":") || $0.contains("._tcp") }) { return ready }
            for line in Tools.adb(["mdns", "services"], timeout: 10).output.split(separator: "\n")
            where line.contains("_adb-tls-connect._tcp") && line.contains(host + ":") {
                if let address = line.split(whereSeparator: { $0 == " " || $0 == "\t" }).last {
                    Tools.adb(["connect", String(address)], timeout: 15)
                }
            }
            Thread.sleep(forTimeInterval: 1)
        }
        return nil
    }

    /// Installs or updates the phone app and grants its permissions. Returns a problem to show, or nil.
    static func setUp(serial: String, progress: (String) -> Void) -> String? {
        let pkg = Tools.package
        func shell(_ command: String...) -> String { Tools.adb(["-s", serial, "shell"] + command, timeout: 30).output }

        let installed = shell("dumpsys", "package", pkg)
        let version = installed.range(of: #"versionCode=(\d+)"#, options: .regularExpression)
            .flatMap { Int(installed[$0].dropFirst("versionCode=".count)) }
        if let apk = Tools.apk, version ?? 0 < Tools.apkVersionCode {
            progress(version == nil ? "Installing Macindroid on your phone…" : "Updating Macindroid on your phone…")
            let result = Tools.adb(["-s", serial, "install", "-r", apk.path], timeout: 180)
            log("installed phone app: \(result.output.trimmingCharacters(in: .whitespacesAndNewlines))")
            if !result.output.contains("Success") {
                if version == nil { return "Couldn't install Macindroid on the phone: \(result.output.suffix(200))" }
            }
        } else if version == nil, Tools.apk == nil {
            return "Macindroid isn't on the phone yet. Install it first, then try again."
        }

        progress("Turning on power features…")
        for command in [
            ["pm", "grant", pkg, "android.permission.READ_LOGS"],
            ["pm", "grant", pkg, "android.permission.WRITE_SECURE_SETTINGS"],
            ["pm", "grant", pkg, "android.permission.POST_NOTIFICATIONS"],
            ["pm", "grant", pkg, "android.permission.READ_MEDIA_IMAGES"],
            ["appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow"],
            ["appops", "set", pkg, "RECEIVE_SENSITIVE_NOTIFICATIONS", "allow"],
            ["cmd", "notification", "allow_listener", "\(pkg)/\(pkg).NotifListener"],
            ["dumpsys", "deviceidle", "whitelist", "+\(pkg)"],
        ] {
            let out = Tools.adb(["-s", serial, "shell"] + command, timeout: 30).output.trimmingCharacters(in: .whitespacesAndNewlines)
            log("phone setup: \(command.joined(separator: " "))\(out.isEmpty ? "" : " → \(out.prefix(160))")")
        }
        let after = shell("dumpsys", "package", pkg)
        let granted = ["READ_LOGS", "WRITE_SECURE_SETTINGS"].allSatisfy { after.contains("android.permission.\($0): granted=true") }
        shell("am", "start", "-n", "\(pkg)/.Launcher")
        return granted ? nil : "Android didn't allow the clipboard-log permission. Check that the phone is unlocked and try again."
    }

    private static func random(_ count: Int, lowercase: Bool) -> String {
        let chars = Array(lowercase ? "abcdefghijkmnpqrstuvwxyz23456789" : "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789")
        return String((0..<count).map { _ in chars.randomElement()! })
    }

    @objc private func close() {
        if button.title == "Try Again" { start(); return }
        panel?.close()
    }

    func windowWillClose(_ notification: Notification) {
        session = UUID() // stops the search
    }
}
