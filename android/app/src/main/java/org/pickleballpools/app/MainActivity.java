package org.pickleballpools.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.pickleballpools.core.Match;
import org.pickleballpools.core.Pool;
import org.pickleballpools.core.PrintSheets;
import org.pickleballpools.core.Rules;
import org.pickleballpools.core.Standings;
import org.pickleballpools.core.Team;
import org.pickleballpools.core.Tournament;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The whole UI, built in code (no XML) with big text and big buttons.
 * Screens: Home -> Teams | a Pool (standings + matches). Every change is saved immediately.
 */
public class MainActivity extends Activity {
    /** Colors for the current theme (light or dark); set by palette() before any screen is drawn. */
    static int GREEN, LIGHT, GREY, RED, INK, PAGE, CARD, PENDING, EDGE;
    static boolean dark;
    static final String[] THEMES = {"System", "Light", "Dark"};

    static void palette(boolean d) {
        dark = d;
        if (d) {
            GREEN = 0xFF81C784; LIGHT = 0xFF1F3323; GREY = 0xFFB4B4B4; RED = 0xFFEF9A9A; INK = 0xFFECECEC;
            PAGE = 0xFF121212; CARD = 0xFF1E1E1E; PENDING = 0xFF3A3220; EDGE = 0xFF3A3A3A;
        } else {
            GREEN = 0xFF1B5E20; LIGHT = 0xFFE8F5E9; GREY = 0xFF555555; RED = 0xFFB71C1C; INK = 0xFF000000;
            PAGE = 0xFFFFFFFF; CARD = 0xFFF5F5F5; PENDING = 0xFFFFF8E1; EDGE = 0xFFCCCCCC;
        }
    }

    int themeSetting() { return getSharedPreferences("ui", MODE_PRIVATE).getInt("theme", 0); }

