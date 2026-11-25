package org.pickleballpools.core;

/** A doubles team. Seeded by combined DUPR (player 1 + player 2). */
public final class Team {
    public String id;
    public String player1 = "", player2 = "";
    public double dupr1, dupr2;
    public String duprId1 = "", duprId2 = "";
    public String email1 = "", email2 = "";   // where their score sheet photos and results are emailed (optional) // the players' DUPR IDs (identity), so they can see their own matches and photos
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

    /** "Player 1 / Player 2" (teams have no names: they are the two players). */
    public String label() {
        if (player2 == null || player2.isBlank()) return player1;
        if (player1 == null || player1.isBlank()) return player2;
        return player1 + " / " + player2;
    }
}
