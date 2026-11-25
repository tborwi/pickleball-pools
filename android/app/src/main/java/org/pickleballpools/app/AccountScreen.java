package org.pickleballpools.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Account (optional): sign in with Google or a code sent by email, then see your roles.
 * Admins give organization roles by email (admin, tournament director, treasurer); directors add court captains
 * to a tournament. Everyone who signs in is a player. What each role may do: ROLES.md.
 */
final class AccountScreen {
    static final String[][] ORG_ROLES = {{"admin", "Admin"}, {"director", "Tournament director"}, {"treasurer", "Treasurer"}};

    final MainActivity a;
    final Api api;

    AccountScreen(MainActivity a) {
        this.a = a;
        api = new Api(a);
    }

    void show() {
        a.page.removeAllViews();
        a.title("Account");
        if (api.server().isEmpty()) {
            a.note("Accounts are only for the people running a tournament (admins, directors, court captains, "
                    + "treasurer). Players don't need one: they get a live scores link and their score sheet photos by email. "
                    + "The app also works without any of this on one phone. Your organization runs the server; ask the admin for its address.");
            a.button("Set the server address", v -> server());
            return;
        }
        if (!api.signedIn()) {
            a.note("For tournament staff. Players don't need to sign in.");
            a.button("Continue with Google", v -> a.startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse(api.server() + "/auth/google/start"))));
            a.button("Email me a sign-in code", v -> emailCode());
            a.smallButton("Server: " + api.server(), v -> server());
            return;
        }
        JSONObject me = api.me();
        a.heading(me.optString("name").isEmpty() ? me.optString("email") : me.optString("name"));
        a.note(me.optString("email"));
        a.note("Roles: " + roleNames(me.optJSONArray("roles")));
        JSONArray ts = me.optJSONArray("tournaments");
        if (ts != null) for (int i = 0; i < ts.length(); i++) {
            JSONObject x = ts.optJSONObject(i);
            a.note("• " + x.optString("name") + ": " + ("captain".equals(x.optString("role"))
                    ? "court captain" + (x.optString("courts").isEmpty() ? "" : ", courts " + x.optString("courts")) : "director"));
        }
        a.button("My details (name, phone)", v -> details());
        if (api.has("admin")) {
            a.button("People and roles", v -> a.go(this::people, true));
            a.button("Club settings", v -> clubSettings());
            a.button("Practice roster", v -> practiceRoster());
        }
        if (api.has("admin") || api.has("director")) {
            if (a.t.serverId.isEmpty()) a.button("Put this tournament online (for court captains)", v -> putOnline());
            else a.button("Court captains for " + a.t.name, v -> a.go(this::captains, true));
        }
        a.button("Sign out", v -> a.confirm("Sign out?", "", () -> {
            api.call("POST", "/auth/signout", new JSONObject(), r -> {});
            api.signOutLocally();
            a.refresh();
        }));
        a.smallButton("Server: " + api.server(), v -> server());
        refreshMe();
    }

    /** The audit log of the online tournament: every score change (old and new), photo, publish, staff change. */
    void history() {
        a.page.removeAllViews();
        a.title("History");
        a.note(a.t.name + ": newest first. Every change on the server is recorded with who made it.");
        api.call("GET", "/tournaments/" + a.t.serverId + "/audit", null, r -> {
            JSONArray es = r.optJSONArray("entries");
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.US);
            for (int i = 0; es != null && i < es.length(); i++) {
                JSONObject e = es.optJSONObject(i), d = e.optJSONObject("detail");
                if (d == null) d = new JSONObject();
                String when = f.format(new java.util.Date(e.optLong("at") * 1000)), what;
                switch (e.optString("action")) {
                    case "score": {
                        String old = CaptainScreen.text(Live.games(d.optJSONArray("old"))), now = CaptainScreen.text(Live.games(d.optJSONArray("new")));
                        what = "Score, court " + d.optString("court") + ": " + d.optString("a") + " vs " + d.optString("b") + "\n"
                                + (old.isEmpty() ? "entered " + now : now.isEmpty() ? "cleared (was " + old + ")" : "changed " + old + "  →  " + now);
                        break;
                    }
                    case "receipt": what = "📷 Score sheet photo, court " + d.optString("court") + ": " + d.optString("a") + " vs " + d.optString("b") + " (tap to see)"; break;
                    case "publish": what = "Pools sent to captains (" + d.optInt("matches") + " matches)"; break;
                    case "staff": what = ("removed".equals(d.optString("change")) ? "Removed " : "Set ") + label(d.optString("role")).toLowerCase(java.util.Locale.US)
                            + " " + d.optString("email") + (d.optString("courts").isEmpty() ? "" : ", courts " + d.optString("courts")); break;
                    case "email": {
                        JSONArray to = d.optJSONArray("to");
                        what = "✉️ Emailed " + (to == null ? 0 : to.length()) + " player" + (to != null && to.length() == 1 ? "" : "s") + ": " + d.optString("subject");
                        break;
                    }
                    case "started": what = "▶ Tournament started (pools locked)"; break;
                    case "unlocked": what = "🔓 Pools unlocked"; break;
                    default: what = e.optString("action");
                }
                android.widget.TextView v = a.card(a.text(when + " · " + e.optString("email") + "\n" + what, 16, false, android.graphics.Color.BLACK));
                String rid = d.optString("receipt");
                if (!rid.isEmpty()) v.setOnClickListener(x -> a.receipts.view(a.t.serverId, rid, "Score sheet photo"));
            }
            if (es == null || es.length() == 0) a.note("Nothing yet.");
        });
    }

    /** Fetches the latest profile and redraws if it changed (roles given since the last visit). */
    void refreshMe() {
        String before = api.me().toString();
        if (!api.signedIn()) return;
        api.call("GET", "/me", null, r -> {
            api.setMe(r);
            if (!r.toString().equals(before)) a.refresh();
        }, (net, msg) -> {});
        api.call("GET", "/club", null, r -> a.getSharedPreferences("account", 0).edit()
                .putString("club", r.optJSONObject("club") == null ? "{}" : r.optJSONObject("club").toString()).apply(), (net, msg) -> {});
    }

    /** The club's settings from the server (last copy), or empty. */
    JSONObject club() {
        try { return new JSONObject(a.getSharedPreferences("account", 0).getString("club", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static final String[][] CLUB_FIELDS = {{"name", "Club name"}, {"contactEmail", "Contact email (for players' questions)"},
            {"paymentLink", "Payment link (Zeffy, Stripe...)"}, {"poolCount", "Round 1 pools"},
            {"advancePerPool", "Teams per pool to the Championship bracket"}, {"finalistsPerPool", "Teams per Championship pool to the Finals"},
            {"pointsToWin", "Games are played to"}, {"winBy", "Win by (USA Pickleball: 2)"}, {"firstCourt", "First court number"}};

    /** Admins: the club's settings, kept on the club's server. New tournaments start with them. */
    void clubSettings() {
        JSONObject c = club();
        LinearLayout f = a.form();
        EditText[] in = new EditText[CLUB_FIELDS.length];
        for (int i = 0; i < CLUB_FIELDS.length; i++) {
            boolean number = i >= 3;
            in[i] = a.field(f, CLUB_FIELDS[i][1], c.optString(CLUB_FIELDS[i][0]), number);
            if (!number) in[i].setInputType(InputType.TYPE_CLASS_TEXT | (i == 0 ? InputType.TYPE_TEXT_FLAG_CAP_WORDS : InputType.TYPE_TEXT_VARIATION_URI));
        }
        new AlertDialog.Builder(a).setTitle("Club settings").setView(a.scroll(f))
                .setPositiveButton("Save", (d, w) -> {
                    JSONObject b = new JSONObject();
                    try {
                        for (int i = 0; i < CLUB_FIELDS.length; i++) b.put(CLUB_FIELDS[i][0], in[i].getText().toString().trim());
                        api.call("PUT", "/club", new JSONObject().put("club", b), r -> {
                            a.getSharedPreferences("account", 0).edit().putString("club", r.optJSONObject("club").toString()).apply();
                            a.toast("Club settings saved");
                        });
                    } catch (Exception ignored) {}
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** Admins: the practice tournament's players, kept on the club's server (not in the public app). */
    void practiceRoster() {
        api.call("GET", "/rosters/practice", null, r -> {
            JSONArray p = r.optJSONArray("players");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; p != null && i < p.length(); i++) sb.append(p.optString(i)).append('\n');
            LinearLayout f = a.form();
            f.addView(a.free(a.text("One player per line. Partners on consecutive lines. Add * after a name to make that "
                    + "player the highest rated. At least 8 players.", 15, false, MainActivity.GREY)));
            EditText e = new EditText(a);
            e.setMinLines(10);
            e.setGravity(android.view.Gravity.TOP);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            e.setText(sb.toString());
            f.addView(e);
            new AlertDialog.Builder(a).setTitle("Practice roster").setView(a.scroll(f))
                    .setPositiveButton("Save", (d, w) -> {
                        JSONArray out = new JSONArray();
                        for (String l : e.getText().toString().split("\n")) if (!l.isBlank()) out.put(l.trim());
                        try { api.call("PUT", "/rosters/practice", new JSONObject().put("players", out), rr -> a.toast("Practice roster saved")); }
                        catch (Exception ignored) {}
                    })
                    .setNegativeButton("Cancel", null).show();
        });
    }

    static String roleNames(JSONArray roles) {
        List<String> out = new ArrayList<>();
        if (roles != null) for (int i = 0; i < roles.length(); i++) out.add(label(roles.optString(i)));
        return out.isEmpty() ? "no tournament jobs yet (ask your director)" : String.join(", ", out);
    }

    static String label(String role) {
        for (String[] r : ORG_ROLES) if (r[0].equals(role)) return r[1];
        return "captain".equals(role) ? "Court captain" : "player".equals(role) ? "Player" : role;
    }

    void server() {
        LinearLayout f = a.form();
        EditText e = a.field(f, "Server address (from your organization's admin)", api.server(), false);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(a).setTitle("Accounts server").setView(f)
                .setPositiveButton("Save", (d, w) -> {
                    if (!e.getText().toString().trim().replaceAll("/+$", "").equals(api.server())) api.signOutLocally();
                    api.setServer(e.getText().toString());
                    a.refresh();
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---------- sign in ----------

    void emailCode() {
        LinearLayout f = a.form();
        EditText e = a.field(f, "Your email address", api.me().optString("email"), false);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        new AlertDialog.Builder(a).setTitle("Sign in by email").setView(f)
                .setPositiveButton("Send the code", (d, w) -> {
                    String email = e.getText().toString().trim();
                    api.call("POST", "/auth/email/start", obj("email", email), r -> enterCode(email));
                })
                .setNegativeButton("Cancel", null).show();
    }

    void enterCode(String email) {
        LinearLayout f = a.form();
        EditText c = a.field(f, "We emailed a 6-digit code to " + email + ". Enter it here:", "", true);
        c.setInputType(InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(a).setTitle("Enter the code").setView(f)
                .setPositiveButton("Sign in", (d, w) ->
                        api.call("POST", "/auth/email/verify", obj("email", email, "code", c.getText().toString()), this::signedIn))
                .setNegativeButton("Cancel", null).show();
    }

    /** Google sign-in comes back as pickleballpools://signin?code=... (see MainActivity.onNewIntent). */
    void onSignInLink(Uri u) {
        String code = u.getQueryParameter("code");
        if (code == null) return;
        api.call("POST", "/auth/exchange", obj("code", code), this::signedIn);
    }

    void signedIn(JSONObject r) {
        api.setToken(r.optString("token"));
        api.setMe(r.optJSONObject("user") == null ? new JSONObject() : r.optJSONObject("user"));
        a.toast("Signed in");
        a.go(this::show, false);
    }

    // ---------- my details ----------

    void details() {
        JSONObject me = api.me();
        LinearLayout f = a.form();
        EditText name = a.field(f, "Name", me.optString("name"), false);
        EditText phone = a.field(f, "Phone (so the director can reach you)", me.optString("phone"), false);
        phone.setInputType(InputType.TYPE_CLASS_PHONE);
        new AlertDialog.Builder(a).setTitle("My details").setView(a.scroll(f))
                .setPositiveButton("Save", (d, w) -> {
                    JSONObject b = obj("name", name.getText().toString(), "phone", phone.getText().toString());
                    api.call("POST", "/me", b, r -> { api.setMe(r); a.refresh(); });
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---------- admin: people and roles ----------

    void people() {
        a.page.removeAllViews();
        a.title("People and roles");
        a.note("Everyone who signs in is a player. Give other roles by email; they apply as soon as that person signs in.");
        a.button("+ Give someone a role", v -> roles("", new JSONArray()));
        api.call("GET", "/admin/users", null, r -> {
            JSONArray us = r.optJSONArray("users");
            if (us == null) return;
            for (int i = 0; i < us.length(); i++) {
                JSONObject u = us.optJSONObject(i);
                String line = (u.optString("name").isEmpty() ? "" : u.optString("name") + "\n") + u.optString("email")
                        + "\n" + roleNames(u.optJSONArray("roles")) + (u.optBoolean("invited") ? " (hasn't signed in yet)" : "");
                a.button(line, v -> roles(u.optString("email"), u.optJSONArray("roles")));
            }
        });
    }

    void roles(String email, JSONArray current) {
        LinearLayout f = a.form();
        EditText e = a.field(f, "Email", email, false);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        if (!email.isEmpty()) e.setEnabled(false);
        CheckBox[] boxes = new CheckBox[ORG_ROLES.length];
        for (int i = 0; i < ORG_ROLES.length; i++) {
            boxes[i] = new CheckBox(a);
            boxes[i].setText(ORG_ROLES[i][1]);
            boxes[i].setTextSize(18);
            boxes[i].setChecked(contains(current, ORG_ROLES[i][0]));
            f.addView(boxes[i]);
        }
        new AlertDialog.Builder(a).setTitle(email.isEmpty() ? "Give a role" : "Roles").setView(f)
                .setPositiveButton("Save", (d, w) -> {
                    String who = e.getText().toString().trim();
                    for (int i = 0; i < ORG_ROLES.length; i++) {
                        boolean was = contains(current, ORG_ROLES[i][0]);
                        if (boxes[i].isChecked() != was)
                            api.call(was ? "POST" : "PUT", was ? "/admin/roles/remove" : "/admin/roles", obj("email", who, "role", ORG_ROLES[i][0]), r -> a.refresh());
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---------- director: tournament online + court captains ----------

    void putOnline() {
        a.confirm("Put " + a.t.name + " online?", "This lets you add court captains for it. Teams and scores stay on this phone for now.",
                () -> api.call("POST", "/tournaments", obj("name", a.t.name), r -> {
                    a.t.serverId = r.optString("id");
                    a.save();
                    a.go(this::captains, true);
                }));
    }

    void captains() {
        a.page.removeAllViews();
        a.title("Court captains");
        a.note(a.t.name + ". Captains sign in with the email you enter here and see the courts you give them.");
        a.button("+ Add a court captain", v -> captain("", ""));
        api.call("GET", "/tournaments/" + a.t.serverId + "/staff", null, r -> {
            JSONArray s = r.optJSONArray("staff");
            if (s == null) return;
            for (int i = 0; i < s.length(); i++) {
                JSONObject x = s.optJSONObject(i);
                boolean cap = "captain".equals(x.optString("role"));
                String line = x.optString("email") + "\n" + (cap ? "Court captain" + (x.optString("courts").isEmpty()
                        ? "" : ", courts " + x.optString("courts")) : "Director");
                a.button(line, v -> {
                    if (cap) captain(x.optString("email"), x.optString("courts"));
                    else a.confirm("Remove " + x.optString("email") + " as director?", "",
                            () -> api.call("POST", "/tournaments/" + a.t.serverId + "/staff/remove",
                                    obj("email", x.optString("email"), "role", "director"), rr -> a.refresh()));
                });
            }
        });
    }

    void captain(String email, String courts) {
        LinearLayout f = a.form();
        EditText e = a.field(f, "Captain's email", email, false);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        if (!email.isEmpty()) e.setEnabled(false);
        EditText c = a.field(f, "Courts (e.g. 7, 8)", courts, false);
        c.setInputType(InputType.TYPE_CLASS_PHONE);
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle("Court captain").setView(f)
                .setPositiveButton("Save", (d, w) -> api.call("PUT", "/tournaments/" + a.t.serverId + "/staff",
                        obj("email", e.getText().toString(), "role", "captain", "courts", c.getText().toString()), r -> a.refresh()))
                .setNegativeButton("Cancel", null);
        if (!email.isEmpty()) b.setNeutralButton("Remove", (d, w) -> api.call("POST", "/tournaments/" + a.t.serverId + "/staff/remove",
                obj("email", email, "role", "captain"), r -> a.refresh()));
        b.show();
    }

    // ---------- helpers ----------

    static boolean contains(JSONArray a, String s) {
        if (a != null) for (int i = 0; i < a.length(); i++) if (s.equals(a.optString(i))) return true;
        return false;
    }

    static JSONObject obj(String... kv) {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i + 1 < kv.length; i += 2) o.put(kv[i], kv[i + 1].trim()); } catch (Exception ignored) {}
        return o;
    }
}
