import AppKit
import CryptoKit
import UniformTypeIdentifiers

/// File drop both ways. A transfer is FILE_OFFER (JSON), FILE_CHUNKs (8-byte id + up to 256 KB), then FILE_END
/// (8-byte id + SHA-256 of the whole file). Chunks are separate frames, so clipboard and notifications keep
/// flowing during a long transfer. Files are streamed from and to disk; nothing large is held in memory.
final class Transfers {
    final class Incoming {
        let id: UInt64, name: String, size: Int64, temp: URL, handle: FileHandle
        var received: Int64 = 0
        var hasher = SHA256()
        init(id: UInt64, name: String, size: Int64, temp: URL, handle: FileHandle) {
            self.id = id; self.name = name; self.size = size; self.temp = temp; self.handle = handle
        }
    }

    final class Outgoing {
        let id: UInt64, url: URL, size: Int64, handle: FileHandle
        var sent: Int64 = 0
        var hasher = SHA256()
        init(id: UInt64, url: URL, size: Int64, handle: FileHandle) {
            self.id = id; self.url = url; self.size = size; self.handle = handle
        }
    }

    struct Done {
        let name: String
        let url: URL?
        let toPhone: Bool
        let isLink: Bool
        let date: Date
    }

    private var incoming: [UInt64: Incoming] = [:]
    private var outgoing: Outgoing?
    private var queue: [URL] = []
    private(set) var recent: [Done] = []
    private var lastShownPercent: [UInt64: Int] = [:]
    private let downloads = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]

    /// A file that's moving right now, for the menu's live rows.
    struct Live {
        let key: String
        let name: String
        let toPhone: Bool
        let done: Int64
        let total: Int64
    }

    var live: [Live] {
        var items = incoming.values.sorted { $0.id < $1.id }.map {
            Live(key: "in\($0.id)", name: $0.name, toPhone: false, done: $0.received, total: $0.size)
        }
        if let o = outgoing {
            items.append(Live(key: "out\(o.id)", name: o.url.lastPathComponent, toPhone: true, done: o.sent, total: o.size))
        }
        return items
    }

    /// Everything moving, as one fraction (for the menu-bar ring); nil when idle.
    var overallProgress: Double? {
        let items = live
        guard !items.isEmpty else { return nil }
        let total = items.reduce(Int64(0)) { $0 + $1.total }
        return total > 0 ? Double(items.reduce(Int64(0)) { $0 + $1.done }) / Double(total) : 0
    }

    var waiting: Int { queue.count }

    private func percent(_ part: Int64, _ whole: Int64) -> Int { whole > 0 ? Int(part * 100 / whole) : 0 }

    private func idBytes(_ id: UInt64) -> Data { withUnsafeBytes(of: id.bigEndian) { Data($0) } }
    private func readID(_ payload: Data) -> UInt64 { payload.prefix(8).reduce(0) { ($0 << 8) | UInt64($1) } }

    // MARK: Mac → phone

    func send(_ urls: [URL]) {
        var files: [URL] = []
        for url in urls {
            var isDir: ObjCBool = false
            if FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir), !isDir.boolValue { files.append(url) }
        }
        if files.count < urls.count {
            notifications.postLocal(id: "folders", title: "Folders weren't sent",
                                    body: "Macindroid sends files; compress a folder first to send it.", info: [:])
        }
        queue.append(contentsOf: files)
        next()
    }

    private func next() {
        guard outgoing == nil, !queue.isEmpty else { link.onChange?(); return }
        guard link.connected else {
            queue.removeAll()
            notifications.postLocal(id: "offline", title: "Not sent",
                                    body: "\(Store.phoneName ?? "Your phone") isn't connected.", info: [:])
            link.onChange?()
            return
        }
        let url = queue.removeFirst()
        guard let handle = try? FileHandle(forReadingFrom: url),
              let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize else { next(); return }
        let id = UInt64.random(in: 1...UInt64.max)
        let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        let transfer = Outgoing(id: id, url: url, size: Int64(size), handle: handle)
        outgoing = transfer
        link.sendToPhone(.fileOffer, json(["id": String(id), "name": url.lastPathComponent, "size": size, "mime": mime]))
        log("Mac -> \(link.phoneName): file \(url.lastPathComponent) (\(size) bytes)")
        link.onChange?()
        pump(transfer)
    }

    /// Sends one chunk, then the next when the network has taken it, so memory use stays flat.
    private func pump(_ o: Outgoing) {
        guard outgoing === o else { return }
        let data = (try? o.handle.read(upToCount: chunkSize)) ?? nil
        guard let data, !data.isEmpty else {
            link.sendToPhone(.fileEnd, idBytes(o.id) + Data(o.hasher.finalize()))
            try? o.handle.close()
            record(Done(name: o.url.lastPathComponent, url: o.url, toPhone: true, isLink: false, date: Date()))
            outgoing = nil
            next()
            return
        }
        o.hasher.update(data: data)
        link.sendToPhone(.fileChunk, idBytes(o.id) + data) { [weak self] ok in
            guard let self, self.outgoing === o else { return }
            guard ok else { self.abortOutgoing(); return }
            o.sent += Int64(data.count)
            self.showProgress(o.id, o.sent, o.size)
            self.pump(o)
        }
    }

    private func abortOutgoing() {
        guard let o = outgoing else { return }
        try? o.handle.close()
        outgoing = nil
        queue.removeAll()
        link.onChange?()
    }

    // MARK: phone → Mac

    func handle(_ kind: Kind, _ payload: Data) {
        switch kind {
        case .fileOffer:
            guard let info = parseJSON(payload), let idText = info["id"] as? String, let id = UInt64(idText) else { return }
            let name = safeName(info["name"] as? String ?? "file")
            let temp = downloads.appendingPathComponent(".macindroid-\(idText).part")
            FileManager.default.createFile(atPath: temp.path, contents: nil)
            guard let handle = try? FileHandle(forWritingTo: temp) else {
                log("cannot write to \(downloads.path); allow Macindroid to use Downloads in System Settings")
                return
            }
            incoming[id] = Incoming(id: id, name: name, size: (info["size"] as? NSNumber)?.int64Value ?? -1, temp: temp, handle: handle)
            link.onChange?()
        case .fileChunk:
            guard payload.count > 8, let t = incoming[readID(payload)] else { return }
            let data = payload.dropFirst(8)
            do { try t.handle.write(contentsOf: data) } catch { cancelIncoming(t.id); return }
            t.hasher.update(data: data)
            t.received += Int64(data.count)
            showProgress(t.id, t.received, t.size)
        case .fileEnd:
            guard payload.count >= 40, let t = incoming.removeValue(forKey: readID(payload)) else { return }
            try? t.handle.close()
            lastShownPercent[t.id] = nil
            guard Data(t.hasher.finalize()) == Data(payload.dropFirst(8).prefix(32)) else {
                try? FileManager.default.removeItem(at: t.temp)
                log("\(link.phoneName) -> Mac: file \(t.name) failed its checksum; discarded")
                notifications.postLocal(id: "file:\(t.id)", title: "\(t.name) didn't arrive intact",
                                        body: "Send it again from your phone.", info: [:])
                link.onChange?()
                return
            }
            let dest = uniqueURL(t.name)
            do { try FileManager.default.moveItem(at: t.temp, to: dest) } catch { log("could not save \(t.name): \(error)"); return }
            log("\(link.phoneName) -> Mac: file \(dest.lastPathComponent) (\(t.received) bytes, checksum ok)")
            record(Done(name: dest.lastPathComponent, url: dest, toPhone: false, isLink: false, date: Date()))
            notifications.postLocal(id: "file:\(t.id)", title: dest.lastPathComponent,
                                    body: "From \(link.phoneName) · in Downloads", info: ["kind": "file", "path": dest.path])
        case .fileCancel:
            cancelIncoming(readID(payload))
        default: break
        }
    }

    private func cancelIncoming(_ id: UInt64) {
        guard let t = incoming.removeValue(forKey: id) else { return }
        try? t.handle.close()
        try? FileManager.default.removeItem(at: t.temp)
        link.onChange?()
    }

    func peerLost() {
        incoming.keys.forEach(cancelIncoming)
        abortOutgoing()
    }

    func recordLink(_ url: URL, toPhone: Bool = false) {
        record(Done(name: url.host ?? url.absoluteString, url: url, toPhone: toPhone, isLink: true, date: Date()))
    }

    private func record(_ done: Done) {
        recent.insert(done, at: 0)
        if recent.count > 8 { recent.removeLast() }
        link.onChange?()
    }

    private func showProgress(_ id: UInt64, _ part: Int64, _ whole: Int64) {
        let p = percent(part, whole)
        if lastShownPercent[id] != p { lastShownPercent[id] = p; link.onChange?() }
    }

    private func safeName(_ name: String) -> String {
        var clean = name.replacingOccurrences(of: "/", with: "-").replacingOccurrences(of: ":", with: "-")
        while clean.hasPrefix(".") { clean.removeFirst() }
        return clean.isEmpty ? "file" : String(clean.prefix(200))
    }

    private func uniqueURL(_ name: String) -> URL {
        let base = (name as NSString).deletingPathExtension, ext = (name as NSString).pathExtension
        var candidate = downloads.appendingPathComponent(name)
        var n = 2
        while FileManager.default.fileExists(atPath: candidate.path) {
            candidate = downloads.appendingPathComponent(ext.isEmpty ? "\(base) \(n)" : "\(base) \(n).\(ext)")
            n += 1
        }
        return candidate
    }
}
