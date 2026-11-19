package org.pickleballpools.core;

import java.util.ArrayList;
import java.util.List;

/** One best-of-3 match between team A and team B. games holds {scoreA, scoreB} per game played. */
public final class Match {
    public final String a, b;
    public final int round; // 1-based round within the pool's round robin (for court scheduling)
    public final List<int[]> games = new ArrayList<>();
    public String forfeitWinner; // set when the other team withdrew before this match was played (counts 2-0, no points)

    public Match(String a, String b, int round) { this.a = a; this.b = b; this.round = round; }

    public boolean involves(String id) { return a.equals(id) || b.equals(id); }

    public String opponent(String id) { return a.equals(id) ? b : a; }

    public boolean forfeit() { return forfeitWinner != null; }

    public int gamesWon(String id) {
        if (forfeit()) return id.equals(forfeitWinner) ? 2 : 0;
        int n = 0;
        for (int[] g : games) if ((a.equals(id) && g[0] > g[1]) || (b.equals(id) && g[1] > g[0])) n++;
        return n;
    }

    /** Points scored by team id across all games of this match. */
    public int points(String id) {
        int n = 0;
        for (int[] g : games) n += a.equals(id) ? g[0] : g[1];
        return n;
    }

    public boolean done() { return winner() != null; }

    /** Winner's id once a team has won 2 games, else null. */
    public String winner() {
        if (forfeit()) return forfeitWinner;
        if (gamesWon(a) >= 2) return a;
        if (gamesWon(b) >= 2) return b;
        return null;
    }

    public String scoreText() {
        if (forfeit()) return "Forfeit";
        StringBuilder sb = new StringBuilder();
        for (int[] g : games) sb.append(sb.length() == 0 ? "" : ", ").append(g[0]).append('-').append(g[1]);
        return sb.toString();
    }
}
