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
    public int winBy = 1;

    public String paymentLink = "";  // the page players pay on (Stripe Payment Link, PayPal, Zeffy…), shared from the app
    public int[] importColumns;      // last column match used for a payment export (Registration.FIELDS order)
    public final List<Player> players = new ArrayList<>(); // paid registrations, one per player

    public final List<Team> teams = new ArrayList<>();
    public final List<Pool> round1 = new ArrayList<>();
    public final List<Pool> round2 = new ArrayList<>();
    public int nextId = 1;

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
            r.added++;
        }
        return r;
    }

    public Map<String, Team> byId() {
        Map<String, Team> m = new LinkedHashMap<>();
        for (Team t : teams) m.put(t.id, t);
        return m;
    }

    /** Teams by combined DUPR, highest first: this is the seed order. */
    public List<Team> seeded() {
        List<Team> s = new ArrayList<>(teams);
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
            for (Standings.Row r : Standings.of(p, ids)) (r.place <= advancePerPool ? champ : consol).add(r);
        Comparator<Standings.Row> byRecord = Comparator
                .comparingInt((Standings.Row r) -> r.place)
                .thenComparingInt(r -> -r.wins)
                .thenComparingInt(r -> -r.diff())
                .thenComparingInt(r -> -r.pointsFor)
                .thenComparingDouble(r -> -r.team.combined());
        champ.sort(byRecord);
        consol.sort(byRecord);
        int half = Math.max(1, poolCount / 2);
        addBracket("Championship", champ, half, 0);
        addBracket("Consolation", consol, Math.max(1, poolCount - half), half);
    }

    private void addBracket(String bracket, List<Standings.Row> rows, int pools, int firstLetter) {
        if (rows.isEmpty()) return;
        List<Pool> ps = new ArrayList<>();
        for (int i = 0; i < pools; i++) ps.add(new Pool(letter(firstLetter + i), bracket));
        for (int i = 0; i < rows.size(); i++) ps.get(snake(i, pools)).teamIds.add(rows.get(i).team.id);
        for (Pool p : ps) { p.schedule(); round2.add(p); }
    }

    /** Teams that qualify for the Finals (top of each finished Championship pool). */
    public List<Team> finalists() {
        List<Team> f = new ArrayList<>();
        Map<String, Team> ids = byId();
        for (Pool p : round2) {
            if (!p.bracket.equals("Championship") || !p.complete()) continue;
            for (Standings.Row r : Standings.of(p, ids)) if (r.place <= finalistsPerPool) f.add(r.team);
        }
        return f;
    }
}
