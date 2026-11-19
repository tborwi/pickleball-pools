package org.pickleballpools.core;

/** One registered (paid) player. Every player pays for themselves; partners are matched by DUPR ID. */
public final class Player {
    public String ref = "";            // payment reference from the payment system (dedupes re-imports); "" if added by hand
    public String name = "", email = "";
    public String duprId = "";         // the player's identity
    public String partnerName = "", partnerDuprId = "";
    public double rating;              // DUPR rating looked up by the director (0 = not yet)
    public String ratingDate = "";     // when it was looked up, e.g. 2026-11-18

    /** DUPR IDs compare case-insensitively, ignoring spaces, '#' and dashes. */
    public static String normId(String s) {
        return s == null ? "" : s.replaceAll("[\\s#-]", "").toUpperCase(java.util.Locale.ROOT);
    }
}
