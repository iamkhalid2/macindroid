import AppKit
import Symbols

/// The menu's look, after the system's own menu-bar extras (Wi-Fi, Bluetooth): a device header with a live
/// battery, round symbol tiles on the actions, and live rows for transfers. Animation only marks a state
/// (looking for the phone, ringing) and is off when Reduce Motion is on.
enum MenuStyle {
    static let width: CGFloat = 300
    static let inset: CGFloat = 15  // where AppKit puts a menu item's image
    static let gap: CGFloat = 7     // between a menu item's image and its title

    static var reduceMotion: Bool { NSWorkspace.shared.accessibilityDisplayShouldReduceMotion }

    /// A file's Finder icon (or the default browser's, for a link) at menu size.
    static func fileIcon(for url: URL?, size: CGFloat = 26) -> NSImage {
        var icon: NSImage
        if let url, !url.isFileURL, let app = NSWorkspace.shared.urlForApplication(toOpen: url) {
            icon = NSWorkspace.shared.icon(forFile: app.path)
        } else if let url, url.isFileURL, FileManager.default.fileExists(atPath: url.path) {
            icon = NSWorkspace.shared.icon(forFile: url.path)
        } else {
            icon = NSWorkspace.shared.icon(for: .data)
        }
        icon = icon.copy() as! NSImage
        icon.size = NSSize(width: size, height: size)
        return icon
    }

    static func label(_ text: String, size: CGFloat, weight: NSFont.Weight = .regular, colour: NSColor = .labelColor) -> NSTextField {
        let field = NSTextField(labelWithString: text)
        field.font = .systemFont(ofSize: size, weight: weight)
        field.textColor = colour
        field.lineBreakMode = .byTruncatingTail
        field.maximumNumberOfLines = 1
        return field
    }
}

/// A round symbol tile in Liquid Glass, like the system's Control Center and menu-extra tiles: clear glass when
/// idle, tinted glass when active. The symbol can animate (pulse while looking, wiggle while ringing).
/// Before macOS 26 (no Liquid Glass) it's a flat circle.
final class GlassTile: NSView {
    enum Animation { case none, pulse, wiggle }

    private let glyph = NSImageView()
    private var glass: NSView?
    private var fill: NSColor = .quaternaryLabelColor
    var enabled = true { didSet { alphaValue = enabled ? 1 : 0.45 } }

    init(size: CGFloat) {
        super.init(frame: NSRect(x: 0, y: 0, width: size, height: size))
        glyph.imageScaling = .scaleNone
        glyph.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: (size * 0.44).rounded(), weight: .semibold)
        glyph.frame = bounds
        glyph.autoresizingMask = [.width, .height]
        if #available(macOS 26.0, *) {
            let g = NSGlassEffectView(frame: bounds)
            g.cornerRadius = size / 2
            g.style = .clear
            g.contentView = glyph
            if #available(macOS 27.0, *) { g.effectIsInteractive = true }
            addSubview(g)
            glass = g
        } else {
            wantsLayer = true
            layer?.cornerRadius = size / 2
            addSubview(glyph)
        }
        setAccessibilityElement(false)
    }

    required init?(coder: NSCoder) { nil }

    /// tint: nil for clear glass, a colour for tinted (active) glass.
    func show(_ symbol: String, tint: NSColor?, animation: Animation = .none) {
        glyph.image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)
        glyph.contentTintColor = tint == nil ? .labelColor : .white
        if #available(macOS 26.0, *), let g = glass as? NSGlassEffectView {
            g.tintColor = tint
            g.style = tint == nil ? .clear : .regular
        } else {
            fill = tint ?? .quaternaryLabelColor
            needsDisplay = true
        }
        animate(animation)
    }

    func animate(_ animation: Animation) {
        glyph.removeAllSymbolEffects(animated: false)
        guard !MenuStyle.reduceMotion else { return }
        switch animation {
        case .none: break
        case .pulse: glyph.addSymbolEffect(.pulse, options: .repeating)
        case .wiggle:
            if #available(macOS 15.0, *) { glyph.addSymbolEffect(.wiggle, options: .repeating) }
            else { glyph.addSymbolEffect(.pulse, options: .repeating) }
        }
    }

    override var wantsUpdateLayer: Bool { glass == nil }
    override func updateLayer() {
        effectiveAppearance.performAsCurrentDrawingAppearance { layer?.backgroundColor = fill.cgColor }
    }
}

