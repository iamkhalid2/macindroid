package xyz.iamkhalid.macindroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Pairing key (private file), the Mac's last address and name, and the user's settings. */
final class Config {
    private Config() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("macindroid", Context.MODE_PRIVATE);
    }

    static SecretKey key(Context c) {
        String b64 = read(c, "key");
        if (b64 == null) return null;
        try {
            byte[] raw = Base64.decode(b64.trim(), Base64.DEFAULT);
            return raw.length == 32 ? new SecretKeySpec(raw, "AES") : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static boolean paired(Context c) {
        return key(c) != null;
    }

    static void savePairing(Context c, byte[] key, String host, String macName) {
        write(c, "key", Base64.encodeToString(key, Base64.NO_WRAP));
        write(c, "host", host);
        prefs(c).edit().putString("macName", macName).apply();
    }

    static void unpair(Context c) {
        new File(c.getFilesDir(), "key").delete();
        new File(c.getFilesDir(), "host").delete();
        prefs(c).edit().remove("macName").apply();
    }

    static String host(Context c) {
        String h = read(c, "host");
        return h == null ? null : h.trim();
    }

    static void setHost(Context c, String host) {
        write(c, "host", host);
    }

    static String macName(Context c) {
        return prefs(c).getString("macName", "your Mac");
    }

    static void setMacName(Context c, String name) {
        prefs(c).edit().putString("macName", name).apply();
    }

    static boolean onboarded(Context c) {
        return prefs(c).getBoolean("onboarded", false);
    }

    static void setOnboarded(Context c) {
        prefs(c).edit().putBoolean("onboarded", true).apply();
    }

    static boolean mirrorNotifications(Context c) {
        return prefs(c).getBoolean("mirrorNotifications", true);
    }

    static void setMirrorNotifications(Context c, boolean on) {
        prefs(c).edit().putBoolean("mirrorNotifications", on).apply();
    }

    /** True while Wireless debugging is on only because the Mac asked for it (so it is switched off afterwards). */
    static boolean wifiAdbByUs(Context c) {
        return prefs(c).getBoolean("wifiAdbByUs", false);
    }

    static void setWifiAdbByUs(Context c, boolean on) {
        prefs(c).edit().putBoolean("wifiAdbByUs", on).apply();
    }

    private static String read(Context c, String name) {
        File f = new File(c.getFilesDir(), name);
        if (!f.isFile()) return null;
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(Context c, String name, String value) {
        try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), name))) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
