// The encrypted link to the phone.
// Frames: 4-byte big-endian length, then either
//   - an AES-256-GCM sealed box (12-byte nonce | ciphertext | 16-byte tag) whose plaintext is 1 kind byte + payload, or
//   - before pairing only, a plaintext pairing message starting with "MDP1" (see handlePairing).
import AppKit
import CryptoKit
import Network

let listenPort: NWEndpoint.Port = 47800
let serviceType = "_macindroid._tcp"
let maxFrame = 32 * 1024 * 1024
let chunkSize = 256 * 1024
let pairMagic = Data("MDP1".utf8)
let macName = Host.current().localizedName ?? "Mac"

enum Kind: UInt8 {
    case text = 1, image = 2, hello = 3, ping = 4, pong = 5
    case notifPost = 10, notifRemove = 11, notifReply = 12, notifAction = 13, notifDismiss = 14, notifOpen = 15
    case fileOffer = 20, fileChunk = 21, fileEnd = 22, fileCancel = 23, link = 24
    case ring = 30, ringStop = 31, status = 32, mirrorPrep = 33, mirrorReady = 34, mirrorDone = 35
    case photo = 36, photoGet = 37, photoFull = 38
    case code = 40 // a one-time code from a phone notification: JSON {code, from, app, key}
}

enum PairMessage: UInt8 { case request = 1, challenge = 2, closed = 3, declined = 4 }

/// ~/Library/Logs/Macindroid.log, written by the app itself so it logs however it was started (at login, from
/// Finder, or by a macindroid:// link). Trimmed to its last half when it passes 1 MB.
private let logFile: FileHandle? = {
    let url = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0].appendingPathComponent("Logs/Macindroid.log")
    if let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize, size > 1_000_000,
       let data = try? Data(contentsOf: url) {
        try? data.suffix(500_000).write(to: url)
    }
    if !FileManager.default.fileExists(atPath: url.path) { FileManager.default.createFile(atPath: url.path, contents: nil) }
    let handle = try? FileHandle(forWritingTo: url)
    handle?.seekToEndOfFile()
    return handle
}()

func log(_ message: String) {
    let stamp = ISO8601DateFormatter().string(from: Date())
    (logFile ?? FileHandle.standardError).write(Data("\(stamp) \(message)\n".utf8))
}

func json(_ object: Any) -> Data { (try? JSONSerialization.data(withJSONObject: object)) ?? Data() }
func parseJSON(_ data: Data) -> [String: Any]? { (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] }

/// Pairing data. The key file is readable only by this user.
enum Store {
    static let dir = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent("Library/Application Support/Macindroid", isDirectory: true)
    private static let keyURL = dir.appendingPathComponent("key")
    static private(set) var key: SymmetricKey? = load()

    static var phoneName: String? {
        get { UserDefaults.standard.string(forKey: "phoneName") }
        set { UserDefaults.standard.set(newValue, forKey: "phoneName") }
    }

    private static func load() -> SymmetricKey? {
        guard let text = try? String(contentsOf: keyURL, encoding: .utf8),
              let data = Data(base64Encoded: text.trimmingCharacters(in: .whitespacesAndNewlines)), data.count == 32 else { return nil }
        return SymmetricKey(data: data)
    }

    static func save(_ newKey: SymmetricKey, phone: String) {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? FileManager.default.removeItem(at: keyURL)
        let encoded = newKey.withUnsafeBytes { Data($0) }.base64EncodedString()
        FileManager.default.createFile(atPath: keyURL.path, contents: Data(encoded.utf8), attributes: [.posixPermissions: 0o600])
        key = newKey
        phoneName = phone
    }
}

enum Prefs {
    static func flag(_ name: String) -> Bool { UserDefaults.standard.object(forKey: name) as? Bool ?? true }
    static func toggle(_ name: String) { UserDefaults.standard.set(!flag(name), forKey: name) }
    static var syncClipboard: Bool { flag("syncClipboard") }
    static var phoneNotifications: Bool { flag("phoneNotifications") }
    static var openLinks: Bool { flag("openLinks") }
    static var copyCodes: Bool { flag("copyCodes") }
    static var photoPreviews: Bool { flag("photoPreviews") }
}

func framed(_ body: Data) -> Data {
    let n = UInt32(body.count)
    var out = Data([UInt8(n >> 24 & 0xff), UInt8(n >> 16 & 0xff), UInt8(n >> 8 & 0xff), UInt8(n & 0xff)])
    out.append(body)
    return out
}

func seal(_ kind: Kind, _ payload: Data, key: SymmetricKey) -> Data? {
    var plain = Data([kind.rawValue])
    plain.append(payload)
    return try? AES.GCM.seal(plain, using: key).combined
}

func unseal(_ combined: Data, key: SymmetricKey) -> (UInt8, Data)? {
    guard let box = try? AES.GCM.SealedBox(combined: combined), let plain = try? AES.GCM.open(box, using: key),
          let first = plain.first else { return nil }
    return (first, Data(plain.dropFirst()))
}

