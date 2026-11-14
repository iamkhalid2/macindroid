package xyz.iamkhalid.macindroid;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.net.Uri;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;
import android.util.Log;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Calls on the Mac. A ringing call is found the way the Mac's other notifications are, through notification access:
 * every calling app (the phone's own dialer, WhatsApp, Signal) posts one in the "call" category, and the modern
 * ones (Notification.CallStyle) carry their own Answer and Decline intents, which is what the Mac's buttons press.
 * A dialer that doesn't is answered through Android's telecom service, which needs the Phone permission. The call
 * itself stays on this phone: Android gives another device no way to carry its sound.
 *
 * Dialling from the Mac places the call with the Phone permission, or without it leaves a notification that opens
 * the dialer with the number filled in. Who is calling, and the number dialled, are never logged.
 */
final class Calls {
    private Calls() {}

    private static final Set<String> ringing = new HashSet<>(); // notification keys; main thread

    static void reset() {
        ringing.clear();
    }

    /** True when this is a call's notification, which then isn't shown on the Mac as an ordinary one. Main thread. */
    static boolean onPosted(NotifListener l, LinkService s, StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (!Notification.CATEGORY_CALL.equals(n.category) || !Config.on(l, Config.CALLS)) return false;
        if (sbn.getPackageName().equals(l.getPackageName())) return false;
        int type = n.extras.getInt(Notification.EXTRA_CALL_TYPE, Notification.CallStyle.CALL_TYPE_UNKNOWN);
        boolean incoming = type == Notification.CallStyle.CALL_TYPE_INCOMING
                || (type == Notification.CallStyle.CALL_TYPE_UNKNOWN // not CallStyle: it's ringing if the phone is
                        && (n.fullScreenIntent != null || l.getSystemService(AudioManager.class).getMode() == AudioManager.MODE_RINGTONE));
        String key = sbn.getKey();
        if (!incoming) { // answered, or a call you made
            if (ringing.remove(key)) send(s, key, "ended", null, null);
            return true;
        }
        if (!ringing.add(key)) return true; // an update of the one already on the Mac
        String name = text(n.extras.getCharSequence(Notification.EXTRA_TITLE));
        Person person = n.extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person.class);
        if (name.isEmpty() && person != null && person.getName() != null) name = person.getName().toString();
        try {
            JSONObject o = new JSONObject().put("id", key).put("state", "ringing").put("name", name)
                    .put("app", l.appLabel(sbn.getPackageName()))
                    .put("answer", intent(n, true) != null || viaTelecom(l, sbn))
                    .put("decline", intent(n, false) != null || viaTelecom(l, sbn));
            Icon large = n.getLargeIcon();
            send(s, key, "ringing", o, large == null ? null : NotifListener.png(large.loadDrawable(l)));
            Log.i("Macindroid", "a call is ringing in " + sbn.getPackageName()); // never who
        } catch (Exception ignored) {
        }
        return true;
    }

    static void onRemoved(LinkService s, StatusBarNotification sbn) {
        if (ringing.remove(sbn.getKey()) && s != null) send(s, sbn.getKey(), "ended", null, null);
    }

    private static void send(LinkService s, String key, String state, JSONObject full, byte[] photo) {
        try {
            byte[] json = Proto.utf8((full != null ? full : new JSONObject().put("id", key).put("state", state)).toString());
            byte[] length = {(byte) (json.length >>> 24), (byte) (json.length >>> 16), (byte) (json.length >>> 8), (byte) json.length};
            s.sendAsync(Proto.CALL, Proto.concat(length, json, photo == null ? new byte[0] : photo));
        } catch (Exception ignored) {
        }
    }

    /** CALL_ACTION from the Mac: JSON {id, action: "answer" | "decline"}. Main thread. */
    static void act(NotifListener l, byte[] payload) {
        try {
            JSONObject o = new JSONObject(Proto.utf8(payload));
            boolean answer = "answer".equals(o.getString("action"));
            StatusBarNotification[] found = l.getActiveNotifications(new String[] {o.getString("id")});
            if (found == null || found.length == 0) return; // it stopped ringing meanwhile
            PendingIntent own = intent(found[0].getNotification(), answer);
            if (own != null) {
                own.send(l, 0, null, null, null, null, NotifListener.allowLaunch());
            } else if (viaTelecom(l, found[0])) {
                TelecomManager telecom = l.getSystemService(TelecomManager.class);
                if (answer) telecom.acceptRingingCall();
                else telecom.endCall();
            }
            Log.i("Macindroid", (answer ? "answered" : "declined") + " a call from the Mac");
        } catch (Exception e) {
            Log.w("Macindroid", "couldn't act on the call", e);
        }
    }

    private static PendingIntent intent(Notification n, boolean answer) {
        return n.extras.getParcelable(answer ? Notification.EXTRA_ANSWER_INTENT : Notification.EXTRA_DECLINE_INTENT, PendingIntent.class);
    }

    /**
     * Only for the phone's own dialer: the telecom service acts on the phone line, so using it for another app's
     * call could hang up a different one.
     */
    @SuppressWarnings("deprecation") // acceptRingingCall and endCall: still the only way without being the dialer
    private static boolean viaTelecom(Context c, StatusBarNotification sbn) {
        if (c.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) return false;
        String pkg = sbn.getPackageName();
        return pkg.equals(c.getSystemService(TelecomManager.class).getDefaultDialerPackage())
                || pkg.endsWith(".incallui") || pkg.equals("com.android.server.telecom");
    }

    /** DIAL from the Mac (you clicked a number there and confirmed): the number, as text. Answers how it went. */
    static void dial(LinkService s, byte[] payload) {
        String number = Proto.utf8(payload).replaceAll("[^0-9+*#,;]", "");
        String how = "off";
        if (number.isEmpty() || number.length() > 40) {
            how = "invalid";
        } else if (Config.on(s, Config.CALLS)) {
            Uri tel = Uri.fromParts("tel", number, null);
            if (s.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    s.getSystemService(TelecomManager.class).placeCall(tel, null);
                    how = "call";
                } catch (RuntimeException e) {
                    how = "failed";
                }
            } else { // no Phone permission: the dialer, filled in, one tap away
                PendingIntent open = PendingIntent.getActivity(s, 0, new Intent(Intent.ACTION_DIAL, tel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                s.notify(5, new Notification.Builder(s, LinkService.CH_ALERTS).setSmallIcon(R.drawable.ic_glyph)
                        .setContentTitle("Call " + number).setContentText("From " + Config.macName(s) + ". Tap to dial.")
                        .setContentIntent(open).setAutoCancel(true).build());
                how = "notification";
            }
        }
        Log.i("Macindroid", "the Mac asked to dial: " + how); // never the number
        try {
            s.sendAsync(Proto.DIAL, Proto.utf8(new JSONObject().put("how", how).toString()));
        } catch (Exception ignored) {
        }
    }

    private static String text(CharSequence s) {
        return s == null ? "" : s.toString().trim();
    }
}
