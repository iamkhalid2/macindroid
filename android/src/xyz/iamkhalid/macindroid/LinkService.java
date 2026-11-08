package xyz.iamkhalid.macindroid;

import android.Manifest;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKey;

/** Keeps the encrypted link to the Mac and runs every feature that rides on it. */
public class LinkService extends Service {
    static final String ACTION_SEND_CLIPBOARD = "xyz.iamkhalid.macindroid.SEND_CLIPBOARD";
    static final String ACTION_SEND_FILES = "xyz.iamkhalid.macindroid.SEND_FILES";
    static final String ACTION_STOP_RING = "xyz.iamkhalid.macindroid.STOP_RING";
    private static final String ACTION_RETRY = "xyz.iamkhalid.macindroid.RETRY";
    static final String LABEL = "Macindroid";
    static final String CH_STATUS = "status", CH_FILES = "files", CH_ALERTS = "alerts", CH_RING = "ring";
    static final int NOTIFICATION_ID = 1;

    static final int STARTING = 0, NOT_PAIRED = 1, SEARCHING = 2, RETRYING = 3, CONNECTED = 4, KEY_MISMATCH = 5;
    static volatile LinkService instance;
    static volatile int state = STARTING;
    static volatile String peer;
    /** True once Android is actually giving us the system log (proved by a line arriving), not just once we asked. */
    static volatile boolean autoSend;
    /** Android declined log access: it only asks while this app is on screen, so it's asked again when it is. */
    static volatile boolean logDeclined;
    private volatile boolean restartLog;
    private volatile boolean askingOnScreen; // the next logcat start happens while we're on screen, so Android asks
    private volatile boolean userSaidNo;     // Android asked on screen and access still didn't come
    private volatile long logAskedAt;
    private static volatile String status = "Starting";

    // ------------------------------------------------------------------ recent activity for the home screen

    static final int EV_TEXT = 0, EV_IMAGE = 1, EV_FILE = 2, EV_LINK = 3;

    static final class Event {
        final boolean toMac;
        final int type;
        final long bytes;
        final String title;
        final Uri uri;
        final String mime;
        final long at = System.currentTimeMillis();

        Event(boolean toMac, int type, long bytes, String title, Uri uri, String mime) {
            this.toMac = toMac;
            this.type = type;
            this.bytes = bytes;
            this.title = title;
            this.uri = uri;
            this.mime = mime;
        }
    }

    private static final ArrayDeque<Event> history = new ArrayDeque<>();
    static volatile int historyVersion;

    static List<Event> recent() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    static void record(Event e) {
        synchronized (history) {
            history.addFirst(e);
            while (history.size() > 8) history.removeLast();
        }
        historyVersion++;
    }

    // ------------------------------------------------------------------ state

