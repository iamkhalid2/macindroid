import AppKit
import CryptoKit

/// Looks for a newer Macindroid on GitHub: one anonymous request at launch and once a day, nothing else sent.
/// Off with Settings → Check for Updates. Installing one downloads the release's disk image, checks it against the
/// release's SHA256SUMS and the app inside against this one (same bundle ID, the advertised version, a valid
/// signature), swaps it in and relaunches. The download never gets the quarantine flag, so there's no second
/// Open Anyway. The phone app is updated from the Mac (see PhoneUpdate), so the phone never goes online for this.
final class Updates {
    struct Release { let version: String; let page: URL; let dmg: URL?; let sums: URL? }

    private(set) var latest: Release?
    private(set) var installing = false
    private var timer: Timer?
    var onChange: (() -> Void)?

    static let current = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    /// `defaults write xyz.iamkhalid.macindroid updateFeed <url>` points the check at a test feed.
    private static var api: URL {
        UserDefaults.standard.string(forKey: "updateFeed").flatMap(URL.init)
            ?? URL(string: "https://api.github.com/repos/iamkhalid2/macindroid/releases/latest")!
    }

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
            var assets: [String: URL] = [:]
            for asset in info["assets"] as? [[String: Any]] ?? [] {
                if let name = asset["name"] as? String, let url = (asset["browser_download_url"] as? String).flatMap(URL.init) {
                    assets[name] = url
                }
            }
            let release = Release(version: version, page: page, dmg: assets["Macindroid-\(version).dmg"], sums: assets["SHA256SUMS"])
            DispatchQueue.main.async {
                guard let self else { return }
                let newer = Self.isNewer(version, than: Self.current)
                let was = self.latest?.version
                self.latest = newer ? release : nil
                if newer, was != version {
                    log("Macindroid \(version) is available")
                    notifications.postLocal(id: "update", title: "Macindroid \(version) is available",
                                            body: "Choose Install Update in the Macindroid menu. It takes a few seconds.", info: [:])
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

    // MARK: installing

    /// Downloads, checks and installs the latest release, then relaunches. Falls back to the release page when the
    /// release has no disk image or the app can't replace itself (say, it isn't in a folder this user can write to).
    func install() {
        guard let release = latest, !installing else { return }
        guard let dmg = release.dmg, let sums = release.sums,
              FileManager.default.isWritableFile(atPath: Bundle.main.bundleURL.deletingLastPathComponent().path) else {
            NSWorkspace.shared.open(release.page)
            return
        }
        installing = true
        onChange?()
        log("installing Macindroid \(release.version)")
        DispatchQueue.global(qos: .userInitiated).async {
            let result = Result {
                try Self.download(dmg: dmg, sums: sums, version: release.version, replacing: Bundle.main.bundleURL,
                                  bundleID: Bundle.main.bundleIdentifier ?? "")
            }
            DispatchQueue.main.async {
                switch result {
                case .success:
                    log("installed Macindroid \(release.version); relaunching")
                    Self.relaunch()
                case .failure(let error):
                    log("update failed: \(error)")
                    self.installing = false
                    self.onChange?()
                    notifications.postLocal(id: "update", title: "Couldn't install Macindroid \(release.version)",
                                            body: "\(error) The release page is open so you can download it yourself.", info: [:])
                    NSWorkspace.shared.open(release.page)
                }
            }
        }
    }

    struct Failure: Error, CustomStringConvertible { let description: String }

    /// Downloads the disk image and SHA256SUMS, checks them, and puts the app inside in place of `target`.
    static func download(dmg: URL, sums: URL, version: String, replacing target: URL, bundleID: String) throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("macindroid-update-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        let image = dir.appendingPathComponent(dmg.lastPathComponent)
        try fetch(dmg, to: image)
        let sumsFile = dir.appendingPathComponent("SHA256SUMS")
        try fetch(sums, to: sumsFile)
        let listed = try String(contentsOf: sumsFile, encoding: .utf8).split(separator: "\n")
            .map { $0.split(separator: " ", omittingEmptySubsequences: true) }
            .first { $0.count == 2 && $0[1] == Substring(dmg.lastPathComponent) }?[0]
        let actual = SHA256.hash(data: try Data(contentsOf: image, options: .mappedIfSafe)).map { String(format: "%02x", $0) }.joined()
        guard let listed, listed.lowercased() == actual else { throw Failure(description: "The download didn't match its checksum.") }

        let mount = dir.appendingPathComponent("mnt")
        let attach = Tools.run("/usr/bin/hdiutil", ["attach", image.path, "-nobrowse", "-readonly", "-noautoopen",
                                                    "-mountpoint", mount.path], timeout: 60)
        guard attach.status == 0 else { throw Failure(description: "The disk image wouldn't open.") }
        defer { _ = Tools.run("/usr/bin/hdiutil", ["detach", mount.path, "-force"], timeout: 30) }

        let source = mount.appendingPathComponent("Macindroid.app")
        guard let info = NSDictionary(contentsOf: source.appendingPathComponent("Contents/Info.plist")),
              info["CFBundleIdentifier"] as? String == bundleID,
              info["CFBundleShortVersionString"] as? String == version else {
            throw Failure(description: "The disk image doesn't hold Macindroid \(version).")
        }
        guard Tools.run("/usr/bin/codesign", ["--verify", "--deep", "--strict", source.path], timeout: 60).status == 0 else {
            throw Failure(description: "The new app's signature doesn't check out.")
        }

        let staged = target.deletingLastPathComponent().appendingPathComponent(".Macindroid-\(version)-update.app")
        try? FileManager.default.removeItem(at: staged)
        guard Tools.run("/usr/bin/ditto", [source.path, staged.path], timeout: 120).status == 0 else {
            throw Failure(description: "Couldn't copy the new app.")
        }
        do {
            _ = try FileManager.default.replaceItemAt(target, withItemAt: staged)
        } catch {
            try? FileManager.default.removeItem(at: staged)
            throw Failure(description: "Couldn't replace the app: \(error.localizedDescription)")
        }
    }

    private static func fetch(_ url: URL, to file: URL) throws {
        var request = URLRequest(url: url, timeoutInterval: 120)
        request.setValue("Macindroid/\(current)", forHTTPHeaderField: "User-Agent")
        var outcome: Result<URL, Error> = .failure(Failure(description: "The download didn't finish."))
        let done = DispatchSemaphore(value: 0)
        URLSession.shared.downloadTask(with: request) { temp, response, error in
            defer { done.signal() }
            if let error { outcome = .failure(error); return }
            guard let temp, (response as? HTTPURLResponse)?.statusCode == 200 else {
                outcome = .failure(Failure(description: "GitHub didn't send \(url.lastPathComponent)."))
                return
            }
            do {
                try FileManager.default.moveItem(at: temp, to: file)
                outcome = .success(file)
            } catch { outcome = .failure(error) }
        }.resume()
        done.wait()
        _ = try outcome.get()
    }

    /// Opens the freshly installed copy once this one has quit.
    private static func relaunch() {
        let path = Bundle.main.bundleURL.path
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/sh")
        p.arguments = ["-c", "while kill -0 \(ProcessInfo.processInfo.processIdentifier) 2>/dev/null; do sleep 0.2; done; /usr/bin/open \"$0\"", path]
        try? p.run()
        NSApp.terminate(nil)
    }
}

let updates = Updates()

/// The phone app is updated from the copy bundled in this Mac app, over the encrypted link, so the phone never goes
/// online for it: the Mac offers its version (UPDATE_OFFER), the phone shows Update, and asks for the APK
/// (UPDATE_GET) when you tap it; Android's installer then asks you to confirm. Only phone apps that say they can take
/// an update this way get offers; older ones get the QR code.
enum PhoneUpdate {
    private static var offeredTo: ObjectIdentifier?

    static var available: Bool { Tools.apk != nil && Tools.apkVersionCode > 0 }

    /// Offer once per connection when the phone's app is older than the bundled one; `force` offers again.
    static func offerIfNeeded(_ status: PhoneStatus, peer: ObjectIdentifier, force: Bool = false) {
        guard available, status.updates, status.version > 0, status.version < Tools.apkVersionCode,
              force || offeredTo != peer, let apk = Tools.apk,
              let size = (try? FileManager.default.attributesOfItem(atPath: apk.path)[.size]) as? Int else { return }
        offeredTo = peer
        let offer: [String: Any] = ["version": Updates.current, "code": Tools.apkVersionCode, "size": size, "show": force]
        link.sendToPhone(.updateOffer, (try? JSONSerialization.data(withJSONObject: offer)) ?? Data())
        log("offered the phone Macindroid \(Updates.current) (\(Tools.apkVersionCode)); it has \(status.version)")
    }

    /// The phone asked for the update: send the bundled APK in one frame (it's about 100 KB).
    static func send() {
        guard available, let apk = Tools.apk, let data = try? Data(contentsOf: apk) else { return }
        link.sendToPhone(.updateApk, data)
        log("sent the phone app update (\(data.count) bytes)")
    }
}
