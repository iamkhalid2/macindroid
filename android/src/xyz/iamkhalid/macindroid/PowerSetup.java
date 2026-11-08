package xyz.iamkhalid.macindroid;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Power features: copies on the phone reaching the Mac by themselves, and mirroring over Wi-Fi. They need two
 * permissions only a computer can grant. The Mac does it (Settings → Set Up Power Features…) after pairing with
 * the phone through Android's Wireless debugging QR code. This asks the Mac to show that code and opens the right
 * Settings screen on the phone.
 */
final class PowerSetup {
    private PowerSetup() {}

    static boolean done(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    static void start(Activity a) {
        LinkService s = LinkService.instance;
        boolean asked = s != null && LinkService.connected();
        if (asked) s.sendAsync(Proto.POWER_SETUP, new byte[0]);
        boolean developer = Settings.Global.getInt(a.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        String mac = Config.macName(a);
        String where = asked ? mac + " is showing a QR code now.\n\n"
                : "On " + mac + ", click Macindroid in the menu bar and choose Settings → Set Up Power Features…\n\n";
        String steps = developer
                ? "Here, open Developer options → Wireless debugging. Turn it on, tap Pair device with QR code and scan the code on your Mac."
                : "1. Turn on Developer options: in About phone, tap Build number seven times (on some phones it's under "
                        + "Version or Software information).\n\n2. Then open Developer options → Wireless debugging, turn it on, "
                        + "tap Pair device with QR code and scan the code on your Mac.";
        new AlertDialog.Builder(a)
                .setTitle("Turn on power features")
                .setMessage(where + steps + "\n\nOne time, no cable. Your Mac switches Wireless debugging off again afterwards.")
                .setPositiveButton(developer ? "Open Developer options" : "Open About phone", (d, w) -> {
                    try {
                        a.startActivity(new Intent(developer ? Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                                : Settings.ACTION_DEVICE_INFO_SETTINGS));
                    } catch (ActivityNotFoundException e) {
                        a.startActivity(new Intent(Settings.ACTION_SETTINGS));
                    }
                })
                .setNegativeButton("Not now", null)
                .show();
        if (!asked) Toast.makeText(a, mac + " isn't connected, so open the setup there yourself", Toast.LENGTH_LONG).show();
    }
}
