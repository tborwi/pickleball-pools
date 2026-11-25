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

    /** Sets a match's games, each written from team x's side: {x points, y points}. */
    static void games(Pool p, String x, String y, int[]... gs) {
        for (Match m : p.matches) {
            if (!(m.involves(x) && m.involves(y))) continue;
            m.games.clear();
            for (int[] g : gs) m.games.add(m.a.equals(x) ? g : new int[]{g[1], g[0]});
            return;
        }
    }

    static void tiebreakContinues() {
        Tournament t = new Tournament();
        for (String n : new String[]{"X", "Y", "Z", "W"}) { Team x = t.addTeam(); x.name = n; x.dupr1 = x.dupr2 = 5; }
        Pool p = new Pool("A", "");
        for (Team x : t.teams) p.teamIds.add(x.id);
        p.schedule();
        String X = "t1", Y = "t2", Z = "t3", W = "t4";
        games(p, X, Y, new int[]{0, 11}, new int[]{11, 9}, new int[]{11, 9}); // X wins the match but is -7 on points
        games(p, Y, Z, new int[]{11, 9}, new int[]{11, 9});
        games(p, Z, X, new int[]{11, 9}, new int[]{11, 9});
        games(p, X, W, new int[]{11, 5}, new int[]{11, 4});                     // X +13
        games(p, Y, W, new int[]{0, 11}, new int[]{11, 10}, new int[]{11, 10}); // Y wins but -9
        games(p, Z, W, new int[]{11, 0}, new int[]{11, 0});                     // Z +22
        // X, Y, Z all 2-1, head to head circular. Overall diff: X -7-4+13 = 2, Y 7+4-9 = 2, Z -4+4+22 = 22
        Map<String, Team> ids = t.byId();
        List<Standings.Row> now = Standings.of(p, ids);
        // 15.B.4: X and Y continue to head-to-head point differential; they do NOT go back to head to head (X won)
        check(order(now).equals("Z Y X W"), "15.B.4: X/Y continue to head-to-head point diff -> Y ahead (got " + order(now) + ")");
        check(now.get(1).tiebreak.equals("Tiebreak: head-to-head point differential"), "note: " + now.get(1).tiebreak);
    }

    static Pool poolOf(Tournament t, List<Pool> round, String id) {
        for (Pool p : round) if (p.teamIds.contains(id)) return p;
        return null;
    }

    static void withdrawals() {
        // 1. No-show before play: redo Round 1 with 15 teams
        Tournament t = sixteenTeams();
        t.startRound1();
        Team s16 = t.teams.get(15);
        t.withdraw(s16);
        check(t.outOfDate(t.round1) != null, "pools flagged out of date after a drop-out: " + t.outOfDate(t.round1));
        t.startRound1();
        int total = 0, threes = 0;
        for (Pool p : t.round1) { total += p.teamIds.size(); if (p.teamIds.size() == 3) { threes++; check(p.matches.size() == 3, "3-team pool plays 3 matches"); } }
        check(total == 15 && threes == 1 && poolOf(t, t.round1, s16.id) == null, "redo: 15 teams, one pool of 3, S16 left out");
        check(t.outOfDate(t.round1) == null, "pools up to date after redo");
        t.reinstate(s16);
        check(t.outOfDate(t.round1).contains("isn't in a pool"), "brought-back team flagged as not in a pool");
        t.startRound1();

        // 2. Mid-pool withdrawal (USA Pickleball 15.B.4.a): S8 beat S1, then withdraws -> its results don't count
        Map<String, Team> ids = t.byId();
        Pool a = t.round1.get(0);
        String s1 = a.teamIds.get(0), s8 = a.teamIds.get(1), s9 = a.teamIds.get(2), s16id = a.teamIds.get(3);
        play(a, s8, s1, 5);
        t.withdraw(ids.get(s8));
        play(a, s1, s9, 3); play(a, s1, s16id, 3); play(a, s9, s16id, 3);
        check(a.complete(), "pool complete without the withdrawn team's unplayed matches");
        List<Standings.Row> st = Standings.of(a, ids);
        check(st.get(3).team.id.equals(s8) && st.get(3).withdrawn && st.get(3).tiebreak.equals(Standings.WITHDREW),
                "withdrawn team listed last (got " + order(st) + ")");
        check(st.get(0).team.id.equals(s1) && st.get(0).wins == 2 && st.get(0).losses == 0,
                "S1's loss to the withdrawn team no longer counts (got " + st.get(0).wins + "-" + st.get(0).losses + ")");

        // 3. Bring back: the loss counts again; withdraw again for the rest of the test
        t.reinstate(ids.get(s8));
        check(!a.complete() && Standings.of(a, ids).stream().anyMatch(r -> r.team.id.equals(s1) && r.losses == 1),
                "bring back: results count again");
        t.withdraw(ids.get(s8));

        // 4. Finish Round 1; next team moves up into the Championship bracket
        for (int p = 1; p < 4; p++) {
            Pool q = t.round1.get(p);
            List<String> s = q.teamIds;
            for (int i = 0; i < s.size(); i++) for (int j = i + 1; j < s.size(); j++) play(q, s.get(i), s.get(j), 5);
        }
        t.startRound2();
        int champ = 0;
        for (Pool p : t.round2) if (p.bracket.equals("Championship")) champ += p.teamIds.size();
        check(champ == 8 && poolOf(t, t.round2, s16id) != null && poolOf(t, t.round2, s8) == null,
                "Round 2: still 8 Championship teams; S8 out");
        check(poolOf(t, t.round2, s16id).bracket.equals("Consolation") && poolOf(t, t.round2, s9).bracket.equals("Championship"),
                "Pool A's 3rd place (S9) moved up to the Championship bracket");

        // 5. A Championship team drops before Round 2 starts: redo Round 2
        Team c = ids.get(t.round2.get(0).teamIds.get(0));
        t.withdraw(c);
        check(t.outOfDate(t.round2) != null, "Round 2 flagged out of date");
        t.startRound2();
        champ = 0;
        for (Pool p : t.round2) if (p.bracket.equals("Championship")) champ += p.teamIds.size();
        check(champ == 8 && poolOf(t, t.round2, c.id) == null, "redo Round 2: next team moved up, still 8");
    }

    static void printSheets() {
        Tournament t = sixteenTeams();
        t.firstCourt = 7;
        t.startRound1();
        Match m = t.round1.get(0).matches.get(0);
        m.games.add(new int[]{11, 5}); m.games.add(new int[]{11, 9});
        List<int[]> c = PrintSheets.courts(t, t.round1);
        check(c.size() == 4 && c.get(0)[0] == 7 && c.get(0)[1] == 8 && c.get(3)[1] == 14, "4-team pools use 2 courts each, numbered from 7");
        try {
            String dir = System.getenv("SHEETS_OUT");
            for (String[] n : new String[][]{{"schedule"}, {"captains"}, {"grid"}}) {
                Xlsx x = n[0].equals("schedule") ? PrintSheets.schedule(t, t.round1)
                        : n[0].equals("captains") ? PrintSheets.captainSheets(t, t.round1) : PrintSheets.resultsGrid(t, t.round1);
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                x.write(b);
                check(b.size() > 1000, n[0] + " workbook written");
                if (dir != null) java.nio.file.Files.write(java.nio.file.Paths.get(dir, n[0] + ".xlsx"), b.toByteArray());
            }
        } catch (java.io.IOException e) { check(false, "sheets: " + e); }
        java.util.Map<Match, Integer> co = PrintSheets.courtOf(t, t.round1);
        check(co.get(t.round1.get(0).matches.get(0)) == 7 && co.get(t.round1.get(0).matches.get(1)) == 8
                && co.get(t.round1.get(1).matches.get(0)) == 9, "each match gets its printed court");
        check(t.matchKey(t.round1.get(0), m).equals("r1:A:" + m.a + ":" + m.b), "live match key");
        check(Xlsx.ref(0, 0).equals("A1") && Xlsx.ref(2, 27).equals("AB3"), "cell references");
    }

    static void registration() {
        String csv = "id,Customer Name,Customer Email,Status,Your DUPR ID (custom),Partner name,Partner DUPR ID\n"
                + "pi_1,\"Smith, Pat\",pat@x.org,Paid,ab12cd,Lee Jones,EF34GH\n"
                + "pi_2,Lee Jones,lee@x.org,Succeeded,EF34GH,Pat Smith,AB12CD\r\n"
                + "pi_3,Kim Lo,kim@x.org,Paid,KK11,Sam Wu,ZZ99\n"           // partner hasn't paid
                + "pi_4,Ann Ray,ann@x.org,Paid,AA22,Lee Jones,EF34GH\n"     // names Lee, but Lee named Pat
                + "pi_5,Bo Ng,bo@x.org,Paid,BB33,,\n"                        // needs a partner
                + "pi_6,Pat Smith,pat@x.org,Paid,AB12CD,Lee Jones,EF34GH\n"  // paid twice
                + "pi_7,Al Fox,al@x.org,Refunded,CC44,Bo Ng,BB33\n";        // refunded: ignored
        List<List<String>> rows = Registration.parseCsv(csv);
        check(rows.size() == 8 && rows.get(1).get(1).equals("Smith, Pat"), "CSV: quoted comma kept in one field");
        int[] m = Registration.guessColumns(rows.get(0));
        check(m[Registration.NAME] == 1 && m[Registration.EMAIL] == 2 && m[Registration.STATUS] == 3
                && m[Registration.DUPR] == 4 && m[Registration.PARTNER_NAME] == 5 && m[Registration.PARTNER_DUPR] == 6
                && m[Registration.REF] == 0, "column guess from header");
        Tournament t = new Tournament();
        Registration.ImportResult r = Registration.importRows(rows, m, t.players);
        check(r.added == 6 && r.notPaid == 1, "import: 6 paid, 1 refunded skipped (got " + r.added + "/" + r.notPaid + ")");
        check(Registration.importRows(rows, m, t.players).added == 0, "re-import adds nothing (same payment IDs)");
        Registration.Pairing p = Registration.pair(t.players);
        check(p.ready.size() == 1 && p.ready.get(0)[0].duprId.equals("AB12CD"), "ready: Pat + Lee (IDs case-insensitive)");
        check(p.waiting.size() == 1 && p.waiting.get(0).name.equals("Kim Lo"), "waiting on partner: Kim");
        check(p.mismatch.size() == 1 && p.mismatch.get(0)[0].name.equals("Ann Ray"), "mismatch: Ann named Lee");
        check(p.needsPartner.size() == 1 && p.needsPartner.get(0).name.equals("Bo Ng"), "needs a partner: Bo");
        check(p.duplicates.size() == 1, "paid twice flagged");
        check(t.addReadyTeams().needRating == 1 && t.teams.isEmpty(), "no team until both ratings are entered");
        p.ready.get(0)[0].rating = 5.56; p.ready.get(0)[1].rating = 5.51;
        check(t.addReadyTeams().added == 1 && t.teams.get(0).combined() == 11.07, "ready pair -> team, combined 11.07");
        check(t.addReadyTeams().added == 0, "team only added once");
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
        check(new Tournament().winBy == 2, "default win by 2 (USA Pickleball 15.C)");
        check(Rules.checkGame(13, 11, 11, 2) == null && Rules.checkGame(11, 10, 11, 2) != null, "win by 2: 13-11 ok, 11-10 not");
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
        check(order(st).equals("S1 S8 S16 S9"), "3-way circular tie -> point diff (got " + order(st) + ")");
        check(st.get(1).tiebreak.contains("point differential"), "tiebreak note explains: " + st.get(1).tiebreak);

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

        registration();
        withdrawals();
        tiebreakContinues();
        printSheets();

        Tournament go = sixteenTeams();
        check(go.start(1) != null && !go.started(), "can't start before the pools are made");
        go.startRound1();
        check(go.start(1000) == null && go.started(), "start locks the pools");
        go.unlock();
        check(!go.started(), "unlock");

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