struct PhoneStatus {
    var battery: Int
    var charging: Bool
}

final class Peer {
    let connection: NWConnection
    var buffer = Data()
    var name = "Phone"
    var authenticated = false
    var pendingKey: SymmetricKey?
    init(_ connection: NWConnection) { self.connection = connection }
}

final class Link {
    private var listener: NWListener?
    private var peers: [ObjectIdentifier: Peer] = [:]
    /// Pairing requests are refused unless the Mac's pairing window is open.
    var pairingMode = false
    var onPairRequest: ((Peer, String) -> Void)? // peer, 6-digit code
    var onChange: (() -> Void)?
    private(set) var status: PhoneStatus?
    var ringing = false

    var phone: Peer? { peers.values.first { $0.authenticated } }
    var connected: Bool { phone != nil }
    var phoneName: String { Store.phoneName ?? "Phone" }

    func start() throws {
        let parameters = NWParameters.tcp
        parameters.allowLocalEndpointReuse = true
        let listener = try NWListener(using: parameters, on: listenPort)
        listener.service = NWListener.Service(name: macName, type: serviceType)
        listener.stateUpdateHandler = { state in log("listener: \(state)") }
        listener.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        listener.start(queue: .main)
        self.listener = listener
    }

    private func accept(_ connection: NWConnection) {
        let peer = Peer(connection)
        peers[ObjectIdentifier(peer)] = peer
        connection.stateUpdateHandler = { [weak self, weak peer] state in
            guard let self, let peer else { return }
            if case .failed = state { self.drop(peer) }
            if case .cancelled = state { self.drop(peer) }
        }
        connection.start(queue: .main)
        receive(peer)
    }

    func drop(_ peer: Peer) {
        guard peers.removeValue(forKey: ObjectIdentifier(peer)) != nil else { return }
        peer.connection.cancel()
        if peer.authenticated { log("\(peer.name) disconnected") }
        if !connected {
            status = nil
            ringing = false
            transfers.peerLost()
        }
        onChange?()
    }

    private func receive(_ peer: Peer) {
        peer.connection.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { [weak self, weak peer] data, _, isComplete, error in
            guard let self, let peer else { return }
            if let data, !data.isEmpty {
                peer.buffer.append(data)
                self.drainFrames(peer)
            }
            if isComplete || error != nil { self.drop(peer); return }
            if self.peers[ObjectIdentifier(peer)] != nil { self.receive(peer) }
        }
    }

    private func drainFrames(_ peer: Peer) {
        var offset = peer.buffer.startIndex
        defer { if offset > peer.buffer.startIndex { peer.buffer.removeSubrange(peer.buffer.startIndex..<offset) } }
        while peer.buffer.endIndex - offset >= 4 {
            let length = peer.buffer[offset..<offset + 4].reduce(0) { ($0 << 8) | Int($1) }
            guard length > 0, length <= maxFrame else { log("bad frame size \(length)"); drop(peer); return }
            guard peer.buffer.endIndex - offset >= 4 + length else { return }
            let frame = Data(peer.buffer[offset + 4..<offset + 4 + length])
            offset += 4 + length
            guard handleFrame(peer, frame) else { return }
        }
    }

    /// Returns false when the peer was dropped.
    private func handleFrame(_ peer: Peer, _ frame: Data) -> Bool {
        if !peer.authenticated, frame.starts(with: pairMagic) {
            handlePairing(peer, frame)
            return peers[ObjectIdentifier(peer)] != nil
        }
        guard let key = Store.key, let (kind, payload) = unseal(frame, key: key) else {
            log("rejected a connection from \(peer.connection.endpoint): not paired with this Mac")
            drop(peer)
            return false
        }
        if !peer.authenticated {
            peer.authenticated = true
            for other in peers.values where other !== peer && other.authenticated { drop(other) } // a reconnect replaces the old link
            onChange?()
        }
        dispatch(peer, kind, payload)
        return true
    }

    private func dispatch(_ peer: Peer, _ raw: UInt8, _ payload: Data) {
        guard let kind = Kind(rawValue: raw) else { return } // newer phone app: ignore what we don't know
        switch kind {
        case .hello:
            peer.name = String(decoding: payload, as: UTF8.self)
            Store.phoneName = peer.name
            send(peer, .hello, Data(macName.utf8))
            log("\(peer.name) connected from \(peer.connection.endpoint)")
            onChange?()
        case .ping: send(peer, .pong, Data())
        case .text, .image: clipboard.apply(kind, payload)
        case .notifPost: notifications.post(payload)
        case .notifRemove: notifications.remove(String(decoding: payload, as: UTF8.self))
        case .fileOffer, .fileChunk, .fileEnd, .fileCancel: transfers.handle(kind, payload)
        case .link: openLink(String(decoding: payload, as: UTF8.self))
        case .status:
            if let info = parseJSON(payload) {
                status = PhoneStatus(battery: info["battery"] as? Int ?? -1, charging: info["charging"] as? Bool ?? false)
                if let name = info["name"] as? String, !name.isEmpty { peer.name = name; Store.phoneName = name }
                onChange?()
            }
        case .ringStop:
            ringing = false
            onChange?()
        case .mirrorReady: mirror.phoneReady(parseJSON(payload) ?? [:])
        case .code: receiveCode(payload)
        case .photo: photos.offered(payload)
        case .photoFull: photos.received(payload)
        default: break
        }
    }

