package org.pickleballpools.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.pickleballpools.core.Player;
import org.pickleballpools.core.Registration;
import org.pickleballpools.core.Team;
import org.pickleballpools.core.Tournament;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Registrations: every player pays for themselves on the payment page (Stripe Payment Link or any other system).
 * The director loads the payment system's export file here; players are paired by DUPR ID, the director enters
 * each player's DUPR rating, and ready teams are added to the tournament.
 */
final class RegistrationsScreen {
    static final int PICK_FILE = 7;
    final MainActivity a;

    RegistrationsScreen(MainActivity a) { this.a = a; }

    Tournament t() { return a.t; }

    void show() {
        a.page.removeAllViews();
        a.title("Registrations");
        a.note(t().players.size() + " paid players. " + (t().singles
                ? "Singles: each player is their own entry."
                : "Everyone pays for themselves; partners are matched by DUPR ID."));
        a.button("Load a payment file (CSV)", v -> pickFile());
        a.button("Paste payment data", v -> paste());
        a.button("+ Add a player by hand (cash, check…)", v -> editPlayer(null));
        a.button(t().paymentLink.isEmpty() ? "Set the payment link" : "Payment link: " + t().paymentLink, v -> editLink());
        if (!t().paymentLink.isEmpty()) a.button("Share the payment link", v -> shareLink());

        Registration.Pairing p = Registration.pair(t().players, t().singles);
        String unit = t().singles ? "player" : "team";

        a.heading("✅ " + (t().singles ? "Players" : "Teams") + " ready (" + p.ready.size() + ")");
        if (p.ready.isEmpty()) a.note("None yet.");
        int needRating = 0, toAdd = 0;
        for (Player[] pr : p.ready) {
            String key = Registration.key(pr);
            boolean added = t().teams.stream().anyMatch(x -> x.regKey.equals(key));
            if (!added && java.util.Arrays.stream(pr).anyMatch(x -> x.rating <= 0)) needRating++;
            else if (!added) toAdd++;
            if (!t().singles) a.text(added ? "Team ✓ in the tournament" : "Team", 15, true, MainActivity.GREY);
            else if (added) a.text("✓ in the tournament", 15, true, MainActivity.GREY);
            for (Player x : pr) playerCard(x, "");
        }
        if (toAdd > 0) a.button("Add " + toAdd + " ready " + unit + (toAdd == 1 ? "" : "s") + " to the tournament", v -> addTeams());
        if (needRating > 0) a.note(needRating + " " + unit + "(s) need DUPR ratings first: tap a player to enter their rating.");

        section("⏳ Waiting on partner to pay", p.waiting.size());
        for (Player x : p.waiting)
            playerCard(x, "Partner " + x.partnerName + " (" + x.partnerDuprId + ") hasn't paid yet");
        section("⚠️ Partners don't match", p.mismatch.size());
        for (Player[] pr : p.mismatch)
            playerCard(pr[0], "Named " + pr[1].name + ", but " + pr[1].name + " named "
                    + (pr[1].partnerName.isEmpty() ? pr[1].partnerDuprId : pr[1].partnerName));
        section("🙋 Needs a partner", p.needsPartner.size());
        for (Player x : p.needsPartner) playerCard(x, "Tap to set a partner's DUPR ID");
        section("🔁 Paid twice (refund?)", p.duplicates.size());
        for (Player x : p.duplicates) playerCard(x, "Same DUPR ID as another payment. Delete after refunding.");
        section("❓ No DUPR ID", p.noId.size());
        for (Player x : p.noId) playerCard(x, "Tap to enter their DUPR ID");
    }

    void section(String title, int n) { if (n > 0) a.heading(title + " (" + n + ")"); }

    void playerCard(Player x, String note) {
        String s = x.name + "   ·   " + (x.duprId.isEmpty() ? "no DUPR ID" : x.duprId) + "\n     "
                + (x.rating > 0 ? "DUPR " + MainActivity.num(x.rating) + " (checked " + x.ratingDate + ")" : "DUPR rating: tap to enter");
        if (!note.isEmpty()) s += "\n     " + note;
        TextView v = a.card(a.text(s, 17, false, x.rating > 0 || !note.isEmpty() ? MainActivity.INK : MainActivity.RED));
        v.setOnClickListener(c -> editPlayer(x));
    }

    void addTeams() {
        if (t().started()) { a.toast("The tournament has started, so teams can't be added."); return; }
        Tournament.AddResult r = t().addReadyTeams();
        a.save();
        a.toast("Added " + r.added + (t().singles ? " player" : " team") + (r.added == 1 ? "" : "s") + ". See " + (t().singles ? "Players." : "Teams."));
        a.refresh();
    }

