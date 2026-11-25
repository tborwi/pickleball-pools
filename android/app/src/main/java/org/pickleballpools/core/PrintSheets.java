package org.pickleballpools.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The three printed sheets a tournament director uses on the day, as Excel workbooks (layout copied from HOWPA's
 * hand-made sheets):
 * <ol>
 *   <li>{@link #schedule}: every pool's teams and which match is on which court each round (posted on the wall).</li>
 *   <li>{@link #captainSheets}: one page per pool for the court captains to write game scores and initials.</li>
 *   <li>{@link #resultsGrid}: one page per pool, every team against every other, with record, points and place.
 *       Filled in from the app when scores have been entered, blank otherwise.</li>
 * </ol>
 * Courts: each pool gets as many courts as it plays matches at once, taken in order from the venue's courts
 * ({@link Tournament#courts}), or numbered on from {@link Tournament#firstCourt} when no venue is set. If the venue has
 * too few, courts are shared (a match waits for its court); {@link #shortOfCourts} warns about that.
 */
public final class PrintSheets {
    private PrintSheets() {}

    /** Courts for each pool of a round, in order. courts.get(p)[k] = court number for the k-th match of a round. */
    public static List<int[]> courts(Tournament t, List<Pool> round) {
        List<int[]> out = new ArrayList<>();
        int next = 0;
        for (Pool p : round) {
            int[] c = new int[perRound(p)];
            for (int k = 0; k < c.length; k++, next++)
                c[k] = t.courts.isEmpty() ? t.firstCourt + next : t.courts.get(next % t.courts.size());
            out.add(c);
        }
        return out;
    }

    /** Courts a round needs so every pool plays at once. */
    public static int courtsNeeded(List<Pool> round) {
        int n = 0;
        for (Pool p : round) n += perRound(p);
        return n;
    }

    /** Null if the venue has enough courts for this round, else a warning for the director. */
    public static String shortOfCourts(Tournament t, List<Pool> round) {
        int need = courtsNeeded(round), have = t.courts.size();
        if (t.courts.isEmpty() || need <= have) return null;
        return "This round needs " + need + " courts at once but " + (t.venue.isEmpty() ? "the venue" : t.venue) + " has " + have
                + ". Some pools share courts, so those matches wait for a free court.";
    }

    /** The court of every match in a round, as on the printed schedule (k-th match of a pool round -> its k-th court). */
    public static java.util.Map<Match, Integer> courtOf(Tournament t, List<Pool> round) {
        java.util.Map<Match, Integer> out = new java.util.IdentityHashMap<>();
        List<int[]> c = courts(t, round);
        for (int i = 0; i < round.size(); i++) {
            Pool p = round.get(i);
            for (int r = 1; r <= rounds(p); r++) {
                List<Match> ms = inRound(p, r);
                for (int k = 0; k < ms.size(); k++) out.put(ms.get(k), c.get(i)[Math.min(k, c.get(i).length - 1)]);
            }
        }
        return out;
    }

    /** Matches played at the same time in a pool (= courts it needs). */
    public static int perRound(Pool p) {
        int max = 0;
        for (int r = 1; r <= rounds(p); r++) max = Math.max(max, inRound(p, r).size());
        return Math.max(max, 1);
    }

    static int rounds(Pool p) {
        int n = 0;
        for (Match m : p.matches) n = Math.max(n, m.round);
        return n;
    }

    static List<Match> inRound(Pool p, int r) {
        List<Match> l = new ArrayList<>();
        for (Match m : p.matches) if (m.round == r) l.add(m);
        return l;
    }

    static int num(Pool p, String id) { return p.teamIds.indexOf(id) + 1; }

    static String format(Tournament t) {
        return "Best 2 of 3 games to " + t.pointsToWin + ", win by " + t.winBy;
    }

    static String roundName(Tournament t, List<Pool> round) { return round == t.round2 ? "ROUND 2" : "ROUND 1"; }

    static String when(Tournament t, List<Pool> round) { return round == t.round2 ? t.round2When : t.round1When; }

    /** Public team name: "Pat L. / Sam R." (short names, see Names). Printed sheets go on the wall. */
    public static String players(Map<String, String> sn, Team x) { return Names.team(sn, x); }

    // ---------------------------------------------------------------- 1. schedule

    public static Xlsx schedule(Tournament t, List<Pool> round) {
        Xlsx x = new Xlsx();
        Xlsx.Sheet s = x.sheet("Schedule");
        s.landscape = true;
        s.width(0, 12).width(1, 26).width(2, 26).width(3, 10).width(4, 3);
        Map<String, Team> ids = t.byId();
        Map<String, String> sn = Names.forTournament(t);
        List<int[]> courts = courts(t, round);
        int maxCourts = 1;
        for (int[] c : courts) maxCourts = Math.max(maxCourts, c.length);
        for (int k = 0; k < Math.max(maxCourts, 4); k++) s.width(5 + k, 14);
        int r = 0;
        s.set(r, 0, (t.name + (t.venue.isEmpty() ? "" : " - " + t.venue)).toUpperCase(Locale.US), Xlsx.TITLE).height(r, 20);
        r += 2;
        for (int i = 0; i < round.size(); i++) {
            Pool p = round.get(i);
            int[] c = courts.get(i);
            s.set(r, 0, roundName(t, round), Xlsx.HEADER).merge(r, 1, r, 2, p.title().toUpperCase(Locale.US), Xlsx.HEADER)
                    .set(r, 3, "", Xlsx.HEADER).merge(r, 5, r, 5 + Math.max(c.length, 2) - 1, when(t, round), Xlsx.HEADER).height(r, 24);
            r++;
            s.set(r, 0, t.singles ? "#" : "TEAM", Xlsx.BOLD_BOX).set(r, 1, "PLAYER", Xlsx.BOLD_BOX).set(r, 2, t.singles ? "" : "PLAYER", Xlsx.BOLD_BOX)
                    .set(r, 3, "DUPR", Xlsx.BOLD_BOX);
            String[] notes = {p.teamIds.size() + (t.singles ? " player singles round robin" : " team round robin"), format(t),
                    "Ties: USA Pickleball rule 15.B.4", round == t.round1
                    ? "Top " + t.advancePerPool + " go to the Championship pools" : finalsNote(t, p)};
            for (int n = 0; n < notes.length; n++) s.set(r + 1 + n, 5, notes[n], Xlsx.PLAIN);
            r++;
            for (String id : p.teamIds) {
                Team tm = ids.get(id);
                s.set(r, 0, num(p, id), Xlsx.BOX).set(r, 1, Names.player(sn, tm, 1), Xlsx.LEFT_BOX)
                        .set(r, 2, Names.player(sn, tm, 2), Xlsx.LEFT_BOX).set(r, 3, String.format(Locale.US, "%.2f", tm.combined()), Xlsx.BOX);
                r++;
            }
            r = Math.max(r, r - p.teamIds.size() + notes.length) + 1;
            s.set(r, 0, "POOL PLAY", Xlsx.BOLD_BOX);
            for (int k = 0; k < c.length; k++) s.set(r, 1 + k, "COURT " + c[k], Xlsx.BOLD_BOX);
            r++;
            for (int rd = 1; rd <= rounds(p); rd++) {
                s.set(r, 0, "Round " + rd, Xlsx.BOLD_BOX);
                List<Match> ms = inRound(p, rd);
                for (int k = 0; k < c.length; k++)
                    s.set(r, 1 + k, k < ms.size() ? num(p, ms.get(k).a) + " V " + num(p, ms.get(k).b) : "", Xlsx.BOX);
                r++;
            }
            r += 2;
        }
        return x;
    }

    static String finalsNote(Tournament t, Pool p) {
        return "Championship".equals(p.bracket) ? "Top " + t.finalistsPerPool + " qualify for the Finals" : "Consolation pool";
    }

    // ---------------------------------------------------------------- 2. court captain score sheets

    public static Xlsx captainSheets(Tournament t, List<Pool> round) {
        Xlsx x = new Xlsx();
        Map<String, Team> ids = t.byId();
        Map<String, String> sn = Names.forTournament(t);
        List<int[]> courts = courts(t, round);
        for (int i = 0; i < round.size(); i++) {
            Pool p = round.get(i);
            int[] c = courts.get(i);
            Xlsx.Sheet s = x.sheet(p.title());
            s.landscape = c.length > 3;
            int cols = c.length * 5; // per court: TEAMS, two score columns, INITIALS, gap
            for (int k = 0; k < c.length; k++)
                s.width(k * 5, 11).width(k * 5 + 1, 9).width(k * 5 + 2, 9).width(k * 5 + 3, 10).width(k * 5 + 4, 2);
            int r = 0;
            s.merge(r, 0, r, Math.max(3, cols / 2 - 1), "COURT CAPTAIN:", Xlsx.HEADER)
                    .merge(r, Math.max(4, cols / 2), r, Math.max(cols - 2, 8), "Note: " + format(t), Xlsx.HEADER).height(r, 28);
            r++;
            s.merge(r, 0, r, Math.max(cols - 2, 8), t.name + " - " + roundName(t, round) + " - " + p.title().toUpperCase(Locale.US), Xlsx.HEADER).height(r, 28);
            r++;
            for (int k = 0; k < c.length; k++) s.merge(r, k * 5, r, k * 5 + 3, "COURT " + c[k], Xlsx.HEADER);
            s.height(r, 26);
            r++;
            for (int rd = 1; rd <= rounds(p); rd++) {
                List<Match> ms = inRound(p, rd);
                for (int k = 0; k < c.length && k < ms.size(); k++) {
                    Match m = ms.get(k);
                    int col = k * 5;
                    s.set(r, col, "Rd " + rd + ":  " + num(p, m.a) + " V " + num(p, m.b), Xlsx.BOLD_BOX)
                            .set(r, col + 1, num(p, m.a), Xlsx.YELLOW).set(r, col + 2, num(p, m.b), Xlsx.YELLOW)
                            .set(r, col + 3, "INITIALS", Xlsx.BOLD_BOX);
                    for (int g = 0; g < 3; g++) {
                        int[] sc = g < m.games.size() ? m.games.get(g) : null;
                        s.set(r + 1 + g, col, "Game " + (g + 1), Xlsx.BOX)
                                .set(r + 1 + g, col + 1, sc == null ? null : sc[0], Xlsx.BOX)
                                .set(r + 1 + g, col + 2, sc == null ? null : sc[1], Xlsx.BOX)
                                .set(r + 1 + g, col + 3, null, Xlsx.BOX);
                    }
                }
                s.height(r, 22);
                for (int g = 1; g <= 3; g++) s.height(r + g, 26);
                r += 5;
            }
            s.set(r, 0, t.singles ? "PLAYERS" : "TEAMS", Xlsx.BOLD);
            r++;
            for (String id : p.teamIds) {
                s.set(r, 0, num(p, id), Xlsx.BOX).merge(r, 1, r, Math.max(cols - 2, 8), players(sn, ids.get(id)), Xlsx.LEFT_BOX);
                r++;
            }
        }
        return x;
    }

    // ---------------------------------------------------------------- 3. results grid

    public static Xlsx resultsGrid(Tournament t, List<Pool> round) {
        Xlsx x = new Xlsx();
        Map<String, Team> ids = t.byId();
        Map<String, String> sn = Names.forTournament(t);
        for (Pool p : round) {
            Xlsx.Sheet s = x.sheet(p.title());
            s.landscape = true;
            int n = p.teamIds.size();
            s.width(0, 24).width(1, 10);
            for (int j = 0; j < n; j++) s.width(2 + j, 18);
            int res = 2 + n;
            s.width(res, 12).width(res + 1, 12).width(res + 2, 9);
            s.merge(0, 0, 0, res + 2, t.name + " - " + roundName(t, round) + " - " + p.title(), Xlsx.TITLE).height(0, 22);
            s.set(1, 0, null, Xlsx.PLAIN);
            for (int j = 0; j < n; j++) s.set(1, 2 + j, j + 1, Xlsx.NAME_BOX);
            s.merge(1, res, 1, res + 2, "Results", Xlsx.HEADER);
            for (int j = 0; j < n; j++) s.set(2, 2 + j, players(sn, ids.get(p.teamIds.get(j))).replace(" / ", "\n"), Xlsx.BOX);
            s.set(2, res, "Record W-L", Xlsx.BOLD_BOX).set(2, res + 1, "Points F-A", Xlsx.BOLD_BOX).set(2, res + 2, "Place", Xlsx.BOLD_BOX);
            s.height(1, 20).height(2, 40);
            boolean any = p.scored() > 0;
            List<Standings.Row> st = any ? Standings.of(p, ids) : new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String id = p.teamIds.get(i);
                int r = 3 + i * 2;
                s.merge(r, 0, r + 1, 0, (i + 1) + "  " + players(sn, ids.get(id)).replace(" / ", "\n"), Xlsx.NAME_BOX);
                s.set(r, 1, "score", Xlsx.BOX).set(r + 1, 1, "points f-a", Xlsx.BOX);
                for (int j = 0; j < n; j++) {
                    String o = p.teamIds.get(j);
                    if (o.equals(id)) { s.set(r, 2 + j, null, Xlsx.GREY).set(r + 1, 2 + j, null, Xlsx.GREY); continue; }
                    Match m = find(p, id, o);
                    String score = null, pts = null;
                    if (m != null && !m.games.isEmpty()) {
                        StringBuilder b = new StringBuilder();
                        for (int[] g : m.games) {
                            int mine = m.a.equals(id) ? g[0] : g[1], theirs = m.a.equals(id) ? g[1] : g[0];
                            b.append(b.length() == 0 ? "" : ", ").append(mine).append('-').append(theirs);
                        }
                        score = b.toString();
                        pts = m.points(id) + "-" + m.points(o);
                    }
                    s.set(r, 2 + j, score, Xlsx.BOX).set(r + 1, 2 + j, pts, Xlsx.BOX);
                }
                Standings.Row row = null;
                for (Standings.Row sr : st) if (sr.team.id.equals(id)) row = sr;
                s.merge(r, res, r + 1, res, row == null ? null : row.wins + "-" + row.losses, Xlsx.NAME_BOX)
                        .merge(r, res + 1, r + 1, res + 1, row == null ? null : row.pointsFor + "-" + row.pointsAgainst, Xlsx.NAME_BOX)
                        .merge(r, res + 2, r + 1, res + 2, row == null || !p.complete() || row.withdrawn ? null : row.place, Xlsx.NAME_BOX);
                s.height(r, 30).height(r + 1, 30);
            }
            int r = 4 + n * 2;
            s.set(r, 0, "Ties (USA Pickleball 15.B.4): head to head, point differential, head-to-head point differential, "
                    + "point differential vs next-highest team, total points.", Xlsx.PLAIN);
        }
        return x;
    }

    static Match find(Pool p, String a, String b) {
        for (Match m : p.matches) if (m.involves(a) && m.involves(b)) return m;
        return null;
    }
}
