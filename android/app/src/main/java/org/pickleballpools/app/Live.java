package org.pickleballpools.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.pickleballpools.core.Match;
import org.pickleballpools.core.Pool;
import org.pickleballpools.core.PrintSheets;
import org.pickleballpools.core.Team;
import org.pickleballpools.core.Tournament;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Live scoring with court captains, through the accounts server.
 * Director's phone: publishes the pools (matches, team names, courts), sends its own score edits and pulls the
 * captains' scores every 20 seconds while the app is open. Captain's phone: CaptainScreen.
 * Scores that can't be sent (no signal) wait in a queue on the phone and go out when the connection is back.
 */
final class Live {
    static final long EVERY_MS = 20_000;

    final MainActivity a;
    final Api api;
    final SharedPreferences prefs;
    final Handler h = new Handler(Looper.getMainLooper());
    Runnable extra; // the captain screen's own refresh, while it is open
    boolean running, busy;

    Live(MainActivity a, Api api) {
        this.a = a;
        this.api = api;
        prefs = a.getSharedPreferences("live", Context.MODE_PRIVATE);
    }

    /** This phone runs an online tournament as its director (or admin). */
    boolean directing() {
        return api.signedIn() && !a.t.serverId.isEmpty() && (api.has("admin") || api.has("director"));
    }

    void start() {
        if (running) return;
        running = true;
        h.post(tick);
    }

    void stop() {
        running = false;
        h.removeCallbacks(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            syncNow();
            h.postDelayed(this, EVERY_MS);
        }
    };

    void syncNow() {
        if (busy) return;
        if (directing()) {
            busy = true;
            flush(() -> publish(() -> pull(() -> busy = false)));
        } else flush(() -> {});
        a.receipts.flush();
        if (extra != null) extra.run();
    }

    // ---------- director: publish pools ----------

    JSONObject snapshot() {
        Tournament t = a.t;
        JSONObject d = new JSONObject();
        try {
            d.put("name", t.name).put("singles", t.singles).put("pointsToWin", t.pointsToWin).put("winBy", t.winBy).put("startedAt", t.startedAt);
            JSONArray ms = new JSONArray();
            Map<String, Team> ids = t.byId();
            Map<String, String> sn = org.pickleballpools.core.Names.forTournament(t); // short public names
            for (List<Pool> round : List.of(t.round1, t.round2)) {
                Map<Match, Integer> court = PrintSheets.courtOf(t, round);
                for (Pool p : round) for (Match m : p.matches) {
                    if (p.voided.contains(m.a) || p.voided.contains(m.b)) continue;
                    ms.put(new JSONObject().put("key", t.matchKey(p, m)).put("court", court.get(m))
                            .put("aId", m.a).put("bId", m.b)
                            .put("round", m.round).put("stage", round == t.round2 ? "Round 2" : "Round 1")
                            .put("pool", p.title())
                            .put("a", (p.teamIds.indexOf(m.a) + 1) + "  " + PrintSheets.players(sn, ids.get(m.a)))
                            .put("b", (p.teamIds.indexOf(m.b) + 1) + "  " + PrintSheets.players(sn, ids.get(m.b))));
                }
            }
            d.put("matches", ms);
            // Pools (for players' standings) and who plays on each team (only the server and directors see "who")
            JSONArray pools = new JSONArray();
            JSONObject who = new JSONObject();
            for (List<Pool> round : List.of(t.round1, t.round2))
                for (Pool p : round) {
                    JSONArray teams = new JSONArray();
                    for (String tid : p.teamIds) {
                        Team x = ids.get(tid);
                        teams.put(new JSONObject().put("id", tid).put("label", PrintSheets.players(sn, x))
                                .put("dupr", x.combined()).put("withdrawn", p.voided.contains(tid)));
                        if (!who.has(tid)) who.put(tid, identities(x).put("players", players(x, sn)));
                    }
                    pools.put(new JSONObject().put("stage", round == t.round2 ? "Round 2" : "Round 1").put("title", p.title())
                            .put("name", p.name).put("teams", teams));
                }
            d.put("pools", pools).put("who", who);
            // Standings for the public live page and the results email, worked out here with the tiebreak rules
            JSONArray standings = new JSONArray();
            for (List<Pool> round : List.of(t.round1, t.round2))
                for (Pool p : round) {
                    JSONArray rows = new JSONArray();
                    for (org.pickleballpools.core.Standings.Row r : org.pickleballpools.core.Standings.of(p, ids))
                        rows.put(new JSONObject().put("place", r.withdrawn ? "–" : String.valueOf(r.place)).put("label", PrintSheets.players(sn, r.team))
                                .put("w", r.wins).put("l", r.losses).put("diff", r.diff()));
                    standings.put(new JSONObject().put("stage", round == t.round2 ? "Round 2" : "Round 1").put("title", p.title()).put("rows", rows));
                }
            JSONArray fin = new JSONArray();
            for (Team f : t.finalists()) fin.put(PrintSheets.players(sn, f));
            d.put("standings", standings).put("finalists", fin);
        } catch (Exception ignored) {}
        return d;
    }