/// A menu row with a glass tile: the menu highlights it (AppKit tracks the highlight of view items), a click or
/// Return performs its action, and VoiceOver reads and presses it as a menu item.
final class GlassMenuRow: NSView {
    static let height: CGFloat = 34
    private let tile = GlassTile(size: 26)
    private let title = NSTextField(labelWithString: "")
    private let enabled: Bool

    init(title text: String, symbol: String, tint: NSColor? = nil, enabled: Bool = true,
         animation: GlassTile.Animation = .none) {
        self.enabled = enabled
        super.init(frame: NSRect(x: 0, y: 0, width: MenuStyle.width, height: Self.height))
        autoresizingMask = [.width]
        tile.show(symbol, tint: tint, animation: animation)
        tile.enabled = enabled
        title.stringValue = text
        title.font = .menuFont(ofSize: 0)
        title.lineBreakMode = .byTruncatingTail
        addSubview(tile)
        addSubview(title)
        setAccessibilityElement(true)
        setAccessibilityRole(.menuItem)
        setAccessibilityLabel(text)
        setAccessibilityEnabled(enabled)
        title.setAccessibilityElement(false)
    }

    required init?(coder: NSCoder) { nil }

    private var highlighted: Bool { enabled && enclosingMenuItem?.isHighlighted == true }

    override func layout() {
        super.layout()
        tile.frame.origin = NSPoint(x: MenuStyle.inset, y: (bounds.height - 26) / 2)
        title.sizeToFit()
        let x = tile.frame.maxX + MenuStyle.gap
        title.frame = NSRect(x: x, y: (bounds.height - title.frame.height) / 2,
                             width: bounds.width - x - MenuStyle.inset, height: title.frame.height)
    }

    override func draw(_ dirtyRect: NSRect) {
        title.textColor = !enabled ? .disabledControlTextColor : highlighted ? .selectedMenuItemTextColor : .labelColor
        guard highlighted else { return }
        NSColor.selectedContentBackgroundColor.setFill()
        NSBezierPath(roundedRect: bounds.insetBy(dx: 5, dy: 0), xRadius: 7, yRadius: 7).fill()
    }

    override func mouseUp(with event: NSEvent) { perform() }

    // The menu hands key presses to the highlighted item's view; AppKit doesn't act on Return for view items itself.
    override var acceptsFirstResponder: Bool { true }
    override func keyDown(with event: NSEvent) {
        if [36, 76].contains(event.keyCode) { perform() } else { super.keyDown(with: event) } // Return, Enter
    }

    override func accessibilityPerformPress() -> Bool {
        perform()
        return true
    }

    func perform() {
        guard enabled, let item = enclosingMenuItem, let action = item.action else { return }
        item.menu?.cancelTracking()
        NSApp.sendAction(action, to: item.target, from: item)
    }
}

/// The battery as the system draws it: an outline filled to the exact level; green while charging, red when low.
final class BatteryView: NSView {
    var level = 0 { didSet { needsDisplay = true } }
    var charging = false { didSet { needsDisplay = true } }

    override var intrinsicContentSize: NSSize { NSSize(width: 25, height: 12) }

    override func draw(_ dirtyRect: NSRect) {
        let body = NSRect(x: 0.5, y: (bounds.height - 11) / 2, width: 22, height: 11)
        NSColor.labelColor.withAlphaComponent(0.4).setStroke()
        let outline = NSBezierPath(roundedRect: body, xRadius: 3, yRadius: 3)
        outline.lineWidth = 1
        outline.stroke()
        NSColor.labelColor.withAlphaComponent(0.4).setFill()
        NSBezierPath(roundedRect: NSRect(x: body.maxX + 1.5, y: body.midY - 2, width: 1.5, height: 4), xRadius: 0.75, yRadius: 0.75).fill()
        let inner = body.insetBy(dx: 2, dy: 2)
        let width = max(level > 0 ? 2 : 0, inner.width * CGFloat(min(level, 100)) / 100)
        (charging ? NSColor.systemGreen : level <= 20 ? NSColor.systemRed : NSColor.labelColor).setFill()
        NSBezierPath(roundedRect: NSRect(x: inner.minX, y: inner.minY, width: width, height: inner.height), xRadius: 1.5, yRadius: 1.5).fill()
        if charging, let bolt = NSImage(systemSymbolName: "bolt.fill", accessibilityDescription: nil)?
            .withSymbolConfiguration(NSImage.SymbolConfiguration(pointSize: 8, weight: .black)
                .applying(NSImage.SymbolConfiguration(paletteColors: [.labelColor]))) {
            let s = bolt.size
            bolt.draw(in: NSRect(x: body.midX - s.width / 2, y: body.midY - s.height / 2, width: s.width, height: s.height))
        }
    }
}

