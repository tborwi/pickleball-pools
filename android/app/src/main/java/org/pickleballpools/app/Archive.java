package org.pickleballpools.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.pickleballpools.core.Match;
import org.pickleballpools.core.Pool;
import org.pickleballpools.core.Tournament;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Two separate things:
 * <ul>
 *   <li><b>Backup files</b> (private): the whole tournament as a .json file, with names, emails, DUPR IDs, pools and
 *       scores. Shared to email or Drive, and imported on any phone (a new phone, or another director taking over).</li>
 *   <li><b>Published results</b> (public): a copy on the club server with short names, pools, scores and standings
 *       only, shown on the Past tournaments web page (server /results) and downloadable as JSON there.</li>
 * </ul>
 */
final class Archive {
    static final int PICK_BACKUP = 9;
    final MainActivity a;

    Archive(MainActivity a) { this.a = a; }

    // ---------- Backup files ----------

    void exportBackup(Tournament t) {
        try {
            JSONObject o = new JSONObject().put("app", "Pickleball Pools").put("kind", "tournament backup").put("version", 1)
                    .put("exported", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date()))
                    .put("tournament", Store.toJson(t));
            File dir = new File(a.getCacheDir(), "sheets");
            dir.mkdirs();
            File[] old = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (old != null) for (File f : old) f.delete();
            String day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
            File f = new File(dir, (t.name.replaceAll("[^A-Za-z0-9 _-]", "").trim() + " backup " + day + ".json").trim());
            try (FileOutputStream os = new FileOutputStream(f)) { os.write(o.toString(1).getBytes(StandardCharsets.UTF_8)); }
            Intent i = new Intent(Intent.ACTION_SEND).setType("application/json")
                    .putExtra(Intent.EXTRA_STREAM, SheetProvider.uri(a, f))
                    .putExtra(Intent.EXTRA_SUBJECT, t.name + " backup")
                    .putExtra(Intent.EXTRA_TEXT, "Pickleball Pools backup of " + t.name + ". It holds players' emails and DUPR IDs: keep it private. "
                            + "To open it: Pickleball Pools → All tournaments → Import a backup file.")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            a.startActivity(Intent.createChooser(i, "Save the backup to email or Drive"));
        } catch (Exception e) {
            a.toast("Couldn't make the backup: " + e.getMessage());
        }
    }

    void pickBackup() {
        a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_BACKUP);
    }

    void onBackup(Uri uri) {
        Tournament t;
        try (InputStream in = a.getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            t = parseBackup(out.toString(StandardCharsets.UTF_8.name()));
        } catch (Exception e) {
            a.toast("That isn't a Pickleball Pools backup file.");
            return;
        }
        // The same online tournament already on this phone: replace it, or keep both (the copy goes offline)
        String sameKey = null;
        for (Object[] e : Store.all(a))
            if (!t.serverId.isEmpty() && t.serverId.equals(((Tournament) e[1]).serverId)) sameKey = (String) e[0];
        if (sameKey == null) { a.create(t); a.toast("Opened " + t.name + " from the backup."); return; }
        String k = sameKey;
        new AlertDialog.Builder(a).setTitle(t.name + " is already on this phone")
                .setMessage("Replace it with the backup, or keep both? A kept copy is offline (no live scores), so the two never mix.")
                .setPositiveButton("Replace", (d, w) -> { Store.delete(a, k); a.create(t); a.toast("Replaced with the backup."); })
                .setNeutralButton("Keep both", (d, w) -> {
                    t.serverId = ""; t.liveSeq = 0; t.resultsId = "";
                    t.name = t.name + " (copy)";
                    a.create(t);
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** A backup file ({"tournament": {...}}), or a bare saved tournament. */
    static Tournament parseBackup(String text) throws Exception {
        JSONObject o = new JSONObject(text);
        JSONObject t = o.optJSONObject("tournament");
        if (t == null && o.has("teams")) t = o;
        if (t == null || !t.has("teams")) throw new Exception("not a backup");
        return Store.fromJson(t);
    }

    // ---------- Published results ----------

    boolean canPublish() { return a.account.api.signedIn() && (a.account.api.has("admin") || a.account.api.has("director")); }

    String url(String id) { return a.account.api.server() + "/results/" + id; }

    /** The public copy: short names, pools, scores and standings (the live page's data), never emails or DUPR IDs. */
    JSONObject results() throws Exception {
        Tournament t = a.t;
        JSONObject s = a.live.snapshot();
        JSONArray ms = new JSONArray(), all = s.optJSONArray("matches");
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject m = all.optJSONObject(i);
            m.remove("aId");
            m.remove("bId");
            ms.put(m);
        }
        JSONObject scores = new JSONObject();
        for (List<Pool> round : List.of(t.round1, t.round2))
            for (Pool p : round) for (Match m : p.matches) {
                if (m.games.isEmpty()) continue;
                JSONArray g = new JSONArray();
                for (int[] x : m.games) g.put(new JSONArray().put(x[0]).put(x[1]));
                scores.put(t.matchKey(p, m), g);
            }
        return new JSONObject().put("name", t.name).put("day", t.date).put("venue", t.venue).put("singles", t.singles)
                .put("pointsToWin", t.pointsToWin).put("winBy", t.winBy)
                .put("standings", s.opt("standings")).put("finalists", s.opt("finalists")).put("matches", ms).put("scores", scores);
    }

    void publish() {
        Tournament t = a.t;
        if (t.round1.isEmpty()) { a.toast("Make the pools first."); return; }
        boolean done = Tournament.complete(t.round1) && !t.round2.isEmpty() && Tournament.complete(t.round2);
        a.confirm(t.resultsId.isEmpty() ? "Publish the results?" : "Update the published results?",
                (done ? "" : "Not every match has a score yet. You can publish now and update it later.\n\n")
                        + "Puts the pools, scores and standings on the club's Past tournaments page, for anyone to see. "
                        + "Players are shown by first name and last initial. No emails or DUPR IDs.", () -> {
                    try {
                        String id = !t.resultsId.isEmpty() ? t.resultsId : !t.serverId.isEmpty() ? t.serverId : java.util.UUID.randomUUID().toString();
                        a.account.api.call("POST", "/results", new JSONObject().put("id", id).put("results", results()), r -> {
                            t.resultsId = r.optString("id", id);
                            a.save();
                            a.refresh();
                            share(t.name, url(t.resultsId), "Published. Share the link?");
                        });
                    } catch (Exception e) { a.toast("Couldn't publish: " + e.getMessage()); }
                });
    }

    void unpublish() {
        Tournament t = a.t;
        a.confirm("Remove the published results?", "Takes " + t.name + " off the Past tournaments page. Nothing on this phone changes.", () -> {
            try {
                a.account.api.call("POST", "/results/remove", new JSONObject().put("id", t.resultsId), r -> {
                    t.resultsId = "";
                    a.save();
                    a.refresh();
                    a.toast("Removed from the Past tournaments page.");
                });
            } catch (Exception ignored) {}
        });
    }

    void share(String name, String link, String title) {
        new AlertDialog.Builder(a).setTitle(title).setMessage(link)
                .setPositiveButton("Share", (d, w) -> a.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, name + " results").putExtra(Intent.EXTRA_TEXT, name + " results:\n" + link), "Share the results link")))
                .setNeutralButton("Open", (d, w) -> pastPage(link))
                .setNegativeButton("Close", null).show();
    }

    void pastPage(String link) {
        a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
    }
}
