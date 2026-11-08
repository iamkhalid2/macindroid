package xyz.iamkhalid.macindroid;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * File drop both ways (see mac/Sources/Transfers.swift): FILE_OFFER (JSON), FILE_CHUNKs (8-byte id + data),
 * FILE_END (8-byte id + SHA-256). Received files go to Downloads/Macindroid and are only made visible once the
 * checksum matches. Everything is streamed; nothing large is held in memory.
 */
final class Transfers {
    private final LinkService service;
    private final ContentResolver resolver;
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private final Map<Long, Incoming> incoming = new HashMap<>(); // network thread only

    private static final class Incoming {
        String name, mime;
        long size, received, lastUpdate;
        Uri uri;
        OutputStream out;
        MessageDigest md;
        int notificationId;
    }

    Transfers(LinkService service) {
        this.service = service;
        this.resolver = service.getContentResolver();
    }

    void shutdown() {
        sender.shutdownNow();
        onDisconnected();
    }

    // ------------------------------------------------------------------ phone -> Mac

    void send(List<Uri> uris) {
        for (Uri uri : uris) sender.execute(() -> sendOne(uri));
    }

    private void sendOne(Uri uri) {
        String name = "file";
        long size = -1;
        try (Cursor c = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                if (!c.isNull(0)) name = c.getString(0);
                if (!c.isNull(1)) size = c.getLong(1);
            }
        } catch (Exception ignored) {
        }
        String mime = resolver.getType(uri);
        long id = Proto.randomId();
        int notificationId = (int) (id ^ (id >>> 32));
        String mac = Config.macName(service);
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("unreadable");
            JSONObject offer = new JSONObject().put("id", Long.toUnsignedString(id)).put("name", name).put("size", size)
                    .put("mime", mime == null ? "application/octet-stream" : mime);
            if (!service.send(Proto.FILE_OFFER, Proto.utf8(offer.toString()))) {
                service.toast(mac + " isn't connected");
                return;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] head = Proto.id(id);
            byte[] buf = new byte[Proto.CHUNK];
            long sent = 0, lastUpdate = 0;
            int n;
            while ((n = readFully(in, buf)) > 0) {
                md.update(buf, 0, n);
                byte[] payload = new byte[8 + n];
                System.arraycopy(head, 0, payload, 0, 8);
                System.arraycopy(buf, 0, payload, 8, n);
                if (!service.send(Proto.FILE_CHUNK, payload)) throw new IllegalStateException("disconnected");
                sent += n;
                long now = SystemClock.uptimeMillis();
                if (size > 4 * Proto.CHUNK && now - lastUpdate > 500) {
                    lastUpdate = now;
                    progress(notificationId, "Sending " + name, sent, size);
                }
            }
            service.send(Proto.FILE_END, Proto.concat(head, md.digest()));
            LinkService.record(new LinkService.Event(true, LinkService.EV_FILE, sent, name, null, mime));
            service.notify(notificationId, new Notification.Builder(service, LinkService.CH_FILES)
                    .setSmallIcon(R.drawable.ic_glyph)
                    .setContentTitle(name)
                    .setContentText("Sent to " + mac)
                    .setTimeoutAfter(4000)
                    .build());
        } catch (Exception e) {
            service.send(Proto.FILE_CANCEL, Proto.id(id));
            service.notify(notificationId, new Notification.Builder(service, LinkService.CH_FILES)
                    .setSmallIcon(R.drawable.ic_glyph)
                    .setContentTitle(name + " wasn't sent")
                    .setContentText(LinkService.connected() ? "Couldn't read the file" : mac + " disconnected")
                    .build());
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws java.io.IOException {
        int total = 0, n;
        while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) total += n;
        return total;
    }

    // ------------------------------------------------------------------ Mac -> phone

    void onFrame(byte kind, byte[] payload) {
        try {
            switch (kind) {
                case Proto.FILE_OFFER: onOffer(new JSONObject(Proto.utf8(payload))); break;
                case Proto.FILE_CHUNK: onChunk(payload); break;
                case Proto.FILE_END: onEnd(payload); break;
                case Proto.FILE_CANCEL: discard(incoming.remove(Proto.readId(payload))); break;
                default:
            }
        } catch (Exception e) {
            if (payload.length >= 8) discard(incoming.remove(Proto.readId(payload)));
        }
    }

    private void onOffer(JSONObject offer) throws Exception {
        long id = Long.parseUnsignedLong(offer.getString("id"));
        Incoming t = new Incoming();
        t.name = offer.optString("name", "file").replace('/', '-');
        t.size = offer.optLong("size", -1);
        t.mime = offer.optString("mime", "");
        if (t.mime.isEmpty() || t.mime.equals("application/octet-stream")) {
            String ext = MimeTypeMap.getFileExtensionFromUrl(Uri.encode(t.name));
            String guess = ext == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
            t.mime = guess != null ? guess : "application/octet-stream";
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, t.name);
        v.put(MediaStore.Downloads.MIME_TYPE, t.mime);
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Macindroid");
        v.put(MediaStore.Downloads.IS_PENDING, 1); // hidden from other apps until it is complete and verified
        t.uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (t.uri == null) throw new IllegalStateException("cannot create download");
        t.out = new BufferedOutputStream(resolver.openOutputStream(t.uri), 256 * 1024);
        t.md = MessageDigest.getInstance("SHA-256");
        t.notificationId = (int) (id ^ (id >>> 32));
        incoming.put(id, t);
    }

    private void onChunk(byte[] payload) throws Exception {
        Incoming t = incoming.get(Proto.readId(payload));
        if (t == null) return;
        t.out.write(payload, 8, payload.length - 8);
        t.md.update(payload, 8, payload.length - 8);
        t.received += payload.length - 8;
        long now = SystemClock.uptimeMillis();
        if (t.size > 4 * Proto.CHUNK && now - t.lastUpdate > 500) {
            t.lastUpdate = now;
            progress(t.notificationId, "Receiving " + t.name, t.received, t.size);
        }
    }

    private void onEnd(byte[] payload) throws Exception {
        Incoming t = incoming.remove(Proto.readId(payload));
        if (t == null) return;
        t.out.close();
        if (!Arrays.equals(t.md.digest(), Arrays.copyOfRange(payload, 8, 40))) {
            resolver.delete(t.uri, null, null);
            service.notify(t.notificationId, simple(t.name + " didn't arrive intact", "Send it again from your Mac.", null));
            return;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(t.uri, done, null, null);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(t.uri, t.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent open = PendingIntent.getActivity(service, t.notificationId, view, PendingIntent.FLAG_IMMUTABLE);
        service.notify(t.notificationId, simple(t.name, "From " + Config.macName(service) + " · in Downloads", open));
        LinkService.record(new LinkService.Event(false, LinkService.EV_FILE, t.received, t.name, t.uri, t.mime));
    }

    void onDisconnected() {
        for (Incoming t : incoming.values()) discard(t);
        incoming.clear();
    }

    private void discard(Incoming t) {
        if (t == null) return;
        try {
            t.out.close();
        } catch (Exception ignored) {
        }
        try {
            resolver.delete(t.uri, null, null);
        } catch (Exception ignored) {
        }
        service.getSystemService(android.app.NotificationManager.class).cancel(t.notificationId);
    }

    private Notification simple(String title, String text, PendingIntent open) {
        Notification.Builder b = new Notification.Builder(service, LinkService.CH_ALERTS)
                .setSmallIcon(R.drawable.ic_glyph)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true);
        if (open != null) b.setContentIntent(open);
        return b.build();
    }

    private void progress(int id, String title, long done, long size) {
        int percent = size > 0 ? (int) (done * 100 / size) : 0;
        service.notify(id, new Notification.Builder(service, LinkService.CH_FILES)
                .setSmallIcon(R.drawable.ic_glyph)
                .setContentTitle(title)
                .setProgress(100, percent, size <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build());
    }
}
