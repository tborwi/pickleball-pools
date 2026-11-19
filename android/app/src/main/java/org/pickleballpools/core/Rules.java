package org.pickleballpools.core;

import java.util.List;

/** Score validation: best 2 out of 3 games, each game to pointsToWin, win by winBy. */
public final class Rules {
    private Rules() {}

    /** Returns an error message, or null when the game score is valid. */
    public static String checkGame(int x, int y, int to, int winBy) {
        if (x < 0 || y < 0) return "Scores can't be negative";
        int hi = Math.max(x, y), lo = Math.min(x, y);
        if (hi < to) return "A game ends when a team reaches " + to;
        if (hi - lo < winBy) return "Must win by " + winBy;
        if (hi > to && hi - lo != winBy) return hi + "-" + lo + " isn't possible: game ends at " + to + " or as soon as the lead is " + winBy;
        return null;
    }

    /** Checks a whole match. Empty list = not played yet (valid). */
    public static String checkMatch(List<int[]> games, int to, int winBy) {
        if (games.isEmpty()) return null;
        int wa = 0, wb = 0;
        for (int i = 0; i < games.size(); i++) {
            int[] g = games.get(i);
            if (wa == 2 || wb == 2) return "Match was already over after game " + i;
            String e = checkGame(g[0], g[1], to, winBy);
            if (e != null) return "Game " + (i + 1) + ": " + e;
            if (g[0] > g[1]) wa++; else wb++;
        }
        if (wa < 2 && wb < 2) return "Enter all games: someone must win 2";
        return null;
    }
}
