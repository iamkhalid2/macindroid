import AppKit
import CryptoKit
import NetFS
import Network

/// The phone's storage as a volume in Finder (the menu's Show Phone in Finder).
///
/// macOS can mount a WebDAV server as a disk, so this is one: a small WebDAV server inside the Mac app, listening
/// only on this Mac (127.0.0.1) behind a password made up at each launch, which turns every request from Finder into
/// a question to the phone over the encrypted link (FS_REQUEST / FS_REPLY, see PhoneFiles.java). The phone opens
/// nothing on the network for it. Files are read and written in pieces as Finder asks, never kept on the Mac.
///
/// Finder's own bookkeeping files (.DS_Store, and the ._name files it writes beside files that have Mac-only
/// attributes) are kept in memory here and never reach the phone.
final class PhoneFiles {
    struct Reply {
        let info: [String: Any]
        let data: Data
        var ok: Bool { info["ok"] as? Bool ?? false }
        var error: String { info["error"] as? String ?? "io" }
        func number(_ key: String) -> Int64 { (info[key] as? NSNumber)?.int64Value ?? 0 }
    }

    private struct Pending {
        let each: (Reply?) -> Void
        var timeout: DispatchWorkItem
    }

    private var pending: [Int: Pending] = [:]
    private var nextID = 1
    private var listener: NWListener?
    private var connections: [ObjectIdentifier: DAVConnection] = [:]
    private var mounting = false
    private var unmountLater: DispatchWorkItem?
    fileprivate var shadows: [String: (data: Data, date: Date)] = [:]
    fileprivate let user = "macindroid", realm = "Macindroid"
    fileprivate let password = UUID().uuidString + UUID().uuidString
    fileprivate let nonce = UUID().uuidString

    /// Where it's mounted, while it is.
    private(set) var mountPoint: String? {
        get { UserDefaults.standard.string(forKey: "filesMount") }
        set { UserDefaults.standard.set(newValue, forKey: "filesMount") }
    }

    /// The volume's name, and the first part of every path Finder asks for.
    fileprivate var volume: String {
        let name = link.phoneName.replacingOccurrences(of: "/", with: "-").replacingOccurrences(of: ":", with: "-")
        return name.isEmpty ? "Phone" : name
    }

    /// At launch: a volume left over from the last run has nothing behind it any more.
    func start() {
        if let old = mountPoint { Self.forceUnmount(old) }
        mountPoint = nil
        // And one whose mount was still under way when the app last quit: a WebDAV volume on this Mac's own
        // address, named after the phone.
        var list: UnsafeMutablePointer<statfs>?
        let count = getmntinfo(&list, MNT_NOWAIT)
        guard let list, let name = volume.addingPercentEncoding(withAllowedCharacters: Self.pathCharacters) else { return }
        for i in 0..<Int(count) {
            var fs = list[i]
            let from = withUnsafePointer(to: &fs.f_mntfromname) { $0.withMemoryRebound(to: CChar.self, capacity: Int(MAXPATHLEN)) { String(cString: $0) } }
            let on = withUnsafePointer(to: &fs.f_mntonname) { $0.withMemoryRebound(to: CChar.self, capacity: Int(MAXPATHLEN)) { String(cString: $0) } }
            if from.hasPrefix("http://127.0.0.1:"), from.hasSuffix("/\(name)/") { Self.forceUnmount(on) }
        }
    }

    // MARK: asking the phone

    /// One request to the phone. `each` gets every reply (a read comes in several; the last has more == false), or
    /// nil if the phone is away or doesn't answer.
    func ask(_ request: [String: Any], data: Data = Data(), each: @escaping (Reply?) -> Void) {
        guard link.connected else { each(nil); return }
        let id = nextID
        nextID += 1
        var request = request
        request["id"] = id
        let head = json(request)
        var frame = Data([UInt8(head.count >> 24 & 0xff), UInt8(head.count >> 16 & 0xff), UInt8(head.count >> 8 & 0xff), UInt8(head.count & 0xff)])
        frame.append(head)
        frame.append(data)
        pending[id] = Pending(each: each, timeout: timeout(id))
        link.sendToPhone(.fsRequest, frame)
    }