    /** DUPR IDs (from registrations) and emails of a team's players, so players can find their own matches. */
    JSONObject identities(Team x) throws org.json.JSONException {
        JSONArray dupr = new JSONArray(), email = new JSONArray();
        for (String id : x.duprIds()) {
            dupr.put(id);
            for (org.pickleballpools.core.Player pl : a.t.players)
                if (org.pickleballpools.core.Player.normId(pl.duprId).equals(id)
                        && pl.email != null && !pl.email.isBlank()) email.put(pl.email.trim().toLowerCase(java.util.Locale.ROOT));
        }
        for (String e : new String[]{x.email1, x.email2})
            if (e != null && e.contains("@")) email.put(e.trim().toLowerCase(java.util.Locale.ROOT));
        return new JSONObject().put("dupr", dupr).put("email", email);
    }

    /** Each player's public name, email and number (when another player has the same full name), for emails. */
    JSONArray players(Team x, Map<String, String> sn) throws org.json.JSONException {
        JSONArray out = new JSONArray();
        for (int slot = 1; slot <= 2; slot++) {
            String shown = org.pickleballpools.core.Names.player(sn, x, slot);
            if (slot == 2 && shown.isEmpty()) continue; // singles
            String email = slot == 1 ? x.email1 : x.email2, id = org.pickleballpools.core.Player.normId(slot == 1 ? x.duprId1 : x.duprId2);
            if ((email == null || email.isBlank()) && !id.isEmpty())
                for (org.pickleballpools.core.Player pl : a.t.players)
                    if (org.pickleballpools.core.Player.normId(pl.duprId).equals(id) && pl.email != null) email = pl.email;
            out.put(new JSONObject().put("name", shown).put("email", email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT))
                    .put("number", org.pickleballpools.core.Names.number(shown)));
        }
        return out;
    }

    /** Sends the pools when they changed since the last time (new pools, redo, withdrawal, rename...). */
    void publish(Runnable then) {
        if (!a.t.started()) { then.run(); return; } // captains see the pools once the director presses Start
        String text = snapshot().toString();
        String k = "published:" + a.t.serverId;
        if (text.equals(prefs.getString(k, ""))) { then.run(); return; }
        try {
            api.call("PUT", "/tournaments/" + a.t.serverId + "/live", new JSONObject().put("data", new JSONObject(text)),
                    r -> { prefs.edit().putString(k, text).apply(); then.run(); }, (net, msg) -> then.run());
        } catch (Exception e) { then.run(); }
    }

    /** Applies scores entered on other phones since the last pull. */
    void pull(Runnable then) {
        Tournament t = a.t;
        String id = t.serverId;
        api.call("GET", "/tournaments/" + id + "/live?since=" + t.liveSeq, null, r -> {
            if (!id.equals(a.t.serverId)) { then.run(); return; }
            JSONArray sc = r.optJSONArray("scores");
            int changed = 0;
            if (sc != null) for (int i = 0; i < sc.length(); i++) {
                JSONObject x = sc.optJSONObject(i);
                if (pending(id).has(x.optString("key"))) continue; // our own newer edit is still on its way
                Match m = find(x.optString("key"));
                if (m == null) continue;
                List<int[]> games = games(x.optJSONArray("games"));
                if (same(m.games, games)) continue;
                m.games.clear();
                m.games.addAll(games);
                changed++;
            }
            t.liveSeq = Math.max(t.liveSeq, r.optLong("seq"));
            receiptCounts(r.optJSONArray("receipts"));
            a.save();
            if (changed > 0) {
                a.toast(changed == 1 ? "1 new score from a court captain" : changed + " new scores from court captains");
                a.refresh();
            }
            then.run();
        }, (net, msg) -> then.run());
    }

    /** Photo receipts per match key, for the director's pool screen. */
    void receiptCounts(JSONArray rs) {
        if (rs == null) return;
        JSONObject byKey = new JSONObject();
        try {
            for (int i = 0; i < rs.length(); i++) {
                JSONObject x = rs.optJSONObject(i);
                JSONArray l = byKey.optJSONArray(x.optString("key"));
                if (l == null) byKey.put(x.optString("key"), l = new JSONArray());
                l.put(x.optString("id"));
            }
        } catch (Exception ignored) {}
        prefs.edit().putString("receipts:" + a.t.serverId, byKey.toString()).apply();
    }

    JSONArray receiptsFor(String key) {
        try { return new JSONObject(prefs.getString("receipts:" + a.t.serverId, "{}")).optJSONArray(key); } catch (Exception e) { return null; }
    }

    /** Tells the server's audit log about Start / Unlock (only this phone knows). */
    void event(String action) {
        if (!directing()) return;
        try { api.call("POST", "/tournaments/" + a.t.serverId + "/events", new JSONObject().put("action", action), r -> {}, (n, m) -> {}); }
        catch (Exception ignored) {}
    }

    Match find(String key) {
        for (List<Pool> round : List.of(a.t.round1, a.t.round2))
            for (Pool p : round) for (Match m : p.matches) if (a.t.matchKey(p, m).equals(key)) return m;
        return null;
    }

    // ---------- sending scores (director or captain) ----------

    /** Queues a score for a match of an online tournament and tries to send it right away. */
    void send(String tournamentId, String key, List<int[]> games) {
        JSONObject q = pending(tournamentId);
        try { q.put(key, json(games)); } catch (Exception ignored) {}
        prefs.edit().putString("pending:" + tournamentId, q.toString()).apply();
        flush(() -> {});
    }

    JSONObject pending(String tournamentId) {
        try { return new JSONObject(prefs.getString("pending:" + tournamentId, "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    int waiting() {
        int n = 0;
        for (String k : prefs.getAll().keySet()) if (k.startsWith("pending:")) n += pending(k.substring(8)).length();
        return n;
    }

    /** Sends every queued score, one at a time. Stops at the first network failure (tries again next tick). */
    void flush(Runnable then) {
        if (!api.signedIn()) { then.run(); return; }
        for (String k : prefs.getAll().keySet()) {
            if (!k.startsWith("pending:")) continue;
            String tid = k.substring(8);
            JSONObject q = pending(tid);
            Iterator<String> it = q.keys();
            if (!it.hasNext()) continue;
            String key = it.next();
            JSONArray g = q.optJSONArray(key);
            try {
                api.call("POST", "/tournaments/" + tid + "/scores", new JSONObject().put("key", key).put("games", g),
                        r -> { drop(tid, key, g); flush(then); },
                        (net, msg) -> {
                            if (net) { then.run(); return; }
                            drop(tid, key, g); // the server refused it (not your court, pools changed...): don't retry forever
                            a.toast("Score not sent: " + msg);
                            flush(then);
                        });
            } catch (Exception e) { then.run(); }
            return;
        }
        then.run();
    }

    private void drop(String tid, String key, JSONArray sent) {
        JSONObject q = pending(tid);
        if (q.has(key) && q.optJSONArray(key).toString().equals(sent.toString())) q.remove(key); // unless edited again meanwhile
        prefs.edit().putString("pending:" + tid, q.toString()).apply();
    }

    // ---------- helpers ----------

    static JSONArray json(List<int[]> games) {
        JSONArray a = new JSONArray();
        for (int[] g : games) a.put(new JSONArray().put(g[0]).put(g[1]));
        return a;
    }

    static List<int[]> games(JSONArray a) {
        List<int[]> out = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONArray g = a.optJSONArray(i);
            if (g != null) out.add(new int[]{g.optInt(0), g.optInt(1)});
        }
        return out;
    }

    static boolean same(List<int[]> x, List<int[]> y) {
        if (x.size() != y.size()) return false;
        for (int i = 0; i < x.size(); i++) if (x.get(i)[0] != y.get(i)[0] || x.get(i)[1] != y.get(i)[1]) return false;
        return true;
    }
}
