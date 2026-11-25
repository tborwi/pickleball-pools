package org.pickleballpools.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * A court captain's screen: the matches on their courts, round by round, and a big score form for each.
 * The pools come from the director's phone through the server; the last copy is kept so the screen still
 * works without signal, and scores entered then are sent when the connection is back (Live).
 */
final class CaptainScreen {
    final MainActivity a;
    final Live live;
    final SharedPreferences cache;
    String id = "", name = "";
    long lastSeq;

    CaptainScreen(MainActivity a, Live live) {
        this.a = a;
        this.live = live;
        cache = a.getSharedPreferences("captain", Context.MODE_PRIVATE);
    }

    void open(String tournamentId, String tournamentName) {
        id = tournamentId;
        name = tournamentName;
        a.go(this::show, true);
    }

    JSONObject state() {
        try { return new JSONObject(cache.getString(id, "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    void show() {
        live.extra = this::fetch; // refresh every 20 s while this screen is open
        a.page.removeAllViews();
        a.title(name);
        JSONObject s = state();
        JSONArray courts = s.optJSONArray("courts");
        a.note(courts == null ? "All courts" : "Your courts: " + join(courts));
        int waiting = live.pending(id).length();
        if (waiting > 0) a.text(waiting + (waiting == 1 ? " score is" : " scores are") + " waiting for signal; it will send by itself.", 16, true, MainActivity.RED);
        JSONObject data = s.optJSONObject("data");
        if (data == null) {
            a.note(s.length() == 0 ? "Loading…" : "The director hasn't put the pools online yet. This screen checks again every 20 seconds.");
            fetch();
            return;
        }
        JSONObject scores = s.optJSONObject("scores");
        JSONObject q = live.pending(id);
        a.note("Games to " + data.optInt("pointsToWin", 11) + ", win by " + data.optInt("winBy", 2) + ". Tap a match to enter its score.");
        JSONArray ms = data.optJSONArray("matches");
        String group = "";
        for (int i = 0; ms != null && i < ms.length(); i++) {
            JSONObject m = ms.optJSONObject(i);
            String g = m.optString("stage") + ", round " + m.optInt("round");
            if (!g.equals(group)) { group = g; a.heading(g); }
            String key = m.optString("key");
            List<int[]> games = Live.games(q.has(key) ? q.optJSONArray(key) : scores == null ? null : scores.optJSONArray(key));
            String txt = "Court " + m.optString("court") + " · " + m.optString("pool") + "\n" + m.optString("a") + "\n   vs  "
                    + m.optString("b") + "\n   " + (games.isEmpty() ? "not played yet" : text(games)) + (q.has(key) ? "  (sending…)" : "");
            TextView row = a.text(txt, 18, false, Color.BLACK);
            a.card(row).setBackground(a.box(games.isEmpty() ? 0xFFFFF8E1 : MainActivity.LIGHT));
            JSONArray rc = receipts(s, key);
            if (rc.length() > 0) row.append("\n   📷 score sheet photo sent");
            Runnable photo = () -> a.receipts.take(id, key);
            Runnable enter = () -> a.scoreDialog(m.optString("a"), m.optString("b"), games,
                    data.optInt("pointsToWin", 11), data.optInt("winBy", 2), null, entered -> {
                        live.send(id, key, entered);
                        a.refresh();
                        if (!entered.isEmpty()) a.confirm("Take a photo of the score sheet?",
                                "A picture of the paper sheet with your initials, as the receipt for this match.", photo);
                    });
            row.setOnClickListener(v -> {
                if (games.isEmpty() && rc.length() == 0) { enter.run(); return; }
                String[] opts = rc.length() > 0
                        ? new String[]{"Enter or change the score", "📷 Take another photo", "See the photo"}
                        : new String[]{"Enter or change the score", "📷 Take a photo of the score sheet"};
                new android.app.AlertDialog.Builder(a).setTitle(m.optString("a") + "  vs  " + m.optString("b"))
                        .setItems(opts, (d, w) -> {
                            if (w == 0) enter.run();
                            else if (w == 1) photo.run();
                            else a.receipts.view(id, rc.optJSONObject(rc.length() - 1).optString("id"), "Score sheet, court " + m.optString("court"));
                        }).show();
            });
        }
        int photos = a.receipts.waiting();
        if (photos > 0) a.text(photos + " photo" + (photos == 1 ? "" : "s") + " waiting for signal; will send by itself.", 16, true, MainActivity.RED);
        a.button("📊 All live scores", v -> a.liveScreen.open(id, name));
        a.smallButton("Refresh now", v -> fetch());
        if (s.length() > 0 && lastSeq == 0) fetch();
    }

    /** Downloads the pools and every score for my courts, then redraws if anything changed. */
    void fetch() {
        String tid = id;
        live.api.call("GET", "/tournaments/" + tid + "/live", null, r -> {
            try {
                JSONObject s = new JSONObject();
                s.put("data", r.opt("data"));
                s.put("courts", r.opt("courts"));
                s.put("receipts", r.opt("receipts"));
                JSONObject sc = new JSONObject();
                JSONArray arr = r.optJSONArray("scores");
                for (int i = 0; arr != null && i < arr.length(); i++) sc.put(arr.optJSONObject(i).optString("key"), arr.optJSONObject(i).optJSONArray("games"));
                s.put("scores", sc);
                lastSeq = Math.max(1, r.optLong("seq"));
                boolean changed = !s.toString().equals(cache.getString(tid, ""));
                cache.edit().putString(tid, s.toString()).apply();
                if (changed && tid.equals(id) && live.extra != null) a.refresh();
            } catch (Exception ignored) {}
        }, (net, msg) -> { if (!net) a.toast(msg); });
    }

    static JSONArray receipts(JSONObject s, String key) {
        JSONArray out = new JSONArray(), all = s.optJSONArray("receipts");
        for (int i = 0; all != null && i < all.length(); i++) if (key.equals(all.optJSONObject(i).optString("key"))) out.put(all.optJSONObject(i));
        return out;
    }

    static String text(List<int[]> games) {
        StringBuilder b = new StringBuilder();
        for (int[] g : games) b.append(b.length() == 0 ? "" : ", ").append(g[0]).append('-').append(g[1]);
        return b.toString();
    }

    static String join(JSONArray a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length(); i++) b.append(i == 0 ? "" : ", ").append(a.optString(i));
        return b.toString();
    }
}