    /** 0 = follow the phone, 1 = light, 2 = dark. */
    boolean wantDark() {
        int t = themeSetting();
        if (t != 0) return t == 2;
        return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    void chooseTheme() {
        int cur = themeSetting();
        new AlertDialog.Builder(this).setTitle("Appearance").setSingleChoiceItems(THEMES, cur, (d, w) -> {
            d.dismiss();
            if (w == cur) return;
            getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("theme", w).apply();
            recreate();
        }).setNegativeButton("Cancel", null).show();
    }

    Tournament t;
    String key = ""; // which saved tournament file t is (Store)
    LinearLayout page;
    final Deque<Runnable> back = new ArrayDeque<>();
    Runnable current;
    final RegistrationsScreen regs = new RegistrationsScreen(this);
    AccountScreen account;
    Live live;
    CaptainScreen captain;
    Receipts receipts;
    Venues venues;

    @SuppressWarnings("deprecation")
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == Receipts.TAKE_PHOTO) { receipts.onPhoto(res == RESULT_OK); return; }
        if (req == RegistrationsScreen.PICK_FILE && res == RESULT_OK && data != null && data.getData() != null) regs.onFile(data.getData());
    }

    @Override protected void onCreate(Bundle b) {
        palette(wantDark());
        setTheme(dark ? android.R.style.Theme_Material : android.R.style.Theme_Material_Light_DarkActionBar);
        super.onCreate(b);
        t = Store.load(this);
        key = Store.current(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(PAGE);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        page.setPadding(pad, pad, pad, dp(48));
        sv.addView(page);
        sv.setFitsSystemWindows(true);
        setContentView(sv);
        account = new AccountScreen(this);
        live = new Live(this, account.api);
        captain = new CaptainScreen(this, live);
        receipts = new Receipts(this, account.api);
        venues = new Venues(this);
        if (t == null) { key = Store.newKey(); t = clubDefaults(new Tournament()); Store.setCurrent(this, key); save(); }
        go(this::home, false);
        signInLink(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        account.refreshMe(); // roles may have changed (e.g. made a court captain)
        live.start(); // live scoring: send queued scores, publish pools, fetch captains' scores (every 20 s)
    }

    @Override protected void onPause() {
        super.onPause();
        live.stop();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        signInLink(i);
    }

    /** Google sign-in returns here through the link pickleballpools://signin?code=... */
    void signInLink(Intent i) {
        if (i != null && i.getData() != null && "pickleballpools".equals(i.getData().getScheme())) account.onSignInLink(i.getData());
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (back.isEmpty()) super.onBackPressed();
        else { live.extra = null; current = back.pop(); current.run(); }
    }

    void go(Runnable screen, boolean remember) {
        if (remember && current != null) back.push(current);
        current = screen;
        live.extra = null;
        screen.run();
        ((ScrollView) page.getParent()).scrollTo(0, 0);
    }

    void refresh() { current.run(); }

    void save() {
        try { Store.save(this, key, t); } catch (RuntimeException e) { toast("Could not save! " + e.getMessage()); }
    }

    // ---------- Home ----------

    void home() {
        page.removeAllViews();
        logo();
        title(t.name);
        int out = t.teams.size() - t.seeded().size();
        if (!t.date.isEmpty()) note(t.date);
        note((t.singles ? "Singles · " + t.seeded().size() + " players" : t.seeded().size() + " teams") + (out > 0 ? " (" + out + " dropped out)" : "") + " · " + stage(t));
        String courts = t.courts.isEmpty() ? "courts numbered from " + t.firstCourt : "courts " + Tournament.courtsText(t.courts);
        String where = (t.venue.isEmpty() ? "No venue set" : t.venue) + " · " + courts;
        if (t.started()) note("📍 " + where + " (locked)");
        else smallButton("📍 " + where + "  (change)", v -> venues.pick());
        List<org.pickleballpools.core.Pool> now = t.round2.isEmpty() ? t.round1 : t.round2;
        String shortOf = org.pickleballpools.core.PrintSheets.shortOfCourts(t, now);
        if (shortOf != null) text("⚠ " + shortOf, 16, true, RED);

        captainButtons();
        if (live.directing()) {
            int w = live.waiting();
            note("Live scoring is on: court captains' scores arrive by themselves." + (w > 0 ? " " + w + " of your scores waiting for signal." : ""));
        }
        button("Registrations (" + t.players.size() + " paid players)", v -> go(regs::show, true));
        button((t.singles ? "Players (" : "Teams (") + t.seeded().size() + ")", v -> go(this::teams, true));

        if (t.round1.isEmpty()) {
            button("Make Round 1 pools", v -> makeRound1());
        } else {
            if (!t.started()) {
                text("Check the pools, then start. Starting locks the teams and pools and opens score entry "
                        + "(court captains too).", 17, false, MainActivity.INK);
                button("▶  Start the tournament", v -> startTournament());
            }
            heading("Round 1");
            if (t.round2.isEmpty()) redoBanner(t.round1, "Round 1");
            for (Pool p : t.round1) poolButton(p);
            if (t.round2.isEmpty() && !t.started()) smallButton("Redo Round 1 pools…", v -> redo(t.round1, "Round 1"));
        }
        if (Tournament.complete(t.round1) && t.round2.isEmpty()) button("Make Round 2 pools", v -> makeRound2());
        if (!t.round2.isEmpty()) {
            heading("Round 2");
            redoBanner(t.round2, "Round 2");
            for (Pool p : t.round2) poolButton(p);
            List<Team> f = t.finalists();
            if (!f.isEmpty()) {
                heading("Qualified for the Finals");
                for (Team x : f) text("🏆 " + x.label(), 20, true, GREEN);
            }
        }

        if (t.started()) {
            note("🔒 Started" + (t.startedAt < 100000 ? "" : " " + new java.text.SimpleDateFormat("EEE h:mm a", Locale.US).format(new java.util.Date(t.startedAt)))
                    + ". Teams and pools are locked.");
            smallButton("Unlock the pools…", v -> confirm("Unlock the pools?",
                    "Only if the pools really must change (for example, rebuilt after a mistake). Scores already entered are kept, "
                            + "but redoing a round's pools erases that round's scores. Start again when they are right.",
                    () -> { t.unlock(); live.event("unlocked"); save(); refresh(); }));
        }
        if (live.directing()) {
            button("Share the live scores link (players, families)", v -> shareLiveLink());
            button("Email the results to all players", v -> emailResults());
            button("History (who changed what)", v -> go(account::history, true));
        }
        heading("More");
        if (!t.round1.isEmpty()) button("Print sheets (Excel)", v -> printSheets());
        button("Share results (text, email…)", v -> share());
        button("Settings", v -> settings());
        button("Appearance: " + THEMES[themeSetting()], v -> chooseTheme());
        button("Help (how to use the app)", v -> startActivity(new Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://tborwi.github.io/pickleball-pools/"))));
        button(account.api.signedIn() ? "Account: " + AccountScreen.roleNames(account.api.me().optJSONArray("roles"))
                : "Account (sign in, roles)", v -> go(account::show, true));
        button("All tournaments (new, switch)", v -> go(this::tournaments, true));
    }

    String liveLink() { return account.api.server() + "/t/" + t.serverId; }

    /** Players have no accounts: they follow a public page (no sign-in) that updates every 20 seconds. */
    void shareLiveLink() {
        String msg = t.name + ": live scores and standings\n" + liveLink();
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, t.name + " live scores").putExtra(Intent.EXTRA_TEXT, msg), "Share the live scores link"));
    }

    void emailResults() {
        account.api.call("POST", "/tournaments/" + t.serverId + "/results-email?dry=1", new org.json.JSONObject(), r -> {
            int n = r.optInt("recipients");
            if (n == 0) { toast("No player emails yet: add them on the team form or load registrations."); return; }
            confirm("Email the results?", "Sends the standings" + (t.finalists().isEmpty() ? "" : " and Finals qualifiers")
                            + " and the live scores link to " + n + " player" + (n == 1 ? "" : "s") + ", one email each.\n\n"
                            + (Tournament.complete(t.round2) ? "" : "⚠️ The tournament isn't finished yet, so these are the standings so far."),
                    () -> account.api.call("POST", "/tournaments/" + t.serverId + "/results-email", new org.json.JSONObject(),
                            rr -> toast("Results emailed to " + rr.optInt("sent") + " players")));
        });
    }

    void startTournament() {
        int n = 0;
        for (Pool p : t.round1) n += p.matches.size();
        confirm("Start the tournament?", t.round1.size() + " pools, " + t.seeded().size() + " teams, " + n + " matches.\n\n"
                        + "This locks the teams and pools: no more redoing pools or adding teams. Teams can still drop out "
                        + "(USA Pickleball 15.B.4.a). Score entry opens" + (live.directing()
                        ? ", court captains see their matches, and every player with an email is sent the live scores link and their schedule." : "."),
                () -> {
                    String err = t.start(System.currentTimeMillis());
                    if (err != null) { toast(err); return; }
                    save();
                    if (live.directing()) live.syncNow();
                    refresh();
                    live.event("started");
                    toast("Tournament started");
                });
    }

    /** "Score my courts" for each tournament this person is a court captain of. */
    void captainButtons() {
        java.util.Set<String> shown = new java.util.HashSet<>();
        org.json.JSONArray ts = account.api.signedIn() ? account.api.me().optJSONArray("tournaments") : null;
        for (int i = 0; ts != null && i < ts.length(); i++) {
            org.json.JSONObject x = ts.optJSONObject(i);
            if (!"captain".equals(x.optString("role"))) continue;
            shown.add(x.optString("id"));
            button("🏓 Score my courts" + (x.optString("courts").isEmpty() ? "" : " (" + x.optString("courts") + ")") + ": "
                    + x.optString("name"), v -> captain.open(x.optString("id"), x.optString("name")));
        }

    }

    /** Red warning + button when a round's pools no longer match the teams still in. */
    void redoBanner(List<Pool> round, String name) {
        String why = t.outOfDate(round);
        if (why == null || t.started()) return; // locked: withdrawn teams' matches just don't count (15.B.4.a)
        int scored = Tournament.scored(round);
        text("⚠️ " + why + "." + (scored == 0 ? " No scores yet, so the pools can be redone."
                : " " + scored + " match score" + (scored == 1 ? " is" : "s are") + " already entered."), 17, true, RED);
        button("Redo " + name + " pools", v -> redo(round, name));
    }

    /**
     * Rebuilds a round's pools from the teams still in: Round 1 by DUPR seed, Round 2 from Round 1 results
     * (so the next-best team moves up). Scores in that round are erased, so it asks first.
     */
    void redo(List<Pool> round, String name) {
        boolean r1 = round == t.round1;
        int teams = t.seeded().size(), scored = Tournament.scored(round);
        if (r1 && teams < t.poolCount * 2) { toast("Only " + teams + " teams left: need " + t.poolCount * 2 + " for " + t.poolCount + " pools. Lower the pool count in Settings."); return; }
        String msg = r1 ? "Rebuilds the " + t.poolCount + " pools from the " + teams + " teams still in, seeded by DUPR."
                : "Rebuilds the Championship and Consolation pools from Round 1 results, leaving out teams that dropped out. "
                  + "The next-best team from their Round 1 pool moves up.";
        msg += scored == 0 ? "\n\nNo scores have been entered in " + name + " yet."
                : "\n\n⚠️ This ERASES the " + scored + " match score" + (scored == 1 ? "" : "s") + " already entered in " + name + ".";
        confirm("Redo " + name + " pools?", msg, () -> {
            if (r1) t.startRound1(); else t.startRound2();
            save();
            back.clear();
            current = this::home;
            refresh();
            toast(name + " pools redone");
        });
    }

    void smallButton(String label, View.OnClickListener l) {
        TextView v = text(label, 16, false, GREEN);
        v.setPadding(dp(4), dp(10), dp(4), dp(10));
        v.setOnClickListener(l);
    }

    static String stage(Tournament t) {
        if (t.round1.isEmpty()) return "Adding teams";
        if (!Tournament.complete(t.round1)) return "Round 1 in progress";
        if (t.round2.isEmpty()) return "Round 1 finished";
        if (!Tournament.complete(t.round2)) return "Round 2 in progress";
        return "Finished";
    }

    void poolButton(Pool p) {
        int done = 0;
        for (Match m : p.matches) if (m.done()) done++;
        String s = p.title() + "   (" + done + " of " + p.matches.size() + " matches)" + (p.complete() ? " ✓" : "");
        button(s, v -> go(() -> pool(p), true));
    }

    void makeRound1() {
        int min = t.poolCount * 2;
        if (t.teams.size() < min) { toast("Need at least " + min + " teams for " + t.poolCount + " pools"); return; }
        confirm("Make Round 1 pools?",
                "Teams are seeded by combined DUPR and snaked into " + t.poolCount + " pools.\n\n"
                        + "After this you can still fix names, but DUPR changes won't move teams between pools.",
                () -> { t.startRound1(); save(); refresh(); });
    }

    void makeRound2() {
        confirm("Make Round 2 pools?",
                "Top " + t.advancePerPool + " of each pool go to the Championship bracket, the rest to Consolation. "
                        + "Teams are seeded by their Round 1 finish, then record.\n\n"
                        + "Round 1 scores should be final before you do this.",
                () -> { t.startRound2(); save(); refresh(); });
    }

    // ---------- Teams ----------

    void teams() {
        page.removeAllViews();
        String unit = t.singles ? "player" : "team";
        title(t.singles ? "Players (singles)" : "Teams");
        note("Listed by seed (" + (t.singles ? "DUPR" : "combined DUPR") + ", highest first). Tap a " + unit + " to change it.");
        if (t.started()) note("🔒 The tournament has started: " + unit + "s can be changed or withdrawn, not added.");
        else {
            button("+ Add a " + unit, v -> editTeam(null));
            button("Paste a list of " + unit + "s", v -> pasteTeams());
        }
        if (!t.round1.isEmpty()) note("Someone can't play? Tap the team: replace the player (change name and DUPR), or Withdraw the team.");
        List<Team> s = t.seeded();
        for (int i = 0; i < s.size(); i++) {
            Team x = s.get(i);
            TextView row = text(t.singles
                    ? String.format(Locale.US, "%d.  %s   %.2f%s", i + 1, x.label(), x.combined(), x.duprIds().isEmpty() ? "\n     (no DUPR ID)" : "")
                    : String.format(Locale.US, "%d.  %s   %.2f\n     %s %.2f  +  %s %.2f%s",
                    i + 1, x.label(), x.combined(), x.player1, x.dupr1, x.player2, x.dupr2,
                    x.duprIds().size() == 2 ? "" : "\n     (no DUPR ID" + (x.duprIds().isEmpty() ? "s" : " for one player") + ")"), 18, false, MainActivity.INK);
            card(row);
            row.setOnClickListener(v -> editTeam(x));
        }
        boolean header = false;
        for (Team x : t.teams) {
            if (!x.withdrawn) continue;
            if (!header) { heading("Dropped out"); header = true; }
            TextView row = text(x.label() + "\n     Tap to bring back", 18, false, GREY);
            card(row);
            row.setOnClickListener(v -> editTeam(x));
        }
    }

    void editTeam(Team existing) {
        LinearLayout f = form();
        EditText p1 = field(f, "Player 1 name", existing == null ? "" : existing.player1, false);
        EditText d1 = field(f, "Player 1 DUPR rating (e.g. 5.56)", existing == null ? "" : num(existing.dupr1), true);
        EditText i1 = field(f, "Player 1 DUPR ID (optional, e.g. L8EW8W)", existing == null ? "" : existing.duprId1, false);
        EditText p2 = field(f, "Player 2 (partner) name", existing == null ? "" : existing.player2, false);
        EditText d2 = field(f, "Player 2 DUPR rating (e.g. 5.51)", existing == null ? "" : num(existing.dupr2), true);
        EditText i2 = field(f, "Player 2 DUPR ID (optional)", existing == null ? "" : existing.duprId2, false);
        EditText e1 = field(f, "Player 1 email (optional: gets score sheet photos and results)", existing == null ? "" : existing.email1, false);
        EditText e2 = field(f, "Player 2 email (optional)", existing == null ? "" : existing.email2, false);
        for (EditText e : new EditText[]{e1, e2}) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        for (EditText e : new EditText[]{i1, i2})
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        f.addView(free(text("With an email, each player is sent the photo of every score sheet from their matches, and the results.", 14, false, GREY)));
        if (t.singles) for (EditText e : new EditText[]{p2, d2, i2, e2}) { e.setVisibility(View.GONE); RegistrationsScreen.hideLabelBefore(f, e); }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(existing == null ? (t.singles ? "Add a player" : "Add a team") : (t.singles ? "Change player" : "Change team"))
                .setView(scroll(f))
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null);
        if (existing != null && t.round1.isEmpty())
            b.setNeutralButton("Delete", (d, w) -> confirm("Delete " + existing.label() + "?", "", () -> {
                t.teams.remove(existing); save(); refresh(); }));
        else if (existing != null && existing.withdrawn)
            b.setNeutralButton("Bring back", (d, w) -> confirm("Bring back " + existing.label() + "?",
                    "Its results count again. If its pool was redone without it, redo the pools again to add it.",
                    () -> { t.reinstate(existing); save(); refresh(); }));
        else if (existing != null)
            b.setNeutralButton("Withdraw", (d, w) -> withdraw(existing));
        AlertDialog d = b.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (t.singles) { p2.setText(""); i2.setText(""); e2.setText(""); d2.setText("0"); }
            Double a = parse(d1), c = parse(d2);
            if (p1.getText().toString().isBlank() || (!t.singles && p2.getText().toString().isBlank())) { toast(t.singles ? "Enter the player's name" : "Enter both player names"); return; }
            if (a == null || c == null) { toast(t.singles ? "Enter the DUPR as a number, like 4.25" : "Enter both DUPRs as numbers, like 5.56"); return; }
            String id1 = org.pickleballpools.core.Player.normId(i1.getText().toString()), id2 = org.pickleballpools.core.Player.normId(i2.getText().toString());
            for (String id : new String[]{id1, id2})
                if (!id.isEmpty() && !id.matches("[A-Z0-9]{4,12}")) { toast("A DUPR ID is letters and numbers only, like L8EW8W"); return; }
            String m1 = e1.getText().toString().trim().toLowerCase(Locale.ROOT), m2 = e2.getText().toString().trim().toLowerCase(Locale.ROOT);
            for (String m : new String[]{m1, m2})
                if (!m.isEmpty() && !m.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) { toast(m + " isn't an email address"); return; }
            if (!id1.isEmpty() && id1.equals(id2)) { toast("The two players can't have the same DUPR ID"); return; }
            for (Team o : t.teams)
                if (o != existing && !o.withdrawn) for (String id : new String[]{id1, id2})
                    if (!id.isEmpty() && o.duprIds().contains(id)) { toast("DUPR ID " + id + " is already on " + o.label()); return; }
            Team x = existing == null ? t.addTeam(id1, id2) : existing;
            x.player1 = p1.getText().toString().trim();
            x.player2 = p2.getText().toString().trim();
            x.dupr1 = a;
            x.dupr2 = c;
            x.duprId1 = id1;
            x.duprId2 = id2;
            x.email1 = m1;
            x.email2 = m2;
            save();
            d.dismiss();
            refresh();
        });
    }

    /** A team drops out. USA Pickleball 15.B.4.a: its results in its current round stop counting, and it can't advance. */
    void withdraw(Team x) {
        List<Pool> round = t.currentRound(x);
        String name = round == t.round2 ? "Round 2" : "Round 1";
        Runnable after = () -> {
            save();
            refresh();
            if (!round.isEmpty() && Tournament.scored(round) == 0 && !t.started())
                confirm("Redo " + name + " pools?", x.label() + " dropped out and no scores are entered yet in " + name
                        + ". Redo the pools so they stay even?", () -> { if (round == t.round1) t.startRound1(); else t.startRound2(); save(); refresh(); });
        };
        int played = t.played(x);
        String msg = played == 0 ? "It hasn't played yet in " + name + ", so its matches simply won't count."
                : "Its " + played + " match" + (played == 1 ? "" : "es") + " already played in " + name
                  + " will no longer count for anyone, and it can't advance (USA Pickleball rule 15.B.4.a).";
        confirm("Withdraw " + x.label() + "?", msg + "\n\nYou can bring it back later.", () -> { t.withdraw(x); after.run(); });
    }

    void pasteTeams() {
        LinearLayout f = form();
        TextView help = text(t.singles
                ? "One player per line:\nName, DUPR, DUPR ID (ID optional)\n\nExample:\nPat Smith, 4.25\n\nCommas or tabs (copied from a spreadsheet) both work."
                : "One team per line:\nPlayer 1, DUPR, Player 2, DUPR, Player 1 DUPR ID, Player 2 DUPR ID (IDs optional)\n\n"
                + "Example:\nPat Smith, 5.56, Lee Jones, 5.51\n\nCommas or tabs (copied from a spreadsheet) both work.", 16, false, GREY);
        page.removeView(help);
        f.addView(help);
        EditText box = new EditText(this);
        box.setMinLines(6);
        box.setGravity(Gravity.TOP);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        f.addView(box);
        new AlertDialog.Builder(this).setTitle("Paste teams").setView(scroll(f))
                .setPositiveButton("Add", (d, w) -> {
                    int added = 0;
                    List<String> bad = new ArrayList<>();
                    for (String line : box.getText().toString().split("\n")) {
                        if (line.isBlank()) continue;
                        String[] c = line.split("[,\t]");
                        if (t.singles) {
                            try {
                                if (c.length < 2) throw new IllegalArgumentException();
                                double r = Double.parseDouble(c[1].trim());
                                Team x = t.addTeam(c.length > 2 ? c[2].trim() : "", "");
                                x.player1 = c[0].trim();
                                x.dupr1 = r;
                                added++;
                            } catch (RuntimeException e) { bad.add(line.trim()); }
                            continue;
                        }
                        try {
                            if (c.length < 4) throw new IllegalArgumentException();
                            Team x = t.addTeam(c.length > 4 ? c[4].trim() : "", c.length > 5 ? c[5].trim() : "");
                            x.player1 = c[0].trim();
                            x.dupr1 = Double.parseDouble(c[1].trim());
                            x.player2 = c[2].trim();
                            x.dupr2 = Double.parseDouble(c[3].trim());
                            added++;
                        } catch (RuntimeException e) {
                            if (c.length >= 4) t.teams.remove(t.teams.size() - 1);
                            bad.add(line.trim());
                        }
                    }
                    save();
                    refresh();
                    String msg = "Added " + added + " teams.";
                    if (!bad.isEmpty()) msg += "\n\nCouldn't read these lines:\n" + String.join("\n", bad);
                    new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---------- Pool ----------

    void pool(Pool p) {
        page.removeAllViews();
        Map<String, Team> ids = t.byId();
        title(p.title());
        heading("Standings");
        for (Standings.Row r : Standings.of(p, ids)) {
            String s = String.format(Locale.US, "%s  %s\n     Won %d  Lost %d  ·  Games %d-%d\n     Points %+d  (%d scored)",
                    r.withdrawn ? "–" : r.place + ".", r.team.label(), r.wins, r.losses, r.gamesWon, r.gamesLost, r.diff(), r.pointsFor);
            if ((p.complete() || r.withdrawn) && !r.tiebreak.isEmpty()) s += "\n     " + r.tiebreak; // tiebreak notes once final
            card(text(s, 17, false, r.withdrawn ? GREY : r.tiebreak.equals(Standings.UNRESOLVED) ? RED : MainActivity.INK));
        }
        heading("Matches  (tap to enter scores)");
        int round = 0;
        for (Match m : p.matches) {
            if (m.round != round) { round = m.round; text("Round " + round, 16, true, GREY); }
            String s = ids.get(m.a).label() + "\n   vs  " + ids.get(m.b).label() + "\n";
            if (p.voided.contains(m.a) || p.voided.contains(m.b))
                s += "   doesn't count: " + ids.get(p.voided.contains(m.a) ? m.a : m.b).label() + " dropped out";
            else s += m.games.isEmpty() ? "   not played yet" : "   " + m.scoreText()
                    + (m.done() ? "   → " + ids.get(m.winner()).label() + " won" : "");
            TextView row = text(s, 18, false, MainActivity.INK);
            card(row).setBackground(box(m.done() ? LIGHT : MainActivity.PENDING));
            org.json.JSONArray rc = live.directing() ? live.receiptsFor(t.matchKey(p, m)) : null;
            if (rc != null) row.append("\n   📷 " + rc.length() + " score sheet photo" + (rc.length() == 1 ? "" : "s"));
            row.setOnClickListener(v -> {
                if (!live.directing() || !t.started()) { score(p, m); return; }
                String[] opts = rc == null ? new String[]{"Enter or change the score", "📷 Take a photo of the score sheet"}
                        : new String[]{"Enter or change the score", "📷 Take a photo of the score sheet", "See the score sheet photo"};
                new AlertDialog.Builder(this).setTitle(ids.get(m.a).label() + "  vs  " + ids.get(m.b).label()).setItems(opts, (d, w) -> {
                    if (w == 0) score(p, m);
                    else if (w == 1) receipts.take(t.serverId, t.matchKey(p, m));
                    else receipts.view(t.serverId, rc.optString(rc.length() - 1), "Score sheet");
                }).show();
            });
        }
    }

    void score(Pool p, Match m) {
        if (!t.started()) { toast("Press \"Start the tournament\" on the home screen first"); return; }
        Map<String, Team> ids = t.byId();
        String warn = t.round1.contains(p) && !t.round2.isEmpty()
                ? "Round 2 is already made. Changing this score will NOT move teams in Round 2." : null;
        scoreDialog(ids.get(m.a).label(), ids.get(m.b).label(), m.games, t.pointsToWin, t.winBy, warn, games -> {
            m.games.clear();
            m.games.addAll(games);
            save();
            if (live.directing()) live.send(t.serverId, t.matchKey(p, m), games);
            refresh();
        });
    }

    /** The score form (3 games, two teams), shared by the director's pool screen and the court captain's screen. */
    void scoreDialog(String aName, String bName, List<int[]> current, int to, int winBy, String warning,
                     java.util.function.Consumer<List<int[]>> done) {
        LinearLayout f = form();
        if (warning != null) f.addView(free(text(warning, 15, true, RED)));
        LinearLayout header = new LinearLayout(this);
        header.addView(cell(text("", 14, false, GREY), 1.4f));
        header.addView(cell(text(aName, 15, true, MainActivity.INK), 2));
        header.addView(cell(text(bName, 15, true, MainActivity.INK), 2));
        f.addView(header);
        EditText[][] in = new EditText[3][2];
        for (int g = 0; g < 3; g++) {
            LinearLayout row = new LinearLayout(this);
            row.addView(cell(text("Game " + (g + 1), 15, false, MainActivity.INK), 1.4f));
            for (int s = 0; s < 2; s++) {
                EditText e = new EditText(this);
                e.setInputType(InputType.TYPE_CLASS_NUMBER);
                e.setTextSize(22);
                e.setGravity(Gravity.CENTER);
                if (g < current.size()) e.setText(String.valueOf(current.get(g)[s]));
                in[g][s] = e;
                row.addView(cell(e, 2));
            }
            f.addView(row);
        }
        f.addView(free(text("Games to " + to + ", win by " + winBy + ". Leave Game 3 empty if not needed.", 14, false, GREY)));
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Enter scores").setView(scroll(f))
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null)
                .setNeutralButton("Clear", (x, w) -> confirm("Clear this score?", "", () -> done.accept(new ArrayList<>()))).show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<int[]> games = new ArrayList<>();
            for (int g = 0; g < 3; g++) {
                String x = in[g][0].getText().toString().trim(), y = in[g][1].getText().toString().trim();
                if (x.isEmpty() && y.isEmpty()) continue;
                if (x.isEmpty() || y.isEmpty()) { toast("Game " + (g + 1) + ": enter both scores"); return; }
                games.add(new int[]{Integer.parseInt(x), Integer.parseInt(y)});
            }
            String err = Rules.checkMatch(games, to, winBy);
            if (err != null) { toast(err); return; }
            d.dismiss();
            done.accept(games);
        });
    }

    // ---------- Settings, sharing, new tournament ----------

    void settings() {
        LinearLayout f = form();
        EditText name = field(f, "Tournament name", t.name, false);
        android.widget.CheckBox single = new android.widget.CheckBox(this);
        single.setText("Singles (one player per entry, no partner)");
        single.setTextSize(17);
        single.setChecked(t.singles);
        single.setEnabled(!t.started() && t.round1.isEmpty() && t.teams.isEmpty());
        f.addView(single);
        if (!single.isEnabled()) f.addView(free(text("Singles or doubles can be changed only before any "
                + (t.singles ? "players" : "teams") + " are added.", 13, false, GREY)));
        EditText pools = field(f, "Round 1 pools", String.valueOf(t.poolCount), true);
        EditText adv = field(f, "Teams per pool to the Championship bracket", String.valueOf(t.advancePerPool), true);
        EditText fin = field(f, "Teams per Championship pool to the Finals", String.valueOf(t.finalistsPerPool), true);
        EditText to = field(f, "Games are played to", String.valueOf(t.pointsToWin), true);
        EditText by = field(f, "Win by (USA Pickleball: 2)", String.valueOf(t.winBy), true);
        EditText court = field(f, "Printed sheets: first court number", String.valueOf(t.firstCourt), true);
        EditText w1 = field(f, "Round 1 starts (e.g. Saturday 9:00am)", t.round1When, false);
        EditText w2 = field(f, "Round 2 starts", t.round2When, false);
        if (!t.round1.isEmpty()) pools.setEnabled(false);
        if (t.started()) { to.setEnabled(false); by.setEnabled(false); adv.setEnabled(false); court.setEnabled(false); }
        if (!t.courts.isEmpty()) court.setEnabled(false); // the venue's courts are used instead
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Settings").setView(scroll(f))
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null).show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int np = Integer.parseInt(pools.getText().toString().trim());
                int na = Integer.parseInt(adv.getText().toString().trim());
                int nf = Integer.parseInt(fin.getText().toString().trim());
                int nt = Integer.parseInt(to.getText().toString().trim());
                int nb = Integer.parseInt(by.getText().toString().trim());
                int nc = Integer.parseInt(court.getText().toString().trim());
                if (nc < 1) throw new NumberFormatException();
                t.firstCourt = nc;
                t.singles = single.isChecked();
                t.round1When = w1.getText().toString().trim();
                t.round2When = w2.getText().toString().trim();
                if (np < 1 || np > 26 || na < 1 || nf < 1 || nt < 1 || nb < 1) throw new NumberFormatException();
                t.name = name.getText().toString().trim().isEmpty() ? t.name : name.getText().toString().trim();
                t.poolCount = np; t.advancePerPool = na; t.finalistsPerPool = nf; t.pointsToWin = nt; t.winBy = nb;
                save();
                d.dismiss();
                refresh();
            } catch (NumberFormatException e) {
                toast("Please enter whole numbers (1 or more)");
            }
        });
    }

    /** The three printable sheets for a round (schedule, court captain sheets, results grid), shared as Excel files. */
    void printSheets() {
        if (t.round2.isEmpty()) { printSheets(t.round1, "Round 1"); return; }
        new AlertDialog.Builder(this).setTitle("Print sheets for which round?")
                .setItems(new String[]{"Round 1", "Round 2"}, (d, w) -> printSheets(w == 0 ? t.round1 : t.round2, w == 0 ? "Round 1" : "Round 2"))
                .show();
    }

    void printSheets(List<Pool> round, String name) {
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "sheets");
            dir.mkdirs();
            for (java.io.File old : dir.listFiles()) old.delete();
            String base = (t.name + " " + name).replaceAll("[^A-Za-z0-9 _-]", "").trim();
            ArrayList<android.net.Uri> uris = new ArrayList<>();
            org.pickleballpools.core.Xlsx[] books = {PrintSheets.schedule(t, round), PrintSheets.captainSheets(t, round), PrintSheets.resultsGrid(t, round)};
            String[] names = {"1 Schedule", "2 Court captain sheets", "3 Results grid"};
            for (int i = 0; i < 3; i++) {
                java.io.File f = new java.io.File(dir, names[i] + " - " + base + ".xlsx");
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) { books[i].write(os); }
                uris.add(SheetProvider.uri(this, f));
            }
            Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    .putExtra(Intent.EXTRA_SUBJECT, t.name + " " + name + " sheets")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Send the 3 sheets to email, Drive or a printer app"));
        } catch (Exception e) {
            toast("Couldn't make the sheets: " + e.getMessage());
        }
    }

    /** New tournaments start with the club's settings (from its server), when signed in. */
    Tournament clubDefaults(Tournament x) {
        org.json.JSONObject c = account.club();
        int practicePools = x.poolCount;
        x.poolCount = c.optInt("poolCount", x.poolCount);
        if (x.teams.size() > 0 && x.teams.size() < x.poolCount * 2) x.poolCount = practicePools; // small practice roster
        x.advancePerPool = c.optInt("advancePerPool", x.advancePerPool);
        x.finalistsPerPool = c.optInt("finalistsPerPool", x.finalistsPerPool);
        x.pointsToWin = c.optInt("pointsToWin", x.pointsToWin);
        x.winBy = c.optInt("winBy", x.winBy);
        x.firstCourt = c.optInt("firstCourt", x.firstCourt);
        if (!c.optString("paymentLink").isEmpty()) x.paymentLink = c.optString("paymentLink");
        return x;
    }

    /** Optional private roster for the practice tournament (assets/practice-players.txt, not in the public repo). */
    List<String> practiceRoster() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(getAssets().open("practice-players.txt"),
                java.nio.charset.StandardCharsets.UTF_8))) {
            List<String> l = new ArrayList<>();
            for (String s; (s = r.readLine()) != null; ) l.add(s);
            return l;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    // ---------- Tournaments: several on one phone, each its own file ----------

    void tournaments() {
        page.removeAllViews();
        title("Tournaments");
        note("Tap one to open it. Press and hold to delete. Each keeps its own teams, pools and scores.");
        for (Object[] e : Store.all(this)) {
            String k = (String) e[0];
            Tournament x = (Tournament) e[1];
            boolean open = k.equals(key);
            TextView v = card(text((open ? "▶ " : "") + x.name + (x.date.isEmpty() ? "" : "\n" + x.date)
                    + "\n" + x.seeded().size() + " teams · " + stage(x) + (x.started() ? " · started" : "")
                    + (x.zeffyCampaign.isEmpty() ? "" : " · Zeffy"), 17, open, INK));
            if (open) v.setBackground(box(LIGHT));
            v.setOnClickListener(w -> openTournament(k, x));
            v.setOnLongClickListener(w -> {
                if (open) { toast("Open another tournament first, then delete this one."); return true; }
                confirm("Delete " + x.name + "?", "Its teams, pools and scores are erased from this phone. "
                        + "Scores already sent to the server stay there.", () -> { Store.delete(this, k); refresh(); });
                return true;
            });
        }
        heading("New tournament");
        if (account.api.signedIn() && (account.api.has("admin") || account.api.has("director")))
            button("From a Zeffy registration…", v -> fromZeffy());
        button("Empty", v -> create(clubDefaults(new Tournament())));
        button("Practice (made-up teams)", v -> practice());
    }

    void openTournament(String k, Tournament x) {
        key = k;
        t = x;
        Store.setCurrent(this, k);
        back.clear();
        current = null;
        go(this::home, false);
    }

    void create(Tournament x) {
        String k = Store.newKey();
        t = x;
        key = k;
        save();
        openTournament(k, x);
    }

    void practice() {
        Runnable local = () -> create(clubDefaults(Tournament.practice(practiceRoster())));
        if (!account.api.signedIn() || !(account.api.has("admin") || account.api.has("director"))) { local.run(); return; }
        // The club's practice roster from its server, if it has one
        account.api.call("GET", "/rosters/practice", null, r -> {
            org.json.JSONArray p = r.optJSONArray("players");
            if (p == null || p.length() < 8) { local.run(); return; }
            List<String> l = new ArrayList<>();
            for (int i = 0; i < p.length(); i++) l.add(p.optString(i));
            create(clubDefaults(Tournament.practice(l)));
        }, (net, msg) -> local.run());
    }

    /** The club's Zeffy events (through the server, which holds the key); picking one opens its tournament or makes it. */
    void fromZeffy() {
        toast("Asking Zeffy…");
        account.api.call("GET", "/zeffy/campaigns", null, r -> {
            org.json.JSONArray cs = r.optJSONArray("campaigns");
            if (cs == null || cs.length() == 0) { toast("No Zeffy events found. Make a ticketing campaign in Zeffy first."); return; }
            String[] names = new String[cs.length()];
            for (int i = 0; i < cs.length(); i++) {
                org.json.JSONObject c = cs.optJSONObject(i);
                String d = day(c.optLong("start"));
                names[i] = c.optString("title") + (d.isEmpty() ? "" : "\n" + d)
                        + ("active".equals(c.optString("status")) ? "" : "  (" + c.optString("status") + ")");
            }
            new AlertDialog.Builder(this).setTitle("Which Zeffy event?").setItems(names, (dlg, w) -> {
                org.json.JSONObject c = cs.optJSONObject(w);
                for (Object[] e : Store.all(this))
                    if (c.optString("id").equals(((Tournament) e[1]).zeffyCampaign)) { openTournament((String) e[0], (Tournament) e[1]); return; }
                Tournament x = clubDefaults(new Tournament());
                if (!c.optString("title").isEmpty()) x.name = c.optString("title");
                x.date = day(c.optLong("start"));
                x.zeffyCampaign = c.optString("id");
                if (!c.optString("url").isEmpty()) x.paymentLink = c.optString("url");
                create(x);
                toast("Made " + x.name + ". Players will come in from Zeffy once the registration import is ready.");
            }).setNegativeButton("Cancel", null).show();
        }, (net, msg) -> toast(msg));
    }

    static String day(long epochSeconds) {
        if (epochSeconds <= 0) return "";
        return new java.text.SimpleDateFormat("EEE MMM d, yyyy", java.util.Locale.US).format(new java.util.Date(epochSeconds * 1000));
    }

    void share() {
        Map<String, Team> ids = t.byId();
        Map<String, String> sn = org.pickleballpools.core.Names.forTournament(t); // shared publicly: short names
        StringBuilder sb = new StringBuilder(t.name).append("\n");
        List<Pool> all = new ArrayList<>(t.round1);
        all.addAll(t.round2);
        for (Pool p : all) {
            sb.append("\n").append(t.round1.contains(p) ? "Round 1 " : "Round 2 ").append(p.title()).append("\n");
            for (Standings.Row r : Standings.of(p, ids))
                sb.append(String.format(Locale.US, "%d. %s  %d-%d  pts %+d%s\n", r.place, org.pickleballpools.core.Names.team(sn, r.team), r.wins, r.losses,
                        r.diff(), !p.complete() || r.tiebreak.isEmpty() ? "" : "  (" + r.tiebreak + ")"));
        }
        List<Team> f = t.finalists();
        if (!f.isEmpty()) {
            sb.append("\nQualified for the Finals:\n");
            for (Team x : f) sb.append("- ").append(org.pickleballpools.core.Names.team(sn, x)).append("\n");
        }
        if (all.isEmpty()) {
            sb.append("\nTeams by seed:\n");
            List<Team> s = t.seeded();
            for (int i = 0; i < s.size(); i++) sb.append(String.format(Locale.US, "%d. %s  %.2f\n", i + 1, org.pickleballpools.core.Names.team(sn, s.get(i)), s.get(i).combined()));
        }
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, t.name + " results").putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(i, "Share results"));
    }

    // ---------- Small UI helpers ----------

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    TextView text(String s, int sp, boolean bold, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(4), 0, dp(4));
        page.addView(v);
        return v;
    }

    /** The organization's logo (branding/logo.png), centered at the top of the home screen. */
    void logo() {
        ImageView v = new ImageView(this);
        v.setImageResource(R.drawable.brand_logo);
        v.setAdjustViewBounds(true);
        v.setContentDescription(getString(R.string.app_name));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(140));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        v.setLayoutParams(lp);
        page.addView(v);
    }

    void title(String s) { text(s, 28, true, GREEN); }

    void heading(String s) { text(s, 22, true, MainActivity.INK).setPadding(0, dp(20), 0, dp(4)); }

    void note(String s) { text(s, 17, false, GREY); }

    void button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(19);
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        b.setMinHeight(dp(56));
        page.addView(b);
    }

    GradientDrawable box(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(10));
        g.setStroke(dp(1), EDGE);
        return g;
    }

    TextView card(TextView v) {
        v.setBackground(box(CARD));
        v.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        v.setLayoutParams(lp);
        return v;
    }

    LinearLayout form() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        return f;
    }

    ScrollView scroll(View v) {
        ScrollView s = new ScrollView(this);
        s.addView(v);
        return s;
    }

    /** A label + input inside a dialog form. */
    EditText field(LinearLayout f, String label, String value, boolean number) {
        TextView l = text(label, 15, false, GREY);
        page.removeView(l);
        f.addView(l);
        EditText e = new EditText(this);
        e.setTextSize(20);
        e.setText(value);
        e.setInputType(number ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        f.addView(e);
        return e;
    }

    /** Detaches a view that text() put on the page so it can go into a dialog instead. */
    View free(View v) {
        if (v.getParent() == page) page.removeView(v);
        return v;
    }

    /** Wraps a view (removing it from the page if text() added it there) for a weighted row. */
    View cell(View v, float weight) {
        if (v.getParent() == page) page.removeView(v);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight));
        return v;
    }

    void confirm(String title, String msg, Runnable yes) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg)
                .setPositiveButton("Yes", (d, w) -> yes.run()).setNegativeButton("Cancel", null).show();
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    static Double parse(EditText e) {
        try { return Double.parseDouble(e.getText().toString().trim()); } catch (NumberFormatException x) { return null; }
    }

    static String num(double d) { return d == 0 ? "" : String.valueOf(d); }
}
