import AppKit
import UniformTypeIdentifiers

/// New photos and screenshots from the phone (android PhotoWatcher.java). The phone sends a small preview as soon
/// as one is taken; a card drops down from the menu-bar icon with Copy and Save, and the picture can be dragged
/// out of it. The original comes over only when it's wanted (PHOTO_GET, answered by PHOTO_FULL), so a photo you
/// ignore costs one preview of about 50 KB. The card goes away by itself, but not while the pointer is on it.
final class PhotoPreviews {
    struct Photo {
        let id: Int64
        let name: String
        let screenshot: Bool
        let width: Int, height: Int
        let size: Int64
        let preview: NSImage

        /// What the file is, going by its name; the phone sends a JPEG instead only for photos too big for one frame.
        var type: UTType { UTType(filenameExtension: (name as NSString).pathExtension) ?? .jpeg }
    }

    /// The menu-bar icon the card hangs from.
    var anchor: () -> NSStatusBarButton? = { nil }
    private var card: PhotoCard?
    private var waiting: [Int64: [(Data?, UTType) -> Void]] = [:]
    private var requests = 0
    private var asked: [Int64: Int] = [:] // photo id → the request now waiting for it
    private var original: (id: Int64, data: Data, type: UTType)?

    // MARK: From the phone

    func offered(_ payload: Data) {
        guard Prefs.photoPreviews, let (meta, jpeg) = unpack(payload), let id = (meta["id"] as? NSNumber)?.int64Value,
              let preview = NSImage(data: jpeg) else { return }
        let photo = Photo(id: id, name: meta["name"] as? String ?? "Photo.jpg", screenshot: meta["screenshot"] as? Bool ?? false,
                          width: meta["width"] as? Int ?? 0, height: meta["height"] as? Int ?? 0,
                          size: (meta["size"] as? NSNumber)?.int64Value ?? 0, preview: preview)
        log("\(link.phoneName) -> Mac: \(photo.screenshot ? "screenshot" : "photo") preview (\(jpeg.count) bytes)")
        if original?.id != id { original = nil } // only the photo on the card is kept, and it can be up to 32 MB
        if card == nil { card = PhotoCard() }
        card?.show(photo, under: anchor())
    }

    func received(_ payload: Data) {
        guard let (meta, data) = unpack(payload), let id = (meta["id"] as? NSNumber)?.int64Value else { return }
        let type = (meta["mime"] as? String).flatMap { UTType(mimeType: $0) } ?? .jpeg
        if meta["error"] == nil, !data.isEmpty {
            original = (id, data, type)
            log("\(link.phoneName) -> Mac: original photo (\(data.count) bytes)")
        }
        finish(id, meta["error"] == nil && !data.isEmpty ? data : nil, type)
    }

    // MARK: Asking for the original

    /// The original photo, from the phone unless it's the one fetched last. Calls back on the main queue, with nil
    /// if the phone is gone or the photo was deleted.
    func original(of photo: Photo, _ done: @escaping (Data?, UTType) -> Void) {
        if let o = original, o.id == photo.id { done(o.data, o.type); return }
        let first = waiting[photo.id] == nil
        waiting[photo.id, default: []].append(done)
        guard first else { return }
        requests += 1
        let request = requests
        asked[photo.id] = request
        let body = (try? JSONSerialization.data(withJSONObject: ["id": photo.id])) ?? Data()
        link.sendToPhone(.photoGet, body) { [weak self] sent in
            if !sent { DispatchQueue.main.async { self?.finish(photo.id, nil, photo.type) } }
        }
        // Give up after 30 s, unless this request was already answered and a newer one is waiting.
        DispatchQueue.main.asyncAfter(deadline: .now() + 30) { [weak self] in
            if self?.asked[photo.id] == request { self?.finish(photo.id, nil, photo.type) }
        }
    }

