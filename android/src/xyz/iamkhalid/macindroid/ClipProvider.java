package xyz.iamkhalid.macindroid;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Comparator;

/** Serves images received from the Mac so other apps can paste them. Keeps only the last few. */
public class ClipProvider extends ContentProvider {
    static final String AUTHORITY = "xyz.iamkhalid.macindroid.clips";
    private static final int KEEP = 3;

    static Uri store(Context context, byte[] data) {
        File dir = new File(context.getCacheDir(), "clips");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        String name = "clip-" + System.currentTimeMillis() + "." + Proto.extension(Proto.imageMime(data));
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            out.write(data);
        } catch (Exception e) {
            return null;
        }
        File[] files = dir.listFiles();
        if (files != null && files.length > KEEP) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            for (int i = KEEP; i < files.length; i++) files[i].delete();
        }
        return Uri.parse("content://" + AUTHORITY + "/" + name);
    }

    private File file(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) throw new FileNotFoundException(String.valueOf(uri));
        File f = new File(new File(getContext().getCacheDir(), "clips"), name);
        if (!f.isFile()) throw new FileNotFoundException(String.valueOf(uri));
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        String name = String.valueOf(uri.getLastPathSegment());
        if (name.endsWith(".jpg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        return "image/png";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("read-only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        File f;
        try {
            f = file(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] columns = projection != null ? projection : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = f.length();
        }
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
