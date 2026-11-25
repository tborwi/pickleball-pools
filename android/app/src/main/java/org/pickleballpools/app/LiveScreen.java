package org.pickleballpools.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.pickleballpools.core.Match;
import org.pickleballpools.core.Pool;
import org.pickleballpools.core.Standings;
import org.pickleballpools.core.Team;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Live scores for players (and anyone on the tournament's staff): your own matches first, with the photo receipts
 * of the score sheets, then every pool's standings and scores. Standings are worked out on the phone with the same
 * rules as the director's (core/Standings). Refreshes every 20 seconds while open.
 */
final class LiveScreen {
    final MainActivity a;
    final Live live;
    final SharedPreferences cache;
    String id = "", name = "";

    LiveScreen(MainActivity a, Live live) {
        this.a = a;
        this.live = live;
        cache = a.getSharedPreferences("livescores", Context.MODE_PRIVATE);
    }

    void open(String tournamentId, String tournamentName) {
        id = tournamentId;
        name = tournamentName;
        a.go(this::show, true);
    }

    void show() {
        live.extra = this::fetch;
        a.page.removeAllViews();
        a.title(name);
        JSONObject r;
        try { r = new JSONObject(cache.getString(id, "{}")); } catch (Exception e) { r = new JSONObject(); }
        JSONObject data = r.optJSONObject("data");
        if (data == null) {
            a.note(r.length() == 0 ? "Loading…" : "The pools aren't online yet. This screen checks every 20 seconds.");
            if (r.length() == 0) fetch();
            return;
        }
        a.note("Live: updates by itself every 20 seconds.");
        Map<String, List<int[]>> scores = new HashMap<>();
        JSONArray sc = r.optJSONArray("scores");
        for (int i = 0; sc != null && i < sc.length(); i++) scores.put(sc.optJSONObject(i).optString("key"), Live.games(sc.optJSONObject(i).optJSONArray("games")));
        Map<String, JSONArray> receipts = new HashMap<>();
        JSONArray rs = r.optJSONArray("receipts");
        for (int i = 0; rs != null && i < rs.length(); i++) {
            JSONObject x = rs.optJSONObject(i);
            receipts.computeIfAbsent(x.optString("key"), k -> new JSONArray()).put(x);
        }
        Set<String> mine = new HashSet<>();
        JSONArray my = r.optJSONArray("myTeams");
        for (int i = 0; my != null && i < my.length(); i++) mine.add(my.optString(i));

        JSONArray ms = data.optJSONArray("matches");
        if (!mine.isEmpty()) {
            a.heading("Your matches");
            for (int i = 0; ms != null && i < ms.length(); i++) {
                JSONObject m = ms.optJSONObject(i);
                if (mine.contains(m.optString("aId")) || mine.contains(m.optString("bId"))) match(m, scores, receipts, true);
            }
        }
        JSONArray pools = data.optJSONArray("pools");
        for (int i = 0; pools != null && i < pools.length(); i++) {
            JSONObject pj = pools.optJSONObject(i);
            String title = pj.optString("title"), stage = pj.optString("stage");
            a.heading(stage + " · " + title);
            Pool p = new Pool(pj.optString("name"), title.replace("Pool " + pj.optString("name"), "").trim());
            Map<String, Team> teams = new HashMap<>();
            JSONArray ts = pj.optJSONArray("teams");
            for (int k = 0; ts != null && k < ts.length(); k++) {
                JSONObject tj = ts.optJSONObject(k);
                Team x = new Team(tj.optString("id"));
                x.name = tj.optString("label");
                x.dupr1 = tj.optDouble("dupr", 0);
                x.withdrawn = tj.optBoolean("withdrawn");
                if (x.withdrawn) p.voided.add(x.id);
                teams.put(x.id, x);
                p.teamIds.add(x.id);
            }
            for (int k = 0; ms != null && k < ms.length(); k++) {
                JSONObject m = ms.optJSONObject(k);
                if (!title.equals(m.optString("pool")) || !stage.equals(m.optString("stage"))) continue;
                Match mm = new Match(m.optString("aId"), m.optString("bId"), m.optInt("round"));
                List<int[]> g = scores.get(m.optString("key"));
                if (g != null) mm.games.addAll(g);
                if (teams.containsKey(mm.a) && teams.containsKey(mm.b)) p.matches.add(mm);
            }
            StringBuilder st = new StringBuilder();
            for (Standings.Row row : Standings.of(p, teams))
                st.append(String.format(Locale.US, "%s %s   %d-%d   %+d%s\n", row.withdrawn ? "–" : row.place + ".",
                        row.team.label(), row.wins, row.losses, row.diff(), mine.contains(row.team.id) ? "  ⭐" : ""));
            a.card(a.text(st.toString().trim(), 17, false, Color.BLACK));
            for (int k = 0; ms != null && k < ms.length(); k++) {
                JSONObject m = ms.optJSONObject(k);
                if (title.equals(m.optString("pool")) && stage.equals(m.optString("stage"))) match(m, scores, receipts, false);
            }
        }
        a.smallButton("Refresh now", v -> fetch());
    }

    void match(JSONObject m, Map<String, List<int[]>> scores, Map<String, JSONArray> receipts, boolean big) {
        List<int[]> g = scores.get(m.optString("key"));
        JSONArray rc = receipts.get(m.optString("key"));
        String s = "Round " + m.optInt("round") + " · court " + m.optString("court") + (big ? " · " + m.optString("pool") : "")
                + "\n" + m.optString("a") + "\n   vs  " + m.optString("b") + "\n   "
                + (g == null || g.isEmpty() ? "not played yet" : CaptainScreen.text(g))
                + (rc == null ? "" : "\n   📷 " + rc.length() + " score sheet photo" + (rc.length() == 1 ? "" : "s") + ": tap to see");
        TextView v = a.text(s, big ? 18 : 16, false, Color.BLACK);
        a.card(v).setBackground(a.box(g == null || g.isEmpty() ? 0xFFFFF8E1 : MainActivity.LIGHT));
        if (rc != null) v.setOnClickListener(x -> {
            JSONObject last = rc.optJSONObject(rc.length() - 1);
            a.receipts.view(id, last.optString("id"), "Score sheet, court " + m.optString("court") + " (sent by " + last.optString("by") + ")");
        });
    }

    void fetch() {
        String tid = id;
        live.api.call("GET", "/tournaments/" + tid + "/live?view=all", null, r -> {
            boolean changed = !r.toString().equals(cache.getString(tid, ""));
            cache.edit().putString(tid, r.toString()).apply();
            if (changed && tid.equals(id) && live.extra != null) a.refresh();
        }, (net, msg) -> { if (!net) a.toast(msg); });
    }
}
