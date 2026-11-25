package org.pickleballpools.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pickleballpools.core.Match;
import org.pickleballpools.core.Player;
import org.pickleballpools.core.Pool;
import org.pickleballpools.core.Team;
import org.pickleballpools.core.Tournament;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Saves the tournament as one JSON file in the app's private storage after every change. */
final class Store {
    private Store() {}

    static File file(Context c) { return new File(c.getFilesDir(), "tournament.json"); }

    static Tournament load(Context c) {
        try {
            File f = file(c);
            if (f.exists()) return fromJson(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            android.util.Log.e("Store", "load failed", e);
        }
        return new Tournament();
    }

    static void save(Context c, Tournament t) {
        File f = file(c), tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(toJson(t).toString(1).getBytes(StandardCharsets.UTF_8));
            o.getFD().sync();
        } catch (Exception e) {
            throw new RuntimeException("Could not save", e);
        }
        if (!tmp.renameTo(f)) throw new RuntimeException("Could not save");
    }

    static JSONObject toJson(Tournament t) throws JSONException {
        JSONObject o = new JSONObject()
                .put("name", t.name).put("poolCount", t.poolCount).put("advancePerPool", t.advancePerPool)
                .put("finalistsPerPool", t.finalistsPerPool).put("pointsToWin", t.pointsToWin)
                .put("winBy", t.winBy).put("nextId", t.nextId).put("paymentLink", t.paymentLink)
                .put("serverId", t.serverId).put("liveSeq", t.liveSeq).put("startedAt", t.startedAt).put("firstCourt", t.firstCourt).put("round1When", t.round1When).put("round2When", t.round2When);
        if (t.importColumns != null) {
            JSONArray c = new JSONArray();
            for (int i : t.importColumns) c.put(i);
            o.put("importColumns", c);
        }
        JSONArray players = new JSONArray();
        for (Player p : t.players)
            players.put(new JSONObject().put("ref", p.ref).put("name", p.name).put("email", p.email).put("duprId", p.duprId)
                    .put("partnerName", p.partnerName).put("partnerDuprId", p.partnerDuprId)
                    .put("rating", p.rating).put("ratingDate", p.ratingDate));
        o.put("players", players);
        JSONArray teams = new JSONArray();
        for (Team x : t.teams)
            teams.put(new JSONObject().put("id", x.id).put("name", x.name).put("player1", x.player1)
                    .put("player2", x.player2).put("dupr1", x.dupr1).put("dupr2", x.dupr2).put("regKey", x.regKey).put("withdrawn", x.withdrawn));
        return o.put("teams", teams).put("round1", pools(t.round1)).put("round2", pools(t.round2));
    }

    private static JSONArray pools(List<Pool> pools) throws JSONException {
        JSONArray a = new JSONArray();
        for (Pool p : pools) {
            JSONArray ms = new JSONArray();
            for (Match m : p.matches) {
                JSONArray g = new JSONArray();
                for (int[] s : m.games) g.put(new JSONArray().put(s[0]).put(s[1]));
                JSONObject mj = new JSONObject().put("a", m.a).put("b", m.b).put("round", m.round).put("games", g);
                ms.put(mj);
            }
            a.put(new JSONObject().put("name", p.name).put("bracket", p.bracket)
                    .put("teams", new JSONArray(p.teamIds)).put("matches", ms).put("voided", new JSONArray(p.voided)));
        }
        return a;
    }

    static Tournament fromJson(JSONObject o) throws JSONException {
        Tournament t = new Tournament();
        t.name = o.optString("name", t.name);
        t.poolCount = o.optInt("poolCount", t.poolCount);
        t.advancePerPool = o.optInt("advancePerPool", t.advancePerPool);
        t.finalistsPerPool = o.optInt("finalistsPerPool", t.finalistsPerPool);
        t.pointsToWin = o.optInt("pointsToWin", t.pointsToWin);
        t.winBy = o.optInt("winBy", t.winBy);
        t.nextId = o.optInt("nextId", 1);
        t.paymentLink = o.optString("paymentLink");
        t.serverId = o.optString("serverId");
        t.liveSeq = o.optLong("liveSeq");
        t.startedAt = o.optLong("startedAt");
        t.firstCourt = o.optInt("firstCourt", 1);
        t.round1When = o.optString("round1When");
        t.round2When = o.optString("round2When");
        JSONArray cols = o.optJSONArray("importColumns");
        if (cols != null) {
            t.importColumns = new int[cols.length()];
            for (int i = 0; i < cols.length(); i++) t.importColumns[i] = cols.getInt(i);
        }
        JSONArray players = o.optJSONArray("players");
        for (int i = 0; players != null && i < players.length(); i++) {
            JSONObject j = players.getJSONObject(i);
            Player p = new Player();
            p.ref = j.optString("ref"); p.name = j.optString("name"); p.email = j.optString("email");
            p.duprId = j.optString("duprId"); p.partnerName = j.optString("partnerName");
            p.partnerDuprId = j.optString("partnerDuprId");
            p.rating = j.optDouble("rating", 0); p.ratingDate = j.optString("ratingDate");
            t.players.add(p);
        }
        JSONArray teams = o.optJSONArray("teams");
        for (int i = 0; teams != null && i < teams.length(); i++) {
            JSONObject j = teams.getJSONObject(i);
            Team x = new Team(j.getString("id"));
            x.name = j.optString("name");
            x.player1 = j.optString("player1");
            x.player2 = j.optString("player2");
            x.dupr1 = j.optDouble("dupr1", 0);
            x.dupr2 = j.optDouble("dupr2", 0);
            x.regKey = j.optString("regKey");
            x.withdrawn = j.optBoolean("withdrawn");
            t.teams.add(x);
        }
        readPools(o.optJSONArray("round1"), t.round1);
        readPools(o.optJSONArray("round2"), t.round2);
        // Saved before "Start" existed (1.7 and older): a tournament with scores is already under way
        if (t.startedAt == 0 && (org.pickleballpools.core.Tournament.scored(t.round1) > 0 || !t.round2.isEmpty())) t.startedAt = 1;
        // 1.3 could keep a withdrawn team's results (with forfeits); USA Pickleball 15.B.4.a removes them.
        for (Team x : t.teams) if (x.withdrawn) t.withdraw(x);
        return t;
    }

    private static void readPools(JSONArray a, List<Pool> into) throws JSONException {
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject j = a.getJSONObject(i);
            Pool p = new Pool(j.getString("name"), j.optString("bracket"));
            JSONArray ids = j.getJSONArray("teams");
            for (int k = 0; k < ids.length(); k++) p.teamIds.add(ids.getString(k));
            JSONArray ms = j.getJSONArray("matches");
            for (int k = 0; k < ms.length(); k++) {
                JSONObject mj = ms.getJSONObject(k);
                Match m = new Match(mj.getString("a"), mj.getString("b"), mj.optInt("round", 1));
                JSONArray g = mj.getJSONArray("games");
                for (int n = 0; n < g.length(); n++) m.games.add(new int[]{g.getJSONArray(n).getInt(0), g.getJSONArray(n).getInt(1)});
                p.matches.add(m);
            }
            JSONArray v = j.optJSONArray("voided");
            for (int k = 0; v != null && k < v.length(); k++) p.voided.add(v.getString(k));
            into.add(p);
        }
    }
}
