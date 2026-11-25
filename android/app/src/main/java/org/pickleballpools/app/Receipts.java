package org.pickleballpools.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.MediaStore;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

/**
 * Photo receipts: a court captain photographs the paper score sheet (with their initials) after a match.
 * The phone's camera app takes the picture; it is shrunk to about 1600 px (a few hundred KB), saved in a queue
 * folder and uploaded, so photos taken without signal are sent later. Anyone allowed (director, that court's
 * captain, the players in that match) can open it.
 */
final class Receipts {
    static final int TAKE_PHOTO = 8;

    final MainActivity a;
    final Api api;
    String pendingTid = "", pendingKey = "";

    Receipts(MainActivity a, Api api) { this.a = a; this.api = api; }

    File shot() { return new File(new File(a.getCacheDir(), "photos"), "shot.jpg"); }

    File queue() {
        File d = new File(a.getFilesDir(), "receipts");
        d.mkdirs();
        return d;
    }

    /** Opens the camera for this match's score sheet. */
    void take(String tournamentId, String matchKey) {
        pendingTid = tournamentId;
        pendingKey = matchKey;
        File f = shot();
        f.getParentFile().mkdirs();
        f.delete();
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, SheetProvider.uri(a, f))
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivityForResult(i, TAKE_PHOTO);
        } catch (Exception e) {
            a.toast("No camera app found on this phone.");
        }
    }

    /** Back from the camera: shrink, queue, upload. */
    void onPhoto(boolean ok) {
        File f = shot();
        if (!ok || !f.isFile() || f.length() == 0 || pendingKey.isEmpty()) return;
        try {
            byte[] jpeg = shrink(f);
            String name = System.currentTimeMillis() + "__" + pendingTid + "__" + Uri.encode(pendingKey) + ".jpg";
            try (FileOutputStream os = new FileOutputStream(new File(queue(), name))) { os.write(jpeg); }
            f.delete();
            a.toast("Photo saved. Sending…");
            flush();
        } catch (Exception e) {
            a.toast("Couldn't use that photo: " + e.getMessage());
        }
    }

    static byte[] shrink(File f) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) throw new Exception("not a picture");
        float scale = 1600f / Math.max(b.getWidth(), b.getHeight());
        Matrix m = new Matrix();
        if (scale < 1) m.postScale(scale, scale);
        int rot = new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        if (rot == ExifInterface.ORIENTATION_ROTATE_90) m.postRotate(90);
        else if (rot == ExifInterface.ORIENTATION_ROTATE_180) m.postRotate(180);
        else if (rot == ExifInterface.ORIENTATION_ROTATE_270) m.postRotate(270);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        r.compress(Bitmap.CompressFormat.JPEG, 80, out);
        return out.toByteArray();
    }

    int waiting() {
        String[] n = queue().list();
        return n == null ? 0 : n.length;
    }

    boolean busy;

    /** Uploads queued photos one at a time; stops at the first network failure (Live tries again every 20 s). */
    void flush() {
        if (busy || !api.signedIn()) return;
        File[] fs = queue().listFiles();
        if (fs == null || fs.length == 0) return;
        java.util.Arrays.sort(fs);
        File f = fs[0];
        String[] parts = f.getName().replaceAll("\\.jpg$", "").split("__", 3);
        if (parts.length < 3) { f.delete(); flush(); return; }
        byte[] jpeg;
        try { jpeg = Files.readAllBytes(f.toPath()); } catch (Exception e) { f.delete(); return; }
        busy = true;
        api.postJpeg("/tournaments/" + parts[1] + "/receipts?key=" + parts[2], jpeg, r -> {
            busy = false;
            f.delete();
            a.toast("Photo receipt sent");
            a.refresh();
            flush();
        }, (net, msg) -> {
            busy = false;
            if (net) return;
            f.delete(); // refused (not your court, pools changed): don't retry forever
            a.toast("Photo not sent: " + msg);
        });
    }

    /** Downloads and shows a receipt photo, full width, pinch-free (scrolls). */
    void view(String tournamentId, String receiptId, String caption) {
        a.toast("Opening photo…");
        api.getBytes("/tournaments/" + tournamentId + "/receipts/" + receiptId, b -> {
            Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length);
            if (bmp == null) { a.toast("Couldn't open the photo"); return; }
            ImageView v = new ImageView(a);
            v.setImageBitmap(bmp);
            v.setAdjustViewBounds(true);
            v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            LinearLayout box = new LinearLayout(a);
            box.addView(v);
            new AlertDialog.Builder(a).setTitle(caption).setView(a.scroll(box)).setPositiveButton("Close", null).show();
        }, (net, msg) -> a.toast(msg));
    }
}
