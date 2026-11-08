import AppKit

/// Looks for a newer Macindroid on GitHub: one anonymous request at launch and once a day, nothing else sent.
/// Off with Settings → Check for Updates. The phone app is updated from the Mac (it's bundled), so the phone itself
/// never goes online for this.
final class Updates {
    private(set) var latest: (version: String, page: URL)?
    private var timer: Timer?
    var onChange: (() -> Void)?

    static let current = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    private static let api = URL(string: "https://api.github.com/repos/iamkhalid2/macindroid/releases/latest")!

    func start() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in self?.check() }
        timer = Timer.scheduledTimer(withTimeInterval: 24 * 3600, repeats: true) { [weak self] _ in self?.check() }
    }

    func check() {
        guard Prefs.checkUpdates else {
            if latest != nil { latest = nil; onChange?() }
            return
        }
        var request = URLRequest(url: Self.api, timeoutInterval: 20)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("Macindroid/\(Self.current)", forHTTPHeaderField: "User-Agent")
        URLSession.shared.dataTask(with: request) { [weak self] data, _, error in
            guard let data, error == nil,
                  let info = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let tag = info["tag_name"] as? String, let page = (info["html_url"] as? String).flatMap(URL.init) else { return }
            let version = tag.hasPrefix("v") ? String(tag.dropFirst()) : tag
            DispatchQueue.main.async {
                guard let self else { return }
                let newer = Self.isNewer(version, than: Self.current)
                let was = self.latest?.version
                self.latest = newer ? (version, page) : nil
                if newer, was != version {
                    log("Macindroid \(version) is available")
                    notifications.postLocal(id: "update", title: "Macindroid \(version) is available",
                                            body: "Choose Update Available in the Macindroid menu to download it.", info: [:])
                }
                self.onChange?()
            }
        }.resume()
    }

    static func isNewer(_ a: String, than b: String) -> Bool {
        let x = a.split(separator: ".").map { Int($0) ?? 0 }, y = b.split(separator: ".").map { Int($0) ?? 0 }
        for i in 0..<max(x.count, y.count) {
            let p = i < x.count ? x[i] : 0, q = i < y.count ? y[i] : 0
            if p != q { return p > q }
        }
        return false
    }
}

let updates = Updates()
