import AppKit
import CoreImage

/// Where the phone gets its app: the APK attached to this version's GitHub release, so it always matches this Mac
/// app. (Serving it from the Mac over Wi-Fi doesn't work: Android 17's browsers refuse pages on the local network
/// unless given a permission they don't ask for.)
enum PhoneAppLink {
    static var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0.0" }
    static var url: URL {
        URL(string: "https://github.com/iamkhalid2/macindroid/releases/download/v\(version)/Macindroid-\(version).apk")!
    }
}

/// QR codes for the setup windows.
enum QRCode {
    /// A crisp QR code `points` wide, dark on white, with a quiet zone.
    static func image(_ text: String, points: CGFloat) -> NSImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(text.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }
        let pixels = points * 2
        let scale = floor(pixels / output.extent.width)
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let cg = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return NSImage(cgImage: cg, size: NSSize(width: scaled.extent.width / 2, height: scaled.extent.height / 2))
    }

    /// The code on a white rounded card, so it scans in dark mode too.
    static func view(_ text: String, points: CGFloat) -> NSView {
        let card = NSView()
        card.wantsLayer = true
        card.layer?.backgroundColor = NSColor.white.cgColor
        card.layer?.cornerRadius = 14
        let image = NSImageView(image: image(text, points: points) ?? NSImage())
        image.imageScaling = .scaleNone
        image.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(image)
        card.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            card.widthAnchor.constraint(equalToConstant: points + 24),
            card.heightAnchor.constraint(equalToConstant: points + 24),
            image.centerXAnchor.constraint(equalTo: card.centerXAnchor),
            image.centerYAnchor.constraint(equalTo: card.centerYAnchor),
        ])
        return card
    }
}