/// The top of the menu: the phone, how it's connected, and its battery.
final class PhoneHeaderView: NSView {
    struct Model: Equatable {
        enum Mode { case unpaired, searching, connected }
        var name: String
        var detail: String
        var mode: Mode
        var ringing: Bool
        var battery: Int?
        var charging: Bool
    }

    private let tile = GlassTile(size: 32)
    private let name = MenuStyle.label("", size: 13, weight: .semibold)
    private let detail = MenuStyle.label("", size: 11, colour: .secondaryLabelColor)
    private let percent = MenuStyle.label("", size: 11, colour: .secondaryLabelColor)
    private let battery = BatteryView()
    private var model: Model?

    init() {
        super.init(frame: NSRect(x: 0, y: 0, width: MenuStyle.width, height: 52))
        autoresizingMask = [.width]
        percent.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
        percent.alignment = .right
        for view in [tile, name, detail, percent, battery] as [NSView] {
            addSubview(view)
            view.setAccessibilityElement(false) // read as one line, below
        }
        setAccessibilityElement(true)
        setAccessibilityRole(.staticText)
    }

    required init?(coder: NSCoder) { nil }

    func update(_ m: Model) {
        guard m != model else { return }
        let animationChanged = m.mode != model?.mode || m.ringing != model?.ringing
        model = m
        name.stringValue = m.name
        detail.stringValue = m.detail
        if animationChanged {
            tile.show(m.mode == .unpaired ? "plus" : "smartphone",
                      tint: m.mode != .connected ? nil : m.ringing ? .systemRed : .controlAccentColor,
                      animation: m.ringing ? .wiggle : m.mode == .searching ? .pulse : .none)
        }
        battery.isHidden = m.battery == nil
        percent.isHidden = m.battery == nil
        if let level = m.battery {
            battery.level = level
            battery.charging = m.charging
            percent.stringValue = "\(level)%"
        }
        var spoken = "\(m.name), \(m.detail)"
        if let level = m.battery { spoken += ", battery \(level) percent" + (m.charging ? ", charging" : "") }
        setAccessibilityLabel(spoken)
        needsLayout = true
    }

    override func layout() {
        super.layout()
        let h = bounds.height
        tile.frame.origin = NSPoint(x: MenuStyle.inset - 3, y: (h - 32) / 2) // centred over the row tiles below
        var right = bounds.width - MenuStyle.inset
        if !battery.isHidden {
            battery.frame = NSRect(x: right - 25, y: (h - 12) / 2, width: 25, height: 12)
            percent.sizeToFit()
            percent.frame = NSRect(x: battery.frame.minX - 4 - percent.frame.width, y: (h - percent.frame.height) / 2,
                                   width: percent.frame.width, height: percent.frame.height)
            right = percent.frame.minX - 8
        }
        let x = tile.frame.maxX + 8
        name.sizeToFit()
        detail.sizeToFit()
        let textHeight = name.frame.height + 1 + detail.frame.height
        let top = (h + textHeight) / 2
        name.frame = NSRect(x: x, y: top - name.frame.height, width: right - x, height: name.frame.height)
        detail.frame = NSRect(x: x, y: name.frame.minY - 1 - detail.frame.height, width: right - x, height: detail.frame.height)
    }
}

/// A file on its way, with a live progress bar.
final class TransferRowView: NSView {
    private let icon = GlassTile(size: 26)
    private let name = MenuStyle.label("", size: 13)
    private let amount = MenuStyle.label("", size: 11, colour: .secondaryLabelColor)
    private let bar = NSProgressIndicator()

