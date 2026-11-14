import AppKit

/// Settings → Lock Mac When Phone Leaves. Off unless you switch it on.
///
/// "Leaves" means the phone drops off the link and stays off: you walked out of Wi-Fi range with it, or switched it
/// off. It is only ever armed by the phone being here (connected since the screen was last unlocked), so a Mac used
/// without the phone is left alone, and the Mac going to sleep or a phone that blinks out for a few seconds doesn't
/// count. While it's on and the screen is unlocked the Mac asks the phone "still there?" every 20 seconds, because
/// a phone that walks away can't say goodbye; that costs the phone a little battery.
final class AwayLock {
    private var armed = false
    private var asking: Timer?
    private var pendingLock: DispatchWorkItem?
    private var screenLocked = false
    // test_client.py runs it faster (see Prefs.debug).
    private var interval: TimeInterval { Prefs.debug ? 3 : 20 }
    private var grace: TimeInterval { Prefs.debug ? 4 : 25 }

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
            stop()
            return
        }
        if link.connected {
            armed = true
            pendingLock?.cancel()
            pendingLock = nil
            if asking == nil {
                let timer = Timer(timeInterval: interval, repeats: true) { _ in link.probe() }
                timer.tolerance = interval / 10
                RunLoop.main.add(timer, forMode: .common)
                asking = timer
            }
        } else {
            asking?.invalidate()
            asking = nil
            guard armed, pendingLock == nil else { return }
            let lock = DispatchWorkItem { [weak self] in
                guard let self else { return }
                self.pendingLock = nil
                guard Self.enabled, self.armed, !link.connected, !link.asleep, !self.screenLocked else { return }
                self.armed = false
                log("\(link.phoneName) has been gone \(Int(self.grace)) s: locking the Mac")
                Self.lockScreen()
            }
            pendingLock = lock
            DispatchQueue.main.asyncAfter(deadline: .now() + grace, execute: lock)
        }
    }

    private func stop() {
        asking?.invalidate()
        asking = nil
        pendingLock?.cancel()
        pendingLock = nil
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