    func sendToPhone(_ kind: Kind, _ payload: Data = Data(), completion: ((Bool) -> Void)? = nil) {
        guard let peer = phone else { completion?(false); return }
        send(peer, kind, payload, completion: completion)
    }

    private func send(_ peer: Peer, _ kind: Kind, _ payload: Data, completion: ((Bool) -> Void)? = nil) {
        guard let key = Store.key, let body = seal(kind, payload, key: key) else { completion?(false); return }
        peer.connection.send(content: framed(body), completion: .contentProcessed { error in completion?(error == nil) })
    }

    private func sendRaw(_ peer: Peer, _ body: Data) {
        peer.connection.send(content: framed(body), completion: .contentProcessed { _ in })
    }

    // MARK: pairing (numeric comparison, like Bluetooth)
    // phone → Mac: MDP1 01 | phone X25519 public key (32) | phone name
    // Mac → phone: MDP1 02 | Mac X25519 public key (32) | Mac name     (or MDP1 03 when not in pairing mode)
    // Both derive key = HKDF-SHA256(shared, salt: phonePub‖macPub, info: "macindroid key v1") and a 6-digit code from
    // info "macindroid code v1". The user checks the codes match and clicks Pair; the Mac then sends a sealed HELLO.

    private func handlePairing(_ peer: Peer, _ frame: Data) {
        let body = Data(frame.dropFirst(pairMagic.count))
        guard body.first == PairMessage.request.rawValue, body.count >= 33 else { drop(peer); return }
        guard pairingMode else {
            sendRaw(peer, pairMagic + Data([PairMessage.closed.rawValue]))
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self.drop(peer) }
            return
        }
        let phonePub = Data(body[1..<33])
        let name = String(decoding: body.dropFirst(33), as: UTF8.self)
        let mine = Curve25519.KeyAgreement.PrivateKey()
        guard let theirs = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: phonePub),
              let shared = try? mine.sharedSecretFromKeyAgreement(with: theirs) else { drop(peer); return }
        let macPub = mine.publicKey.rawRepresentation
        let salt = phonePub + macPub
        let key = shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: salt,
                                                 sharedInfo: Data("macindroid key v1".utf8), outputByteCount: 32)
        let codeBytes = shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: salt,
                                                       sharedInfo: Data("macindroid code v1".utf8), outputByteCount: 4)
        let number = codeBytes.withUnsafeBytes { $0.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) } } % 1_000_000
        peer.name = name.isEmpty ? "Phone" : name
        peer.pendingKey = key
        sendRaw(peer, pairMagic + Data([PairMessage.challenge.rawValue]) + macPub + Data(macName.utf8))
        log("pairing request from \(peer.name)")
        onPairRequest?(peer, String(format: "%06d", number))
    }

    func confirmPairing(_ peer: Peer) {
        guard let key = peer.pendingKey, peers[ObjectIdentifier(peer)] != nil else { return }
        Store.save(key, phone: peer.name)
        for other in peers.values where other !== peer { drop(other) }
        peer.pendingKey = nil
        peer.authenticated = true
        send(peer, .hello, Data(macName.utf8))
        pairingMode = false
        log("paired with \(peer.name)")
        onChange?()
    }

    func declinePairing(_ peer: Peer) {
        sendRaw(peer, pairMagic + Data([PairMessage.declined.rawValue]))
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self.drop(peer) }
    }

    /// A one-time code that arrived on the phone: onto the clipboard, with a quiet notice. Never logged.
    private func receiveCode(_ payload: Data) {
        guard Prefs.copyCodes, let info = parseJSON(payload), let code = info["code"] as? String,
              (4...8).contains(code.count), code.allSatisfy({ $0.isASCII && ($0.isNumber || $0.isUppercase) }) else { return }
        let app = info["app"] as? String ?? "an app"
        let from = (info["from"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? app
        clipboard.copyCode(code)
        notifications.postCode(code, from: from, key: info["key"] as? String ?? "")
        log("\(phoneName) -> Mac: one-time code from \(app)") // never the code itself
    }

    private func openLink(_ text: String) {
        guard let url = URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { return }
        log("\(phoneName) -> Mac: link")
        transfers.recordLink(url)
        if Prefs.openLinks {
            NSWorkspace.shared.open(url)
        } else {
            notifications.postLocal(id: "link:\(url.absoluteString)", title: "Link from \(phoneName)",
                                    body: url.host ?? url.absoluteString, info: ["kind": "link", "url": url.absoluteString])
        }
    }
}

// Shared services. Globals outside main.swift are created lazily on first use.
let link = Link()
let clipboard = ClipboardSync()
let notifications = PhoneNotifications()
let transfers = Transfers()
let mirror = Mirror()
let photos = PhotoPreviews()