    private func finish(_ id: Int64, _ data: Data?, _ type: UTType) {
        asked[id] = nil
        guard let callbacks = waiting.removeValue(forKey: id) else { return }
        if data == nil { log("could not get photo \(id) from the phone") }
        callbacks.forEach { $0(data, type) }
    }

    /// 4-byte big-endian JSON length, the JSON, then the image.
    private func unpack(_ payload: Data) -> ([String: Any], Data)? {
        guard payload.count >= 4 else { return nil }
        let start = payload.startIndex
        let n = payload[start..<start + 4].reduce(0) { $0 << 8 | Int($1) }
        guard payload.count >= 4 + n, let meta = parseJSON(payload.subdata(in: start + 4..<start + 4 + n)) else { return nil }
        return (meta, payload.subdata(in: start + 4 + n..<payload.endIndex))
    }
}

/// The card itself: a borderless, non-activating panel in Liquid Glass (a popover-style blur before macOS 26), so
/// clicking it never takes focus from the app you're in.
final class PhotoCard: NSPanel, NSFilePromiseProviderDelegate {
    private static let width: CGFloat = 320
    private static let pad: CGFloat = 14
    private static let maxImageHeight: CGFloat = 230

    private var photo: PhotoPreviews.Photo?
    private let titleLabel = MenuStyle.label("", size: 13, weight: .semibold)
    private let detailLabel = MenuStyle.label("", size: 11, colour: .secondaryLabelColor)
    private let symbol = NSImageView()
    private let picture = DraggableImageView()
    private let copyButton = CardButton(primary: true)
    private let saveButton = CardButton(primary: false)
    private let closeButton = NSButton()
    private let content = NSView()
    private var hideTimer: Timer?
    private var pointerInside = false
    private var saved: URL?

    init() {
        super.init(contentRect: NSRect(x: 0, y: 0, width: Self.width, height: 200),
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

        symbol.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 13, weight: .semibold)
        symbol.contentTintColor = .secondaryLabelColor
        closeButton.image = NSImage(systemSymbolName: "xmark.circle.fill", accessibilityDescription: "Close")
        closeButton.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 15, weight: .regular)
        closeButton.contentTintColor = .tertiaryLabelColor
        closeButton.isBordered = false
        closeButton.target = self
        closeButton.action = #selector(dismissCard)
        closeButton.toolTip = "Close"

        picture.imageScaling = .scaleProportionallyUpOrDown
        picture.wantsLayer = true
        picture.layer?.cornerRadius = 8
        picture.layer?.masksToBounds = true
        picture.layer?.borderWidth = 0.5
        picture.card = self
        picture.setAccessibilityRole(.button)
        picture.toolTip = "Click to open, or drag it anywhere"

