import AppKit
import UserNotifications

/// Shows the phone's notifications as native macOS notifications, and routes Reply, actions, clicks and
/// dismissals back to the phone. Also posts Macindroid's own notices (received files, links).
final class PhoneNotifications: NSObject, UNUserNotificationCenterDelegate {
    private let center = UNUserNotificationCenter.current()
    private var categories: [String: UNNotificationCategory] = [:]
    private let iconDir = FileManager.default.temporaryDirectory.appendingPathComponent("macindroid-icons", isDirectory: true)
    private var appIcons: [String: Data] = [:] // the phone sends each app's icon once per connection
    private var codeKeys: [String: Date] = [:] // phone notifications already shown as "Code … copied"
    private var codes: [String: String] = [:] // notification id -> code, so a click copies it again; kept 2 minutes

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
    }

    /// Payload: 4-byte JSON length | JSON | optional PNG icon.
    func post(_ payload: Data) {
        guard Prefs.phoneNotifications, payload.count >= 4 else { return }
        let length = payload.prefix(4).reduce(0) { ($0 << 8) | Int($1) }
        guard payload.count >= 4 + length, let info = parseJSON(Data(payload.dropFirst(4).prefix(length))),
              let key = info["key"] as? String else { return }
        if let shown = codeKeys[key], Date().timeIntervalSince(shown) < ClipboardSync.codeLifetime { return } // the code notice covers it
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

    func remove(_ key: String) {
        center.removeDeliveredNotifications(withIdentifiers: ["phone:" + key])
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
                switch action {
                case UNNotificationDefaultActionIdentifier:
                    // Like iPhone Mirroring: open the app on the phone and show the phone on the Mac.
                    link.sendToPhone(.notifOpen, Data(key.utf8))
                    mirror.start(quietly: true)
                case UNNotificationDismissActionIdentifier:
                    link.sendToPhone(.notifDismiss, Data(key.utf8))
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
