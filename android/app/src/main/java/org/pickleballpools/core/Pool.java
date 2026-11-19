package org.pickleballpools.core;

import java.util.ArrayList;
import java.util.List;

/** A round-robin pool: every team plays every other team once. */
public final class Pool {
    public final String name;    // "A", "B", ...
    public final String bracket; // "" in round 1, "Championship" or "Consolation" in round 2
    public final List<String> teamIds = new ArrayList<>();
    public final List<Match> matches = new ArrayList<>();

    public Pool(String name, String bracket) { this.name = name; this.bracket = bracket; }

    public String title() { return (bracket.isEmpty() ? "" : bracket + " ") + "Pool " + name; }

    /** Builds the round-robin schedule with the circle method (each team plays at most once per round). */
    public void schedule() {
        matches.clear();
        List<String> t = new ArrayList<>(teamIds);
        if (t.size() % 2 == 1) t.add(null); // bye
        int n = t.size();
        for (int r = 0; r < n - 1; r++) {
            for (int i = 0; i < n / 2; i++) {
                String x = t.get(i), y = t.get(n - 1 - i);
                if (x != null && y != null) matches.add(new Match(x, y, r + 1));
            }
            t.add(1, t.remove(n - 1)); // rotate everyone except the first team
        }
    }

    public boolean complete() {
        for (Match m : matches) if (!m.done()) return false;
        return !matches.isEmpty();
    }
}
