// Draws the DMG window's background: "drag to Applications", and how to get past macOS's first-open warning
// (Macindroid isn't notarized). Run by release.sh: swift mac/dmg/background.swift <out-1x.png> <out-2x.png>
import AppKit

let size = NSSize(width: 660, height: 500)
let green = NSColor(srgbRed: 0x2E / 255, green: 0xB8 / 255, blue: 0x62 / 255, alpha: 1)
let ink = NSColor(srgbRed: 0x16 / 255, green: 0x18 / 255, blue: 0x1D / 255, alpha: 1)

func text(_ s: String, _ font: NSFont, _ color: NSColor, in rect: NSRect, center: Bool = true) {
    let style = NSMutableParagraphStyle()
    style.alignment = center ? .center : .left
    style.lineSpacing = 2
    (s as NSString).draw(in: rect, withAttributes: [.font: font, .foregroundColor: color, .paragraphStyle: style])
}

func draw() {
    NSGradient(starting: NSColor(srgbRed: 0.975, green: 0.977, blue: 0.983, alpha: 1),
               ending: NSColor(srgbRed: 0.925, green: 0.93, blue: 0.94, alpha: 1))!.draw(in: NSRect(origin: .zero, size: size), angle: -90)
    // y grows upwards. Icons sit centred at y = 500 - 150 = 350. The bottom 90 points stay empty: Finder may
    // draw its path and status bars there.
    text("Drag Macindroid into Applications", .systemFont(ofSize: 20, weight: .semibold), ink,
         in: NSRect(x: 0, y: 444, width: size.width, height: 30))
    // Arrow between the two icons
    let arrow = NSBezierPath()
    arrow.move(to: NSPoint(x: 262, y: 350)); arrow.line(to: NSPoint(x: 392, y: 350))
    arrow.move(to: NSPoint(x: 378, y: 362)); arrow.line(to: NSPoint(x: 394, y: 350)); arrow.line(to: NSPoint(x: 378, y: 338))
    arrow.lineWidth = 5; arrow.lineCapStyle = .round; arrow.lineJoinStyle = .round
    green.setStroke(); arrow.stroke()

    // The first-open card
    let card = NSRect(x: 40, y: 100, width: 580, height: 142)
    NSColor.white.setFill()
    NSBezierPath(roundedRect: card, xRadius: 16, yRadius: 16).fill()
    NSColor(white: 0, alpha: 0.08).setStroke()
    let edge = NSBezierPath(roundedRect: card.insetBy(dx: 0.5, dy: 0.5), xRadius: 16, yRadius: 16); edge.lineWidth = 1; edge.stroke()
    text("Opening it the first time", .systemFont(ofSize: 14, weight: .semibold), ink,
         in: NSRect(x: 64, y: 206, width: 532, height: 22), center: false)
    let steps = [
        "Open Macindroid from Applications. macOS says it can't check it for malicious software,",
        "because it isn't notarized by Apple yet. Click Done.",
        "Open System Settings → Privacy & Security, scroll down and click Open Anyway.",
        "Open Macindroid again and confirm. You only do this once.",
    ]
    let numbers = ["1", "", "2", "3"]
    for (i, line) in steps.enumerated() {
        let y = 180 - CGFloat(i) * 21
        if !numbers[i].isEmpty {
            green.setFill()
            NSBezierPath(ovalIn: NSRect(x: 64, y: y + 1, width: 17, height: 17)).fill()
            text(numbers[i], .systemFont(ofSize: 11, weight: .bold), .white, in: NSRect(x: 64, y: y + 1, width: 17, height: 16))
        }
        text(line, .systemFont(ofSize: 12.5), ink, in: NSRect(x: 90, y: y, width: 520, height: 20), center: false)
    }
}

for (scale, path) in [(1, CommandLine.arguments[1]), (2, CommandLine.arguments[2])] {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size.width) * scale, pixelsHigh: Int(size.height) * scale,
                               bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                               colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    rep.size = size
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    draw()
    NSGraphicsContext.restoreGraphicsState()
    try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
}
