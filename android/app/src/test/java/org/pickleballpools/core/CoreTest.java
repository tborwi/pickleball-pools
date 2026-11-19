package org.pickleballpools.core;

import java.util.List;
import java.util.Map;

/** Plain-Java checks of the tournament rules. Run with ../test.sh (no Android SDK needed). */
public final class CoreTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failures++;
    }

    static Tournament sixteenTeams() {
        Tournament t = new Tournament();
        for (int i = 0; i < 16; i++) {
            Team x = t.addTeam();
            x.name = "S" + (i + 1); // S1 = top seed
            x.dupr1 = 6.0 - i * 0.1;
            x.dupr2 = 5.0;
        }
        return t;
    }

    /** Plays match a-vs-b in pool p: winner takes two games, loser scores the given points each game. */
    static void play(Pool p, String winner, String loser, int loserPts) {
        for (Match m : p.matches) {
            if (!(m.involves(winner) && m.involves(loser))) continue;
            m.games.clear();
            boolean wa = m.a.equals(winner);
            for (int g = 0; g < 2; g++) m.games.add(wa ? new int[]{11, loserPts} : new int[]{loserPts, 11});
            return;
        }
        throw new IllegalStateException("no match " + winner + " v " + loser);
    }

    static String order(List<Standings.Row> rows) {
        StringBuilder sb = new StringBuilder();
        for (Standings.Row r : rows) sb.append(r.team.label()).append(' ');
        return sb.toString().trim();
    }

    public static void main(String[] args) {
        Team ex = new Team("x");
        ex.dupr1 = 5.56; ex.dupr2 = 5.51;
        check(ex.combined() == 11.07, "combined DUPR 5.56 + 5.51 = 11.07");

        // Snake seeding
        Tournament t = sixteenTeams();
        t.startRound1();
        Map<String, Team> ids = t.byId();
        String poolA = "", poolD = "";
        for (String id : t.round1.get(0).teamIds) poolA += ids.get(id).label() + " ";
        for (String id : t.round1.get(3).teamIds) poolD += ids.get(id).label() + " ";
        check(poolA.trim().equals("S1 S8 S9 S16"), "snake: Pool A = S1 S8 S9 S16 (got " + poolA.trim() + ")");
        check(poolD.trim().equals("S4 S5 S12 S13"), "snake: Pool D = S4 S5 S12 S13 (got " + poolD.trim() + ")");
        check(t.round1.get(0).matches.size() == 6, "4-team round robin = 6 matches");

        // Game rules: to 11, win by 1
        check(Rules.checkGame(11, 10, 11, 1) == null, "11-10 valid (win by 1)");
        check(Rules.checkGame(9, 11, 11, 1) == null, "9-11 valid");
        check(Rules.checkGame(12, 10, 11, 1) != null, "12-10 invalid (game ends at 11)");
        check(Rules.checkGame(10, 8, 11, 1) != null, "10-8 invalid (nobody reached 11)");
        check(Rules.checkGame(12, 10, 11, 2) == null, "12-10 valid when win by 2");
        check(Rules.checkMatch(List.of(new int[]{11, 5}, new int[]{5, 11}), 11, 1) != null, "1-1 needs a third game");
        check(Rules.checkMatch(List.of(new int[]{11, 5}, new int[]{11, 5}, new int[]{11, 5}), 11, 1) != null, "no third game after 2-0");

        // Pool A: S1 beats everyone. S8, S9, S16 each 1-2 in a circle (S8>S9, S9>S16, S16>S8)
        // with different margins, so head to head can't split them and overall point diff decides.
        Pool a = t.round1.get(0);
        String s1 = a.teamIds.get(0), s8 = a.teamIds.get(1), s9 = a.teamIds.get(2), s16 = a.teamIds.get(3);
        play(a, s1, s8, 9); play(a, s1, s9, 9); play(a, s1, s16, 9);
        play(a, s8, s9, 2);   // S8 +18
        play(a, s9, s16, 8);  // S9 +6
        play(a, s16, s8, 6);  // S16 +10
        List<Standings.Row> st = Standings.of(a, ids);
        // diffs: S8 = -4 +18 -10 = +4 ; S9 = -4 -18 +6 = -16 ; S16 = -4 -6 +10 = 0
        check(order(st).equals("S1 S8 S16 S9"), "3-way circular tie -> overall point diff (got " + order(st) + ")");
        check(st.get(1).tiebreak.contains("overall point differential"), "tiebreak note explains: " + st.get(1).tiebreak);

        // Two-way tie decided head to head: Pool B, S2 & S7 both 2-1, S7 beat S2.
        Pool b = t.round1.get(1);
        String s2 = b.teamIds.get(0), s7 = b.teamIds.get(1), s10 = b.teamIds.get(2), s15 = b.teamIds.get(3);
        play(b, s7, s2, 10); play(b, s2, s10, 0); play(b, s2, s15, 0);
        play(b, s7, s10, 10); play(b, s15, s7, 10); play(b, s10, s15, 3);
        st = Standings.of(b, ids);
        check(order(st).startsWith("S7 S2"), "2-way tie -> head to head (got " + order(st) + ")");
        check(st.get(0).tiebreak.equals("Tiebreak: head to head"), "note: " + st.get(0).tiebreak);

        // Finish pools C, D: higher seed always wins
        for (int p = 2; p < 4; p++) {
            Pool q = t.round1.get(p);
            List<String> s = t.round1.get(p).teamIds;
            for (int i = 0; i < 4; i++) for (int j = i + 1; j < 4; j++) play(q, s.get(i), s.get(j), 5);
        }
        check(Tournament.complete(t.round1), "round 1 complete");
        t.startRound2();
        check(t.round2.size() == 4, "round 2 has 4 pools");
        check(t.round2.get(0).title().equals("Championship Pool A"), "Championship Pool A");
        check(t.round2.get(2).title().equals("Consolation Pool C"), "Consolation Pool C");
        int champTeams = t.round2.get(0).teamIds.size() + t.round2.get(1).teamIds.size();
        check(champTeams == 8, "8 teams in the Championship bracket");
        // Seed 1 of round 2 is the best pool winner: all four winners went 3-0; S1 (+..)
        check(!t.round2.get(0).teamIds.contains(s9) && !t.round2.get(1).teamIds.contains(s9), "S9 (3rd in Pool A) is in Consolation");

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
