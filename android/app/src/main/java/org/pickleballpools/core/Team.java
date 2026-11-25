package org.pickleballpools.core;

/** A doubles team. Seeded by combined DUPR (player 1 + player 2). */
public final class Team {
    public String id;
    public String name = "";
    public String player1 = "", player2 = "";
    public double dupr1, dupr2;
    public String duprId1 = "", duprId2 = ""; // the players' DUPR IDs (identity), so they can see their own matches and photos
    public boolean withdrawn;  // dropped out: never seeded, never advances; see Tournament.withdraw
    public String regKey = ""; // set when the team came from registrations (Registration.key), so it is added only once

    public Team(String id) { this.id = id; }

    /** The players' DUPR IDs that are filled in, normalized (Player.normId). */
    public java.util.List<String> duprIds() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : new String[]{duprId1, duprId2}) if (!Player.normId(s).isEmpty()) out.add(Player.normId(s));
        return out;
    }

    /** Combined DUPR, e.g. 5.56 + 5.51 = 11.07. */
    public double combined() { return Math.round((dupr1 + dupr2) * 1000) / 1000.0; }

    /** Team name if given, otherwise "Player 1 / Player 2". */
    public String label() {
        if (name != null && !name.isBlank()) return name.trim();
        return player1 + " / " + player2;
    }
}
