package org.pickleballpools.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole event:
 *  1. Teams are seeded by combined DUPR and snaked into pools (A, B, C, D, D, C, B, A, A, ...).
 *  2. Round 1: round robin in each pool.
 *  3. Round 2: the top teams of every round 1 pool form the Championship pools, the rest the Consolation pools.
 *     They are re-seeded by round 1 result (pool finish, then wins, point differential, points scored, DUPR)
 *     and snaked again.
 *  4. The top teams of each Championship pool qualify for the Finals.
 */
public final class Tournament {
    public String name = "Pickleball Open";
    public int poolCount = 4;        // round 1 pools
    public int advancePerPool = 2;   // round 1 -> Championship bracket
    public int finalistsPerPool = 1; // round 2 Championship pool -> Finals
    public int pointsToWin = 11;
    public int winBy = 2; // USA Pickleball 15.C: all scoring options are win by two (win by 1 only for team play)

    public String serverId = "";
    public long liveSeq;             // last live score received from the server     // id on the accounts server once put online (for court captains)
    public int firstCourt = 1;       // printed sheets: courts are numbered on from here, pool by pool
    public String round1When = "", round2When = ""; // printed sheets, e.g. "Saturday 9:00am"

    public String paymentLink = "";  // the page players pay on (Stripe Payment Link, PayPal, Zeffy…), shared from the app
    public int[] importColumns;      // last column match used for a payment export (Registration.FIELDS order)
    public final List<Player> players = new ArrayList<>(); // paid registrations, one per player

    public final List<Team> teams = new ArrayList<>();
    public final List<Pool> round1 = new ArrayList<>();
    public final List<Pool> round2 = new ArrayList<>();
    public int nextId = 1;

    public long startedAt; // when the director pressed Start (ms); 0 = not started. Started = pools locked.

    public boolean started() { return startedAt > 0; }

    /**
     * Starts the tournament: locks the teams and pools (no redo, no new teams, no format changes) and opens
     * score entry. Returns an error message, or null when started.
     */
    public String start(long now) {
        if (round1.isEmpty()) return "Make the Round 1 pools first";
        if (started()) return "Already started";
        startedAt = now;
        return null;
    }

    /** Unlocks the pools again (director's escape hatch). Scores are kept. */
    public void unlock() { startedAt = 0; }

    /** Live-scoring id of a match: round, pool and the two team ids, e.g. "r1:A:t3:t7". */
    public String matchKey(Pool p, Match m) {
        return "r" + (round2.contains(p) ? 2 : 1) + ":" + p.name + ":" + m.a + ":" + m.b;
    }

    public Team addTeam() {
        Team t = new Team("t" + nextId++);
        teams.add(t);
        return t;
    }

    /** Result of {@link #addReadyTeams()}. */
    public static final class AddResult { public int added, needRating; }

    /**
     * Turns every ready pair (both paid, both named each other) into a team, once both DUPR ratings are entered.
     * Pairs already added are skipped.
     */
    public AddResult addReadyTeams() {
        AddResult r = new AddResult();
        for (Player[] pr : Registration.pair(players).ready) {
            String key = Registration.key(pr[0], pr[1]);
            if (teams.stream().anyMatch(x -> x.regKey.equals(key))) continue;
            if (pr[0].rating <= 0 || pr[1].rating <= 0) { r.needRating++; continue; }
            Team x = addTeam();
            x.regKey = key;
            x.player1 = pr[0].name; x.dupr1 = pr[0].rating;
            x.player2 = pr[1].name; x.dupr2 = pr[1].rating;
            x.duprId1 = pr[0].duprId; x.duprId2 = pr[1].duprId;
            x.email1 = pr[0].email; x.email2 = pr[1].email;
            r.added++;
        }
        return r;
    }

    public Map<String, Team> byId() {
        Map<String, Team> m = new LinkedHashMap<>();
        for (Team t : teams) m.put(t.id, t);
        return m;
    }

    /** Teams still in the event, by combined DUPR, highest first: this is the seed order. */
    public List<Team> seeded() {
        List<Team> s = new ArrayList<>();
        for (Team x : teams) if (!x.withdrawn) s.add(x);
        s.sort(Comparator.comparingDouble(Team::combined).reversed());
        return s;
    }

    /** Snake order: pool index for the i-th seed among n pools. 0,1,2,3,3,2,1,0,0,1,... */
    public static int snake(int seed, int n) {
        int row = seed / n, col = seed % n;
        return row % 2 == 0 ? col : n - 1 - col;
    }

    public static String letter(int i) { return String.valueOf((char) ('A' + i)); }

    public void startRound1() {
        round1.clear();
        round2.clear();
        for (int i = 0; i < poolCount; i++) round1.add(new Pool(letter(i), ""));
        List<Team> s = seeded();
        for (int i = 0; i < s.size(); i++) round1.get(snake(i, poolCount)).teamIds.add(s.get(i).id);
        for (Pool p : round1) p.schedule();
    }

    public static boolean complete(List<Pool> pools) {
        if (pools.isEmpty()) return false;
        for (Pool p : pools) if (!p.complete()) return false;
        return true;
    }

