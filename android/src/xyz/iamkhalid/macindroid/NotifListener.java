package xyz.iamkhalid.macindroid;

import android.app.ActivityOptions;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Shows the phone's notifications on the Mac and carries Reply, actions, dismissals and clicks back.
 * Quiet by design: skips ongoing, silent, media and summary notifications, anything an app marked
 * FLAG_LOCAL_ONLY ("don't bridge to other devices"), and everything while you are using the phone.
 */
public class NotifListener extends NotificationListenerService {
    static volatile NotifListener instance;
    private final Map<String, Integer> forwarded = new HashMap<>(); // key -> content hash; main thread
    private final Set<String> iconsSent = new HashSet<>();

    @Override
    public void onListenerConnected() {
        instance = this;
        // Android 15+ hides one-time codes from listeners unless setup-phone.sh allowed this app op. Android reads
        // it when the listener connects, so this line says whether codes can reach the Mac in this session.
        try {
            int mode = getSystemService(AppOpsManager.class).unsafeCheckOpNoThrow(
                    "android:receive_sensitive_notifications", android.os.Process.myUid(), getPackageName());
            Log.i("Macindroid", mode == AppOpsManager.MODE_ALLOWED ? "one-time codes: visible"
                    : "one-time codes: hidden by Android; run setup-phone.sh to copy them to the Mac");
        } catch (RuntimeException ignored) { // an Android without the op
        }
    }

    @Override
    public void onListenerDisconnected() {
        dropPendingOpen();
        instance = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankings) {
        LinkService service = LinkService.instance;
        if (service == null || !LinkService.connected()) return;
        // Codes go even while the phone is in use: you are often signing in on the Mac with the phone in your hand.
        sendCode(service, sbn);
        if (!Config.mirrorNotifications(this)) return;
        if (service.phoneInUse()) return;
        if (!worthForwarding(sbn, rankings)) {
            Log.d("Macindroid", "not forwarding a notification from " + sbn.getPackageName());
            return;
        }
        forward(service, sbn, false);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (forwarded.remove(sbn.getKey()) == null) return;
        LinkService service = LinkService.instance;
        if (service != null) service.sendAsync(Proto.NOTIF_REMOVE, Proto.utf8(sbn.getKey()));
    }

    /** After a reconnect, quietly bring the Mac up to date with what is still waiting on the phone. */
    void resendActive() {
        forwarded.clear();
        iconsSent.clear();
        LinkService service = LinkService.instance;
        if (service == null || !Config.mirrorNotifications(this)) return;
        try {
            RankingMap rankings = getCurrentRanking();
            for (StatusBarNotification sbn : getActiveNotifications()) if (worthForwarding(sbn, rankings)) forward(service, sbn, true);
        } catch (RuntimeException ignored) {
        }
    }

    private boolean worthForwarding(StatusBarNotification sbn, RankingMap rankings) {
        if (sbn.getPackageName().equals(getPackageName()) || sbn.getPackageName().equals("android")
                || sbn.getPackageName().equals("com.android.systemui")) return false;
        Notification n = sbn.getNotification();
        int skip = Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_GROUP_SUMMARY
                | Notification.FLAG_LOCAL_ONLY;
        if ((n.flags & skip) != 0 || n.visibility == Notification.VISIBILITY_SECRET) return false;
        if (Notification.CATEGORY_PROGRESS.equals(n.category) || Notification.CATEGORY_TRANSPORT.equals(n.category)
                || Notification.CATEGORY_SERVICE.equals(n.category) || Notification.CATEGORY_SYSTEM.equals(n.category)) return false;
        if (String.valueOf(n.extras.getString(Notification.EXTRA_TEMPLATE)).contains("MediaStyle")) return false;
        Ranking r = new Ranking();
        if (rankings != null && rankings.getRanking(sbn.getKey(), r) && r.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) return false;
        return true;
    }

