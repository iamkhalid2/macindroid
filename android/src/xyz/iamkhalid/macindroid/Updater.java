package xyz.iamkhalid.macindroid;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Updates come from the Mac, never from the internet: the Mac app carries the matching phone app and offers it when
 * this one is older (UPDATE_OFFER). Tapping Update asks for it (UPDATE_GET); it arrives over the encrypted link in
 * one frame (UPDATE_APK) and goes to Android's own installer, which asks you to confirm and only accepts an update
 * signed with the same key as the installed app. The first time, Android also asks you to let Macindroid install apps.
 */
final class Updater {
    private Updater() {}

    static final int NOTIFICATION_ID = 41;
    static final String EXTRA_UPDATE = "update";

    /** The Mac's offer: {version, code, size}. Null when it has nothing newer. */
    static volatile JSONObject offer;
    /** What the update is doing, for the Home screen: null when idle. */
    static volatile String progress;
    private static volatile boolean requested;
    /** Sent to Settings to allow installing: carry on when back, if allowed. */
    private static volatile boolean awaitingPermission;

    static long installed(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    static boolean available(Context c) {
        JSONObject o = offer;
        return o != null && o.optLong("code") > installed(c);
    }

    static String version() {
        JSONObject o = offer;
        return o == null ? "" : o.optString("version", "");
    }

    /** UPDATE_OFFER from the Mac. Notifies once per offered version, or again when the Mac's menu asks for it. */
    static void onOffer(LinkService s, byte[] payload) {
        try {
            JSONObject o = new JSONObject(Proto.utf8(payload));
            if (o.optLong("code") <= installed(s)) return;
            JSONObject was = offer;
            offer = o;
            LinkService.historyVersion++; // the Home screen redraws
            boolean again = was == null || was.optLong("code") != o.optLong("code") || o.optBoolean("show");
            if (again) s.notify(NOTIFICATION_ID, notification(s, o.optString("version")));
        } catch (Exception e) {
            Log.w("Macindroid", "bad update offer", e);
        }
    }

    private static Notification notification(Context c, String version) {
        Intent open = new Intent(c, HomeActivity.class).putExtra(EXTRA_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(c, LinkService.CH_ALERTS)
                .setSmallIcon(R.drawable.ic_glyph)
                .setContentTitle("Macindroid " + version + " is ready")
                .setContentText("From " + Config.macName(c) + ". Tap to update.")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
    }

    /** The Update button. */
    static void start(Activity a) {
        if (!available(a)) return;
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            awaitingPermission = true;
            Toast.makeText(a, "Allow Macindroid to install its updates, then come back", Toast.LENGTH_LONG).show();
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            return;
        }
        LinkService s = LinkService.instance;
        if (s == null || !LinkService.connected()) {
            Toast.makeText(a, Config.macName(a) + " isn't connected", Toast.LENGTH_SHORT).show();
            return;
        }
        requested = true;
        progress = "Getting it from " + Config.macName(a);
        LinkService.historyVersion++;
        s.sendAsync(Proto.UPDATE_GET, new byte[0], ok -> {
            if (!ok) fail(s, "The Mac didn't get the request");
        });
    }

    /** The Home screen is back in front: continue an update that was waiting for the install permission. */
    static void resume(Activity a) {
        if (!awaitingPermission) return;
        awaitingPermission = false;
        if (a.getPackageManager().canRequestPackageInstalls()) start(a);
    }

    /** UPDATE_APK from the Mac: hand it to Android's installer. Off the main thread. */
    static void onApk(LinkService s, byte[] apk) {
        if (!requested) return; // only what we asked for
        requested = false;
        progress = "Installing";
        LinkService.historyVersion++;
        PackageInstaller installer = s.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(s.getPackageName());
        params.setSize(apk.length);
        try {
            int id = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(id)) {
                try (OutputStream out = session.openWrite("base.apk", 0, apk.length)) {
                    out.write(apk);
                    session.fsync(out);
                }
                Intent done = new Intent(s, UpdateReceiver.class);
                PendingIntent result = PendingIntent.getBroadcast(s, id, done, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                session.commit(result.getIntentSender());
            }
            s.getSystemService(android.app.NotificationManager.class).cancel(NOTIFICATION_ID);
        } catch (Exception e) {
            Log.w("Macindroid", "update install failed", e);
            fail(s, "Android wouldn't start the install");
        }
    }

    static void fail(Context c, String why) {
        progress = null;
        LinkService.historyVersion++;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> Toast.makeText(c, "Update: " + why, Toast.LENGTH_LONG).show());
    }

    static void finished() {
        progress = null;
        LinkService.historyVersion++;
    }
}
