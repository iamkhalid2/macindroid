import AppKit

/// Menu-bar UI. Everything lives in one menu; the windows are only for setting up (pairing, the phone app, power
/// features).
final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private var status: StatusBarItem!
    private let menu = NSMenu()
    private let pairing = PairingPanel()
    private let phoneApp = PhoneAppPanel()
    private let power = PowerSetupPanel()
    private var menuOpen = false
    private var wasConnected = false
    // Live parts of the open menu, updated in place; the menu is rebuilt only when its shape changes.
    private var header: PhoneHeaderView?
    private var transferRows: [String: TransferRowView] = [:]
    private var shape = ""

    private static let browsers: [String: String] = [
        "com.apple.Safari": "Safari", "com.google.Chrome": "Chrome", "com.microsoft.edgemac": "Edge",
        "com.brave.Browser": "Brave", "company.thebrowser.Browser": "Arc", "com.vivaldi.Vivaldi": "Vivaldi",
    ]

    func applicationDidFinishLaunching(_ notification: Notification) {
        status = StatusBarItem()
        if let button = status.item.button {
            button.toolTip = "Macindroid — drop files here to send them to your phone"
            let drop = DropView(frame: button.bounds)
            drop.autoresizingMask = [.width, .height]
            button.addSubview(drop)
        }
        photos.anchor = { [weak self] in self?.status.item.button }
        menu.delegate = self
        menu.autoenablesItems = false
        menu.minimumWidth = MenuStyle.width
        status.item.menu = menu

        link.onChange = { [weak self] in self?.changed() }
        link.onPairRequest = { [weak self] peer, code in self?.pairing.request(from: peer, code: code) }
        link.onPowerSetupRequest = { [weak self] in self?.power.show() }
        updates.onChange = { [weak self] in self?.changed() }
        updates.start()
        notifications.setup()
        clipboard.start()
        do { try link.start() } catch { log("could not listen on port \(listenPort): \(error)") }
        changed()
        LoginItem.setUpOnFirstLaunch()
        if Store.key == nil { pairing.show() }
    }

    /// Finder's Open With → Macindroid sends files to the phone. macindroid://ring, stop-ring, mirror, menu,
    /// power-setup and phone-app let
    /// Shortcuts, Spotlight or a launcher reach the phone. (Deliberately no URL for sending files: any web page can
    /// open a URL.)
    func application(_ application: NSApplication, open urls: [URL]) {
        let files = urls.filter(\.isFileURL)
        if !files.isEmpty { transfers.send(files) }
        for url in urls where url.scheme == "macindroid" {
            switch url.host {
            case "ring": if !link.ringing { ringPhone() }
            case "stop-ring": if link.ringing { ringPhone() }
            case "mirror": mirror.start()
            case "power-setup": power.show()
            case "phone-app": phoneApp.show(update: false)
            case "menu": openMenu(closingAfter: URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?.first { $0.name == "close" }?.value.flatMap(Double.init))
            default: log("unknown action \(url.absoluteString)")
            }
        }
    }

    private func openMenu(closingAfter seconds: Double?) {
        DispatchQueue.main.async { [self] in
            if let seconds {
                let timer = Timer(timeInterval: seconds, repeats: false) { [weak self] _ in self?.menu.cancelTracking() }
                RunLoop.main.add(timer, forMode: .common)
            }
            // From the menu-bar icon when it's showing; otherwise (a full-screen app hides the menu bar) at the pointer.
            if let button = status.item.button, button.window?.occlusionState.contains(.visible) == true {
                button.performClick(nil)
            } else {
                menu.popUp(positioning: nil, at: NSEvent.mouseLocation, in: nil)
            }
        }
    }

    private func changed() {
        status.update(connected: link.connected, progress: transfers.overallProgress, ringing: link.ringing)
        if wasConnected != link.connected {
            wasConnected = link.connected
            notifications.connectionChanged()
        }
        guard menuOpen else { return }
        if currentShape() == shape { refreshLive() } else { rebuild() }
    }

    func menuNeedsUpdate(_ menu: NSMenu) { rebuild() }
    func menuWillOpen(_ menu: NSMenu) { menuOpen = true }

    func menuDidClose(_ menu: NSMenu) {
        menuOpen = false
        header = nil
        transferRows = [:]
    }

    /// What the menu's items are, apart from live values (battery, progress).
    private func currentShape() -> String {
        [Store.key == nil, link.connected, link.ringing, mirror.running, mirror.available,
         transfers.waiting > 0, browserTarget().0, browserTarget().1, updates.latest?.version ?? "", phoneAppOutdated,
         link.status?.power ?? true].map { "\($0)" }.joined(separator: "|")
            + transfers.live.map(\.key).joined(separator: ",") + "|\(transfers.recent.count)"
    }

    private func rebuild() {
        shape = currentShape()
        menu.removeAllItems()
        transferRows = [:]
        let headerView = PhoneHeaderView()
        header = headerView
        let headerItem = NSMenuItem()
        headerItem.view = headerView
        menu.addItem(headerItem)
        menu.addItem(.separator())

        if Store.key == nil {
            menu.addItem(item("Pair a Phone…", #selector(pairPhone), tile: "link.badge.plus", tint: .controlAccentColor))
        } else {
            let on = link.connected
            menu.addItem(item("Send Files…", #selector(sendFiles), enabled: on, tile: "arrow.up.doc.fill"))
            let (tabTitle, tabEnabled) = browserTarget()
            let front = NSWorkspace.shared.frontmostApplication?.bundleIdentifier
            menu.addItem(item(tabTitle, #selector(sendTab), enabled: on && tabEnabled,
                              tile: front == "com.apple.Safari" ? "safari.fill" : "globe"))
            menu.addItem(item(mirror.running ? "Show Phone Window" : "Mirror Phone", #selector(mirrorPhone),
                              enabled: mirror.available && (on || mirror.running),
                              tile: "rectangle.portrait.on.rectangle.portrait.fill",
                              tint: mirror.running ? .controlAccentColor : nil))
            menu.addItem(item(link.ringing ? "Stop Ringing" : "Ring Phone", #selector(ringPhone), enabled: on,
                              tile: link.ringing ? "bell.slash.fill" : "bell.and.waves.left.and.right.fill",
                              tint: link.ringing ? .systemRed : nil, animation: link.ringing ? .wiggle : .none))

            let moving = transfers.live
            if !moving.isEmpty || transfers.waiting > 0 {
                menu.addItem(.separator())
                menu.addItem(.sectionHeader(title: transfers.waiting > 0 ? "Transfers · \(transfers.waiting) waiting" : "Transfers"))
                for t in moving {
                    let row = TransferRowView(toPhone: t.toPhone, name: t.name)
                    transferRows[t.key] = row
                    let rowItem = NSMenuItem()
                    rowItem.view = row
                    menu.addItem(rowItem)
                }
            }

            if !transfers.recent.isEmpty {
                menu.addItem(.separator())
                menu.addItem(.sectionHeader(title: "Recent"))
                let when = RelativeDateTimeFormatter()
                when.unitsStyle = .short
                for (index, done) in transfers.recent.prefix(4).enumerated() {
                    let entry = item(MenuStyle.fit(done.name), #selector(openRecent(_:)), enabled: done.url != nil)
                    entry.tag = index
                    entry.toolTip = done.name
                    entry.image = MenuStyle.fileIcon(for: done.url)
                    if #available(macOS 27.0, *) { entry.preferredImageVisibility = .visible }
                    let direction = done.toPhone ? "To \(link.phoneName)" : "From \(link.phoneName)"
                    let ago = Date().timeIntervalSince(done.date) < 60 ? "just now" : when.localizedString(for: done.date, relativeTo: Date())
                    if #available(macOS 14.4, *) { entry.subtitle = "\(direction) · \(ago)" } else { entry.toolTip = "\(done.name)\n\(direction)" }
                    menu.addItem(entry)
                }
            }
        }
        if let update = updates.latest {
            menu.addItem(.separator())
            let get = item("Update Available: Macindroid \(update.version)", #selector(openUpdate), tile: "arrow.down.circle.fill",
                           tint: .controlAccentColor)
            menu.addItem(get)
        }
        if Store.key != nil, link.connected, phoneAppOutdated || link.status?.power == false {
            if updates.latest == nil { menu.addItem(.separator()) }
            if phoneAppOutdated {
                menu.addItem(item("Update the Phone App…", #selector(updatePhoneApp), tile: "arrow.down.app.fill", tint: .controlAccentColor))
            }
            if link.status?.power == false {
                menu.addItem(item("Turn On Power Features…", #selector(setUpPower), tile: "bolt.fill"))
            }
        }
        menu.addItem(.separator())
        menu.addItem(settingsItem())
        menu.addItem(item("Quit Macindroid", #selector(quit), key: "q"))
        refreshLive()
    }

    /// Values that change while the menu is open: the header and the progress rows.
    private func refreshLive() {
        header?.update(headerModel())
        for t in transfers.live { transferRows[t.key]?.update(done: t.done, total: t.total) }
    }

    private func headerModel() -> PhoneHeaderView.Model {
        if Store.key == nil {
            return .init(name: "No Phone Paired", detail: "Pair your Android phone to begin", mode: .unpaired,
                         ringing: false, battery: nil, charging: false)
        }
        guard link.connected else {
            return .init(name: link.phoneName, detail: "Looking for your phone · same Wi-Fi?", mode: .searching,
                         ringing: false, battery: nil, charging: false)
        }
        var detail = link.ringing ? "Ringing" : mirror.running ? "Connected · Mirroring" : "Connected"
        if let s = link.status, s.charging, !link.ringing { detail += s.battery >= 100 ? " · Charged" : " · Charging" }
        let battery = link.status.flatMap { $0.battery >= 0 ? $0.battery : nil }
        return .init(name: link.phoneName, detail: detail, mode: .connected, ringing: link.ringing,
                     battery: battery, charging: link.status?.charging ?? false)
    }

    private func settingsItem() -> NSMenuItem {
        let settings = NSMenuItem(title: "Settings", action: nil, keyEquivalent: "")
        let sub = NSMenu()
        sub.autoenablesItems = false
        for (title, key) in [("Sync Clipboard", "syncClipboard"), ("Copy Codes from Phone", "copyCodes"),
                             ("Show Phone Notifications", "phoneNotifications"),
                             ("Open Links from Phone Automatically", "openLinks"),
                             ("Show New Photos from Phone", "photoPreviews"), ("Check for Updates", "checkUpdates")] {
            let toggle = item(title, #selector(togglePref(_:)))
            toggle.representedObject = key
            toggle.state = Prefs.flag(key) ? .on : .off
            sub.addItem(toggle)
        }
        let login = item("Open at Login", #selector(toggleLogin), enabled: !LoginItem.managedByAgent)
        login.state = LoginItem.enabled ? .on : .off
        if LoginItem.managedByAgent { login.toolTip = "Started at login by the LaunchAgent that install.sh set up" }
        sub.addItem(login)
        sub.addItem(.separator())
        sub.addItem(item("Get the Phone App…", #selector(getPhoneApp)))
        sub.addItem(item("Set Up Power Features…", #selector(setUpPower), enabled: Tools.adb != nil))
        sub.addItem(item(Store.key == nil ? "Pair a Phone…" : "Pair a Different Phone…", #selector(pairPhone)))
        settings.submenu = sub
        return settings
    }

    /// A menu item; with `tile`, a row with a Liquid Glass symbol tile like the system's menu extras.
    private func item(_ title: String, _ action: Selector, enabled: Bool = true, tile: String? = nil,
                      tint: NSColor? = nil, animation: GlassTile.Animation = .none, key: String = "") -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: key)
        item.target = self
        item.isEnabled = enabled
        if let tile {
            item.view = GlassMenuRow(title: title, symbol: tile, tint: tint, enabled: enabled, animation: animation)
        }
        return item
    }

    // MARK: actions

    @objc private func pairPhone() { pairing.show() }
    @objc private func getPhoneApp() { phoneApp.show(update: false) }
    @objc private func updatePhoneApp() { phoneApp.show(update: true) }
    @objc private func setUpPower() { power.show() }
    @objc private func openUpdate() { if let page = updates.latest?.page { NSWorkspace.shared.open(page) } }

    /// The phone runs an older Macindroid than the one bundled in this Mac app.
    private var phoneAppOutdated: Bool {
        guard let v = link.status?.version, v > 0, Tools.apk != nil else { return false }
        return v < Tools.apkVersionCode
    }

    @objc private func sendFiles() {
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.prompt = "Send"
        panel.message = "Choose files to send to \(link.phoneName)"
        if panel.runModal() == .OK { transfers.send(panel.urls) }
    }

    /// The front browser tab if a browser is in front, otherwise a link on the clipboard.
    private func browserTarget() -> (String, Bool) {
        if let app = NSWorkspace.shared.frontmostApplication, let id = app.bundleIdentifier, let name = Self.browsers[id] {
            return ("Open \(name) Tab on Phone", true)
        }
        if clipboard.copiedURL() != nil { return ("Open Copied Link on Phone", true) }
        return ("Open Browser Tab on Phone", false)
    }

    @objc private func sendTab() {
        var url: URL?
        if let id = NSWorkspace.shared.frontmostApplication?.bundleIdentifier, Self.browsers[id] != nil {
            let source = id == "com.apple.Safari"
                ? "tell application id \"\(id)\" to return URL of front document"
                : "tell application id \"\(id)\" to return URL of active tab of front window"
            var error: NSDictionary?
            if let text = NSAppleScript(source: source)?.executeAndReturnError(&error).stringValue { url = URL(string: text) }
            if let error { log("could not read the browser tab: \(error)") }
        }
        guard let target = url ?? clipboard.copiedURL() else { return }
        link.sendToPhone(.link, Data(target.absoluteString.utf8))
        transfers.recordLink(target, toPhone: true)
    }

    @objc private func mirrorPhone() { mirror.start() }

    @objc private func ringPhone() {
        guard link.connected else {
            notifications.postLocal(id: "offline", title: "Can't ring your phone",
                                    body: "\(Store.phoneName ?? "Your phone") isn't connected.", info: [:])
            return
        }
        link.ringing.toggle()
        link.sendToPhone(link.ringing ? .ring : .ringStop)
    }

    @objc private func openRecent(_ sender: NSMenuItem) {
        guard transfers.recent.indices.contains(sender.tag), let url = transfers.recent[sender.tag].url else { return }
        if url.isFileURL { NSWorkspace.shared.activateFileViewerSelecting([url]) } else { NSWorkspace.shared.open(url) }
    }

    @objc private func togglePref(_ sender: NSMenuItem) {
        guard let key = sender.representedObject as? String else { return }
        Prefs.toggle(key)
        if key == "checkUpdates" { updates.check() }
    }

    @objc private func toggleLogin() { LoginItem.set(!LoginItem.enabled) }

    @objc private func quit() { NSApp.terminate(nil) }
}

/// Lets files be dropped on the menu-bar icon. Clicks pass through to the status button.
final class DropView: NSView {
    override init(frame: NSRect) {
        super.init(frame: frame)
        registerForDraggedTypes([.fileURL])
    }

    required init?(coder: NSCoder) { nil }

    private var button: NSButton? { superview as? NSButton }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        guard link.connected else { return [] }
        button?.highlight(true)
        return .copy
    }

    override func draggingExited(_ sender: NSDraggingInfo?) { button?.highlight(false) }
    override func draggingEnded(_ sender: NSDraggingInfo) { button?.highlight(false) }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        let urls = sender.draggingPasteboard.readObjects(forClasses: [NSURL.self],
                                                          options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
        guard !urls.isEmpty, link.connected else { return false }
        transfers.send(urls)
        return true
    }
}
