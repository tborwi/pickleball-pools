package org.pickleballpools.core;

/** A doubles team. Seeded by combined DUPR (player 1 + player 2). */
public final class Team {
    public String id;
    public String name = "";
    public String player1 = "", player2 = "";
    public double dupr1, dupr2;

    public Team(String id) { this.id = id; }

    /** Combined DUPR, e.g. 5.56 + 5.51 = 11.07. */
    public double combined() { return Math.round((dupr1 + dupr2) * 1000) / 1000.0; }

    /** Team name if given, otherwise "Player 1 / Player 2". */
    public String label() {
        if (name != null && !name.isBlank()) return name.trim();
        return player1 + " / " + player2;
    }
}