    public void startRound2() {
        round2.clear();
        Map<String, Team> ids = byId();
        List<Standings.Row> champ = new ArrayList<>(), consol = new ArrayList<>();
        for (Pool p : round1)
            for (Standings.Row r : Standings.of(p, ids)) {
                if (r.withdrawn) continue; // withdrawn teams are listed last, so the next team moves up
                (r.place <= advancePerPool ? champ : consol).add(r);
            }
        // Across pools: pool finish, then share of matches won. Point differential and points scored are only
        // compared when every pool has the same number of teams (USA Pickleball 15.B.4.c and 15.B.4.f).
        java.util.Set<Integer> sizes = new java.util.HashSet<>();
        for (Pool p : round1) sizes.add((int) p.teamIds.stream().filter(id -> !ids.get(id).withdrawn).count());
        boolean equalPools = sizes.size() == 1;
        Comparator<Standings.Row> byRecord = Comparator
                .comparingInt((Standings.Row r) -> r.place)
                .thenComparingDouble(r -> -(double) r.wins / Math.max(1, r.wins + r.losses))
                .thenComparingInt(r -> equalPools ? -r.diff() : 0)
                .thenComparingInt(r -> equalPools ? -r.pointsFor : 0)
                .thenComparingDouble(r -> -r.team.combined());
        champ.sort(byRecord);
        consol.sort(byRecord);
        int half = Math.max(1, poolCount / 2);
        addBracket("Championship", champ, half, 0);
        addBracket("Consolation", consol, Math.max(1, poolCount - half), half);
    }

    private void addBracket(String bracket, List<Standings.Row> rows, int pools, int firstLetter) {
        if (rows.isEmpty()) return;
        pools = Math.max(1, Math.min(pools, rows.size() / 2)); // no pool smaller than 2 teams
        List<Pool> ps = new ArrayList<>();
        for (int i = 0; i < pools; i++) ps.add(new Pool(letter(firstLetter + i), bracket));
        for (int i = 0; i < rows.size(); i++) ps.get(snake(i, pools)).teamIds.add(rows.get(i).team.id);
        for (Pool p : ps) { p.schedule(); round2.add(p); }
    }

    // ---------- Drop-outs and reseeding ----------

    /** The round a team is playing in now: Round 2 if it is in a Round 2 pool, else Round 1 (empty if none). */
    public List<Pool> currentRound(Team x) {
        for (Pool p : round2) if (p.teamIds.contains(x.id)) return round2;
        for (Pool p : round1) if (p.teamIds.contains(x.id)) return round1;
        return new ArrayList<>();
    }

    /** Matches with scores this team has in its current round. */
    public int played(Team x) {
        int n = 0;
        for (Pool p : currentRound(x)) for (Match m : p.matches) if (m.involves(x.id) && !m.games.isEmpty()) n++;
        return n;
    }

    /**
     * A team drops out (withdraws, retires or is forfeited). USA Pickleball 15.B.4.a: its match results don't count
     * toward the standings of the pool it was playing in, and it can't advance. Earlier rounds are never changed.
     * Before pools exist it just leaves.
     */
    public void withdraw(Team x) {
        x.withdrawn = true;
        for (Pool p : currentRound(x)) if (p.teamIds.contains(x.id) && !p.voided.contains(x.id)) p.voided.add(x.id);
    }

    /** Undoes withdraw: its results count again. */
    public void reinstate(Team x) {
        x.withdrawn = false;
        for (Pool p : round1) p.voided.remove(x.id);
        for (Pool p : round2) p.voided.remove(x.id);
    }

    public static int scored(List<Pool> pools) {
        int n = 0;
        for (Pool p : pools) n += p.scored();
        return n;
    }

    /**
     * Why a round's pools no longer match the teams still in the event (withdrawals, new or changed teams),
     * or null if they still match. Round 1 compares against all active teams; Round 2 only checks withdrawals.
     */
    public String outOfDate(List<Pool> round) {
        if (round.isEmpty()) return null;
        int gone = 0, missing = 0;
        List<String> inPools = new ArrayList<>();
        for (Pool p : round) inPools.addAll(p.teamIds);
        Map<String, Team> ids = byId();
        for (String id : inPools) if (ids.get(id) == null || ids.get(id).withdrawn) gone++;
        if (round == round1) for (Team x : seeded()) if (!inPools.contains(x.id)) missing++;
        if (gone == 0 && missing == 0) return null;
        String s = "";
        if (gone > 0) s += gone + " team" + (gone == 1 ? " has" : "s have") + " dropped out";
        if (missing > 0) s += (s.isEmpty() ? "" : " and ") + missing + " team" + (missing == 1 ? " isn't" : "s aren't") + " in a pool";
        return s;
    }

    /** Teams that qualify for the Finals (top of each finished Championship pool). */
    public List<Team> finalists() {
        List<Team> f = new ArrayList<>();
        Map<String, Team> ids = byId();
        for (Pool p : round2) {
            if (!p.bracket.equals("Championship") || !p.complete()) continue;
            for (Standings.Row r : Standings.of(p, ids)) if (r.place <= finalistsPerPool && !r.withdrawn) f.add(r.team);
        }
        return f;
    }
}
