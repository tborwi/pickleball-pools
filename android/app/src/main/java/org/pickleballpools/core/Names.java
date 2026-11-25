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
 * of the last name are added until they differ ("Pat Lee" and "Pat Lew."). Players with exactly the same full name
 * are numbered in team order: "Pat L.", "Pat L. (2)", "Pat L. (3)"; their emails tell them their number.
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
                if (other.equalsIgnoreCase(me)) continue; // same person's name, or a namesake (numbered later)
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

    /**
     * Short names for every player in the tournament, unique within it, keyed by team id + "#1" / "#2"
     * (so two players with the same full name can get different numbers). Use {@link #player} and {@link #team}.
     */
    public static Map<String, String> forTournament(Tournament t) {
        List<String> all = new ArrayList<>();
        for (Team x : t.teams) { all.add(x.player1); all.add(x.player2); }
        Map<String, String> base = shortNames(all);
        Map<String, Integer> seen = new HashMap<>();
        Map<String, String> firstSpelling = new HashMap<>();
        Map<String, String> out = new HashMap<>();
        for (Team x : t.teams)
            for (int slot = 1; slot <= 2; slot++) {
                String full = clean1(slot == 1 ? x.player1 : x.player2);
                if (full.isEmpty()) continue;
                String k = full.toLowerCase(Locale.ROOT);
                int n = seen.merge(k, 1, Integer::sum);
                String shown = of(base, firstSpelling.computeIfAbsent(k, z -> full));
                out.put(x.id + "#" + slot, shown + (n > 1 ? " (" + n + ")" : ""));
            }
        return out;
    }

    /** Short name of player 1 or 2 of a team (map from {@link #forTournament}). */
    public static String player(Map<String, String> map, Team x, int slot) {
        String s = map.get(x.id + "#" + slot);
        return s != null ? s : clean1(slot == 1 ? x.player1 : x.player2);
    }

    /** The number added to a player whose full name is the same as another player's, or 0. */
    public static int number(String shortName) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(" \\((\\d+)\\)$").matcher(shortName);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** "Pat L. / Sam R." */
    public static String team(Map<String, String> map, Team x) {
        String a = player(map, x, 1), b = player(map, x, 2);
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
