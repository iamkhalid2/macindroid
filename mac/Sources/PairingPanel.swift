import AppKit

/// The first-run window. It shows a QR code that downloads the phone app onto the phone (see PhoneAppLink);
/// while it is open the Mac accepts pairing requests, and the user compares the 6-digit code with the phone's and
/// clicks Pair.
final class PairingPanel: NSObject, NSWindowDelegate {
    private var panel: NSPanel?
    private let heading = NSTextField(labelWithString: "")
    private let message = NSTextField(wrappingLabelWithString: "")
    private let code = NSTextField(labelWithString: "")
    private let spinner = NSProgressIndicator()
    private let pairButton = NSButton(title: "Pair", target: nil, action: nil)
    private let cancelButton = NSButton(title: "Cancel", target: nil, action: nil)
    private var peer: Peer?
    private let qrBox = NSStackView()
    private let urlLabel = NSTextField(labelWithString: "")

    func show() {
        link.pairingMode = true
        if panel == nil { build() }
        if qrBox.arrangedSubviews.isEmpty { fillQR() }
        showWaiting()
        NSApp.activate(ignoringOtherApps: true)
        panel?.center()
        panel?.makeKeyAndOrderFront(nil)
    }

    func request(from peer: Peer, code digits: String) {
        guard panel?.isVisible == true else { link.declinePairing(peer); return }
        if let previous = self.peer, previous !== peer { link.declinePairing(previous) }
        self.peer = peer
        heading.stringValue = "Pair with \(peer.name)?"
        message.stringValue = "Check that your phone shows the same code."
        code.stringValue = String(digits.prefix(3)) + " " + String(digits.suffix(3))
        code.isHidden = false
        qrBox.isHidden = true
        spinner.stopAnimation(nil)
        spinner.isHidden = true
        pairButton.isHidden = false
        cancelButton.title = "Don't Pair"
        NSApp.activate(ignoringOtherApps: true)
        panel?.makeKeyAndOrderFront(nil)
    }

    private func showWaiting() {
        peer = nil
        heading.stringValue = "Set Up Your Phone"
        message.stringValue = "No Macindroid on your Android phone yet? Scan this with its camera, open the download and "
            + "tap Install. Then open Macindroid on the phone and choose “\(macName)”. Keep both on the same Wi-Fi."
        qrBox.isHidden = false
        code.isHidden = true
        spinner.isHidden = false
        spinner.startAnimation(nil)
        pairButton.isHidden = true
        cancelButton.title = "Cancel"
    }

    @objc private func pair() {
        guard let peer else { return }
        self.peer = nil
        link.confirmPairing(peer)
        notifications.postLocal(id: "paired", title: "Paired with \(peer.name)",
                                body: "Find Macindroid in the menu bar.", info: [:])
        panel?.close()
    }

    @objc private func cancel() {
        panel?.close()
    }

    func windowWillClose(_ notification: Notification) {
        if let peer { link.declinePairing(peer) }
        peer = nil
        link.pairingMode = false
        spinner.stopAnimation(nil)
    }

    /// The QR code that downloads the phone app.
    private func fillQR() {
        qrBox.addArrangedSubview(QRCode.view(PhoneAppLink.url.absoluteString, points: 160))
        urlLabel.stringValue = "Macindroid \(PhoneAppLink.version) for Android, from GitHub"
        qrBox.addArrangedSubview(urlLabel)
    }

    private func build() {
        let panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 380, height: 300),
                            styleMask: [.titled, .closable, .fullSizeContentView], backing: .buffered, defer: false)
        panel.titlebarAppearsTransparent = true
        panel.titleVisibility = .hidden
        panel.isReleasedWhenClosed = false
        panel.level = .floating
        panel.delegate = self

        let icon = NSImageView(image: Icon.appIconImage(points: 72))
        heading.font = .systemFont(ofSize: 17, weight: .semibold)
        heading.alignment = .center
        message.font = .systemFont(ofSize: 13)
        message.textColor = .secondaryLabelColor
        message.alignment = .center
        message.preferredMaxLayoutWidth = 300
        code.font = .monospacedDigitSystemFont(ofSize: 34, weight: .semibold)
        code.alignment = .center
        spinner.style = .spinning
        spinner.controlSize = .small

        pairButton.target = self
        pairButton.action = #selector(pair)
        pairButton.keyEquivalent = "\r"
        pairButton.bezelStyle = .rounded
        cancelButton.target = self
        cancelButton.action = #selector(cancel)
        cancelButton.keyEquivalent = "\u{1b}"
        cancelButton.bezelStyle = .rounded
        let buttons = NSStackView(views: [cancelButton, pairButton])
        buttons.spacing = 10

        qrBox.orientation = .vertical
        qrBox.alignment = .centerX
        qrBox.spacing = 8
        urlLabel.font = .systemFont(ofSize: 11)
        urlLabel.textColor = .tertiaryLabelColor
        urlLabel.isSelectable = true
        let stack = NSStackView(views: [icon, heading, message, qrBox, code, spinner, buttons])
        stack.orientation = .vertical
        stack.alignment = .centerX
        stack.spacing = 12
        stack.setCustomSpacing(18, after: icon)
        stack.setCustomSpacing(20, after: code)
        stack.edgeInsets = NSEdgeInsets(top: 36, left: 28, bottom: 24, right: 28)
        stack.translatesAutoresizingMaskIntoConstraints = false
        let content = NSView()
        content.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            stack.topAnchor.constraint(equalTo: content.topAnchor),
            stack.bottomAnchor.constraint(equalTo: content.bottomAnchor),
            stack.widthAnchor.constraint(equalToConstant: 380),
        ])
        panel.contentView = content
        self.panel = panel
    }
}