        for (button, action) in [(copyButton, #selector(copyPhoto)), (saveButton, #selector(savePhoto))] {
            button.target = self
            button.action = action
        }

        for view in [symbol, titleLabel, detailLabel, closeButton, picture, copyButton, saveButton] as [NSView] {
            content.addSubview(view)
        }
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
        content.addTrackingArea(NSTrackingArea(rect: .zero, options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect],
                                               owner: self, userInfo: nil))
    }

    // MARK: Showing

    func show(_ photo: PhotoPreviews.Photo, under button: NSStatusBarButton?) {
        self.photo = photo
        saved = nil
        let what = photo.screenshot ? "Screenshot" : "Photo"
        titleLabel.stringValue = what
        var details = [link.phoneName]
        if photo.size > 0 { details.append(ByteCountFormatter.string(fromByteCount: photo.size, countStyle: .file)) }
        detailLabel.stringValue = details.joined(separator: " · ")
        detailLabel.toolTip = photo.width > 0 && photo.height > 0 ? "\(photo.width) × \(photo.height) pixels" : nil
        symbol.image = NSImage(systemSymbolName: photo.screenshot ? "camera.viewfinder" : "camera.fill", accessibilityDescription: nil)
        picture.image = photo.preview
        picture.setAccessibilityLabel("\(what) preview. Press to open it.")
        reset(copyButton, "Copy")
        reset(saveButton, "Save")
        layOut(for: photo.preview.size)
        place(under: button)

        if isVisible {
            alphaValue = 1 // a new one while the last is still up (or fading): just swap the contents
        } else {
            alphaValue = 0
            let end = frame
            if !MenuStyle.reduceMotion { setFrameOrigin(NSPoint(x: end.minX, y: end.minY + 8)) }
            orderFrontRegardless()
            NSAnimationContext.runAnimationGroup { ctx in
                ctx.duration = 0.22
                ctx.timingFunction = CAMediaTimingFunction(name: .easeOut)
                animator().alphaValue = 1
                animator().setFrame(end, display: true)
            }
        }
        NSAccessibility.post(element: NSApp as Any, notification: .announcementRequested,
                             userInfo: [.announcement: "\(what) from \(link.phoneName). Copy or Save from the menu bar.",
                                        .priority: NSAccessibilityPriorityLevel.medium.rawValue])
        scheduleHide(after: 8)
    }

    private func layOut(for imageSize: NSSize) {
        let w = Self.width, p = Self.pad
        let maxW = w - 2 * p
        let aspect = imageSize.height > 0 ? imageSize.width / imageSize.height : 1
        var imgW = maxW, imgH = maxW / aspect
        if imgH > Self.maxImageHeight { imgH = Self.maxImageHeight; imgW = imgH * aspect }
        let buttonH: CGFloat = 32, header: CGFloat = 36
        let h = p + header + 10 + imgH + 12 + buttonH + p
        setContentSize(NSSize(width: w, height: h))
        content.frame = NSRect(x: 0, y: 0, width: w, height: h)

        var y = p
        let half = (maxW - 8) / 2
        copyButton.frame = NSRect(x: p, y: y, width: half, height: buttonH)
        saveButton.frame = NSRect(x: p + half + 8, y: y, width: half, height: buttonH)
        y += buttonH + 12
        picture.frame = NSRect(x: (w - imgW) / 2, y: y, width: imgW, height: imgH)
        picture.layer?.borderColor = NSColor.separatorColor.cgColor
        y += imgH + 10
        symbol.frame = NSRect(x: p, y: y + 12, width: 18, height: 18)
        titleLabel.frame = NSRect(x: p + 24, y: y + 17, width: maxW - 24 - 26, height: 17)
        detailLabel.frame = NSRect(x: p + 24, y: y + 1, width: maxW - 24 - 26, height: 15)
        closeButton.frame = NSRect(x: w - p - 20, y: y + 14, width: 20, height: 20)
    }

    /// Just under the menu-bar icon, like the system's own menu-bar extras; at the top right of the screen when the
    /// icon isn't showing (a full-screen app, or hidden behind the notch).
    private func place(under button: NSStatusBarButton?) {
        let size = frame.size
        if let button, let window = button.window, window.occlusionState.contains(.visible), let screen = window.screen {
            let icon = window.convertToScreen(button.convert(button.bounds, to: nil))
            let visible = screen.visibleFrame
            let x = min(max(icon.midX - size.width / 2, visible.minX + 8), visible.maxX - size.width - 8)
            setFrameOrigin(NSPoint(x: x, y: icon.minY - 6 - size.height))
        } else if let visible = NSScreen.main?.visibleFrame {
            setFrameOrigin(NSPoint(x: visible.maxX - size.width - 12, y: visible.maxY - size.height - 8))
        }
    }

    // MARK: Going away

    private func scheduleHide(after seconds: TimeInterval) {
        hideTimer?.invalidate()
        guard !pointerInside, !picture.dragging else { return }
        let timer = Timer(timeInterval: seconds, repeats: false) { [weak self] _ in self?.dismissCard() }
        RunLoop.main.add(timer, forMode: .common)
        hideTimer = timer
    }

    @objc func dismissCard() {
        hideTimer?.invalidate()
        guard isVisible else { return }
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = 0.2
            animator().alphaValue = 0
        }, completionHandler: { [weak self] in
            guard let self, self.alphaValue == 0 else { return }
            self.orderOut(nil)
        })
    }

    override func mouseEntered(with event: NSEvent) {
        pointerInside = true
        hideTimer?.invalidate()
    }

    override func mouseExited(with event: NSEvent) {
        pointerInside = false
        scheduleHide(after: 4)
    }

    func dragEnded() { scheduleHide(after: 4) }

    // MARK: Actions

    @objc private func copyPhoto() {
        guard let photo else { return }
        busy(copyButton, "Copying…")
        photos.original(of: photo) { [weak self] data, type in
            guard let self, self.photo?.id == photo.id else { return }
            guard let data else { self.failed(self.copyButton); return }
            clipboard.copyPhoto(data, type: type)
            self.done(self.copyButton, "Copied")
            self.scheduleHide(after: 1.2)
        }
    }

    @objc private func savePhoto() {
        guard let photo else { return }
        if let saved { NSWorkspace.shared.activateFileViewerSelecting([saved]); dismissCard(); return }
        busy(saveButton, "Saving…")
        photos.original(of: photo) { [weak self] data, type in
            guard let self, self.photo?.id == photo.id else { return }
            guard let data, let url = try? self.write(data, type: type, of: photo, to: nil) else { self.failed(self.saveButton); return }
            log("saved \(url.lastPathComponent) to Downloads")
            self.saved = url
            self.done(self.saveButton, "Show in Finder", symbol: "folder")
            self.scheduleHide(after: 6)
        }
    }

    /// A click on the picture opens the original in its default app (Preview).
    func openPhoto() {
        guard let photo else { return }
        photos.original(of: photo) { [weak self] data, type in
            guard let self, let data else { return }
            let folder = FileManager.default.temporaryDirectory.appendingPathComponent("Macindroid Photos", isDirectory: true)
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            if let url = try? self.write(data, type: type, of: photo, to: folder) { NSWorkspace.shared.open(url) }
            self.dismissCard()
        }
    }

    /// Writes the original into `folder`, or Downloads (with a unique name) when nil.
    private func write(_ data: Data, type: UTType, of photo: PhotoPreviews.Photo, to folder: URL?) throws -> URL {
        var name = photo.name.isEmpty ? "Photo" : photo.name
        if type != photo.type, let ext = type.preferredFilenameExtension {
            name = (name as NSString).deletingPathExtension + "." + ext
        }
        let url = folder.map { $0.appendingPathComponent(name) } ?? transfers.uniqueURL(name)
        try data.write(to: url, options: .atomic)
        return url
    }

    // MARK: Dragging the picture out

    func dragItem() -> NSDraggingItem? {
        guard let photo else { return nil }
        let provider = NSFilePromiseProvider(fileType: photo.type.identifier, delegate: self)
        let item = NSDraggingItem(pasteboardWriter: provider)
        item.setDraggingFrame(picture.bounds, contents: photo.preview)
        return item
    }

    func filePromiseProvider(_ provider: NSFilePromiseProvider, fileNameForType fileType: String) -> String {
        photo?.name.isEmpty == false ? photo!.name : "Photo.jpg"
    }

    func filePromiseProvider(_ provider: NSFilePromiseProvider, writePromiseTo url: URL,
                             completionHandler: @escaping (Error?) -> Void) {
        guard let photo else { completionHandler(CocoaError(.fileNoSuchFile)); return }
        DispatchQueue.main.async {
            photos.original(of: photo) { data, _ in
                guard let data else { completionHandler(CocoaError(.fileReadUnknown)); return }
                do { try data.write(to: url, options: .atomic); completionHandler(nil) } catch { completionHandler(error) }
            }
        }
    }

    // MARK: Button states

    private func label(_ button: NSButton, _ title: String) {
        let colour: NSColor = button === copyButton ? .white : .labelColor
        button.attributedTitle = NSAttributedString(string: title, attributes: [
            .foregroundColor: button.isEnabled ? colour : colour.withAlphaComponent(0.5),
            .font: NSFont.systemFont(ofSize: NSFont.systemFontSize, weight: button === copyButton ? .semibold : .regular),
        ])
        button.contentTintColor = colour
    }

    private func reset(_ button: NSButton, _ title: String) {
        button.isEnabled = true
        label(button, title)
        button.image = nil
        button.isEnabled = true
    }

    private func busy(_ button: NSButton, _ title: String) {
        button.isEnabled = false
        label(button, title)
        hideTimer?.invalidate()
    }

    private func done(_ button: NSButton, _ title: String, symbol: String = "checkmark") {
        button.isEnabled = true
        label(button, title)
        button.image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)
        button.imagePosition = .imageLeading
        button.imageHugsTitle = true
    }

    private func failed(_ button: NSButton) {
        button.isEnabled = true
        label(button, "Phone didn't send it")
        button.image = NSImage(systemSymbolName: "exclamationmark.triangle", accessibilityDescription: nil)
        button.imagePosition = .imageLeading
        button.imageHugsTitle = true
        scheduleHide(after: 4)
    }

    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}

