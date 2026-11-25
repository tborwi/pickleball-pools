package org.pickleballpools.app;

import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.pickleballpools.core.Tournament;

import java.util.List;

/**
 * Venues: the places the club plays and the courts each has (e.g. "1-8, 10"). Admins keep the list on the server;
 * directors pick one for a tournament, which copies its name and courts into the tournament (so it works offline and
 * can't change under a running event). The pick is locked once the tournament starts.
 */
final class Venues {
    final MainActivity a;

    Venues(MainActivity a) { this.a = a; }

    /** Last list from the server, kept so picking works without signal. */
    JSONArray cached() {
        try { return new JSONArray(a.getSharedPreferences("account", 0).getString("venues", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    void remember(JSONObject r) {
        JSONArray v = r.optJSONArray("venues");
        if (v != null) a.getSharedPreferences("account", 0).edit().putString("venues", v.toString()).apply();
    }

    boolean canRead() { return a.account.api.signedIn() && (a.account.api.has("admin") || a.account.api.has("director")); }

    // ---------- Director: pick this tournament's venue ----------

    void pick() {
        if (a.t.started()) { a.toast("The tournament has started: the venue is locked."); return; }
        if (!canRead()) { showPicker(cached()); return; }
        a.account.api.call("GET", "/venues", null, r -> { remember(r); showPicker(r.optJSONArray("venues")); },
                (net, msg) -> showPicker(cached()));
    }

    void showPicker(JSONArray vs) {
        int n = vs == null ? 0 : vs.length();
        String[] items = new String[n + 2];
        for (int i = 0; i < n; i++) {
            JSONObject v = vs.optJSONObject(i);
            items[i] = v.optString("name") + "\n   courts " + Tournament.courtsText(courts(v));
        }
        items[n] = "Somewhere else (type the courts)";
        items[n + 1] = "No venue (number courts from the first court in Settings)";
        new AlertDialog.Builder(a).setTitle("Where is it played?").setItems(items, (d, w) -> {
            if (w < n) set(vs.optJSONObject(w).optString("name"), courts(vs.optJSONObject(w)));
            else if (w == n) byHand();
            else set("", null);
        }).setNegativeButton("Cancel", null).show();
    }

    static List<Integer> courts(JSONObject v) {
        List<Integer> out = new java.util.ArrayList<>();
        JSONArray c = v.optJSONArray("courts");
        for (int i = 0; c != null && i < c.length(); i++) out.add(c.optInt(i));
        return out;
    }

    void byHand() {
        LinearLayout f = a.form();
        EditText name = a.field(f, "Venue name", a.t.venue, false);
        EditText cs = a.field(f, "Courts (e.g. 1-8, 10)", Tournament.courtsText(a.t.courts), false);
        cs.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        AlertDialog d = new AlertDialog.Builder(a).setTitle("Venue").setView(f)
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null).show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<Integer> c = Tournament.parseCourts(cs.getText().toString());
            if (c == null || c.isEmpty()) { a.toast("Courts: use numbers and ranges, like 1-8, 10"); return; }
            set(name.getText().toString().trim(), c);
            d.dismiss();
        });
    }

    void set(String name, List<Integer> courts) {
        a.t.venue = name;
        a.t.courts.clear();
        if (courts != null) a.t.courts.addAll(courts);
        a.save();
        a.refresh();
    }

    // ---------- Admin: the club's venues ----------

    void manage() {
        a.page.removeAllViews();
        a.title("Venues and courts");
        a.note("Directors pick one of these for each tournament. Tap to edit, press and hold to remove.");
        show(cached());
        a.account.api.call("GET", "/venues", null, r -> {
            boolean changed = !String.valueOf(r.optJSONArray("venues")).equals(cached().toString());
            remember(r);
            if (changed) a.refresh();
        }, (net, msg) -> a.toast(msg));
    }

    void show(JSONArray vs) {
        for (int i = 0; vs != null && i < vs.length(); i++) {
            JSONObject v = vs.optJSONObject(i);
            List<Integer> c = courts(v);
            TextView row = a.card(a.text(v.optString("name") + (v.optString("address").isEmpty() ? "" : "\n" + v.optString("address"))
                    + "\n" + c.size() + " court" + (c.size() == 1 ? "" : "s") + ": " + Tournament.courtsText(c), 17, false, MainActivity.INK));
            row.setOnClickListener(x -> edit(v));
            row.setOnLongClickListener(x -> {
                a.confirm("Remove " + v.optString("name") + "?", "Tournaments that already use it keep their courts.", () ->
                        a.account.api.call("POST", "/venues/remove", obj("id", v.optString("id")), r -> { remember(r); a.refresh(); }));
                return true;
            });
        }
        a.button("+ Add a venue", v -> edit(new JSONObject()));
    }

    void edit(JSONObject v) {
        LinearLayout f = a.form();
        EditText name = a.field(f, "Name", v.optString("name"), false);
        EditText addr = a.field(f, "Address (optional)", v.optString("address"), false);
        EditText cs = a.field(f, "Courts (e.g. 1-8, 10)", Tournament.courtsText(courts(v)), false);
        cs.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        AlertDialog d = new AlertDialog.Builder(a).setTitle(v.has("id") ? "Edit venue" : "New venue").setView(a.scroll(f))
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null).show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            List<Integer> c = Tournament.parseCourts(cs.getText().toString());
            if (name.getText().toString().trim().isEmpty()) { a.toast("Give the venue a name"); return; }
            if (c == null || c.isEmpty()) { a.toast("Courts: use numbers and ranges, like 1-8, 10"); return; }
            try {
                JSONObject b = new JSONObject().put("name", name.getText().toString().trim())
                        .put("address", addr.getText().toString().trim()).put("courts", new JSONArray(c));
                if (v.has("id")) b.put("id", v.optString("id"));
                a.account.api.call("PUT", "/venues", new JSONObject().put("venue", b), r -> {
                    remember(r);
                    d.dismiss();
                    a.toast("Venue saved");
                    a.refresh();
                });
            } catch (Exception ignored) {}
        });
    }

    static JSONObject obj(String k, String v) {
        try { return new JSONObject().put(k, v); } catch (Exception e) { return new JSONObject(); }
    }
}
