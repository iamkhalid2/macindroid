package xyz.iamkhalid.macindroid;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This phone's storage, for the Mac's Finder. The Mac asks over the encrypted link (FS_REQUEST: 4-byte JSON length |
 * JSON {id, op, path…} | data) and gets FS_REPLY frames in the same shape; a read comes back in several. Nothing
 * listens on the network for this: the only way in is the paired Mac's link. Needs Android's "All files access",
 * and can be switched off on the home screen. Paths are kept inside shared storage; file names are never logged.
 */
final class PhoneFiles {
    private final LinkService s;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Map<Long, Upload> uploads = new ConcurrentHashMap<>();
    private final File root;
    private volatile long askedAt;

    private static final class Upload {
        File temp, target;
        FileOutputStream out;
    }

    PhoneFiles(LinkService s) {
        this.s = s;
        File r = Environment.getExternalStorageDirectory();
        try {
            r = r.getCanonicalFile();
        } catch (IOException ignored) {
        }
        root = r;
    }

    static boolean allowed() {
        return Environment.isExternalStorageManager();
    }

    void onRequest(byte[] payload) {
        try {
            pool.execute(() -> handle(payload));
        } catch (RuntimeException ignored) { // shutting down
        }
    }

    /** The link dropped: unfinished uploads are thrown away. */
    void onDisconnected() {
        for (Long handle : uploads.keySet()) abort(handle);
    }

    void shutdown() {
        onDisconnected();
        pool.shutdownNow();
    }

    private void handle(byte[] payload) {
        long id = -1;
        try {
            int n = ((payload[0] & 0xff) << 24) | ((payload[1] & 0xff) << 16) | ((payload[2] & 0xff) << 8) | (payload[3] & 0xff);
            JSONObject q = new JSONObject(new String(payload, 4, n, java.nio.charset.StandardCharsets.UTF_8));
            id = q.getLong("id");
            if (!Config.on(s, Config.FILES)) {
                fail(id, "off");
                return;
            }
            if (!allowed()) {
                fail(id, "access");
                askForAccess();
                return;
            }
            String op = q.getString("op");
            switch (op) {
                case "list": {
                    File dir = resolve(q.getString("path"));
                    if (!dir.isDirectory()) throw new java.io.FileNotFoundException();
                    JSONArray entries = new JSONArray();
                    File[] children = dir.listFiles(); // null where Android keeps even file managers out (Android/data)
                    if (children != null) {
                        for (File f : children) {
                            if (f.getName().startsWith(".macindroid-") && f.getName().endsWith(".part")) continue;
                            boolean d = f.isDirectory();
                            entries.put(new JSONArray().put(f.getName()).put(d ? 1 : 0).put(d ? 0 : f.length()).put(f.lastModified()));
                        }
                    }
                    reply(id, ok().put("entries", entries).put("m", dir.lastModified())
                            .put("free", root.getUsableSpace()).put("total", root.getTotalSpace()), null, 0);
                    break;
                }
                case "stat": {
                    File f = resolve(q.getString("path"));
                    if (!f.exists()) throw new java.io.FileNotFoundException();
                    reply(id, ok().put("d", f.isDirectory() ? 1 : 0).put("s", f.isDirectory() ? 0 : f.length()).put("m", f.lastModified())
                            .put("free", root.getUsableSpace()).put("total", root.getTotalSpace()), null, 0);
                    break;
                }
                case "read":
                    read(id, resolve(q.getString("path")), q.optLong("offset", 0), q.optLong("length", -1));
                    break;
                case "create": {
                    File target = resolve(q.getString("path"));
                    if (target.isDirectory() || target.getParentFile() == null || !target.getParentFile().isDirectory()) throw new java.io.FileNotFoundException();
                    Upload u = new Upload();
                    u.target = target;
                    u.temp = new File(target.getParentFile(), ".macindroid-" + id + "-" + SystemClock.elapsedRealtimeNanos() + ".part");
                    u.out = new FileOutputStream(u.temp);
                    uploads.put(id, u);
                    reply(id, ok().put("handle", id), null, 0);
                    break;
                }
                case "append": {
                    Upload u = uploads.get(q.getLong("handle"));
                    if (u == null) throw new IOException("no such upload");
                    u.out.write(payload, 4 + n, payload.length - 4 - n);
                    reply(id, ok(), null, 0);
                    break;
                }
                case "commit": {
                    Upload u = uploads.remove(q.getLong("handle"));
                    if (u == null) throw new IOException("no such upload");
                    u.out.getFD().sync();
                    u.out.close();
                    if (!u.temp.renameTo(u.target)) {
                        u.temp.delete();
                        throw new IOException("rename");
                    }
                    scan(u.target);
                    reply(id, ok().put("m", u.target.lastModified()), null, 0);
                    break;
                }
                case "abort":
                    abort(q.getLong("handle"));
                    reply(id, ok(), null, 0);
                    break;
                case "mkdir": {
                    File dir = resolve(q.getString("path"));
                    if (dir.exists()) {
                        fail(id, "exists");
                    } else if (dir.getParentFile() == null || !dir.getParentFile().isDirectory()) {
                        fail(id, "missing");
                    } else if (dir.mkdir()) {
                        reply(id, ok(), null, 0);
                    } else {
                        fail(id, "io");
                    }
                    break;
                }
                case "delete": {
                    File f = resolve(q.getString("path"));
                    if (f.equals(root)) throw new SecurityException();
                    if (!f.exists()) throw new java.io.FileNotFoundException();
                    if (!delete(f)) throw new IOException("delete");
                    scan(f);
                    reply(id, ok(), null, 0);
                    break;
                }
                case "move": {
                    File from = resolve(q.getString("from")), to = resolve(q.getString("to"));
                    if (from.equals(root) || to.equals(root)) throw new SecurityException();
                    if (!from.exists()) throw new java.io.FileNotFoundException();
                    boolean replaced = to.exists();
                    if (replaced && !q.optBoolean("overwrite", true)) {
                        fail(id, "exists");
                        break;
                    }
                    if (replaced && to.isDirectory() && !delete(to)) throw new IOException("replace");
                    if (!from.renameTo(to)) throw new IOException("rename");
                    scan(from);
                    scan(to);
                    reply(id, ok().put("replaced", replaced), null, 0);
                    break;
                }
                default:
                    fail(id, "unknown");
            }
        } catch (java.io.FileNotFoundException e) {
            fail(id, "missing");
        } catch (SecurityException e) {
            fail(id, "access");
        } catch (Exception e) {
            Log.w("Macindroid", "file request failed: " + e.getClass().getSimpleName()); // no path
            fail(id, "io");
        }
    }