/// The card's picture: a click opens it, a drag hands it to Finder, Mail, Messages or anything that takes files.
final class DraggableImageView: NSImageView, NSDraggingSource {
    weak var card: PhotoCard?
    private(set) var dragging = false
    private var downAt: NSPoint?

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    override func mouseDown(with event: NSEvent) { downAt = event.locationInWindow }

    override func mouseDragged(with event: NSEvent) {
        guard let start = downAt, hypot(event.locationInWindow.x - start.x, event.locationInWindow.y - start.y) > 4,
              let item = card?.dragItem() else { return }
        downAt = nil
        dragging = true
        beginDraggingSession(with: [item], event: event, source: self)
    }

    override func mouseUp(with event: NSEvent) {
        if downAt != nil { card?.openPhoto() }
        downAt = nil
    }

    override func accessibilityPerformPress() -> Bool { card?.openPhoto(); return true }

    func draggingSession(_ session: NSDraggingSession, sourceOperationMaskFor context: NSDraggingContext) -> NSDragOperation {
        .copy
    }

    func draggingSession(_ session: NSDraggingSession, endedAt screenPoint: NSPoint, operation: NSDragOperation) {
        dragging = false
        card?.dragEnded()
    }
}

/// The card's Copy and Save: capsules like a notification banner's buttons. AppKit's own buttons dim whenever their
/// app isn't the active one, and a menu-bar app never is, so these draw themselves the same either way.
final class CardButton: NSButton {
    private let primary: Bool
    private var hovering = false { didSet { needsDisplay = true } }

    init(primary: Bool) {
        self.primary = primary
        super.init(frame: .zero)
        isBordered = false
        title = ""
        addTrackingArea(NSTrackingArea(rect: .zero, options: [.mouseEnteredAndExited, .activeAlways, .inVisibleRect],
                                       owner: self, userInfo: nil))
    }

    required init?(coder: NSCoder) { nil }

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    override func mouseEntered(with event: NSEvent) { hovering = true }
    override func mouseExited(with event: NSEvent) { hovering = false }
    override var isHighlighted: Bool { didSet { needsDisplay = true } }

    override func draw(_ dirtyRect: NSRect) {
        var fill: NSColor = primary ? .controlAccentColor : NSColor.labelColor.withAlphaComponent(hovering ? 0.16 : 0.1)
        if primary, hovering { fill = fill.blended(withFraction: 0.12, of: .white) ?? fill }
        if isHighlighted { fill = fill.blended(withFraction: 0.2, of: .black) ?? fill }
        if !isEnabled { fill = fill.withAlphaComponent(fill.alphaComponent * 0.6) }
        fill.setFill()
        NSBezierPath(roundedRect: bounds, xRadius: bounds.height / 2, yRadius: bounds.height / 2).fill()
        super.draw(dirtyRect)
    }
}
