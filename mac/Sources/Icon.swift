import AppKit
import ImageIO
import UniformTypeIdentifiers

/// The Macindroid mark: one face split by a lightning seam. The left half smiles (the Mac), the right half has
/// a round eye and an antenna (Android). 24×24 grid, y pointing down.
/// android/res/drawable/ic_glyph.xml and ic_launcher_foreground.xml draw the same paths; keep them in step.
enum Icon {
    static let graphiteTop = CGColor(srgbRed: 0.17, green: 0.18, blue: 0.22, alpha: 1)   // #2B2F38
    static let graphiteBottom = CGColor(srgbRed: 0.07, green: 0.07, blue: 0.09, alpha: 1) // #111318
    static let macWhite = CGColor(srgbRed: 0.96, green: 0.96, blue: 0.97, alpha: 1)      // #F5F5F7
    static let droidGreen = CGColor(srgbRed: 0.29, green: 0.87, blue: 0.50, alpha: 1)    // #4ADE80
    private static let line: CGFloat = 1.75

    private static func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }

    /// The Mac half: outline, seam, upright eye and smile.
    private static func macHalf() -> CGPath {
        let p = CGMutablePath()
        p.move(to: point(13, 4.5))
        p.addArc(tangent1End: point(2.5, 4.5), tangent2End: point(2.5, 20.5), radius: 5)
        p.addArc(tangent1End: point(2.5, 20.5), tangent2End: point(11.6, 20.5), radius: 5)
        p.addLine(to: point(11.6, 20.5))
        p.move(to: point(13, 4.5)) // the seam
        p.addLine(to: point(10.6, 12.2))
        p.addLine(to: point(13.2, 12.2))
        p.addLine(to: point(11.6, 20.5))
        p.move(to: point(7.5, 9.6)) // eye
        p.addLine(to: point(7.5, 11.6))
        p.move(to: point(5.6, 14.6)) // smile
        p.addQuadCurve(to: point(10.2, 16.4), control: point(8.2, 17.4))
        return p
    }

    /// The Android half: outline and antenna (stroked) plus a round eye (filled).
    private static func droidHalf() -> CGPath {
        let p = CGMutablePath()
        p.move(to: point(13, 4.5))
        p.addArc(tangent1End: point(21.5, 4.5), tangent2End: point(21.5, 20.5), radius: 5)
        p.addArc(tangent1End: point(21.5, 20.5), tangent2End: point(11.6, 20.5), radius: 5)
        p.addLine(to: point(11.6, 20.5))
        p.move(to: point(18.2, 4.8)) // antenna
        p.addLine(to: point(19.9, 2.0))
        return p
    }

    private static let droidEye = CGPath(ellipseIn: CGRect(x: 15.45, y: 10.05, width: 2.3, height: 2.3), transform: nil)

    /// Draws the mark centred in `rect` (a y-up context). `glow` adds a soft halo to the Android half.
    static func drawGlyph(in ctx: CGContext, rect: CGRect, mac: CGColor, droid: CGColor, glow: CGFloat = 0) {
        let scale = min(rect.width, rect.height) / 24
        ctx.saveGState()
        ctx.translateBy(x: rect.midX, y: rect.midY)
        ctx.scaleBy(x: scale, y: -scale)
        ctx.translateBy(x: -12, y: -11.25) // the mark spans x 1.6…22.4, y 1.1…21.4
        ctx.setLineCap(.round)
        ctx.setLineJoin(.round)
        ctx.setLineWidth(line)

        ctx.saveGState()
        if glow > 0 { ctx.setShadow(offset: .zero, blur: glow * scale, color: droid.copy(alpha: 0.55)) }
        ctx.beginTransparencyLayer(auxiliaryInfo: nil)
        ctx.addPath(droidHalf())
        ctx.setStrokeColor(droid)
        ctx.strokePath()
        ctx.addPath(droidEye)
        ctx.setFillColor(droid)
        ctx.fillPath()
        ctx.endTransparencyLayer()
        ctx.restoreGState()

        ctx.addPath(macHalf())
        ctx.setStrokeColor(mac)
        ctx.strokePath()
        ctx.restoreGState()
    }

    static func drawGlyph(in ctx: CGContext, rect: CGRect, color: CGColor) {
        drawGlyph(in: ctx, rect: rect, mac: color, droid: color)
    }

    /// Template image for the menu bar; macOS tints it for light, dark and highlighted states.
    static func menuBarImage(trailingSpace: CGFloat = 0) -> NSImage {
        let image = NSImage(size: NSSize(width: 18 + trailingSpace, height: 18), flipped: false) { rect in
            guard let ctx = NSGraphicsContext.current?.cgContext else { return false }
            drawGlyph(in: ctx, rect: CGRect(x: 0, y: 0, width: 18, height: 18), color: .black)
            return true
        }
        image.isTemplate = true
        return image
    }

    /// The mark with a progress ring beside it (0...1), for while files are moving.
    static func menuBarImage(progress: Double) -> NSImage {
        let image = NSImage(size: NSSize(width: 18 + 17, height: 18), flipped: false) { _ in
            guard let ctx = NSGraphicsContext.current?.cgContext else { return false }
            drawGlyph(in: ctx, rect: CGRect(x: 0, y: 0, width: 18, height: 18), color: .black)
            let center = CGPoint(x: 18 + 4 + 6.5, y: 9), radius: CGFloat = 5.5
            ctx.setLineWidth(2)
            ctx.setStrokeColor(CGColor(gray: 0, alpha: 0.3))
            ctx.addArc(center: center, radius: radius, startAngle: 0, endAngle: .pi * 2, clockwise: false)
            ctx.strokePath()
            ctx.setStrokeColor(CGColor(gray: 0, alpha: 1))
            ctx.setLineCap(.round)
            let end = CGFloat.pi / 2 - CGFloat.pi * 2 * CGFloat(max(0.03, min(1, progress)))
            ctx.addArc(center: center, radius: radius, startAngle: .pi / 2, endAngle: end, clockwise: true)
            ctx.strokePath()
            return true
        }
        image.isTemplate = true
        return image
    }

    /// Full-colour app icon on Apple's 1024-point grid (824-point body, soft shadow).
    static func appIcon(pixels: Int) -> CGImage? {
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let ctx = CGContext(data: nil, width: pixels, height: pixels, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        let u = CGFloat(pixels) / 1024
        let body = CGRect(x: 100 * u, y: 100 * u, width: 824 * u, height: 824 * u)
        let shape = CGPath(roundedRect: body, cornerWidth: 185 * u, cornerHeight: 185 * u, transform: nil)

        ctx.saveGState()
        ctx.setShadow(offset: CGSize(width: 0, height: -8 * u), blur: 24 * u, color: CGColor(gray: 0, alpha: 0.35))
        ctx.addPath(shape)
        ctx.setFillColor(graphiteBottom)
        ctx.fillPath()
        ctx.restoreGState()

        ctx.saveGState()
        ctx.addPath(shape)
        ctx.clip()
        if let gradient = CGGradient(colorsSpace: space, colors: [graphiteTop, graphiteBottom] as CFArray, locations: [0, 1]) {
            ctx.drawLinearGradient(gradient, start: CGPoint(x: 0, y: body.maxY), end: CGPoint(x: 0, y: body.minY), options: [])
        }
        ctx.restoreGState()
        // A hairline rim catches the light along the top edge.
        ctx.saveGState()
        ctx.addPath(CGPath(roundedRect: body.insetBy(dx: 1.5 * u, dy: 1.5 * u), cornerWidth: 184 * u, cornerHeight: 184 * u, transform: nil))
        ctx.setStrokeColor(CGColor(gray: 1, alpha: 0.10))
        ctx.setLineWidth(3 * u)
        ctx.strokePath()
        ctx.restoreGState()

        drawGlyph(in: ctx, rect: body.insetBy(dx: body.width * 0.17, dy: body.height * 0.17),
                  mac: macWhite, droid: droidGreen, glow: 1.2)
        return ctx.makeImage()
    }

    static func appIconImage(points: CGFloat) -> NSImage {
        guard let cg = appIcon(pixels: Int(points * 2)) else { return NSImage() }
        return NSImage(cgImage: cg, size: NSSize(width: points, height: points))
    }

    /// Writes an .iconset folder for `iconutil -c icns`.
    static func writeIconset(to dir: URL) {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        for points in [16, 32, 128, 256, 512] {
            for factor in [1, 2] {
                let name = factor == 1 ? "icon_\(points)x\(points).png" : "icon_\(points)x\(points)@2x.png"
                guard let image = appIcon(pixels: points * factor),
                      let dest = CGImageDestinationCreateWithURL(dir.appendingPathComponent(name) as CFURL,
                                                                 UTType.png.identifier as CFString, 1, nil) else { continue }
                CGImageDestinationAddImage(dest, image, nil)
                CGImageDestinationFinalize(dest)
            }
        }
    }
}