    /** The file, or the part asked for, in chunks; the first says how big the whole file is. */
    private void read(long id, File f, long offset, long length) throws Exception {
        if (!f.isFile()) throw new java.io.FileNotFoundException();
        try (RandomAccessFile in = new RandomAccessFile(f, "r")) {
            long size = in.length();
            offset = Math.max(0, Math.min(offset, size));
            long left = length < 0 ? size - offset : Math.min(length, size - offset);
            in.seek(offset);
            byte[] chunk = new byte[Proto.CHUNK];
            JSONObject head = ok().put("size", size).put("m", f.lastModified()).put("offset", offset).put("length", left);
            do {
                int n = (int) Math.min(chunk.length, left);
                in.readFully(chunk, 0, n);
                left -= n;
                if (!reply(id, head.put("more", left > 0), chunk, n)) return; // the link went
                head = ok();
            } while (left > 0);
        }
    }

    private void abort(long handle) {
        Upload u = uploads.remove(handle);
        if (u == null) return;
        try {
            u.out.close();
        } catch (IOException ignored) {
        }
        u.temp.delete();
    }

    private static boolean delete(File f) {
        if (f.isDirectory() && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) if (!delete(c)) return false;
        }
        return f.delete();
    }

    /** Inside shared storage, whatever the path says ("..", a link out). */
    private File resolve(String path) throws IOException {
        File f = new File(root, path).getCanonicalFile();
        if (!f.equals(root) && !f.getPath().startsWith(root.getPath() + File.separator)) throw new SecurityException();
        return f;
    }

    /** So a photo copied in shows up in the gallery, and one removed leaves it. */
    private void scan(File f) {
        MediaScannerConnection.scanFile(s, new String[] {f.getPath()}, null, null);
    }

    private static JSONObject ok() throws Exception {
        return new JSONObject().put("ok", true);
    }

    private void fail(long id, String error) {
        try {
            reply(id, new JSONObject().put("ok", false).put("error", error), null, 0);
        } catch (Exception ignored) {
        }
    }

    private boolean reply(long id, JSONObject head, byte[] data, int n) throws Exception {
        byte[] json = Proto.utf8(head.put("id", id).toString());
        byte[] length = {(byte) (json.length >>> 24), (byte) (json.length >>> 16), (byte) (json.length >>> 8), (byte) json.length};
        return s.send(Proto.FS_REPLY, Proto.concat(length, json, data == null ? new byte[0] : n == data.length ? data : Arrays.copyOf(data, n)));
    }

    /** The Mac asked for files before "All files access" was given: one notification that opens that switch. */
    private void askForAccess() {
        long now = SystemClock.elapsedRealtime();
        if (askedAt != 0 && now - askedAt < 60_000) return;
        askedAt = now;
        PendingIntent open = PendingIntent.getActivity(s, 6, new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + s.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        s.notify(6, new Notification.Builder(s, LinkService.CH_ALERTS).setSmallIcon(R.drawable.ic_glyph)
                .setContentTitle(Config.macName(s) + " wants to show this phone in Finder")
                .setContentText("Tap, then allow access to all files.")
                .setContentIntent(open).setAutoCancel(true).build());
    }
}
