package org.pickleballpools.app;

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

/**
 * Shares files with other apps, no library needed: the printable sheets (cache/sheets/*.xlsx, read-only) and the
 * camera's photo of a score sheet (cache/photos/*.jpg, which the camera app writes).
 */
public final class SheetProvider extends ContentProvider {
    static Uri uri(Context c, File f) {
        String dir = f.getParentFile().getName();
        return Uri.parse("content://" + c.getPackageName() + ".sheets/" + ("photos".equals(dir) ? "photos/" : "") + Uri.encode(f.getName()));
    }

    private boolean photo(Uri u) { return u.getPathSegments().size() == 2 && "photos".equals(u.getPathSegments().get(0)); }

    private File file(Uri u) throws FileNotFoundException {
        File dir = new File(getContext().getCacheDir(), photo(u) ? "photos" : "sheets");
        File f = new File(dir, u.getLastPathSegment() == null ? "" : u.getLastPathSegment());
        if (!f.getParentFile().equals(dir) || (!photo(u) && !f.isFile())) throw new FileNotFoundException(u.toString());
        return f;
    }

    @Override public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        if (photo(u) && mode.contains("w")) {
            File f = file(u);
            f.getParentFile().mkdirs();
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        return ParcelFileDescriptor.open(file(u), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri u, String[] proj, String sel, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try { File f = file(u); c.addRow(new Object[]{f.getName(), f.length()}); } catch (FileNotFoundException ignored) {}
        return c;
    }

    @Override public String getType(Uri u) { return photo(u) ? "image/jpeg" : String.valueOf(u.getLastPathSegment()).endsWith(".json") ? "application/json"
            : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; }
    @Override public boolean onCreate() { return true; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