    private final Object lock = new Object();
    private final Object sleeper = new Object();
    final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ScheduledExecutorService pinger;
    private volatile boolean running;
    private volatile Socket socket;
    private DataOutputStream out; // guarded by lock
    private volatile SecretKey key;
    private volatile String lastDigest;
    private volatile long ownChangeUntil; // set just before we put a clip from the Mac on the clipboard
    private volatile long lastHeard; // elapsedRealtime of the last frame from the Mac
    private volatile Process logcat;
    private ClipboardManager clipboard;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final ClipboardManager.OnPrimaryClipChangedListener clipListener = this::onClipChangedWhileFocused;
    private BroadcastReceiver batteryReceiver;
    private BroadcastReceiver wakeReceiver;
    private AlarmManager alarms;
    private PendingIntent retryAlarm;
    private PowerManager.WakeLock awake; // held only while trying to reach the Mac, so the phone can't sleep mid-try
    private volatile long lostAt;        // elapsedRealtime when the Mac was last lost, or a new network appeared
    private volatile long lastTry;       // elapsedRealtime of the last connection attempt
    private volatile int battery = -1;
    private volatile boolean charging;
    Transfers transfers;
    Ringer ringer;
    PhotoWatcher photos;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, LinkService.class));
    }

    /**
     * Hands files to the service with read access, so they stream after the calling screen has closed.
     * Returns false when the sharing app didn't actually give us access to them.
     */
    static boolean sendFiles(Context c, List<Uri> uris) {
        if (uris.isEmpty()) return false;
        ClipData clip = ClipData.newRawUri("files", uris.get(0));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        Intent intent = new Intent(c, LinkService.class).setAction(ACTION_SEND_FILES).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(clip);
        try {
            c.startService(intent);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    static boolean connected() {
        return state == CONNECTED;
    }

    static String deviceName(Context c) {
        String name = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME);
        return name == null || name.isEmpty() ? Build.MODEL : name;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        clipboard = getSystemService(ClipboardManager.class);
        createChannels();
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        transfers = new Transfers(this);
        ringer = new Ringer(this);
        photos = new PhotoWatcher(this); // before the network thread, which hands it PHOTO_GET
        // Registering a listener makes Android log a "denied" line for this app on every copy; the log watcher uses it.
        clipboard.addPrimaryClipChangedListener(clipListener);
        awake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "macindroid:reconnect");
        awake.setReferenceCounted(false);
        alarms = getSystemService(AlarmManager.class);
        retryAlarm = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_RETRY).setPackage(getPackageName()), PendingIntent.FLAG_IMMUTABLE);
        wakeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_RETRY.equals(intent.getAction())) {
                    if (state == CONNECTED) return;
                    awake.acquire(ATTEMPT_MS);
                    wake();
                } else {
                    nudge(); // the screen came on: the user may be about to use the link
                }
            }
        };
        IntentFilter wakeFilter = new IntentFilter(ACTION_RETRY);
        wakeFilter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(wakeReceiver, wakeFilter, RECEIVER_NOT_EXPORTED);
        lostAt = SystemClock.elapsedRealtime();
        running = true;
        new Thread(this::networkLoop, "macindroid-net").start();
        new Thread(this::logLoop, "macindroid-log").start();
        pinger = Executors.newSingleThreadScheduledExecutor();
        pinger.scheduleWithFixedDelay(this::ping, 25, 25, TimeUnit.SECONDS);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (state == CONNECTED) return;
                NetworkCapabilities caps = getSystemService(ConnectivityManager.class).getNetworkCapabilities(network);
                if (caps != null && !isLocal(caps)) return; // mobile data: the Mac's LAN address can't answer there
                // A new network, which the Mac may be on: retry now, and quickly again for a while.
                lostAt = SystemClock.elapsedRealtime();
                awake.acquire(ATTEMPT_MS);
                wake();
            }

            @Override
            public void onLost(Network network) {
                closeSocket();
            }
        };
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networkCallback);
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int percent = level < 0 ? -1 : level * 100 / Math.max(scale, 1);
                boolean plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                if (percent != battery || plugged != charging) {
                    battery = percent;
                    charging = plugged;
                    sendStatus();
                }
            }
        };
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_SEND_CLIPBOARD.equals(action)) ClipReader.readWithFocus(this);
        if (ACTION_STOP_RING.equals(action)) ringer.stop(true);
        if (ACTION_SEND_FILES.equals(action) && intent.getClipData() != null) {
            ClipData data = intent.getClipData();
            List<Uri> uris = new ArrayList<>();
            for (int i = 0; i < data.getItemCount(); i++) if (data.getItemAt(i).getUri() != null) uris.add(data.getItemAt(i).getUri());
            transfers.send(uris);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        state = STARTING;
        autoSend = false;
        clipboard.removePrimaryClipChangedListener(clipListener);
        try {
            getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(batteryReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(wakeReceiver);
        } catch (Exception ignored) {
        }
        alarms.cancel(retryAlarm);
        awake.release();
        ringer.stop(false);
        photos.stop();
        finishMirror();
        pinger.shutdownNow();
        Process p = logcat;
        if (p != null) p.destroy();
        closeSocket();
        wake();
        io.shutdown();
        transfers.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ connection

    private void networkLoop() {
        boolean useDiscovery = false;
        awake.acquire(ATTEMPT_MS);
        while (running) {
            lastTry = SystemClock.elapsedRealtime();
            key = Config.key(this);
            if (key == null) {
                state = NOT_PAIRED;
                setStatus("Not paired");
                awake.release();
                pause(10000); // pairing happens on screen, with the phone awake
                awake.acquire(ATTEMPT_MS);
                lostAt = SystemClock.elapsedRealtime();
                continue;
            }
            InetSocketAddress target = useDiscovery ? Discovery.findOne(this, Config.macName(this), 4000) : null;
            if (target != null) Config.setHost(this, target.getAddress().getHostAddress());
            if (target == null) {
                String host = Config.host(this);
                if (host != null && !host.isEmpty()) target = new InetSocketAddress(host, Proto.PORT);
            }
            if (target == null) {
                state = SEARCHING;
                setStatus("Looking for " + Config.macName(this));
                useDiscovery = true;
                waitToRetry();
                continue;
            }
            boolean linked = false;
            try (Socket s = new Socket()) {
                s.connect(target, 5000);
                s.setTcpNoDelay(true);
                s.setSoTimeout(70000); // we ping every 25 s and the Mac answers
                synchronized (lock) {
                    socket = s;
                    out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 64 * 1024));
                }
                send(Proto.HELLO, Proto.utf8(deviceName(this)));
                peer = target.getAddress().getHostAddress();
                state = CONNECTED;
                linked = true;
                setStatus("Connected to " + Config.macName(this));
                useDiscovery = false;
                sendStatus();
                NotifListener listener = NotifListener.instance;
                if (listener != null) main.post(listener::resendActive);
                awake.release(); // from here, frames from the Mac wake the phone as they arrive
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024));
                while (running) {
                    byte[] frame = Proto.readFrame(in);
                    lastHeard = SystemClock.elapsedRealtime();
                    byte[] plain = Proto.open(key, frame);
                    onFrame(plain[0], Arrays.copyOfRange(plain, 1, plain.length));
                }
            } catch (GeneralSecurityException e) {
                awake.acquire(ATTEMPT_MS);
                state = KEY_MISMATCH;
                setStatus("Pairing key doesn't match the Mac");
            } catch (Exception e) {
                awake.acquire(ATTEMPT_MS); // the link may have dropped while the phone slept: stay up to retry
                state = RETRYING;
                if (running) setStatus("Reconnecting to " + Config.macName(this));
                useDiscovery = !useDiscovery; // the Mac's address may have changed
            }
            synchronized (lock) {
                socket = null;
                out = null;
            }
            if (state == CONNECTED) state = RETRYING;
            if (linked) lostAt = SystemClock.elapsedRealtime();
            transfers.onDisconnected();
            waitToRetry();
        }
        awake.release();
    }

    /** Longest one attempt can take (discovery, then connect), and so how long it keeps the phone awake at most. */
    private static final long ATTEMPT_MS = 20_000;
    /** Android won't schedule an app's alarm sooner than this (AlarmManager's min_futurity). */
    private static final long SHORTEST_ALARM_MS = 5_000;

    /**
     * Waits before the next attempt. Object.wait (and every other Java timer) counts only time the CPU is awake,
     * and with the screen off the phone sleeps most of the time, so a plain 15-second wait could take minutes.
     * A wakeup alarm, which Doze lets through for an app on the battery-optimisation allowlist, ends the wait on
     * real time instead.
     *
     * Most drops are the Mac app restarting or a Wi-Fi blip, so retries come every second for the first
     * 15 seconds, with the phone kept awake (an alarm can't be that soon). They then thin out, so a Mac that's
     * asleep or away costs a dozen short wakeups an hour. With no Wi-Fi, Ethernet or VPN the Mac's address can't
     * answer, so there are no alarms: a new network wakes the loop, and otherwise it tries when the phone is
     * awake anyway.
     */
    private void waitToRetry() {
        if (!running) return;
        long gone = SystemClock.elapsedRealtime() - lostAt;
        long ms = gone < 15_000 ? 1_000 : gone < 120_000 ? 5_000 : gone < 600_000 ? 15_000 : gone < 1_800_000 ? 60_000 : 300_000;
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        boolean local = isLocal(cm.getNetworkCapabilities(cm.getActiveNetwork()));
        if (!local) ms = Math.max(ms, 15_000);
        boolean alarm = local && ms >= SHORTEST_ALARM_MS;
        if (alarm) {
            long at = SystemClock.elapsedRealtime() + ms;
            // Exact needs the allowlist (setup puts the app there); without it, Android still wakes us, a little later.
            if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, retryAlarm);
            else alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, retryAlarm);
        }
        if (ms >= SHORTEST_ALARM_MS) awake.release();
        pause(ms);
        if (alarm) alarms.cancel(retryAlarm);
        awake.acquire(ATTEMPT_MS);
    }

    /** Wi-Fi, Ethernet or a VPN: a network the Mac can be reached on. Over mobile data its LAN address can't answer. */
    private static boolean isLocal(NetworkCapabilities caps) {
        return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
    }

    /**
     * The phone is awake for another reason (a notification arrived, the screen came on), so trying the Mac now
     * costs next to nothing: do it rather than wait for the next scheduled attempt.
     */
    void nudge() {
        if (state == CONNECTED || state == NOT_PAIRED || state == STARTING) return;
        if (SystemClock.elapsedRealtime() - lastTry < 30_000) return;
        awake.acquire(ATTEMPT_MS);
        wake();
    }

    private void onFrame(byte kind, byte[] payload) {
        switch (kind) {
            case Proto.PING:
                send(Proto.PONG, new byte[0]);
                break;
            case Proto.HELLO:
                String name = Proto.utf8(payload);
                if (!name.isEmpty() && !name.equals(Config.macName(this))) {
                    Config.setMacName(this, name);
                    setStatus("Connected to " + name);
                }
                break;
            case Proto.TEXT:
            case Proto.IMAGE:
                applyClip(kind, payload);
                break;
            case Proto.NOTIF_REPLY:
            case Proto.NOTIF_ACTION:
            case Proto.NOTIF_DISMISS:
            case Proto.NOTIF_OPEN:
                NotifListener listener = NotifListener.instance;
                if (listener != null) main.post(() -> listener.handle(kind, payload));
                break;
            case Proto.FILE_OFFER:
            case Proto.FILE_CHUNK:
            case Proto.FILE_END:
            case Proto.FILE_CANCEL:
                transfers.onFrame(kind, payload);
                break;
            case Proto.LINK:
                String url = Proto.utf8(payload);
                main.post(() -> openLink(url));
                break;
            case Proto.RING:
                main.post(ringer::start);
                break;
            case Proto.RING_STOP:
                main.post(() -> ringer.stop(false));
                break;
            case Proto.MIRROR_PREP:
                prepareMirror();
                break;
            case Proto.MIRROR_DONE:
                finishMirror();
                break;
            case Proto.PHOTO_GET:
                photos.onGet(payload);
                break;
            default: // something a newer Mac app sends: ignore
        }
    }

    /** Blocking; call off the main thread. Returns false when not connected. */
    boolean send(byte kind, byte[] payload) {
        SecretKey k = key;
        if (k == null) return false;
        synchronized (lock) {
            if (out == null) return false;
            try {
                Proto.writeFrame(out, Proto.seal(k, kind, payload));
                return true;
            } catch (Exception e) {
                closeSocket();
                return false;
            }
        }
    }

    void sendAsync(byte kind, byte[] payload) {
        sendAsync(kind, payload, null);
    }

    /** Like sendAsync, then tells `done` (on the sending thread) whether it went. */
    void sendAsync(byte kind, byte[] payload, java.util.function.Consumer<Boolean> done) {
        try {
            io.execute(() -> {
                boolean sent = send(kind, payload);
                if (done != null) done.accept(sent);
            });
        } catch (RuntimeException ignored) { // shutting down
            if (done != null) done.accept(false);
        }
    }

    void reconnect() {
        closeSocket();
        wake();
    }

    void unpair() {
        Config.unpair(this);
        closeSocket();
        wake();
    }

    private void sendStatus() {
        if (state != CONNECTED) return;
        try {
            JSONObject o = new JSONObject().put("battery", battery).put("charging", charging).put("name", deviceName(this));
            sendAsync(Proto.STATUS, Proto.utf8(o.toString()));
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ clipboard, Mac -> phone

    private void applyClip(byte kind, byte[] payload) {
        String digest = Proto.digest(kind, payload);
        if (digest.equals(lastDigest)) return;
        lastDigest = digest;
        final ClipData clip;
        if (kind == Proto.TEXT) {
            clip = ClipData.newPlainText(LABEL, Proto.utf8(payload));
        } else {
            Uri uri = ClipProvider.store(this, payload);
            if (uri == null) return;
            clip = new ClipData(LABEL, new String[] {Proto.imageMime(payload)}, new ClipData.Item(uri));
        }
        main.post(() -> {
            ownChangeUntil = SystemClock.uptimeMillis() + 1000;
            clipboard.setPrimaryClip(clip);
        });
        record(new Event(false, kind == Proto.TEXT ? EV_TEXT : EV_IMAGE, payload.length, preview(kind, payload), null, null));
    }

    private static String preview(byte kind, byte[] payload) {
        if (kind != Proto.TEXT) return null;
        String text = new String(payload, 0, Math.min(payload.length, 400), java.nio.charset.StandardCharsets.UTF_8);
        return text.replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------ clipboard, phone -> Mac

    /** Call only while this app has focus (overlay probe, activity), otherwise Android returns null. */
    void sendClip(ClipData clip, boolean feedback) {
        if (clip == null || clip.getItemCount() == 0) {
            if (feedback) toast("Clipboard is empty");
            return;
        }
        ClipDescription description = clip.getDescription();
        if (LABEL.contentEquals(String.valueOf(description.getLabel()))) return; // came from the Mac
        PersistableBundle extras = description.getExtras();
        if (extras != null && extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE)) {
            if (feedback) toast("Not sent: marked sensitive (password)");
            return;
        }
        ClipData.Item item = clip.getItemAt(0);
        Uri uri = item.getUri();
        String mime = uri == null ? null : getContentResolver().getType(uri);
        if (mime == null && uri != null && description.getMimeTypeCount() > 0) mime = description.getMimeType(0);
        if (uri != null && mime != null && mime.startsWith("image/")) {
            byte[] bytes = readAll(uri);
            if (bytes == null) {
                if (feedback) toast("Could not read the image");
                return;
            }
            io.execute(() -> offer(Proto.IMAGE, bytes, feedback));
            return;
        }
        CharSequence text = item.coerceToText(this);
        if (text != null && text.length() > 0) sendText(text.toString(), feedback);
        else if (feedback) toast("Nothing to send");
    }

    void sendText(String text, boolean feedback) {
        byte[] bytes = Proto.utf8(text);
        io.execute(() -> offer(Proto.TEXT, bytes, feedback));
    }

    private void offer(byte kind, byte[] payload, boolean feedback) {
        String digest = Proto.digest(kind, payload);
        if (digest.equals(lastDigest) && !feedback) return;
        if (send(kind, payload)) {
            lastDigest = digest;
            record(new Event(true, kind == Proto.TEXT ? EV_TEXT : EV_IMAGE, payload.length, preview(kind, payload), null, null));
            if (feedback) toast("Copied to " + Config.macName(this));
        } else if (feedback) {
            toast(Config.macName(this) + " isn't connected");
        }
    }

    private byte[] readAll(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > Proto.MAX_FRAME - 1024) return null;
            }
            return buf.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private void onClipChangedWhileFocused() {
        // Only fires while one of our windows has focus (for example the home screen).
        if (isOwnChange()) return;
        sendClip(clipboard.getPrimaryClip(), false);
    }

    /**
     * True once, for the one clipboard change our own setPrimaryClip caused, so a clip from the Mac isn't read back.
     * (A time window instead would also swallow a real copy made in that window.) The deadline only covers the
     * case where Android never reports our own change.
     */
    private boolean isOwnChange() {
        if (SystemClock.uptimeMillis() >= ownChangeUntil) return false;
        ownChangeUntil = 0;
        return true;
    }

    /**
     * Watches for Android's "denied clipboard access" log line, which appears for this app whenever anything is
     * copied while it isn't in focus. (While it is in focus, the clipboard listener gets the change instead.)
     */
    private void logLoop() {
        String denied = "Denying clipboard access to " + getPackageName() + ",";
        while (running) {
            if (checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
                autoSend = false;
                pause(30000);
                continue;
            }
            try {
                // Android 13+ asks the user before an app reads the system log, but only while the app is on
                // screen; started in the background (after a reboot or update) it's declined without a word.
                // Logcat still runs then, it just never shows another app's line, so a line is the proof.
                Process p = new ProcessBuilder("logcat", "-T", "1", "-v", "monotonic", "ClipboardService:*", "*:S")
                        .redirectErrorStream(true).start();
                logcat = p;
                autoSend = false;
                long started = SystemClock.uptimeMillis();
                logAskedAt = started;
                boolean onScreen = askingOnScreen;
                askingOnScreen = false;
                main.postDelayed(this::probeLog, 1000);
                main.postDelayed(() -> {
                    if (logcat != p || autoSend) return;
                    if (onScreen) userSaidNo = true;
                    if (!logDeclined) {
                        logDeclined = true;
                        notify(NOTIFICATION_ID, buildNotification());
                    }
                }, 4000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                double lastLine = -1;
                while (running && (line = reader.readLine()) != null) {
                    if (!autoSend && line.contains("ClipboardService")) { // not, say, logcat's own error output
                        autoSend = true;
                        if (logDeclined) {
                            logDeclined = false;
                            notify(NOTIFICATION_ID, buildNotification());
                        }
                    }
                    if (SystemClock.uptimeMillis() - started < 700) continue; // skip the replayed old line
                    if (!line.contains(denied)) continue;
                    // One copy logs a burst of lines within ~60 ms; a new copy is a line after a quiet gap. The gap
                    // is measured on the log's own clock, so lines that reach us late don't merge separate copies.
                    double at = logTime(line);
                    boolean newCopy = lastLine < 0 || at - lastLine > 0.15;
                    lastLine = at;
                    if (newCopy && !isOwnChange()) main.post(() -> ClipReader.readWithFocus(this));
                }
                p.destroy();
            } catch (Exception e) {
                autoSend = false;
            }
            if (restartLog) {
                restartLog = false;
                continue;
            }
            pause(3000);
        }
    }

    /** Makes Android log a clipboard line (a denied check, while we're not in focus), without reading anything. */
    private void probeLog() {
        if (autoSend) return;
        ownChangeUntil = SystemClock.uptimeMillis() + 1000; // the line it causes isn't a copy
        clipboard.hasPrimaryClip();
    }

    /**
     * Call while one of our activities is on screen: if log access was declined, ask again, now that Android will.
     * On its own this asks once, after Android's one-minute memory of its last decision has passed; after a "no",
     * only when the user taps to ask ({@code userAsked}).
     */
    void retryLogAccessIfDeclined(boolean userAsked) {
        Process p = logcat;
        if (!logDeclined || p == null) return;
        if (!userAsked && (userSaidNo || SystemClock.uptimeMillis() - logAskedAt < 65000)) return;
        askingOnScreen = true;
        if (checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) return;
        restartLog = true;
        p.destroy();
    }

    /** Seconds since boot from a "-v monotonic" logcat line ("258353.738  2472  3147 E ..."), else now. */
    private static double logTime(String line) {
        int end = line.indexOf(' ', 1);
        try {
            return Double.parseDouble(line.substring(0, end < 0 ? line.length() : end).trim());
        } catch (RuntimeException e) {
            return SystemClock.uptimeMillis() / 1000.0;
        }
    }

    // ------------------------------------------------------------------ links

    void sendLink(String url) {
        io.execute(() -> {
            if (send(Proto.LINK, Proto.utf8(url))) {
                record(new Event(true, EV_LINK, 0, url, Uri.parse(url), null));
                toast("Opening on " + Config.macName(this));
            } else {
                toast(Config.macName(this) + " isn't connected");
            }
        });
    }

    /** Opens a page sent from the Mac right away if the phone is in use, otherwise leaves a notification. */
    private void openLink(String text) {
        Uri uri = Uri.parse(text.trim());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) return;
        record(new Event(false, EV_LINK, 0, text.trim(), uri, null));
        Intent view = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (phoneInUse() && Settings.canDrawOverlays(this)) {
            main.post(() -> Foreground.run(this, () -> {
                try {
                    startActivity(view);
                } catch (RuntimeException ignored) {
                }
            }));
            return;
        }
        PendingIntent open = PendingIntent.getActivity(this, uri.hashCode(), view, PendingIntent.FLAG_IMMUTABLE);
        notify(3, new Notification.Builder(this, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_glyph)
                .setContentTitle("Link from " + Config.macName(this))
                .setContentText(uri.getHost())
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
    }

    boolean phoneInUse() {
        return getSystemService(PowerManager.class).isInteractive()
                && !getSystemService(KeyguardManager.class).isKeyguardLocked();
    }

    // ------------------------------------------------------------------ mirroring

    /**
     * The Mac mirrors the screen with scrcpy over adb. When it isn't plugged in, switch on Wireless debugging just
     * for the session (needs WRITE_SECURE_SETTINGS, granted once over USB by setup-phone.sh) and off again after.
     */
    private void prepareMirror() {
        boolean can = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        boolean on = Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 1;
        if (!on && can) {
            try {
                Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
                Config.setWifiAdbByUs(this, true);
                on = true;
            } catch (SecurityException ignored) {
            }
        }
        try {
            sendAsync(Proto.MIRROR_READY, Proto.utf8(new JSONObject().put("ok", on).put("ip", wifiAddress()).toString()));
        } catch (Exception ignored) {
        }
    }

    private void finishMirror() {
        if (!Config.wifiAdbByUs(this)) return;
        try {
            Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 0);
        } catch (SecurityException ignored) {
        }
        Config.setWifiAdbByUs(this, false);
    }

    private String wifiAddress() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        LinkProperties props = cm.getLinkProperties(cm.getActiveNetwork());
        if (props == null) return "";
        for (LinkAddress a : props.getLinkAddresses()) if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
        return "";
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Keepalive. A Mac that quit or crashed can vanish without closing the connection, which the read timeout
     * alone would take over a minute to notice; no answer within 8 seconds means reconnect now.
     */
    private void ping() {
        if (state != CONNECTED) return;
        long sentAt = SystemClock.elapsedRealtime();
        if (!send(Proto.PING, new byte[0])) return;
        main.postDelayed(() -> {
            if (state == CONNECTED && lastHeard < sentAt) closeSocket();
        }, 8000);
    }

    private void closeSocket() {
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void pause(long ms) {
        synchronized (sleeper) {
            try {
                sleeper.wait(ms);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void wake() {
        synchronized (sleeper) {
            sleeper.notifyAll();
        }
    }

    void toast(String message) {
        main.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    void notify(int id, Notification n) {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            getSystemService(NotificationManager.class).notify(id, n);
        }
    }

    private void setStatus(String s) {
        if (s.equals(status)) return;
        status = s;
        notify(NOTIFICATION_ID, buildNotification());
    }

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_STATUS, "Connection", NotificationManager.IMPORTANCE_MIN));
        nm.createNotificationChannel(new NotificationChannel(CH_FILES, "File transfers", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_ALERTS, "Files and links from your Mac", NotificationManager.IMPORTANCE_DEFAULT));
        NotificationChannel ring = new NotificationChannel(CH_RING, "Find my phone", NotificationManager.IMPORTANCE_HIGH);
        ring.setSound(null, null); // the ringer plays its own alarm sound
        nm.createNotificationChannel(ring);
    }

    private Notification buildNotification() {
        PendingIntent sendNow = PendingIntent.getActivity(this, 1,
                new Intent(this, ReadClipActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 2,
                new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH_STATUS)
                .setSmallIcon(R.drawable.ic_glyph)
                .setContentTitle(status)
                .setContentText(logDeclined ? "Copies on this phone aren't reaching your Mac. Tap, then Allow." : null)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_glyph), "Send clipboard", sendNow).build())
                .build();
    }
}
