package org.pickleballpools.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Short public names: first name and last initial ("Pat L."), used everywhere except the director's own screens
 * (live page, emails, court captains, printed sheets). When two players would get the same short name, more letters
 * of the last name are added until they differ ("Pat Lee" and "Pat Lew."). Two players with exactly the same full
 * name keep the same short name (their full names can't tell them apart either).
 */
public final class Names {
    private Names() {}

    /** Full name -> short name, unique across the given names. */
    public static Map<String, String> shortNames(Collection<String> fullNames) {
        List<String> names = new ArrayList<>(new LinkedHashSet<>(clean(fullNames)));
        Map<String, String> out = new HashMap<>();
        for (String me : names) {
            String[] p = parts(me);
            if (p[1].isEmpty()) { out.put(me, p[0]); continue; } // one-word name: as is
            int n = 1;
            for (String other : names) {
                if (other.equals(me)) continue;
                String[] q = parts(other);
                if (!q[0].equalsIgnoreCase(p[0]) || q[1].isEmpty()) continue;
                while (n < p[1].length() && prefix(q[1], n).equalsIgnoreCase(prefix(p[1], n))) n++;
            }
            out.put(me, p[0] + " " + (n >= p[1].length() ? p[1] : prefix(p[1], n) + "."));
        }
        return out;
    }

    /** Short name of one player, given the map from {@link #shortNames}. */
    public static String of(Map<String, String> map, String fullName) {
        String c = clean1(fullName);
        String s = map.get(c);
        return s == null ? c : s;
    }

    /** Short names for every player in the tournament, unique within it. */
    public static Map<String, String> forTournament(Tournament t) {
        List<String> all = new ArrayList<>();
        for (Team x : t.teams) { all.add(x.player1); all.add(x.player2); }
        return shortNames(all);
    }

    /** "Pat L. / Sam R." */
    public static String team(Map<String, String> map, Team x) {
        String a = of(map, x.player1), b = of(map, x.player2);
        if (b.isEmpty()) return a;
        if (a.isEmpty()) return b;
        return a + " / " + b;
    }

    /** First name and last name, ignoring middle names and suffixes (Jr., Sr., II, III, IV). */
    static String[] parts(String name) {
        List<String> l = new ArrayList<>(List.of(name.split(" ")));
        while (l.size() > 2 && l.get(l.size() - 1).replace(".", "").toLowerCase(Locale.ROOT).matches("jr|sr|ii|iii|iv")) l.remove(l.size() - 1);
        String[] w = l.toArray(new String[0]);
        if (w.length == 1) return new String[]{w[0], ""};
        return new String[]{w[0], w[w.length - 1]};
    }

    static String prefix(String s, int n) { return s.substring(0, Math.min(n, s.length())); }

    static String clean1(String s) { return s == null ? "" : s.trim().replaceAll("\\s+", " "); }

    static List<String> clean(Collection<String> names) {
        List<String> out = new ArrayList<>();
        for (String s : names) { String c = clean1(s); if (!c.isEmpty()) out.add(c); }
        return out;
    }
}
