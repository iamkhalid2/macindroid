import AppKit
import IOKit
import IOKit.pwr_mgt

/// Closes the link before the Mac sleeps and opens it again when someone is at the Mac (see Link.sleep).
///
/// IOKit's power messages rather than NSWorkspace's, for two reasons: the Mac waits for our answer, so the phone is
/// told and the listener is gone before it sleeps; and they also come for the Mac's own short maintenance wakes
/// ("dark wakes", display off), during which the link stays closed.
final class SleepWatcher {
    private var port: io_connect_t = 0
    private var notifier: io_object_t = 0
    // IOMessage.h's constants are C macros Swift can't see.
    private static let canSleep: UInt32 = 0xe000_0270, willSleep: UInt32 = 0xe000_0280, hasPoweredOn: UInt32 = 0xe000_0300

    func start() {
        var notifyPort: IONotificationPortRef?
        port = IORegisterForSystemPower(Unmanaged.passUnretained(self).toOpaque(), &notifyPort, { context, _, type, argument in
            guard let context else { return }
            Unmanaged<SleepWatcher>.fromOpaque(context).takeUnretainedValue().message(type, Int(bitPattern: argument))
        }, &notifier)
        if port != 0, let notifyPort {
            CFRunLoopAddSource(CFRunLoopGetMain(), IONotificationPortGetRunLoopSource(notifyPort).takeUnretainedValue(), .commonModes)
        } else {
            log("couldn't watch for sleep; the link stays open while the Mac sleeps")
        }
        // The display coming on is the sign that someone is there, whichever kind of wake it was.
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.screensDidWakeNotification, object: nil, queue: .main) { _ in
            link.wake()
        }
    }

    private func message(_ type: UInt32, _ token: Int) {
        switch type {
        case Self.canSleep: IOAllowPowerChange(port, token)
        case Self.willSleep: link.sleep { IOAllowPowerChange(self.port, token) }
        case Self.hasPoweredOn:
            // A full wake, or a Mac with no display at all; not a dark wake. The display can come on a moment after
            // this message, so look again shortly in case its own notification doesn't come.
            for delay in [0.0, 3.0, 10.0] {
                DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                    if NSScreen.screens.isEmpty || CGDisplayIsAsleep(CGMainDisplayID()) == 0 { link.wake() }
                }
            }
        default: break
        }
    }
}

let sleepWatcher = SleepWatcher()
