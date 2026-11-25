package org.pickleballpools.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Talks to the optional accounts server (server/ folder). The app works fully offline without it; signing in
 * adds roles (admin, tournament director, court captain, treasurer, player). Calls run off the UI thread and
 * report back on it.
 */
final class Api {
    interface Done { void ok(JSONObject r); }

    private final Activity a;
    private final SharedPreferences prefs;

    Api(Activity a) {
        this.a = a;
        prefs = a.getSharedPreferences("account", Context.MODE_PRIVATE);
    }

    String server() { return prefs.getString("server", ""); }

    void setServer(String url) {
        String u = url.trim().replaceAll("/+$", "");
        if (!u.isEmpty() && !u.startsWith("http")) u = "https://" + u;
        prefs.edit().putString("server", u).apply();
    }

    boolean signedIn() { return !prefs.getString("token", "").isEmpty(); }

    void setToken(String token) { prefs.edit().putString("token", token).apply(); }

    /** The last profile the server sent (name, email, roles, tournaments), or an empty object. */
    JSONObject me() {
        try { return new JSONObject(prefs.getString("me", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    void setMe(JSONObject me) { prefs.edit().putString("me", me.toString()).apply(); }

    void signOutLocally() { prefs.edit().remove("token").remove("me").apply(); }

    boolean has(String role) {
        org.json.JSONArray r = me().optJSONArray("roles");
        if (r == null) return false;
        for (int i = 0; i < r.length(); i++) if (role.equals(r.optString(i))) return true;
        return false;
    }

    /** Sends a request; on success runs ok on the UI thread, otherwise shows the server's message. */
    void call(String method, String path, JSONObject body, Done ok) {
        if (server().isEmpty()) { ((MainActivity) a).toast("Set the server address first (Account → Server)."); return; }
        String token = prefs.getString("token", "");
        new Thread(() -> {
            String err;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(server() + path).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestMethod(method); // GET, POST, PUT or DELETE (HttpURLConnection has no PATCH)
                if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
                if (body != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                }
                int code = c.getResponseCode();
                InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                JSONObject r = new JSONObject(read(in));
                if (code < 400) { a.runOnUiThread(() -> ok.ok(r)); return; }
                if (code == 401) signOutLocally();
                err = r.optString("error", "Server error " + code);
            } catch (Exception e) {
                err = "Couldn't reach the server. Check the internet connection.";
            }
            String msg = err;
            a.runOnUiThread(() -> ((MainActivity) a).toast(msg));
        }).start();
    }

    private static String read(InputStream in) throws java.io.IOException {
        if (in == null) return "{}";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }
}