    init(toPhone: Bool, name text: String) {
        super.init(frame: NSRect(x: 0, y: 0, width: MenuStyle.width, height: 54))
        autoresizingMask = [.width]
        icon.show(toPhone ? "arrow.up" : "arrow.down", tint: .controlAccentColor)
        name.stringValue = text
        amount.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
        bar.style = .bar
        bar.isIndeterminate = false
        bar.controlSize = .small
        bar.minValue = 0
        bar.maxValue = 1
        for view in [icon, name, amount, bar] as [NSView] {
            addSubview(view)
            view.setAccessibilityElement(false)
        }
        setAccessibilityElement(true)
        setAccessibilityRole(.progressIndicator)
        setAccessibilityLabel((toPhone ? "Sending " : "Receiving ") + text)
    }

    required init?(coder: NSCoder) { nil }

    func update(done: Int64, total: Int64) {
        let fraction = total > 0 ? Double(done) / Double(total) : 0
        bar.doubleValue = fraction
        let f = ByteCountFormatter()
        f.countStyle = .file
        amount.stringValue = "\(f.string(fromByteCount: done)) of \(f.string(fromByteCount: total))"
        setAccessibilityValue("\(Int(fraction * 100)) percent")
        needsLayout = true
    }

    override func layout() {
        super.layout()
        // Name, then the bar, then "22 MB of 400 MB", as Finder and AirDrop show it.
        icon.frame = NSRect(x: MenuStyle.inset, y: (bounds.height - 26) / 2, width: 26, height: 26)
        let x = icon.frame.maxX + MenuStyle.gap, right = bounds.width - MenuStyle.inset
        name.sizeToFit()
        amount.sizeToFit()
        let h = bounds.height
        name.frame = NSRect(x: x, y: h - 6 - name.frame.height, width: right - x, height: name.frame.height)
        bar.frame = NSRect(x: x, y: name.frame.minY - 10, width: right - x, height: 8)
        amount.frame = NSRect(x: x, y: bar.frame.minY - 2 - amount.frame.height, width: right - x, height: amount.frame.height)
    }
}

/// The menu-bar item. Just the mark when idle; it widens while something is happening: a progress ring during
/// transfers, a ringing bell while the phone rings.
final class StatusBarItem {
    let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let bell = PassThroughImageView()
    private var shown: (progress: Int?, ringing: Bool)? = nil

    init() {
        guard let button = item.button else { return }
        button.image = Icon.menuBarImage()
        bell.image = NSImage(systemSymbolName: "bell.and.waves.left.and.right.fill", accessibilityDescription: "Ringing")
        bell.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 12, weight: .semibold)
        bell.isHidden = true
        button.addSubview(bell)
    }

    /// progress: 0...1 while files move, else nil.
    func update(connected: Bool, progress: Double?, ringing: Bool) {
        guard let button = item.button else { return }
        button.appearsDisabled = !connected
        let state = (progress: progress.map { Int($0 * 100) }, ringing: ringing)
        if let s = shown, s.progress == state.progress, s.ringing == state.ringing { return }
        let wasRinging = shown?.ringing ?? false
        shown = state
        if let p = progress {
            button.image = Icon.menuBarImage(progress: p)
            button.setAccessibilityValue("Transferring, \(state.progress ?? 0) percent")
        } else {
            button.image = Icon.menuBarImage(trailingSpace: ringing ? 19 : 0)
            button.setAccessibilityValue(ringing ? "Ringing" : nil)
        }
        bell.isHidden = !(ringing && progress == nil)
        if !bell.isHidden {
            bell.frame = NSRect(x: button.bounds.width - 21, y: (button.bounds.height - 18) / 2, width: 18, height: 18)
            bell.autoresizingMask = [.minXMargin]
            if !wasRinging {
                bell.removeAllSymbolEffects(animated: false)
                if !MenuStyle.reduceMotion {
                    if #available(macOS 15.0, *) { bell.addSymbolEffect(.wiggle, options: .repeating) }
                    else { bell.addSymbolEffect(.pulse, options: .repeating) }
                }
            }
        } else {
            bell.removeAllSymbolEffects(animated: false)
        }
    }
}

/// Clicks go through to the status button underneath.
final class PassThroughImageView: NSImageView {
    override func hitTest(_ point: NSPoint) -> NSView? { nil }
}