    private func timeout(_ id: Int) -> DispatchWorkItem {
        let item = DispatchWorkItem { [weak self] in self?.pending.removeValue(forKey: id)?.each(nil) }
        DispatchQueue.main.asyncAfter(deadline: .now() + 30, execute: item)
        return item
    }

    /// FS_REPLY from the phone: 4-byte JSON length | JSON {id, ok, …} | data.
    func reply(_ payload: Data) {
        guard payload.count >= 4 else { return }
        let length = payload.prefix(4).reduce(0) { ($0 << 8) | Int($1) }
        guard payload.count >= 4 + length, let info = parseJSON(Data(payload.dropFirst(4).prefix(length))),
              let id = (info["id"] as? NSNumber)?.intValue, var waiting = pending[id] else { return }
        waiting.timeout.cancel()
        if info["more"] as? Bool == true {
            waiting.timeout = timeout(id)
            pending[id] = waiting
        } else {
            pending[id] = nil
        }
        waiting.each(Reply(info: info, data: Data(payload.dropFirst(4 + length))))
    }

    /// The phone went: everything waiting fails now, and the volume goes if the phone isn't back within a minute.
    func peerLost() {
        let waiting = pending
        pending = [:]
        for request in waiting.values {
            request.timeout.cancel()
            request.each(nil)
        }
        guard mountPoint != nil, unmountLater == nil else { return }
        let item = DispatchWorkItem { [weak self] in
            self?.unmountLater = nil
            if !link.connected { self?.unmount() }
        }
        unmountLater = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 60, execute: item)
    }

    // MARK: the volume

    /// The menu item: mount it if it isn't, then open it in Finder.
    func showInFinder(opening: Bool = true) {
        if let mountPoint, Self.isMounted(mountPoint) {
            if opening { NSWorkspace.shared.open(URL(fileURLWithPath: mountPoint)) }
            return
        }
        guard link.connected, !mounting else { return }
        mounting = true
        ask(["op": "stat", "path": "/"]) { [self] reply in // can the phone do this at all?
            guard let reply, reply.ok else {
                mounting = false
                let phone = link.phoneName
                let (title, body): (String, String) = switch reply?.error {
                case "access": ("Allow it on \(phone) first", "Tap the notification there, allow access to all files, then choose Show Phone in Finder again.")
                case "off": ("Switched off on \(phone)", "Switch on This phone in Finder in Macindroid on the phone.")
                default: ("\(phone) can't show its files", "Update Macindroid on the phone, then try again.")
                }
                notifications.postLocal(id: "files", title: title, body: body, info: [:])
                log("phone files: not available (\(reply?.error ?? "no answer"))")
                return
            }
            serve { [self] port in
                guard let port, let name = volume.addingPercentEncoding(withAllowedCharacters: Self.pathCharacters),
                      let url = URL(string: "http://127.0.0.1:\(port)/\(name)/") else { mounting = false; return }
                let user = user, password = password
                DispatchQueue.global(qos: .userInitiated).async {
                    var points: Unmanaged<CFArray>?
                    let quiet = ["UIOption": "NoUI"] as NSMutableDictionary                 // kNAUIOptionKey: kNAUIOptionNoUI
                    let options = ["SoftMount": true] as NSMutableDictionary                // kNetFSSoftMountKey: fail, don't hang
                    let status = NetFSMountURLSync(url as CFURL, nil, user as CFString, password as CFString, quiet, options, &points)
                    let path = (points?.takeRetainedValue() as? [String])?.first
                    DispatchQueue.main.async {
                        self.mounting = false
                        guard status == 0, let path else {
                            log("phone files: couldn't mount (\(status))")
                            notifications.postLocal(id: "files", title: "Couldn't show \(link.phoneName) in Finder", body: "macOS wouldn't mount it (error \(status)).", info: [:])
                            return
                        }
                        self.mountPoint = path
                        log("phone files: mounted at \(path)")
                        if opening { NSWorkspace.shared.open(URL(fileURLWithPath: path)) }
                    }
                }
            }
        }
    }

    /// `quitting`: the app is about to exit, so wait for it; nothing more will be answered anyway.
    func unmount(quitting: Bool = false) {
        guard let path = mountPoint else { return }
        mountPoint = nil
        shadows.removeAll()
        Self.forceUnmount(path, wait: quitting)
        log("phone files: unmounted")
    }

    private static func isMounted(_ path: String) -> Bool {
        var info = statfs()
        guard statfs(path, &info) == 0 else { return false }
        let type = withUnsafePointer(to: &info.f_fstypename) { $0.withMemoryRebound(to: CChar.self, capacity: 16) { String(cString: $0) } }
        return type == "webdav"
    }

    /// Off the main thread: unmounting makes macOS send its last requests, and this app has to answer them.
    private static func forceUnmount(_ path: String, wait: Bool = false) {
        guard isMounted(path) else { return }
        let work = { if Darwin.unmount(path, MNT_FORCE) != 0 { _ = Tools.run("/usr/sbin/diskutil", ["unmount", "force", path], timeout: 20) } }
        if wait { work() } else { DispatchQueue.global(qos: .userInitiated).async(execute: work) }
    }

    /// Starts the WebDAV server (once) and gives its port.
    private func serve(_ ready: @escaping (UInt16?) -> Void) {
        if let port = listener?.port?.rawValue { ready(port); return }
        let parameters = NWParameters.tcp
        parameters.requiredInterfaceType = .loopback // this Mac only: nothing on the network can reach it
        guard let listener = try? NWListener(using: parameters, on: .any) else {
            log("phone files: couldn't start the local server")
            ready(nil)
            return
        }
        var told = false
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            switch state {
            case .ready: if !told { told = true; ready(listener?.port?.rawValue) }
            case .failed, .cancelled:
                if !told { told = true; log("phone files: the local server stopped (\(state))"); ready(nil) }
                if self?.listener === listener { self?.listener = nil }
            default: break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return }
            let dav = DAVConnection(connection, self)
            self.connections[ObjectIdentifier(dav)] = dav
        }
        listener.start(queue: .main)
        self.listener = listener
    }

    fileprivate func closed(_ connection: DAVConnection) { connections[ObjectIdentifier(connection)] = nil }

    // MARK: what the server needs

    fileprivate static let pathCharacters = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~/")

    /// "/Volume/DCIM/a.jpg" → "/DCIM/a.jpg"; nil for anything outside the volume.
    fileprivate func phonePath(_ path: String) -> String? {
        let prefix = "/" + volume
        guard path == prefix || path.hasPrefix(prefix + "/") else { return nil }
        var rest = String(path.dropFirst(prefix.count))
        while rest.count > 1, rest.hasSuffix("/") { rest.removeLast() }
        return rest.isEmpty ? "/" : rest
    }

    fileprivate func href(_ path: String, directory: Bool) -> String {
        var full = "/" + volume + (path == "/" ? "" : path)
        if directory { full += "/" }
        return full.addingPercentEncoding(withAllowedCharacters: Self.pathCharacters) ?? full
    }

    /// Finder's bookkeeping, kept here instead of on the phone.
    fileprivate static func isShadow(_ path: String) -> Bool {
        let name = path.split(separator: "/").last.map(String.init) ?? ""
        return name.hasPrefix("._") || name == ".DS_Store"
    }

    /// Things macOS looks for at the top of every disk and the phone doesn't need.
    fileprivate static func isUnwanted(_ path: String) -> Bool {
        [".Spotlight-V100", ".Trashes", ".fseventsd", ".metadata_never_index", ".metadata_never_index_unless_rootfs",
         ".metadata_direct_scope_only", ".hidden", ".ql_disablethumbnails", ".ql_disablecache"]
            .contains(path.split(separator: "/").first.map(String.init) ?? "")
    }

    fileprivate var challenge: String { "Digest realm=\"\(realm)\", nonce=\"\(nonce)\", qop=\"auth\", algorithm=MD5" }

    /// HTTP Digest: what macOS's WebDAV client uses when there's no TLS, so the password itself never crosses even
    /// the loopback. Other accounts on this Mac can reach the port, but not get past this.
    fileprivate func authorized(method: String, target: String, header: String?) -> Bool {
        guard let header, header.hasPrefix("Digest "), let pattern = try? NSRegularExpression(pattern: "(\\w+)=(?:\"([^\"]*)\"|([^,\\s]+))") else { return false }
        var fields: [String: String] = [:]
        let text = header as NSString
        for match in pattern.matches(in: header, range: NSRange(location: 0, length: text.length)) {
            let value = match.range(at: 2).location != NSNotFound ? match.range(at: 2) : match.range(at: 3)
            fields[text.substring(with: match.range(at: 1))] = text.substring(with: value)
        }
        guard fields["username"] == user, fields["nonce"] == nonce, let uri = fields["uri"], uri == target,
              let response = fields["response"] else { return false }
        func md5(_ s: String) -> String { Insecure.MD5.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined() }
        let ha1 = md5("\(user):\(realm):\(password)"), ha2 = md5("\(method):\(uri)")
        let expected = fields["qop"].map { md5("\(ha1):\(nonce):\(fields["nc"] ?? ""):\(fields["cnonce"] ?? ""):\($0):\(ha2)") }
            ?? md5("\(ha1):\(nonce):\(ha2)")
        return expected == response.lowercased()
    }
}