    // ---------- Player ----------

    void editPlayer(Player existing) {
        Player x = existing == null ? new Player() : existing;
        LinearLayout f = a.form();
        EditText rating = a.field(f, "DUPR rating (look it up on DUPR)", MainActivity.num(x.rating), true);
        EditText name = a.field(f, "Name", x.name, false);
        EditText email = a.field(f, "Email", x.email, false);
        EditText id = a.field(f, "DUPR ID", x.duprId, false);
        EditText pn = a.field(f, "Partner's name", x.partnerName, false);
        EditText pid = a.field(f, "Partner's DUPR ID", x.partnerDuprId, false);
        if (t().singles) for (EditText e : new EditText[]{pn, pid}) { e.setVisibility(android.view.View.GONE); hideLabelBefore(f, e); }
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        for (EditText e : new EditText[]{id, pid})
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        if (!x.ref.isEmpty()) f.addView(a.free(a.text("Payment: " + x.ref, 13, false, MainActivity.GREY)));
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle(existing == null ? "Add a player" : "Player")
                .setView(a.scroll(f)).setPositiveButton("Save", null).setNegativeButton("Cancel", null);
        if (existing != null)
            b.setNeutralButton("Delete", (d, w) -> a.confirm("Delete " + x.name + "?",
                    "Only for refunds or mistakes. Teams already added to the tournament stay.", () -> {
                        t().players.remove(x); a.save(); a.refresh();
                        offerWithdraw(x); }));
        AlertDialog d = b.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (name.getText().toString().isBlank()) { a.toast("Enter a name"); return; }
            String r = rating.getText().toString().trim();
            Double val = r.isEmpty() ? Double.valueOf(0) : MainActivity.parse(rating);
            if (val == null || val < 0 || val > 8.5 || !r.matches("\\d*(\\.\\d{0,3})?")) { a.toast("DUPR ratings look like 4.25 or 4.255"); return; }
            x.name = name.getText().toString().trim();
            x.email = email.getText().toString().trim();
            x.duprId = Player.normId(id.getText().toString());
            x.partnerName = pn.getText().toString().trim();
            x.partnerDuprId = Player.normId(pid.getText().toString());
            if (val != x.rating) { x.rating = val; x.ratingDate = val > 0 ? LocalDate.now().toString() : ""; }
            if (existing == null) t().players.add(x);
            a.save();
            d.dismiss();
            a.refresh();
        });
    }

    /** A registered player was removed (refund, drop-out): if their team is already in the tournament, offer to withdraw it. */
    void offerWithdraw(Player x) {
        if (x.duprId.isEmpty()) return;
        for (Team team : t().teams) {
            if (team.withdrawn || !java.util.Arrays.asList(team.regKey.split("\\+")).contains(x.duprId)) continue;
            if (t().round1.isEmpty())
                a.confirm("Remove " + team.label() + " too?", x.name + "'s team was already added to Teams.",
                        () -> { t().teams.remove(team); a.save(); a.refresh(); });
            else
                a.confirm("Withdraw " + team.label() + "?", x.name + "'s team is in the tournament. You can also keep it and "
                        + "replace " + x.name + " with a substitute in Teams.", () -> a.withdraw(team));
            return;
        }
    }

    // ---------- Payment link ----------

    void editLink() {
        LinearLayout f = a.form();
        f.addView(a.free(a.text("The web page where players pay, e.g. a Stripe Payment Link. Ask players for: "
                + "their DUPR ID, partner's name and partner's DUPR ID.", 15, false, MainActivity.GREY)));
        EditText e = a.field(f, "Payment link", t().paymentLink, false);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(a).setTitle("Payment link").setView(a.scroll(f))
                .setPositiveButton("Save", (d, w) -> { t().paymentLink = e.getText().toString().trim(); a.save(); a.refresh(); })
                .setNegativeButton("Cancel", null).show();
    }

    void shareLink() {
        String msg = "Register for " + t().name + ": " + t().paymentLink + "\n\n" + (t().singles
                ? "Singles. Have your DUPR ID ready."
                : "Everyone registers and pays for themselves. Have your DUPR ID and your partner's name and DUPR ID ready.");
        a.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, msg), "Share the payment link"));
    }

    // ---------- Import ----------

    void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/csv", "text/comma-separated-values", "text/plain",
                        "text/tab-separated-values", "application/csv", "application/vnd.ms-excel"});
        a.startActivityForResult(i, PICK_FILE);
    }

    void onFile(Uri uri) {
        try (InputStream in = a.getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            matchColumns(out.toString(StandardCharsets.UTF_8.name()));
        } catch (Exception e) {
            a.toast("Couldn't read that file: " + e.getMessage());
        }
    }

    void paste() {
        LinearLayout f = a.form();
        f.addView(a.free(a.text("Paste rows copied from your payment system or a spreadsheet, including the header row.",
                15, false, MainActivity.GREY)));
        EditText box = new EditText(a);
        box.setMinLines(6);
        box.setGravity(Gravity.TOP);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        f.addView(box);
        new AlertDialog.Builder(a).setTitle("Paste payment data").setView(a.scroll(f))
                .setPositiveButton("Next", (d, w) -> matchColumns(box.getText().toString()))
                .setNegativeButton("Cancel", null).show();
    }

    /** Asks which column is which (pre-filled with a guess and the last match used), then imports. */
    void matchColumns(String text) {
        List<List<String>> rows = Registration.parseCsv(text);
        if (rows.size() < 2) { a.toast("Need a header row and at least one payment"); return; }
        List<String> header = rows.get(0), sample = rows.get(1);
        int[] map = Registration.guessColumns(header);
        int[] last = t().importColumns;
        if (last != null && last.length == map.length)
            for (int i = 0; i < map.length; i++) if (map[i] < 0 && last[i] < header.size()) map[i] = last[i];

        List<String> choices = new ArrayList<>();
        choices.add("(not in this file)");
        for (int c = 0; c < header.size(); c++) {
            String ex = c < sample.size() ? sample.get(c) : "";
            choices.add(header.get(c) + (ex.isEmpty() ? "" : "   e.g. " + (ex.length() > 24 ? ex.substring(0, 24) + "…" : ex)));
        }
        LinearLayout f = a.form();
        f.addView(a.free(a.text("Check which column holds each item. This is remembered for next time.", 15, false, MainActivity.GREY)));
        Spinner[] sp = new Spinner[Registration.FIELDS.length];
        for (int i = 0; i < sp.length; i++) {
            f.addView(a.free(a.text(Registration.FIELDS[i], 16, true, MainActivity.INK)));
            sp[i] = new Spinner(a);
            sp[i].setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, choices));
            sp[i].setSelection(map[i] + 1);
            f.addView(sp[i]);
        }
        AlertDialog d = new AlertDialog.Builder(a).setTitle((rows.size() - 1) + " rows found").setView(a.scroll(f))
                .setPositiveButton("Import", null).setNegativeButton("Cancel", null).show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int[] m = new int[sp.length];
            for (int i = 0; i < sp.length; i++) m[i] = sp[i].getSelectedItemPosition() - 1;
            String missing = Registration.missingField(m);
            if (missing != null) { a.toast("Pick the column for " + missing); return; }
            boolean single = Registration.singlesFile(m), switched = false;
            if (single != t().singles && t().teams.isEmpty() && !t().started()) { t().singles = single; switched = true; }
            Registration.ImportResult r = Registration.importRows(rows, m, t().players);
            t().importColumns = m;
            a.save();
            d.dismiss();
            a.refresh();
            String msg = "Added " + r.added + " paid player" + (r.added == 1 ? "" : "s") + ".";
            if (switched) msg += single ? "\n\nNo partner DUPR ID column, so this is a SINGLES event: each player is their own entry."
                    : "\n\nThis file has partner DUPR IDs, so this is a DOUBLES event.";
            else if (single != t().singles) msg += "\n\nNote: this file looks like " + (single ? "singles" : "doubles")
                    + ", but the tournament is " + (t().singles ? "singles" : "doubles") + ". Change it in Settings if needed.";
            if (r.alreadyHad > 0) msg += "\n" + r.alreadyHad + " were already loaded (skipped).";
            if (r.notPaid > 0) msg += "\n" + r.notPaid + " not paid or refunded (skipped).";
            if (!r.problems.isEmpty()) msg += "\n\n" + String.join("\n", r.problems);
            new AlertDialog.Builder(a).setMessage(msg).setPositiveButton("OK", null).show();
        });
    }

    /** field() puts a label above each input; hide it along with the input. */
    static void hideLabelBefore(LinearLayout f, android.view.View input) {
        int i = f.indexOfChild(input);
        if (i > 0) f.getChildAt(i - 1).setVisibility(android.view.View.GONE);
    }
}
