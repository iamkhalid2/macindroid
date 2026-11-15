import AppKit

/// Settings → Lock Mac When Phone Leaves. Off unless you switch it on.
///
/// "Leaves" means the phone is no longer on the network: you walked out of Wi-Fi range with it, or switched it off.
/// The test is whether the phone's address still answers a ping, not whether the app's link is up, because a phone
/// asleep on the desk lets the link go quiet (Android holds a sleeping app's traffic back, and the app can take
/// many minutes to reconnect) while its Wi-Fi chip still answers a ping at once. So the Mac locks only when the
/// link is down and the address has been silent for about 40 seconds.
///
/// It is only ever armed by the phone being here (connected since the screen was last unlocked), so a Mac used
/// without the phone is left alone, and the Mac going to sleep doesn't count. The ping is one small packet every
/// 10 seconds while the screen is unlocked; it doesn't wake the phone's apps.
final class AwayLock {
    private var armed = false
    private var watching: Timer?
    private var misses = 0
    private var pinging = false
    private var screenLocked = false
    private let queue = DispatchQueue(label: "macindroid.ping", qos: .utility)
    // test_client.py runs it faster and names the address to ping (see Prefs.debug).
    private var interval: TimeInterval { Prefs.debug ? 2 : 10 }
    private var allowed: Int { Prefs.debug ? 2 : 4 } // silent pings in a row before it counts as gone
    private var address: String? {
        (Prefs.debug ? UserDefaults.standard.string(forKey: "debugPingHost") : nil) ?? UserDefaults.standard.string(forKey: "phoneHost")
    }

    static var enabled: Bool { UserDefaults.standard.bool(forKey: "lockWhenAway") }

    func start() {
        let center = DistributedNotificationCenter.default()
        center.addObserver(forName: Notification.Name("com.apple.screenIsLocked"), object: nil, queue: .main) { [weak self] _ in
            self?.screenLocked = true
            self?.update()
        }
        center.addObserver(forName: Notification.Name("com.apple.screenIsUnlocked"), object: nil, queue: .main) { [weak self] _ in
            self?.screenLocked = false
            self?.armed = false // armed again only by the phone being here now
            self?.update()
        }
        update()
    }

    /// Call when the link, the setting or the screen changes.
    func update() {
        guard Self.enabled, !screenLocked, !link.asleep else {
            if !Self.enabled || link.asleep { armed = false }
            watching?.invalidate()
            watching = nil
            misses = 0
            return
        }
        if link.connected {
            armed = true
            misses = 0
        }
        guard armed, watching == nil else { return }
        let timer = Timer(timeInterval: interval, repeats: true) { [weak self] _ in self?.check() }
        timer.tolerance = interval / 10
        RunLoop.main.add(timer, forMode: .common)
        watching = timer
    }

    private func check() {
        guard let address, !pinging else { return }
        pinging = true
        queue.async {
            let here = Tools.run("/sbin/ping", ["-c", "1", "-t", "2", "-q", address], timeout: 5).status == 0
            DispatchQueue.main.async { self.heard(here) }
        }
    }

    private func heard(_ here: Bool) {
        pinging = false
        guard Self.enabled, armed, !screenLocked, !link.asleep else { return }
        if here { misses = 0; return }
        misses += 1
        if link.connected {
            // The link says connected but the address is silent: the phone may have gone without closing it, or this
            // network may not pass pings at all. Ask over the link; if the phone answers, the link is the truth.
            if misses >= 2 { link.probe() }
            return
        }
        guard misses >= allowed else { return }
        armed = false
        misses = 0
        watching?.invalidate()
        watching = nil
        log("\(link.phoneName) is off the link and its address hasn't answered for \(Int(Double(allowed) * interval)) s: locking the Mac")
        Self.lockScreen()
    }

    /// The same lock as Control-Command-Q. macOS has no public call for it; this one (in login.framework) is what
    /// the menu bar's own Lock Screen uses. If it ever goes, the display is put to sleep instead, which locks when
    /// "Require password after display is off" is set to immediately.
    private static func lockScreen() {
        if Prefs.debug { log("debug: would lock the Mac now"); return } // test_client.py must not lock your screen
        typealias Lock = @convention(c) () -> Int32
        if let login = dlopen("/System/Library/PrivateFrameworks/login.framework/Versions/Current/login", RTLD_LAZY),
           let symbol = dlsym(login, "SACLockScreenImmediate") {
            _ = unsafeBitCast(symbol, to: Lock.self)()
        } else {
            _ = Tools.run("/usr/bin/pmset", ["displaysleepnow"], timeout: 10)
        }
    }
}

let awayLock = AwayLock()
