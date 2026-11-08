package xyz.iamkhalid.macindroid;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Size;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * New photos and screenshots on the Mac (see mac/Sources/Photos.swift). When the camera or a screenshot adds an image
 * while the Mac is connected, the phone sends PHOTO: a preview of at most 720 px. The Mac asks for the original with
 * PHOTO_GET only when you click Copy, Save or drag it, and gets it back as PHOTO_FULL. Both carry a 4-byte JSON
 * length, the JSON, then the image bytes.
 *
 * Only images that appear under DCIM/ or in a Screenshots folder count, so files arriving from the Mac, saved from
 * a chat app or synced from the cloud don't pop up, and only images added in the last half minute, so a gallery
 * that is still indexing doesn't flood the Mac. The Mac can fetch only an image it was offered.
 */
final class PhotoWatcher {
    private static final int PREVIEW = 720;
    private static final int RECENT_SECONDS = 30;
    private static final int MAX_FULL = Proto.MAX_FRAME - 64 * 1024;

    private final LinkService service;
    private final ContentResolver resolver;
    private final HandlerThread thread = new HandlerThread("macindroid-photos");
    private final Handler handler;
    private final long startedAt = System.currentTimeMillis() / 1000;
    /** Images offered to the Mac, newest last; also what it may fetch. */
    private final Map<Long, Uri> offered = new LinkedHashMap<Long, Uri>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Uri> eldest) {
            return size() > 50;
        }
    };
    private final Runnable scan = this::scan;
    private final ContentObserver observer;

    PhotoWatcher(LinkService service) {
        this.service = service;
        resolver = service.getContentResolver();
        thread.start();
        handler = new Handler(thread.getLooper());
        observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                // One photo fires several changes (insert, then IS_PENDING cleared, then thumbnails): look once they settle.
                handler.removeCallbacks(scan);
                handler.postDelayed(scan, 500);
            }
        };
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer);
    }

    void stop() {
        resolver.unregisterContentObserver(observer);
        thread.quitSafely();
    }

    static boolean allowed(LinkService s) {
        return s.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
    }

    private void scan() {
        if (!LinkService.connected() || !allowed(service) || !Config.on(service, Config.PHOTOS)) return;
        long since = Math.max(startedAt, System.currentTimeMillis() / 1000 - RECENT_SECONDS);
        String[] columns = {
                MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DATE_ADDED};
        String where = MediaStore.Images.Media.IS_PENDING + " = 0 AND " + MediaStore.Images.Media.DATE_ADDED + " >= ?";
        try (Cursor c = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns, where,
                new String[] {Long.toString(since)}, MediaStore.Images.Media._ID + " ASC")) {
            if (c == null) return;
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String path = c.getString(1) == null ? "" : c.getString(1);
                boolean screenshot = path.toLowerCase().contains("screenshot");
                if (offered.containsKey(id) || !(screenshot || path.startsWith("DCIM/"))) continue;
                Uri uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                offered.put(id, uri);
                offer(id, uri, screenshot, c.getString(2), c.getInt(3), c.getInt(4), c.getLong(5), c.getLong(7));
            }
        } catch (Exception e) {
            android.util.Log.w("Macindroid", "photo scan failed", e);
        }
    }

    private void offer(long id, Uri uri, boolean screenshot, String name, int width, int height, long size, long added) {
        try {
            Bitmap thumb = resolver.loadThumbnail(uri, new Size(PREVIEW, PREVIEW), null);
            Bitmap small = fit(thumb, PREVIEW);
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 85, jpeg);
            if (width <= 0 || height <= 0) { // some cameras fill these in late; the preview's shape is close enough
                width = thumb.getWidth();
                height = thumb.getHeight();
            }
            JSONObject meta = new JSONObject()
                    .put("id", id).put("name", name == null ? "" : name).put("screenshot", screenshot)
                    .put("width", width).put("height", height).put("size", size).put("added", added);
            service.send(Proto.PHOTO, pack(meta, jpeg.toByteArray()));
        } catch (Exception e) {
            android.util.Log.w("Macindroid", "could not offer photo " + id, e);
        }
    }

    /** PHOTO_GET from the Mac: {"id": n}. Answered on this thread so a large image doesn't hold up the link. */
    void onGet(byte[] payload) {
        handler.post(() -> {
            long id = -1;
            try {
                id = new JSONObject(Proto.utf8(payload)).getLong("id");
                Uri uri = offered.get(id);
                if (uri == null) throw new IllegalArgumentException("not offered");
                String mime = resolver.getType(uri);
                byte[] bytes = read(uri);
                if (bytes.length > MAX_FULL) { // a 200-megapixel photo: send a high-quality JPEG that fits in a frame
                    Bitmap full = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
                    full.compress(Bitmap.CompressFormat.JPEG, 92, jpeg);
                    bytes = jpeg.toByteArray();
                    mime = "image/jpeg";
                }
                service.send(Proto.PHOTO_FULL, pack(new JSONObject().put("id", id).put("mime", mime), bytes));
            } catch (Exception e) {
                try {
                    service.send(Proto.PHOTO_FULL, pack(new JSONObject().put("id", id).put("error", "gone"), new byte[0]));
                } catch (Exception ignored) {
                }
            }
        });
    }

    private byte[] read(Uri uri) throws Exception {
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("no stream");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[256 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static Bitmap fit(Bitmap b, int max) {
        int longest = Math.max(b.getWidth(), b.getHeight());
        if (longest <= max) return b;
        float scale = (float) max / longest;
        return Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale), Math.round(b.getHeight() * scale), true);
    }

    private static byte[] pack(JSONObject meta, byte[] data) {
        byte[] json = meta.toString().getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + json.length + data.length).putInt(json.length).put(json).put(data).array();
    }
}
