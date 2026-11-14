import AppKit

/// Calls. A call ringing on the phone shows under the menu-bar icon with Answer and Decline, the way an iPhone's do
/// on a Mac; the buttons press the phone's own. The call itself stays on the phone (Android gives another device no
/// way to carry its sound), so Answer picks it up there, on its speaker or headset.
///
/// And dialling: a phone-number link (tel:) clicked on the Mac asks first, since any web page can carry one, then
/// has the phone place the call. Who is calling and what was dialled are never logged.
final class Calls {
    private var card: CallCard?
    private var ringing: String? // the id of the call on the card
    private var dialling = ""

    /// CALL from the phone: 4-byte JSON length | JSON {id, state, name, app, answer, decline} | optional PNG (the caller's photo).
    func handle(_ payload: Data) {
        guard payload.count >= 4 else { return }
        let length = payload.prefix(4).reduce(0) { ($0 << 8) | Int($1) }
        guard payload.count >= 4 + length, let info = parseJSON(Data(payload.dropFirst(4).prefix(length))),
              let id = info["id"] as? String else { return }
        guard info["state"] as? String == "ringing" else {
            if id == ringing { ended() }
            return
        }
        guard Prefs.flag("showCalls") else { return }
        let app = info["app"] as? String ?? "Phone"
        let name = (info["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "Unknown caller"
        if card == nil { card = CallCard(calls: self) }
        ringing = id
        card?.show(name: name, detail: "\(app) · \(link.phoneName)", photo: NSImage(data: Data(payload.dropFirst(4 + length))),
                   canAnswer: info["answer"] as? Bool ?? false, canDecline: info["decline"] as? Bool ?? false, under: photos.anchor())
        log("\(link.phoneName): a call is ringing (\(app))")
    }

    /// It stopped ringing (answered, declined, missed), or the phone went.
    func ended() {
        guard ringing != nil else { return }
        ringing = nil
        card?.dismiss()
    }

    func act(_ action: String) {
        guard let id = ringing else { return }
        link.sendToPhone(.callAction, json(["id": id, "action": action]))
        log("\(action) the call, from the Mac")
    }

    // MARK: dialling

    /// A tel: link was opened with Macindroid.
    func dial(_ url: URL) {
        let raw = (url.absoluteString.removingPercentEncoding ?? url.absoluteString).dropFirst(4)
        let shown = raw.split(separator: ";")[safe: 0].map(String.init)?.trimmingCharacters(in: CharacterSet(charactersIn: "/ ")) ?? ""
        let number = shown.filter { $0.isNumber && $0.isASCII || "+*#,".contains($0) }
        guard !number.isEmpty, number.count <= 40 else { return }
        guard link.connected else {
            notifications.postLocal(id: "dial", title: "\(link.phoneName) isn't connected", body: "Couldn't call \(shown).", info: [:])
            return
        }
        if !Prefs.debug { // test_client.py can't press a button
            let alert = NSAlert()
            alert.messageText = "Call \(shown)?"
            alert.informativeText = "\(link.phoneName) places the call."
            alert.addButton(withTitle: "Call")
            alert.addButton(withTitle: "Cancel")
            NSApp.activate(ignoringOtherApps: true)
            guard alert.runModal() == .alertFirstButtonReturn else { return }
        }
        dialling = shown
        link.sendToPhone(.dial, Data(number.utf8))
        log("asked \(link.phoneName) to dial a number")
    }

    /// DIAL back from the phone: JSON {how}.
    func dialResult(_ payload: Data) {
        let how = parseJSON(payload)?["how"] as? String ?? ""
        let phone = link.phoneName
        let (title, body): (String, String) = switch how {
        case "call": ("Calling \(dialling)", "On \(phone). Pick it up there.")
        case "notification": ("Tap the notification on \(phone)", "It opens the dialer with \(dialling). To skip this step, allow Phone calls in Macindroid on the phone.")
        case "off": ("Calls are switched off", "Switch Calls on in Macindroid on \(phone).")
        default: ("Couldn't call \(dialling)", "\(phone) wasn't able to place the call.")
        }
        notifications.postLocal(id: "dial", title: title, body: body, info: [:])
        log("dial: \(how)")
    }

    /// Whether phone-number links on this Mac open in Macindroid (otherwise FaceTime has them).
    static var handlesPhoneLinks: Bool {
        get { NSWorkspace.shared.urlForApplication(toOpen: URL(string: "tel:0")!)?.standardizedFileURL == Bundle.main.bundleURL.standardizedFileURL }
        set {
            let app = newValue ? Bundle.main.bundleURL : URL(fileURLWithPath: "/System/Applications/FaceTime.app")
            NSWorkspace.shared.setDefaultApplication(at: app, toOpenURLsWithScheme: "tel") { error in
                if let error { log("couldn't change who opens phone links: \(error.localizedDescription)") }
            }
        }
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}

/// The ringing-call card: who, from which app, and the two buttons. It stays for as long as the phone rings.
final class CallCard: NSPanel {
    private static let width: CGFloat = 320, pad: CGFloat = 14
    private weak var calls: Calls?
    private let picture = NSImageView()
    private let nameLabel = MenuStyle.label("", size: 14, weight: .semibold)
    private let detailLabel = MenuStyle.label("", size: 11, colour: .secondaryLabelColor)
    private let answerButton = CardButton(primary: true)
    private let declineButton = CardButton(primary: true)
    private let closeButton = NSButton()
    private let content = NSView()

    init(calls: Calls) {
        self.calls = calls
        super.init(contentRect: NSRect(x: 0, y: 0, width: Self.width, height: 110),
                   styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        isFloatingPanel = true
        level = .statusBar
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .transient, .ignoresCycle]
        hidesOnDeactivate = false
        becomesKeyOnlyIfNeeded = true
        isReleasedWhenClosed = false
        backgroundColor = .clear
        isOpaque = false
        hasShadow = true
        animationBehavior = .none

        picture.wantsLayer = true
        picture.layer?.cornerRadius = 20
        picture.layer?.masksToBounds = true
        picture.imageScaling = .scaleProportionallyUpOrDown
        closeButton.image = NSImage(systemSymbolName: "xmark.circle.fill", accessibilityDescription: "Hide")
        closeButton.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 15, weight: .regular)
        closeButton.contentTintColor = .tertiaryLabelColor
        closeButton.isBordered = false
        closeButton.target = self
        closeButton.action = #selector(dismiss)
        closeButton.toolTip = "Hide (the phone keeps ringing)"
        answerButton.tint = .systemGreen
        declineButton.tint = .systemRed
        for (button, title, action) in [(answerButton, "Answer", #selector(answer)), (declineButton, "Decline", #selector(decline))] {
            button.attributedTitle = NSAttributedString(string: title, attributes: [
                .font: NSFont.systemFont(ofSize: 13, weight: .semibold), .foregroundColor: NSColor.white])
            button.target = self
            button.action = action
        }
        answerButton.toolTip = "Picks up on the phone: the call's sound stays there"
        for view in [picture, nameLabel, detailLabel, closeButton, answerButton, declineButton] as [NSView] { content.addSubview(view) }
        if #available(macOS 26.0, *) {
            let glass = NSGlassEffectView()
            glass.cornerRadius = 18
            glass.contentView = content
            contentView = glass
        } else {
            let blur = NSVisualEffectView()
            blur.material = .popover
            blur.state = .active
            blur.wantsLayer = true
            blur.layer?.cornerRadius = 14
            blur.layer?.masksToBounds = true
            blur.addSubview(content)
            content.autoresizingMask = [.width, .height]
            contentView = blur
        }
    }

    func show(name: String, detail: String, photo: NSImage?, canAnswer: Bool, canDecline: Bool, under button: NSStatusBarButton?) {
        nameLabel.stringValue = name
        detailLabel.stringValue = canAnswer || canDecline ? detail : "\(detail) · answer on the phone"
        if let photo {
            picture.image = photo
            picture.contentTintColor = nil
        } else {
            picture.image = NSImage(systemSymbolName: "phone.circle.fill", accessibilityDescription: nil)
            picture.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 34, weight: .regular)
            picture.contentTintColor = .systemGreen
        }
        answerButton.isHidden = !canAnswer
        declineButton.isHidden = !canDecline
        answerButton.isEnabled = true
        declineButton.isEnabled = true

        let w = Self.width, p = Self.pad, buttons: CGFloat = canAnswer || canDecline ? 32 + 12 : 0
        let h = p + buttons + 40 + p
        setContentSize(NSSize(width: w, height: h))
        content.frame = NSRect(x: 0, y: 0, width: w, height: h)
        let half = (w - 2 * p - 8) / 2
        if canAnswer && canDecline {
            declineButton.frame = NSRect(x: p, y: p, width: half, height: 32)
            answerButton.frame = NSRect(x: p + half + 8, y: p, width: half, height: 32)
        } else {
            (canAnswer ? answerButton : declineButton).frame = NSRect(x: p, y: p, width: w - 2 * p, height: 32)
        }
        let top = p + buttons
        picture.frame = NSRect(x: p, y: top, width: 40, height: 40)
        nameLabel.frame = NSRect(x: p + 50, y: top + 20, width: w - 2 * p - 50 - 26, height: 18)
        detailLabel.frame = NSRect(x: p + 50, y: top + 3, width: w - 2 * p - 50 - 26, height: 15)
        closeButton.frame = NSRect(x: w - p - 20, y: top + 18, width: 20, height: 20)

        let size = frame.size
        if let button, let window = button.window, window.occlusionState.contains(.visible), let screen = window.screen {
            let icon = window.convertToScreen(button.convert(button.bounds, to: nil))
            let visible = screen.visibleFrame
            let x = min(max(icon.midX - size.width / 2, visible.minX + 8), visible.maxX - size.width - 8)
            setFrameOrigin(NSPoint(x: x, y: icon.minY - 6 - size.height))
        } else if let visible = NSScreen.main?.visibleFrame {
            setFrameOrigin(NSPoint(x: visible.maxX - size.width - 12, y: visible.maxY - size.height - 8))
        }
        alphaValue = 1
        orderFrontRegardless()
        NSAccessibility.post(element: NSApp as Any, notification: .announcementRequested,
                             userInfo: [.announcement: "\(name) is calling \(link.phoneName).",
                                        .priority: NSAccessibilityPriorityLevel.high.rawValue])
    }

    @objc private func answer() { pressed("answer") }
    @objc private func decline() { pressed("decline") }

    private func pressed(_ action: String) {
        answerButton.isEnabled = false
        declineButton.isEnabled = false
        calls?.act(action)
        // The phone says when it stops ringing; if that never comes, don't sit there for ever.
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in self?.calls?.ended() }
    }

    @objc func dismiss() { orderOut(nil) }
}
