package org.pickleballpools.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Pool standings. Teams are ranked by matches won. Teams tied on wins are separated by, in order:
 * <ol>
 *   <li>Head to head (match wins among the tied teams only)</li>
 *   <li>Overall point differential (all round robin games)</li>
 *   <li>Head-to-head point differential (games among the tied teams only)</li>
 *   <li>Point differential vs the next highest team (the team just above the tied group)</li>
 *   <li>Total points scored (all round robin games)</li>
 * </ol>
 * As soon as a step separates any of the tied teams, each group that is still tied starts over at step 1
 * (so a 3-way tie that becomes a 2-way tie is decided by those two teams' head-to-head).
 * If every step fails, teams are listed by seed (combined DUPR); once the pool is finished they are flagged for the director.
 */
public final class Standings {
    public static final String WITHDREW = "Withdrew";
    public static final String WITHDREW_VOID = "Withdrew: results removed";
    public static final String UNRESOLVED = "Still tied after all tiebreakers: director decides (listed by DUPR)";

    public static final class Row {
        public final Team team;
        public boolean withdrawn;
        public int place, wins, losses, gamesWon, gamesLost, pointsFor, pointsAgainst;
        public String tiebreak = ""; // how this team was separated from teams with the same record, if it was

        Row(Team team) { this.team = team; }

        public int diff() { return pointsFor - pointsAgainst; }
    }

    private final Pool pool;
    private final List<Row> out = new ArrayList<>();

    private Standings(Pool pool) { this.pool = pool; }

    /**
     * Ranked standings for a pool, 1st place first. Only finished matches count.
     * Withdrawn teams are listed last and never rank above a team still playing; matches against a team whose
     * results were removed (Pool.voided) don't count for anyone.
     */
    public static List<Row> of(Pool pool, Map<String, Team> teams) {
        Standings s = new Standings(pool);
        Map<String, Row> rows = new LinkedHashMap<>();
        for (String id : pool.teamIds) rows.put(id, new Row(teams.get(id)));
        for (Match m : pool.matches) {
            if (!m.done() || pool.voided.contains(m.a) || pool.voided.contains(m.b)) continue;
            for (String id : new String[]{m.a, m.b}) {
                Row r = rows.get(id);
                String o = m.opponent(id);
                if (id.equals(m.winner())) r.wins++; else r.losses++;
                r.gamesWon += m.gamesWon(id);
                r.gamesLost += m.gamesWon(o);
                r.pointsFor += m.points(id);
                r.pointsAgainst += m.points(o);
            }
        }
        List<Row> all = new ArrayList<>(), gone = new ArrayList<>();
        for (Row r : rows.values()) {
            r.withdrawn = r.team.withdrawn;
            if (r.withdrawn) { r.tiebreak = pool.voided.contains(r.team.id) ? WITHDREW_VOID : WITHDREW; gone.add(r); }
            else all.add(r);
        }
        all.sort(Comparator.comparingInt((Row r) -> -r.wins));
        int i = 0;
        while (i < all.size()) {
            int j = i;
            while (j < all.size() && all.get(j).wins == all.get(i).wins) j++;
            s.resolve(new ArrayList<>(all.subList(i, j)));
            i = j;
        }
        s.out.addAll(gone);
        for (int k = 0; k < s.out.size(); k++) s.out.get(k).place = k + 1;
        return s.out;
    }

    private void resolve(List<Row> group) {
        if (group.size() == 1) { out.add(group.get(0)); return; }
        Set<String> ids = new HashSet<>();
        for (Row r : group) ids.add(r.team.id);
        Row above = out.isEmpty() ? null : out.get(out.size() - 1);

        List<String> names = new ArrayList<>();
        List<ToDoubleFunction<Row>> steps = new ArrayList<>();
        names.add("head to head");
        steps.add(r -> vs(r.team.id, ids, true));
        names.add("overall point differential");
        steps.add(Row::diff);
        names.add("head-to-head point differential");
        steps.add(r -> vs(r.team.id, ids, false));
        if (above != null) {
            Set<String> one = Set.of(above.team.id);
            names.add("point differential vs " + above.team.label());
            steps.add(r -> vs(r.team.id, one, false));
        }
        names.add("total points scored");
        steps.add(r -> r.pointsFor);

        for (int s = 0; s < steps.size(); s++) {
            ToDoubleFunction<Row> f = steps.get(s);
            double first = f.applyAsDouble(group.get(0));
            boolean separates = false;
            for (Row r : group) if (f.applyAsDouble(r) != first) separates = true;
            if (!separates) continue;
            group.sort(Comparator.comparingDouble(f).reversed());
            int i = 0;
            while (i < group.size()) {
                int j = i;
                double v = f.applyAsDouble(group.get(i));
                while (j < group.size() && f.applyAsDouble(group.get(j)) == v) j++;
                List<Row> sub = new ArrayList<>(group.subList(i, j));
                if (sub.size() == 1 && sub.get(0).tiebreak.isEmpty()) sub.get(0).tiebreak = "Tiebreak: " + names.get(s);
                resolve(sub);
                i = j;
            }
            return;
        }
        group.sort(Comparator.comparingDouble((Row r) -> r.team.combined()).reversed());
        String why = pool.complete() ? UNRESOLVED : "";
        for (Row r : group) { r.tiebreak = why; out.add(r); }
    }

    /** Against the given opponents only: match wins (wins=true) or point differential (wins=false). */
    private double vs(String id, Set<String> opponents, boolean wins) {
        double n = 0;
        for (Match m : pool.matches) {
            if (!m.done() || !m.involves(id) || pool.voided.contains(m.a) || pool.voided.contains(m.b)) continue;
            String o = m.opponent(id);
            if (o.equals(id) || !opponents.contains(o)) continue;
            n += wins ? (id.equals(m.winner()) ? 1 : 0) : m.points(id) - m.points(o);
        }
        return n;
    }
}
