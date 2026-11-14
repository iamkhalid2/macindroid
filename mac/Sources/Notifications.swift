import AppKit
import UserNotifications

/// Shows the phone's notifications as native macOS notifications, and routes Reply, actions, clicks and
/// dismissals back to the phone. Also posts Macindroid's own notices (received files, links).
///
/// The two lists are kept the same. Cleared on the phone: the phone says so (NOTIF_REMOVE) and it leaves the Mac.
/// Cleared on the Mac: macOS reports the close button, and for everything it doesn't report (Clear All, a clear
/// while the phone was away) the Mac notices the notification is gone from Notification Centre and tells the phone
/// (NOTIF_DISMISS). After every reconnect the phone lists what it still has (NOTIF_SYNC) and whatever was cleared
/// on either side while they were apart is cleared on the other.
final class PhoneNotifications: NSObject, UNUserNotificationCenterDelegate {
    private let center = UNUserNotificationCenter.current()
    private var categories: [String: UNNotificationCategory] = [:]
    private let iconDir = FileManager.default.temporaryDirectory.appendingPathComponent("macindroid-icons", isDirectory: true)
    private var appIcons: [String: Data] = [:] // the phone sends each app's icon once per connection
    private var codeKeys: [String: Date] = [:] // phone notifications already shown as "Code … copied"
    private var codes: [String: String] = [:] // notification id -> code, so a click copies it again; kept 2 minutes
    private var shown: [String: Date] = [:]       // phone notifications we put up and haven't taken down: key -> when
    private var confirmed: Set<String> = []       // of those, the ones seen in Notification Centre at least once
    private var pendingDismiss: Set<String> = []  // cleared on the Mac while the phone was away; told at the next sync
    private var watcher: Timer?