    private void forward(LinkService service, StatusBarNotification sbn, boolean silent) {
        Notification n = sbn.getNotification();
        Bundle ex = n.extras;
        String title = text(ex.getCharSequence(Notification.EXTRA_TITLE));
        String body = text(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (body.isEmpty()) body = text(ex.getCharSequence(Notification.EXTRA_TEXT));
        if (title.isEmpty() && body.isEmpty()) return;
        int hash = Objects.hash(title, body);
        Integer previous = forwarded.get(sbn.getKey());
        if (previous != null && previous == hash) return; // an update with nothing new to show
        forwarded.put(sbn.getKey(), hash);

        String pkg = sbn.getPackageName();
        PackageManager pm = getPackageManager();
        String app = pkg;
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            app = String.valueOf(pm.getApplicationLabel(info));
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        JSONArray actions = new JSONArray();
        for (Notification.Action a : plainActions(n)) actions.put(String.valueOf(a.title));

        // A contact photo goes every time; the app icon only once per connection (the Mac caches it).
        byte[] icon = null;
        boolean iconIsApp = false;
        Icon large = n.getLargeIcon();
        if (large != null) icon = png(large.loadDrawable(this));
        if (icon == null && iconsSent.add(pkg)) {
            try {
                icon = png(pm.getApplicationIcon(pkg));
                iconIsApp = true;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        try {
            JSONObject o = new JSONObject().put("key", sbn.getKey()).put("pkg", pkg).put("app", app)
                    .put("title", title).put("text", body).put("reply", replyAction(n) != null)
                    .put("actions", actions).put("iconIsApp", iconIsApp).put("silent", silent).put("time", sbn.getPostTime());
            byte[] json = Proto.utf8(o.toString());
            byte[] length = {(byte) (json.length >>> 24), (byte) (json.length >>> 16), (byte) (json.length >>> 8), (byte) json.length};
            service.sendAsync(Proto.NOTIF_POST, Proto.concat(length, json, icon == null ? new byte[0] : icon));
            Log.i("Macindroid", "forwarded a notification from " + pkg); // package only, never content
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ one-time codes

    private static final long CODE_FRESH_MS = 10 * 60_000;
    private final Map<String, Long> codesSent = new HashMap<>(); // code -> elapsedRealtime; main thread

    /**
     * Sends a one-time code in a new notification to the Mac, which puts it on the clipboard. Sent before the
     * notification itself, so the Mac shows one "Code copied" notice instead of two. The code is never logged.
     */
    private void sendCode(LinkService service, StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        if (pkg.equals(getPackageName())) return;
        Notification n = sbn.getNotification();
        int skip = Notification.FLAG_GROUP_SUMMARY | Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE
                | Notification.FLAG_LOCAL_ONLY;
        if ((n.flags & skip) != 0) return;
        Bundle ex = n.extras;
        String title = text(ex.getCharSequence(Notification.EXTRA_TITLE));
        String body;
        long at;
        Notification.MessagingStyle.Message last = lastMessage(ex);
        if (last != null) { // a conversation: only its newest message, so an earlier code isn't sent again
            body = text(last.getText());
            at = last.getTimestamp();
            CharSequence conversation = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
            if (conversation != null && conversation.length() > 0) title = conversation.toString();
            else if (last.getSenderPerson() != null && last.getSenderPerson().getName() != null) title = last.getSenderPerson().getName().toString();
        } else {
            body = text(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (body.isEmpty()) body = text(ex.getCharSequence(Notification.EXTRA_TEXT));
            at = n.when > 0 ? n.when : sbn.getPostTime();
        }
        if (Math.abs(System.currentTimeMillis() - at) > CODE_FRESH_MS) return; // an old message, updated or reposted
        String code = Otp.find(title + "\n" + body);
        if (code == null) return;

        long now = SystemClock.elapsedRealtime();
        codesSent.values().removeIf(sent -> now - sent > CODE_FRESH_MS);
        if (codesSent.containsKey(code)) return; // the same SMS shown by two apps, or a notification update
        codesSent.put(code, now);

        String app = appLabel(pkg);
        // From whom: the sender for a text message or chat (the SMS "Google", "AX-HDFCBK"), otherwise the app.
        boolean conversation = last != null || Notification.CATEGORY_MESSAGE.equals(n.category)
                || pkg.equals(android.provider.Telephony.Sms.getDefaultSmsPackage(this));
        String from = conversation && !title.isEmpty() ? title : app;
        try {
            JSONObject o = new JSONObject().put("code", code).put("from", from).put("app", app).put("key", sbn.getKey());
            service.sendAsync(Proto.CODE, Proto.utf8(o.toString()));
            Log.i("Macindroid", "sent a one-time code from " + pkg); // package only, never the code
        } catch (Exception ignored) {
        }
    }

    private static Notification.MessagingStyle.Message lastMessage(Bundle ex) {
        Parcelable[] bundles = ex.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable.class);
        if (bundles == null || bundles.length == 0) return null;
        List<Notification.MessagingStyle.Message> messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles);
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    private String appLabel(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** Requests from the Mac. Main thread. */
    void handle(byte kind, byte[] payload) {
        try {
            String key;
            JSONObject o = null;
            if (kind == Proto.NOTIF_REPLY || kind == Proto.NOTIF_ACTION) {
                o = new JSONObject(Proto.utf8(payload));
                key = o.getString("key");
            } else {
                key = Proto.utf8(payload);
            }
            StatusBarNotification[] found = getActiveNotifications(new String[] {key});
            if (found == null || found.length == 0) return;
            Notification n = found[0].getNotification();
            switch (kind) {
                case Proto.NOTIF_REPLY:
                    Notification.Action reply = replyAction(n);
                    if (reply == null) return;
                    Intent results = new Intent();
                    Bundle values = new Bundle();
                    for (RemoteInput input : reply.getRemoteInputs()) values.putCharSequence(input.getResultKey(), o.getString("text"));
                    RemoteInput.addResultsToIntent(reply.getRemoteInputs(), results, values);
                    reply.actionIntent.send(this, 0, results);
                    break;
                case Proto.NOTIF_ACTION:
                    List<Notification.Action> plain = plainActions(n);
                    int index = o.getInt("index");
                    if (index >= 0 && index < plain.size()) plain.get(index).actionIntent.send(this, 0, null, null, null, null, allowLaunch());
                    break;
                case Proto.NOTIF_DISMISS:
                    cancelNotification(key);
                    break;
                case Proto.NOTIF_OPEN:
                    if (n.contentIntent != null) {
                        PendingIntent content = n.contentIntent;
                        openWhenUnlocked(() -> {
                            try {
                                content.send(this, 0, null, null, null, null, allowLaunch());
                            } catch (PendingIntent.CanceledException ignored) {
                            }
                        });
                    }
                    if ((n.flags & Notification.FLAG_AUTO_CANCEL) != 0) cancelNotification(key);
                    break;
                default:
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ opening on the phone

    private Runnable pendingOpen; // main thread
    private long pendingUntil;
    private final BroadcastReceiver unlocked = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Runnable open = pendingOpen;
            boolean fresh = SystemClock.elapsedRealtime() < pendingUntil;
            dropPendingOpen();
            if (open != null && fresh) Foreground.run(NotifListener.this, open);
        }
    };

    /**
     * Android won't open an app behind the lock screen for us, so a click on the Mac while the phone is locked
     * opens it as soon as the phone is unlocked (on the phone or through the mirror the click starts), if that's
     * within two minutes.
     */
    private void openWhenUnlocked(Runnable open) {
        if (!getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            dropPendingOpen();
            Foreground.run(this, open);
            return;
        }
        if (pendingOpen == null) registerReceiver(unlocked, new IntentFilter(Intent.ACTION_USER_PRESENT));
        pendingOpen = open;
        pendingUntil = SystemClock.elapsedRealtime() + 120_000;
    }

    private void dropPendingOpen() {
        if (pendingOpen == null) return;
        pendingOpen = null;
        try {
            unregisterReceiver(unlocked);
        } catch (RuntimeException ignored) {
        }
    }

    /** Opt in to letting this send open an app from the background (Foreground provides the visible window). */
    private static Bundle allowLaunch() {
        return ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle();
    }

    private static Notification.Action replyAction(Notification n) {
        if (n.actions == null) return null;
        Notification.Action best = null;
        for (Notification.Action a : n.actions) {
            RemoteInput[] inputs = a.getRemoteInputs();
            if (inputs == null || inputs.length == 0 || !inputs[0].getAllowFreeFormInput()) continue;
            if (a.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_REPLY) return a;
            if (best == null) best = a;
        }
        return best;
    }

    /** Buttons other than Reply, at most two (what fits on a Mac notification). */
    private static List<Notification.Action> plainActions(Notification n) {
        List<Notification.Action> out = new ArrayList<>();
        if (n.actions == null) return out;
        for (Notification.Action a : n.actions) {
            if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) continue;
            if (a.title == null || a.actionIntent == null) continue;
            out.add(a);
            if (out.size() == 2) break;
        }
        return out;
    }

    private static String text(CharSequence s) {
        return s == null ? "" : s.toString().trim();
    }

    private static byte[] png(Drawable d) {
        if (d == null) return null;
        int size = 96;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, size, size);
        d.draw(new Canvas(bitmap));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        bitmap.recycle();
        return out.toByteArray();
    }
}