/// One connection from macOS's WebDAV client: requests one after another, each answered by asking the phone.
private final class DAVConnection {
    private let connection: NWConnection
    private unowned let files: PhoneFiles
    private var buffer = Data()
    private var method = "", target = ""
    private var headers: [String: String] = [:]
    private var bodyLeft = 0
    private var chunked = false
    private var chunkLeft = -1 // -1: a size line is next; -2: the line break after a chunk; -3: the trailers
    private var open = true

    private static let httpDate: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
        return f
    }()

    init(_ connection: NWConnection, _ files: PhoneFiles) {
        self.connection = connection
        self.files = files
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.close()
            default: break
            }
        }
        connection.start(queue: .main)
        readHead()
    }

    private func close() {
        guard open else { return }
        open = false
        connection.cancel()
        files.closed(self)
    }

    // MARK: reading

    private func fill(_ then: @escaping (Bool) -> Void) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1 << 18) { [weak self] data, _, _, _ in
            guard let self, self.open else { return }
            if let data, !data.isEmpty {
                self.buffer.append(data)
                then(true)
            } else {
                then(false)
            }
        }
    }

    private func readHead() {
        if let end = buffer.range(of: Data("\r\n\r\n".utf8)) {
            let head = String(decoding: buffer.subdata(in: 0..<end.lowerBound), as: UTF8.self)
            buffer.removeSubrange(0..<end.upperBound)
            handle(head)
        } else if buffer.count > 64 * 1024 {
            close()
        } else {
            fill { [weak self] more in if more { self?.readHead() } else { self?.close() } }
        }
    }

    private func takeLine() -> String? {
        guard let end = buffer.range(of: Data("\r\n".utf8)) else { return nil }
        let line = String(decoding: buffer.subdata(in: 0..<end.lowerBound), as: UTF8.self)
        buffer.removeSubrange(0..<end.upperBound)
        return line
    }

    /// The next piece of the request's body: empty when it's all been read, nil if the connection broke.
    private func body(_ then: @escaping (Data?) -> Void) {
        let more: () -> Void = { [weak self] in self?.fill { got in if got { self?.body(then) } else { then(nil) } } }
        func take(_ limit: Int) -> Data {
            let n = min(buffer.count, limit, chunkSize)
            let piece = buffer.subdata(in: 0..<n)
            buffer.removeSubrange(0..<n)
            return piece
        }
        if !chunked {
            if bodyLeft == 0 { then(Data()); return }
            if buffer.isEmpty { more(); return }
            let piece = take(bodyLeft)
            bodyLeft -= piece.count
            then(piece)
            return
        }
        if chunkLeft > 0 {
            if buffer.isEmpty { more(); return }
            let piece = take(chunkLeft)
            chunkLeft -= piece.count
            if chunkLeft == 0 { chunkLeft = -2 }
            then(piece)
            return
        }
        if chunkLeft == -2 {
            guard buffer.count >= 2 else { more(); return }
            buffer.removeSubrange(0..<2)
            chunkLeft = -1
        }
        if chunkLeft == -1 {
            guard let line = takeLine() else { more(); return }
            guard let size = Int(line.split(separator: ";").first.map { $0.trimmingCharacters(in: .whitespaces) } ?? "", radix: 16), size >= 0 else { then(nil); return }
            chunkLeft = size == 0 ? -3 : size
            if size > 0 { body(then); return }
        }
        while let line = takeLine() { // the trailers end with an empty line
            if line.isEmpty {
                chunked = false
                bodyLeft = 0
                then(Data())
                return
            }
        }
        more()
    }

    /// Reads and throws away a body we don't use (PROPFIND's list of wanted properties, a LOCK's owner).
    private func drain(_ then: @escaping () -> Void) {
        body { [weak self] piece in
            guard let self, let piece else { self?.close(); return }
            if piece.isEmpty { then() } else { self.drain(then) }
        }
    }

    // MARK: answering

    private static let reasons = [200: "OK", 201: "Created", 204: "No Content", 206: "Partial Content", 207: "Multi-Status",
                                  401: "Unauthorized", 403: "Forbidden", 404: "Not Found", 405: "Method Not Allowed", 409: "Conflict",
                                  412: "Precondition Failed", 500: "Internal Server Error", 501: "Not Implemented", 503: "Service Unavailable"]

    private func head(_ status: Int, _ extra: [(String, String)], length: Int64) -> Data {
        var text = "HTTP/1.1 \(status) \(Self.reasons[status] ?? "")\r\nServer: Macindroid\r\nDate: \(Self.httpDate.string(from: Date()))\r\nContent-Length: \(length)\r\n"
        for (name, value) in extra { text += "\(name): \(value)\r\n" }
        return Data((text + "\r\n").utf8)
    }

    /// A whole answer, then on to the next request. `after` closes instead (when the request's body wasn't read).
    private func respond(_ status: Int, _ extra: [(String, String)] = [], body: Data = Data(), closing: Bool = false) {
        if Prefs.debug { log("dav: \(method) \(target) -> \(status)") }
        var out = head(status, extra + (closing ? [("Connection", "close")] : []), length: Int64(body.count))
        if method != "HEAD" { out.append(body) }
        connection.send(content: out, completion: .contentProcessed { [weak self] _ in
            if closing { self?.close() } else { self?.readHead() }
        })
    }

    private func respond(xml: String, status: Int = 207, _ extra: [(String, String)] = []) {
        respond(status, extra + [("Content-Type", "application/xml; charset=utf-8")], body: Data(("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" + xml).utf8))
    }

    private static func status(for error: String?) -> Int {
        switch error {
        case nil: 503 // the phone is away
        case "missing": 404
        case "exists": 405
        case "access", "off": 403
        default: 500
        }
    }

    private func entry(_ path: String, directory: Bool, size: Int64, modified: Date) -> String {
        var props = directory ? "<D:resourcetype><D:collection/></D:resourcetype>" : "<D:resourcetype/><D:getcontentlength>\(size)</D:getcontentlength>"
        props += "<D:getlastmodified>\(Self.httpDate.string(from: modified))</D:getlastmodified>"
        props += "<D:creationdate>\(ISO8601DateFormatter().string(from: modified))</D:creationdate>"
        return "<D:response><D:href>\(files.href(path, directory: directory))</D:href><D:propstat><D:prop>\(props)</D:prop>"
            + "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
    }

    private static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }

    // MARK: the requests

    private func handle(_ text: String) {
        var lines = text.components(separatedBy: "\r\n")
        let first = lines.removeFirst().split(separator: " ")
        guard first.count >= 2 else { close(); return }
        method = String(first[0])
        target = String(first[1])
        headers = [:]
        for line in lines {
            guard let colon = line.firstIndex(of: ":") else { continue }
            headers[line[..<colon].lowercased()] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        }
        chunked = headers["transfer-encoding"]?.lowercased().contains("chunked") == true
        bodyLeft = chunked ? 0 : Int(headers["content-length"] ?? "") ?? 0
        chunkLeft = -1
        if Prefs.debug { log("dav: > \(method) \(target) depth \(headers["depth"] ?? "-") length \(headers["content-length"] ?? (chunked ? "chunked" : "-"))") }
        if headers["expect"]?.lowercased() == "100-continue" {
            connection.send(content: Data("HTTP/1.1 100 Continue\r\n\r\n".utf8), completion: .contentProcessed { _ in })
        }
        guard files.authorized(method: method, target: target, header: headers["authorization"]) else {
            drain { self.respond(401, [("WWW-Authenticate", self.files.challenge)]) }
            return
        }
        if method == "OPTIONS" {
            drain { self.respond(200, [("DAV", "1, 2"), ("MS-Author-Via", "DAV"),
                                       ("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, MOVE, PROPFIND, PROPPATCH, LOCK, UNLOCK")]) }
            return
        }
        guard let path = Self.path(of: target).flatMap(files.phonePath) else {
            drain { self.respond(404) }
            return
        }
        if PhoneFiles.isUnwanted(path) {
            drain { self.respond(["PUT", "MKCOL", "LOCK"].contains(self.method) ? 403 : 404) }
            return
        }
        switch method {
        case "PROPFIND": drain { self.propfind(path) }
        case "GET", "HEAD": drain { self.get(path) }
        case "PUT": put(path)
        case "MKCOL": drain { self.simple(["op": "mkdir", "path": path], path, success: 201, missing: 409) }
        case "DELETE": drain { self.simple(["op": "delete", "path": path], path, success: 204, missing: 404) }
        case "MOVE": drain { self.move(path) }
        case "LOCK": drain { self.lock(path) }
        case "UNLOCK": drain { self.respond(204) }
        case "PROPPATCH":
            drain { self.respond(xml: "<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>\(self.files.href(path, directory: false))</D:href>"
                                 + "<D:propstat><D:prop/><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>") }
        default: drain { self.respond(self.method == "COPY" ? 501 : 405) }
        }
    }

    /// The path in a request line or a Destination header, decoded.
    private static func path(of target: String) -> String? {
        var raw = target
        if let scheme = raw.range(of: "://") { // an absolute URL
            raw = String(raw[scheme.upperBound...])
            raw = raw.firstIndex(of: "/").map { String(raw[$0...]) } ?? "/"
        }
        if let query = raw.firstIndex(of: "?") { raw = String(raw[..<query]) }
        return raw.removingPercentEncoding
    }

    private func propfind(_ path: String) {
        let open = "<D:multistatus xmlns:D=\"DAV:\">", shut = "</D:multistatus>"
        if PhoneFiles.isShadow(path) {
            guard let shadow = files.shadows[path] else { respond(404); return }
            respond(xml: open + entry(path, directory: false, size: Int64(shadow.data.count), modified: shadow.date) + shut)
            return
        }
        files.ask(["op": "stat", "path": path]) { [weak self] reply in
            guard let self else { return }
            guard let reply, reply.ok else { self.respond(Self.status(for: reply.map(\.error))); return }
            let directory = reply.number("d") == 1
            // No free space (WebDAV's quota properties): macOS only takes it in while mounting, and then its mount
            // stops for a minute and a half asking its own helper for it while that helper is still busy mounting.
            // So Finder shows the volume's size as unknown; copying isn't affected.
            let me = self.entry(path, directory: directory, size: reply.number("s"), modified: Self.date(reply.number("m")))
            guard directory, self.headers["depth"] != "0" else { self.respond(xml: open + me + shut); return }
            self.files.ask(["op": "list", "path": path]) { [weak self] reply in
                guard let self else { return }
                guard let reply, reply.ok else { self.respond(Self.status(for: reply.map(\.error))); return }
                var xml = open + me
                for item in reply.info["entries"] as? [[Any]] ?? [] {
                    guard item.count >= 4, let name = item[0] as? String, !name.contains("/"),
                          let d = item[1] as? NSNumber, let s = item[2] as? NSNumber, let m = item[3] as? NSNumber else { continue }
                    xml += self.entry(path == "/" ? "/" + name : path + "/" + name, directory: d.intValue == 1, size: s.int64Value,
                                      modified: Self.date(m.int64Value))
                }
                self.respond(xml: xml + shut)
            }
        }
    }

    private func get(_ path: String) {
        if PhoneFiles.isShadow(path) {
            guard let shadow = files.shadows[path] else { respond(404); return }
            respond(200, [("Last-Modified", Self.httpDate.string(from: shadow.date))], body: shadow.data)
            return
        }
        if method == "HEAD" {
            files.ask(["op": "stat", "path": path]) { [weak self] reply in
                guard let self else { return }
                guard let reply, reply.ok else { self.respond(Self.status(for: reply.map(\.error))); return }
                let out = self.head(200, [("Last-Modified", Self.httpDate.string(from: Self.date(reply.number("m")))), ("Accept-Ranges", "bytes")],
                                    length: reply.number("s"))
                self.connection.send(content: out, completion: .contentProcessed { [weak self] _ in self?.readHead() })
            }
            return
        }
        var request: [String: Any] = ["op": "read", "path": path]
        var partial = false
        if let range = headers["range"], range.hasPrefix("bytes="), !range.contains(",") {
            let ends = range.dropFirst(6).split(separator: "-", omittingEmptySubsequences: false)
            if ends.count == 2, let from = Int64(ends[0]) {
                partial = true
                request["offset"] = from
                if let to = Int64(ends[1]), to >= from { request["length"] = to - from + 1 }
            }
        }
        var started = false
        files.ask(request) { [weak self] reply in
            guard let self, self.open else { return }
            guard let reply, reply.ok else {
                if started { self.close() } else { self.respond(Self.status(for: reply.map(\.error))) } // cut off: Finder sees a failed read
                return
            }
            var out = Data()
            if !started {
                started = true
                let size = reply.number("size"), offset = reply.number("offset"), length = reply.number("length")
                var extra = [("Last-Modified", Self.httpDate.string(from: Self.date(reply.number("m")))), ("Accept-Ranges", "bytes"),
                             ("Content-Type", "application/octet-stream")]
                if partial { extra.append(("Content-Range", "bytes \(offset)-\(max(offset, offset + length - 1))/\(size)")) }
                out = self.head(partial ? 206 : 200, extra, length: length)
            }
            out.append(reply.data)
            let last = reply.info["more"] as? Bool != true
            self.connection.send(content: out, completion: .contentProcessed { [weak self] _ in if last { self?.readHead() } })
        }
    }

    /// A file from Finder, passed on piece by piece; the next piece is read only once the phone has the last one,
    /// so a big file never piles up in memory. The phone writes to a temporary file and swaps it in at the end.
    private func put(_ path: String) {
        if PhoneFiles.isShadow(path) {
            var kept = Data()
            func next() {
                body { [weak self] piece in
                    guard let self, let piece else { self?.close(); return }
                    if piece.isEmpty {
                        self.files.shadows[path] = (kept, Date())
                        self.respond(201)
                    } else {
                        if kept.count < 4 << 20 { kept.append(piece) }
                        next()
                    }
                }
            }
            next()
            return
        }
        files.ask(["op": "create", "path": path]) { [weak self] reply in
            guard let self else { return }
            guard let reply, reply.ok, let handle = reply.info["handle"] else {
                self.respond(reply?.error == "missing" ? 409 : Self.status(for: reply.map(\.error)), closing: true)
                return
            }
            func next() {
                self.body { [weak self] piece in
                    guard let self else { return }
                    guard let piece else { // Finder gave up
                        self.files.ask(["op": "abort", "handle": handle]) { _ in }
                        self.close()
                        return
                    }
                    self.files.ask(piece.isEmpty ? ["op": "commit", "handle": handle] : ["op": "append", "handle": handle], data: piece) { [weak self] reply in
                        guard let self else { return }
                        guard let reply, reply.ok else {
                            self.files.ask(["op": "abort", "handle": handle]) { _ in }
                            self.respond(Self.status(for: reply.map(\.error)), closing: true)
                            return
                        }
                        if piece.isEmpty { self.respond(201) } else { next() }
                    }
                }
            }
            next()
        }
    }

    private func simple(_ request: [String: Any], _ path: String, success: Int, missing: Int) {
        if PhoneFiles.isShadow(path) {
            if method == "DELETE" { files.shadows[path] = nil }
            respond(success)
            return
        }
        files.ask(request) { [weak self] reply in
            guard let self else { return }
            self.respond(reply?.ok == true ? success : reply?.error == "missing" ? missing : Self.status(for: reply.map(\.error)))
        }
    }

    private func move(_ path: String) {
        guard let to = headers["destination"].flatMap(Self.path(of:)).flatMap(files.phonePath) else { respond(409); return }
        if PhoneFiles.isShadow(path) || PhoneFiles.isShadow(to) {
            guard PhoneFiles.isShadow(path), PhoneFiles.isShadow(to), let shadow = files.shadows.removeValue(forKey: path) else { respond(404); return }
            files.shadows[to] = shadow
            respond(201)
            return
        }
        files.ask(["op": "move", "from": path, "to": to, "overwrite": headers["overwrite"]?.uppercased() != "F"]) { [weak self] reply in
            guard let self else { return }
            guard let reply, reply.ok else { self.respond(reply?.error == "exists" ? 412 : Self.status(for: reply.map(\.error))); return }
            self.respond(reply.info["replaced"] as? Bool == true ? 204 : 201)
        }
    }

    /// macOS locks a file before writing it and won't mount read-write without locking. One Mac, one user: every
    /// lock is granted. Locking a name that doesn't exist yet creates it empty, which is how Finder starts a new file.
    private func lock(_ path: String) {
        let token = "opaquelocktoken:" + UUID().uuidString
        let xml = "<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock><D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope>"
            + "<D:depth>0</D:depth><D:timeout>Second-600</D:timeout><D:locktoken><D:href>\(token)</D:href></D:locktoken>"
            + "</D:activelock></D:lockdiscovery></D:prop>"
        let granted: (Int) -> Void = { self.respond(xml: xml, status: $0, [("Lock-Token", "<\(token)>")]) }
        if PhoneFiles.isShadow(path) {
            let existed = files.shadows[path] != nil
            if !existed { files.shadows[path] = (Data(), Date()) }
            granted(existed ? 200 : 201)
            return
        }
        files.ask(["op": "stat", "path": path]) { [weak self] reply in
            guard let self else { return }
            if reply?.ok == true { granted(200); return }
            guard reply?.error == "missing" else { self.respond(Self.status(for: reply.map(\.error))); return }
            self.files.ask(["op": "create", "path": path]) { [weak self] reply in
                guard let self else { return }
                guard let reply, reply.ok, let handle = reply.info["handle"] else { self.respond(reply?.error == "missing" ? 409 : 403); return }
                self.files.ask(["op": "commit", "handle": handle]) { reply in
                    if reply?.ok == true { granted(201) } else { self.respond(500) }
                }
            }
        }
    }
}
