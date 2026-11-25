package org.pickleballpools.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-player registration: reading a payment export (CSV from Stripe, PayPal, Square, Zeffy, a spreadsheet…)
 * and pairing paid players into teams by DUPR ID.
 *
 * Any payment system works: the app never handles money, it only reads who paid. Each column of the export is
 * matched once to a field (FIELDS); the match is guessed from the header row and can be corrected by the director.
 */
public final class Registration {
    private Registration() {}

    public static final String[] FIELDS = {"Name", "Email", "DUPR ID", "Partner name", "Partner DUPR ID", "Payment ID", "Status"};
    public static final int NAME = 0, EMAIL = 1, DUPR = 2, PARTNER_NAME = 3, PARTNER_DUPR = 4, REF = 5, STATUS = 6;

    // ---------- CSV ----------

    /** Parses CSV (or tab-separated) text, handling quoted fields with commas, quotes and line breaks. */
    public static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null) return rows;
        if (text.startsWith("﻿")) text = text.substring(1);
        String firstLine = text.split("\n", 2)[0];
        char sep = firstLine.indexOf('\t') >= 0 && firstLine.indexOf(',') < 0 ? '\t' : ',';
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { f.append('"'); i++; }
                else if (c == '"') quoted = false;
                else f.append(c);
            } else if (c == '"') quoted = true;
            else if (c == sep) { row.add(f.toString().trim()); f.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(f.toString().trim()); f.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
                row = new ArrayList<>();
            } else f.append(c);
        }
        row.add(f.toString().trim());
        if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
        return rows;
    }

    /** Best guess of which column holds each field (index into the header, or -1). */
    public static int[] guessColumns(List<String> header) {
        int[] m = new int[FIELDS.length];
        java.util.Arrays.fill(m, -1);
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i).toLowerCase(Locale.ROOT);
            boolean partner = h.contains("partner");
            if (partner && h.contains("dupr")) setOnce(m, PARTNER_DUPR, i);
            else if (partner && h.contains("name")) setOnce(m, PARTNER_NAME, i);
            else if (!partner && h.contains("dupr")) setOnce(m, DUPR, i);
            else if (h.contains("email")) setOnce(m, EMAIL, i);
            else if (h.contains("status")) setOnce(m, STATUS, i);
            else if (h.contains("name") && !h.contains("product") && !h.contains("statement")) setOnce(m, NAME, i);
            else if (h.equals("id") || h.contains("payment id") || h.contains("transaction id")
                    || h.contains("payment intent") || h.contains("checkout session")) setOnce(m, REF, i);
        }
        return m;
    }

    private static void setOnce(int[] m, int field, int col) { if (m[field] < 0) m[field] = col; }

    public static String missingField(int[] map) {
        for (int f : new int[]{NAME, DUPR}) if (map[f] < 0) return FIELDS[f];
        return null;
    }

    /** Status column (if mapped) must say paid/succeeded/complete and not refunded/failed. */
    static boolean paid(String status) {
        if (status == null || status.isBlank()) return true;
        String s = status.toLowerCase(Locale.ROOT);
        if (s.contains("refund") || s.contains("fail") || s.contains("cancel") || s.contains("incomplete")
                || s.contains("pending") || s.contains("uncaptured")) return false;
        return s.contains("paid") || s.contains("succeeded") || s.contains("complete") || s.contains("success");
    }

    public static final class ImportResult {
        public int added, alreadyHad, notPaid;
        public final List<String> problems = new ArrayList<>();
    }

    /** Adds paid rows to players. Rows already imported (same payment ID) are skipped, so re-importing is safe. */
    public static ImportResult importRows(List<List<String>> rows, int[] map, List<Player> players) {
        ImportResult r = new ImportResult();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (!paid(cell(row, map[STATUS]))) { r.notPaid++; continue; }
            String ref = cell(row, map[REF]);
            if (!ref.isEmpty() && players.stream().anyMatch(p -> p.ref.equals(ref))) { r.alreadyHad++; continue; }
            Player p = new Player();
            p.ref = ref;
            p.name = cell(row, map[NAME]);
            p.email = cell(row, map[EMAIL]);
            p.duprId = Player.normId(cell(row, map[DUPR]));
            p.partnerName = cell(row, map[PARTNER_NAME]);
            p.partnerDuprId = Player.normId(cell(row, map[PARTNER_DUPR]));
            if (p.name.isEmpty() && p.duprId.isEmpty()) continue; // blank line
            if (p.duprId.isEmpty()) r.problems.add("Row " + (i + 1) + " (" + p.name + "): no DUPR ID");
            players.add(p);
            r.added++;
        }
        return r;
    }

    private static String cell(List<String> row, int col) { return col >= 0 && col < row.size() ? row.get(col) : ""; }

    // ---------- Pairing ----------

    public static final class Pairing {
        public final List<Player[]> ready = new ArrayList<>();        // both paid, both named each other
        public final List<Player> waiting = new ArrayList<>();        // named a partner who hasn't paid yet
        public final List<Player[]> mismatch = new ArrayList<>();     // {player, the partner they named, who named someone else}
        public final List<Player> needsPartner = new ArrayList<>();   // no partner listed
        public final List<Player> duplicates = new ArrayList<>();     // second+ payment for the same DUPR ID: refund?
        public final List<Player> noId = new ArrayList<>();           // no DUPR ID: fix by hand
    }

    public static Pairing pair(List<Player> players) { return pair(players, false); }

    /** Singles: every player with a DUPR ID (paid once) is ready on their own; partners are ignored. */
    public static Pairing pair(List<Player> players, boolean singles) {
        Pairing p = new Pairing();
        Map<String, Player> byId = new LinkedHashMap<>();
        for (Player x : players) {
            if (x.duprId.isEmpty()) p.noId.add(x);
            else if (byId.containsKey(x.duprId)) p.duplicates.add(x);
            else byId.put(x.duprId, x);
        }
        for (Player x : byId.values()) {
            if (singles) { p.ready.add(new Player[]{x}); continue; }
            if (x.partnerDuprId.isEmpty() || x.partnerDuprId.equals(x.duprId)) { p.needsPartner.add(x); continue; }
            Player y = byId.get(x.partnerDuprId);
            if (y == null) p.waiting.add(x);
            else if (x.duprId.equals(y.partnerDuprId)) { if (x.duprId.compareTo(y.duprId) < 0) p.ready.add(new Player[]{x, y}); }
            else p.mismatch.add(new Player[]{x, y});
        }
        return p;
    }

    /** Stable key for an entry (one player in singles, a pair in doubles), so it is added to the tournament once. */
    public static String key(Player[] entry) { return entry.length == 1 ? entry[0].duprId : key(entry[0], entry[1]); }

    /** A payment file without a partner DUPR ID column is a singles event. */
    public static boolean singlesFile(int[] map) { return map[PARTNER_DUPR] < 0; }

    /** Stable key for a pair, so a team is only added to the tournament once. */
    public static String key(Player a, Player b) {
        return a.duprId.compareTo(b.duprId) < 0 ? a.duprId + "+" + b.duprId : b.duprId + "+" + a.duprId;
    }
}