    func setup() {
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound]) { granted, error in
            if !granted { log("notifications not allowed: \(error?.localizedDescription ?? "denied in System Settings")") }
            self.center.getNotificationSettings { s in
                let style = [UNAlertStyle.none: "none", .banner: "banners", .alert: "alerts"][s.alertStyle] ?? "?"
                log("notification settings: authorization \(s.authorizationStatus.rawValue), style \(style), "
                    + "sound \(s.soundSetting == .enabled), in Notification Centre \(s.notificationCenterSetting == .enabled)")
            }
        }
        try? FileManager.default.createDirectory(at: iconDir, withIntermediateDirectories: true)
        // Phone notifications still in Notification Centre from before this launch are watched too.
        center.getDeliveredNotifications { delivered in
            let keys = delivered.compactMap(Self.phoneKey)
            DispatchQueue.main.async {
                for key in keys where self.shown[key] == nil {
                    self.shown[key] = .distantPast
                    self.confirmed.insert(key)
                }
                self.watch()
            }
        }
    }

    private static func phoneKey(_ notification: UNNotification) -> String? {
        let id = notification.request.identifier
        return id.hasPrefix("phone:") ? String(id.dropFirst(6)) : nil
    }

    /// Payload: 4-byte JSON length | JSON | optional PNG icon.
    func post(_ payload: Data) {
        guard Prefs.phoneNotifications, payload.count >= 4 else { return }
        let length = payload.prefix(4).reduce(0) { ($0 << 8) | Int($1) }
        guard payload.count >= 4 + length, let info = parseJSON(Data(payload.dropFirst(4).prefix(length))),
              let key = info["key"] as? String else { return }
        if let shown = codeKeys[key], Date().timeIntervalSince(shown) < ClipboardSync.codeLifetime { return } // the code notice covers it
        if info["silent"] as? Bool == true, pendingDismiss.contains(key) { return } // cleared here while apart; the sync clears it there
        let pkg = info["pkg"] as? String ?? ""
        let app = info["app"] as? String ?? "Phone"
        let title = info["title"] as? String ?? ""
        let canReply = info["reply"] as? Bool ?? false
        let actions = info["actions"] as? [String] ?? []
        let icon = Data(payload.dropFirst(4 + length))
        if !icon.isEmpty, info["iconIsApp"] as? Bool == true { appIcons[pkg] = icon }

        let content = UNMutableNotificationContent()
        content.title = title.isEmpty ? app : title
        content.subtitle = title.isEmpty ? "" : app
        content.body = info["text"] as? String ?? ""
        content.threadIdentifier = pkg
        if info["silent"] as? Bool == true {
            content.interruptionLevel = .passive // catching up after a reconnect: list it, don't pop it
        } else {
            content.sound = .default
        }
        content.userInfo = ["kind": "phone", "key": key]
        content.categoryIdentifier = category(reply: canReply, actions: actions)
        if let image = icon.isEmpty ? appIcons[pkg] : icon {
            let file = iconDir.appendingPathComponent(UUID().uuidString + ".png")
            if (try? image.write(to: file)) != nil,
               let attachment = try? UNNotificationAttachment(identifier: "icon", url: file, options: nil) {
                content.attachments = [attachment]
            }
        }
        shown[key] = Date()
        watch()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in self?.reconcile() } // note that it's up
        center.add(UNNotificationRequest(identifier: "phone:" + key, content: content, trigger: nil)) { error in
            log("phone notification from \(pkg)" + (error.map { ": not shown (\($0.localizedDescription))" } ?? ""))
        }
    }

    /// "Code 123456 from Google copied": a banner without sound, gone when the code leaves the clipboard. Clicking it
    /// copies the code again.
    func postCode(_ code: String, from: String, key: String) {
        let id = "code:" + (key.isEmpty ? UUID().uuidString : key)
        codeKeys = codeKeys.filter { Date().timeIntervalSince($0.value) < ClipboardSync.codeLifetime }
        if !key.isEmpty { codeKeys[key] = Date() }
        codes[id] = code
        let content = UNMutableNotificationContent()
        content.title = "Code \(code) from \(from) copied"
        content.body = "Paste it within 2 minutes. Click to copy it again."
        content.threadIdentifier = "codes"
        content.userInfo = ["kind": "code"] // the code stays in memory, not in Notification Centre's records
        center.removeDeliveredNotifications(withIdentifiers: [id])
        center.add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
        DispatchQueue.main.asyncAfter(wallDeadline: .now() + ClipboardSync.codeLifetime) { [weak self] in
            guard let self, self.codes[id] == code else { return } // replaced by a newer code
            self.codes[id] = nil
            self.center.removeDeliveredNotifications(withIdentifiers: [id])
        }
    }

    /// Cleared on the phone: clear it here.
    func remove(_ key: String) {
        forget(key)
        center.removeDeliveredNotifications(withIdentifiers: ["phone:" + key])
    }

    private func forget(_ key: String) {
        shown[key] = nil
        confirmed.remove(key)
    }

    // MARK: keeping the two lists the same

    /// Cleared on the Mac: clear it on the phone, now or (if the phone is away) at the next sync.
    private func dismissOnPhone(_ key: String) {
        forget(key)
        guard link.connected else { pendingDismiss.insert(key); return }
        link.sendToPhone(.notifDismiss, Data(key.utf8)) { [weak self] sent in
            if !sent { DispatchQueue.main.async { self?.pendingDismiss.insert(key) } }
        }
    }

    /// Looks every few seconds, only while phone notifications are up. (Also while the phone is away, so a clear
    /// then is remembered for the next sync.)
    private func watch() {
        guard watcher == nil, !shown.isEmpty else { return }
        let timer = Timer(timeInterval: 4, repeats: true) { [weak self] _ in
            guard let self else { return }
            if self.shown.isEmpty { self.watcher?.invalidate(); self.watcher = nil; return }
            self.reconcile()
        }
        timer.tolerance = 2
        RunLoop.main.add(timer, forMode: .common)
        watcher = timer
    }

    /// A notification we put up, that was seen in Notification Centre and now isn't, was cleared by you on the Mac
    /// in a way macOS doesn't report (Clear All, or the app wasn't told). One never seen there proves nothing
    /// (Notification Centre may be switched off for Macindroid), so it's left alone.
    private func reconcile(then done: (() -> Void)? = nil) {
        center.getDeliveredNotifications { delivered in
            let present = Set(delivered.compactMap(Self.phoneKey))
            DispatchQueue.main.async {
                var cleared = 0
                for (key, at) in self.shown {
                    if present.contains(key) {
                        self.confirmed.insert(key)
                    } else if self.confirmed.contains(key), Date().timeIntervalSince(at) > 3 { // not one just being replaced
                        self.dismissOnPhone(key)
                        cleared += 1
                    }
                }
                if cleared > 0 { log("\(cleared) phone notification\(cleared == 1 ? "" : "s") cleared on the Mac: clearing on the phone") }
                done?()
            }
        }
    }

    /// NOTIF_SYNC, after every reconnect: the keys of the notifications the phone still has. Anything else we're
    /// showing was cleared on the phone while apart; anything cleared here while apart is cleared there now.
    func sync(_ payload: Data) {
        guard let keys = (try? JSONSerialization.jsonObject(with: payload)) as? [String] else { return }
        let active = Set(keys)
        reconcile { [self] in
            let stale = shown.keys.filter { !active.contains($0) }
            stale.forEach(remove)
            let waiting = pendingDismiss.intersection(active)
            pendingDismiss.removeAll()
            waiting.forEach(dismissOnPhone)
            if !stale.isEmpty || !waiting.isEmpty {
                log("notification sync: \(stale.count) cleared on the phone while apart, \(waiting.count) cleared on the Mac")
            }
        }
    }

    /// For test_client.py (see Prefs.debug): takes a notification out of Notification Centre the way a clear macOS
    /// doesn't report would, leaving it to the watcher to notice.
    func debugClear(_ key: String) {
        center.removeDeliveredNotifications(withIdentifiers: ["phone:" + key])
    }

    /// For test_client.py: writes what macOS says is in Notification Centre to the log.
    func debugList(_ tag: String) {
        center.getDeliveredNotifications { delivered in
            let keys = String(decoding: json(delivered.compactMap(Self.phoneKey).sorted()), as: UTF8.self)
            log("debug \(tag): phone notifications up: \(keys)")
        }
    }

    func connectionChanged() {
        appIcons.removeAll() // the phone re-sends icons on each new connection
    }

    func postLocal(id: String, title: String, body: String, info: [String: String]) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.userInfo = info
        center.add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
    }

    /// One category per distinct set of buttons; macOS needs them registered before posting.
    private func category(reply: Bool, actions: [String]) -> String {
        let id = "phone|" + (reply ? "r|" : "") + actions.joined(separator: "|")
        if categories[id] == nil {
            var buttons: [UNNotificationAction] = []
            if reply {
                buttons.append(UNTextInputNotificationAction(identifier: "reply", title: "Reply", options: [],
                                                             textInputButtonTitle: "Send", textInputPlaceholder: "Message"))
            }
            for (index, title) in actions.prefix(2).enumerated() {
                buttons.append(UNNotificationAction(identifier: "action.\(index)", title: title, options: []))
            }
            categories[id] = UNNotificationCategory(identifier: id, actions: buttons, intentIdentifiers: [],
                                                    options: [.customDismissAction])
            center.setNotificationCategories(Set(categories.values))
        }
        return id
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        let action = response.actionIdentifier
        DispatchQueue.main.async {
            switch info["kind"] as? String {
            case "phone":
                guard let key = info["key"] as? String else { break }
                if action != UNNotificationDismissActionIdentifier { self.forget(key) } // acted on: macOS takes it down itself
                switch action {
                case UNNotificationDefaultActionIdentifier:
                    // Like iPhone Mirroring: open the app on the phone and show the phone on the Mac.
                    link.sendToPhone(.notifOpen, Data(key.utf8))
                    mirror.start(quietly: true)
                case UNNotificationDismissActionIdentifier:
                    self.dismissOnPhone(key)
                case "reply":
                    if let text = (response as? UNTextInputNotificationResponse)?.userText, !text.isEmpty {
                        link.sendToPhone(.notifReply, json(["key": key, "text": text]))
                    }
                default:
                    if action.hasPrefix("action."), let index = Int(action.dropFirst(7)) {
                        link.sendToPhone(.notifAction, json(["key": key, "index": index]))
                    }
                }
            case "code":
                if action == UNNotificationDefaultActionIdentifier, let code = self.codes[response.notification.request.identifier] {
                    clipboard.copyCode(code)
                }
            case "file":
                if action == UNNotificationDefaultActionIdentifier, let path = info["path"] as? String {
                    NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
                }
            case "link":
                if action == UNNotificationDefaultActionIdentifier, let text = info["url"] as? String, let url = URL(string: text) {
                    NSWorkspace.shared.open(url)
                }
            default: break
            }
            completionHandler()
        }
    }
}
