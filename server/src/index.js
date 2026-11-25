// Pickleball Pools accounts server: sign-in (Google, or a code sent by email), sessions and roles.
// Plain JavaScript on Cloudflare Workers + D1, no dependencies. Roles and what they may do: ../ROLES.md
//
// Every request except /auth/* needs "Authorization: Bearer <token>" (the token from sign-in).

const DAY = 86400;
const SESSION_DAYS = 90;
const ORG_ROLES = ["admin", "director", "treasurer"];
const STAFF_ROLES = ["director", "captain"];

export default {
  async fetch(req, env) {
    try {
      return await route(req, env);
    } catch (e) {
      if (e instanceof Http) return json({ error: e.message }, e.status);
      console.error(e);
      return json({ error: "Server error" }, 500);
    }
  },
};

class Http extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

async function route(req, env) {
  const url = new URL(req.url);
  const p = url.pathname.replace(/\/+$/, "") || "/";
  const m = req.method;

  if (p === "/" && m === "GET") return json({ ok: true, service: "pickleball-pools" });
  if (p === "/auth/email/start" && m === "POST") return emailStart(req, env);
  if (p === "/auth/email/verify" && m === "POST") return emailVerify(req, env);
  if (p === "/auth/google/start" && m === "GET") return googleStart(env, url);
  if (p === "/auth/google/callback" && m === "GET") return googleCallback(env, url);
  if (p === "/auth/exchange" && m === "POST") return exchange(req, env);

  const me = await auth(req, env);
  if (p === "/auth/signout" && m === "POST") {
    await env.DB.prepare("DELETE FROM sessions WHERE token_hash = ?").bind(me.tokenHash).run();
    return json({ ok: true });
  }
  if (p === "/me" && m === "GET") return json(await profile(env, me));
  if (p === "/me" && (m === "PATCH" || m === "POST")) return updateMe(req, env, me);

  if (p === "/admin/users" && m === "GET") { need(me, "admin"); return listUsers(env); }
  if (p === "/admin/roles" && m === "PUT") { need(me, "admin"); return grantRole(req, env, me); }
  // "/remove" (POST) does the same as DELETE, for clients that can't send a body with DELETE
  if ((p === "/admin/roles" && m === "DELETE") || (p === "/admin/roles/remove" && m === "POST")) { need(me, "admin"); return revokeRole(req, env, me); }

  if (p === "/tournaments" && m === "GET") return listTournaments(env, me);
  if (p === "/tournaments" && m === "POST") { need(me, "admin", "director"); return createTournament(req, env, me); }
  const t = p.match(/^\/tournaments\/([\w-]+)\/staff$/);
  const tr = p.match(/^\/tournaments\/([\w-]+)\/staff\/remove$/);
  if (tr && m === "POST") { await needStaff(env, me, tr[1], "director"); return deleteStaff(req, env, tr[1]); }
  if (t && m === "GET") { await needStaff(env, me, t[1], "director", "captain"); return listStaff(env, t[1]); }
  if (t && m === "PUT") { await needStaff(env, me, t[1], "director"); return putStaff(req, env, me, t[1]); }
  if (t && m === "DELETE") { await needStaff(env, me, t[1], "director"); return deleteStaff(req, env, t[1]); }

  throw new Http(404, "Not found");
}

// ------------------------------------------------------------------ sign-in by email code

async function emailStart(req, env) {
  const email = cleanEmail((await body(req)).email);
  const now = nowS();
  const row = await env.DB.prepare("SELECT sent, window_start FROM email_codes WHERE email = ?").bind(email).first();
  let sent = 1, windowStart = now;
  if (row && now - row.window_start < 3600) {
    if (row.sent >= 5) throw new Http(429, "Too many codes. Try again in an hour.");
    sent = row.sent + 1; windowStart = row.window_start;
  }
  const code = String(100000 + (randomInt() % 900000));
  await env.DB.prepare(
    "INSERT INTO email_codes (email, code_hash, expires, attempts, sent, window_start) VALUES (?, ?, ?, 0, ?, ?) " +
    "ON CONFLICT(email) DO UPDATE SET code_hash = excluded.code_hash, expires = excluded.expires, attempts = 0, " +
    "sent = excluded.sent, window_start = excluded.window_start"
  ).bind(email, await sha256(email + ":" + code), now + 600, sent, windowStart).run();
  await sendCode(env, email, code);
  // DEV_SHOW_CODE is only for local testing (wrangler dev); never set it on the real server.
  return json(env.DEV_SHOW_CODE === "1" ? { ok: true, devCode: code } : { ok: true });
}

async function emailVerify(req, env) {
  const b = await body(req);
  const email = cleanEmail(b.email);
  const code = String(b.code || "").replace(/\D/g, "");
  const row = await env.DB.prepare("SELECT * FROM email_codes WHERE email = ?").bind(email).first();
  if (!row || row.expires < nowS()) throw new Http(400, "That code has expired. Ask for a new one.");
  if (row.attempts >= 5) throw new Http(429, "Too many tries. Ask for a new code.");
  if (!timingSafeEqual(row.code_hash, await sha256(email + ":" + code))) {
    await env.DB.prepare("UPDATE email_codes SET attempts = attempts + 1 WHERE email = ?").bind(email).run();
    throw new Http(400, "That code isn't right.");
  }
  await env.DB.prepare("DELETE FROM email_codes WHERE email = ?").bind(email).run();
  const user = await signIn(env, { email });
  return json({ token: await newSession(env, user.id), user: await profile(env, await loadUser(env, user.id)) });
}

async function sendCode(env, email, code) {
  if (!env.RESEND_API_KEY) {
    if (env.DEV_SHOW_CODE === "1") return;
    throw new Http(500, "Email sign-in isn't set up on this server (RESEND_API_KEY).");
  }
  const org = env.ORG_NAME || "Pickleball Pools";
  const r = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: { Authorization: "Bearer " + env.RESEND_API_KEY, "Content-Type": "application/json" },
    body: JSON.stringify({
      from: env.MAIL_FROM, to: [email], subject: `${org} sign-in code: ${code}`,
      text: `Your ${org} sign-in code is ${code}\n\nIt works for 10 minutes. If you didn't ask for it, ignore this email.`,
    }),
  });
  if (!r.ok) throw new Http(502, "Couldn't send the email. Try again.");
}

// ------------------------------------------------------------------ sign-in with Google (OAuth code flow + PKCE)

async function googleStart(env, url) {
  if (!env.GOOGLE_CLIENT_ID) throw new Http(500, "Google sign-in isn't set up on this server.");
  const state = randomToken(), verifier = randomToken();
  const now = nowS();
  await env.DB.prepare("DELETE FROM oauth_states WHERE created < ?").bind(now - 900).run();
  await env.DB.prepare("INSERT INTO oauth_states (state, verifier, created) VALUES (?, ?, ?)").bind(state, verifier, now).run();
  const q = new URLSearchParams({
    client_id: env.GOOGLE_CLIENT_ID, redirect_uri: url.origin + "/auth/google/callback", response_type: "code",
    scope: "openid email profile", state, code_challenge: await sha256b64url(verifier), code_challenge_method: "S256",
    prompt: "select_account",
  });
  return Response.redirect((env.GOOGLE_AUTH_URL || "https://accounts.google.com/o/oauth2/v2/auth") + "?" + q, 302);
}

async function googleCallback(env, url) {
  const state = url.searchParams.get("state") || "", code = url.searchParams.get("code") || "";
  const row = await env.DB.prepare("DELETE FROM oauth_states WHERE state = ? RETURNING verifier, created").bind(state).first();
  if (!row || row.created < nowS() - 900 || !code) throw new Http(400, "Sign-in expired. Please try again.");
  const r = await fetch(env.GOOGLE_TOKEN_URL || "https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      code, client_id: env.GOOGLE_CLIENT_ID, client_secret: env.GOOGLE_CLIENT_SECRET,
      redirect_uri: url.origin + "/auth/google/callback", grant_type: "authorization_code", code_verifier: row.verifier,
    }),
  });
  if (!r.ok) throw new Http(400, "Google sign-in failed. Please try again.");
  // The ID token came straight from Google's token endpoint over TLS, so its claims can be read without
  // checking the signature (Google's OpenID Connect docs); audience, issuer and expiry are still checked.
  const c = jwtClaims((await r.json()).id_token);
  if (c.aud !== env.GOOGLE_CLIENT_ID || !["accounts.google.com", "https://accounts.google.com"].includes(c.iss)
      || c.exp < nowS() || !c.sub || !c.email || c.email_verified !== true)
    throw new Http(400, "Google didn't confirm this email address.");
  const user = await signIn(env, { email: c.email, name: c.name || "", googleSub: c.sub });
  const handoff = randomToken();
  await env.DB.prepare("INSERT INTO handoffs (code_hash, user_id, expires) VALUES (?, ?, ?)")
    .bind(await sha256(handoff), user.id, nowS() + 120).run();
  return Response.redirect((env.APP_REDIRECT || "pickleballpools://signin") + "?code=" + handoff, 302);
}

async function exchange(req, env) {
  const code = String((await body(req)).code || "");
  const row = await env.DB.prepare("DELETE FROM handoffs WHERE code_hash = ? RETURNING user_id, expires")
    .bind(await sha256(code)).first();
  if (!row || row.expires < nowS()) throw new Http(400, "Sign-in expired. Please try again.");
  return json({ token: await newSession(env, row.user_id), user: await profile(env, await loadUser(env, row.user_id)) });
}

// ------------------------------------------------------------------ users and sessions

/**
 * Finds or creates the user for a confirmed email. The first admin: ADMIN_EMAIL if it is set (recommended, so
 * nobody else can sign in first and take over a new server), otherwise the very first person to sign in.
 */
async function signIn(env, { email, name = "", googleSub = null }) {
  email = cleanEmail(email);
  const now = nowS();
  let u = googleSub && await env.DB.prepare("SELECT * FROM users WHERE google_sub = ?").bind(googleSub).first();
  if (!u) u = await env.DB.prepare("SELECT * FROM users WHERE email = ?").bind(email).first();
  if (!u) {
    u = { id: randomToken(12), email };
    await env.DB.prepare("INSERT INTO users (id, email, name, google_sub, created, last_login) VALUES (?, ?, ?, ?, ?, ?)")
      .bind(u.id, email, name, googleSub, now, now).run();
    const admins = await env.DB.prepare("SELECT COUNT(*) AS n FROM roles WHERE role = 'admin'").first();
    const first = env.ADMIN_EMAIL ? cleanEmail(env.ADMIN_EMAIL) === email : true;
    if (admins.n === 0 && first)
      await env.DB.prepare("INSERT OR IGNORE INTO roles (email, role, granted_by, created) VALUES (?, 'admin', 'first sign-in', ?)")
        .bind(email, now).run();
  } else {
    await env.DB.prepare("UPDATE users SET last_login = ?, google_sub = COALESCE(google_sub, ?), " +
      "name = CASE WHEN name = '' THEN ? ELSE name END WHERE id = ?").bind(now, googleSub, name, u.id).run();
  }
  return u;
}

async function newSession(env, userId) {
  const token = randomToken();
  const now = nowS();
  await env.DB.prepare("DELETE FROM sessions WHERE expires < ?").bind(now).run();
  await env.DB.prepare("INSERT INTO sessions (token_hash, user_id, created, expires) VALUES (?, ?, ?, ?)")
    .bind(await sha256(token), userId, now, now + SESSION_DAYS * DAY).run();
  return token;
}

async function auth(req, env) {
  const h = req.headers.get("Authorization") || "";
  if (!h.startsWith("Bearer ")) throw new Http(401, "Please sign in.");
  const tokenHash = await sha256(h.slice(7).trim());
  const s = await env.DB.prepare("SELECT user_id, expires FROM sessions WHERE token_hash = ?").bind(tokenHash).first();
  if (!s || s.expires < nowS()) throw new Http(401, "Please sign in again.");
  const u = await loadUser(env, s.user_id);
  if (!u) throw new Http(401, "Please sign in again.");
  u.tokenHash = tokenHash;
  return u;
}

async function loadUser(env, id) {
  const u = await env.DB.prepare("SELECT * FROM users WHERE id = ?").bind(id).first();
  if (!u) return null;
  const r = await env.DB.prepare("SELECT role FROM roles WHERE email = ?").bind(u.email).all();
  u.roles = r.results.map(x => x.role);
  return u;
}

async function profile(env, u) {
  const staff = await env.DB.prepare(
    "SELECT s.tournament_id AS id, t.name, s.role, s.courts FROM staff s JOIN tournaments t ON t.id = s.tournament_id WHERE s.email = ?"
  ).bind(u.email).all();
  return {
    id: u.id, email: u.email, name: u.name, duprId: u.dupr_id, phone: u.phone, marketing: !!u.marketing,
    roles: ["player", ...u.roles], google: !!u.google_sub, tournaments: staff.results,
  };
}

async function updateMe(req, env, me) {
  const b = await body(req);
  const name = b.name === undefined ? me.name : String(b.name).trim().slice(0, 80);
  const dupr = b.duprId === undefined ? me.dupr_id : String(b.duprId).trim().toUpperCase().slice(0, 20);
  const phone = b.phone === undefined ? me.phone : String(b.phone).trim().slice(0, 30);
  const marketing = b.marketing === undefined ? me.marketing : (b.marketing ? 1 : 0);
  await env.DB.prepare("UPDATE users SET name = ?, dupr_id = ?, phone = ?, marketing = ? WHERE id = ?")
    .bind(name, dupr, phone, marketing, me.id).run();
  return json(await profile(env, await loadUser(env, me.id)));
}

// ------------------------------------------------------------------ admin: roles

async function listUsers(env) {
  const users = (await env.DB.prepare("SELECT id, email, name, dupr_id, last_login FROM users ORDER BY email").all()).results;
  const roles = (await env.DB.prepare("SELECT email, role FROM roles ORDER BY email, role").all()).results;
  const byEmail = {};
  for (const r of roles) (byEmail[r.email] ||= []).push(r.role);
  const out = users.map(u => ({ ...u, roles: byEmail[u.email] || [] }));
  // Roles given to people who haven't signed in yet
  for (const email of Object.keys(byEmail))
    if (!users.some(u => u.email === email)) out.push({ email, name: "", roles: byEmail[email], invited: true });
  return json({ users: out });
}

async function grantRole(req, env, me) {
  const b = await body(req);
  const email = cleanEmail(b.email), role = String(b.role || "");
  if (!ORG_ROLES.includes(role)) throw new Http(400, "Role must be one of: " + ORG_ROLES.join(", "));
  await env.DB.prepare("INSERT OR IGNORE INTO roles (email, role, granted_by, created) VALUES (?, ?, ?, ?)")
    .bind(email, role, me.email, nowS()).run();
  return json({ ok: true });
}

async function revokeRole(req, env) {
  const b = await body(req);
  const email = cleanEmail(b.email), role = String(b.role || "");
  if (role === "admin") {
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM roles WHERE role = 'admin' AND email != ?").bind(email).first();
    if (n.n === 0) throw new Http(400, "There must always be at least one admin.");
  }
  await env.DB.prepare("DELETE FROM roles WHERE email = ? AND role = ?").bind(email, role).run();
  return json({ ok: true });
}

// ------------------------------------------------------------------ tournaments and their staff

async function listTournaments(env, me) {
  const all = me.roles.some(r => ORG_ROLES.includes(r)); // admins, directors and treasurers see every tournament
  const rows = all
    ? await env.DB.prepare("SELECT id, name, created_by, created FROM tournaments ORDER BY created DESC").all()
    : await env.DB.prepare("SELECT DISTINCT t.id, t.name, t.created_by, t.created FROM tournaments t JOIN staff s " +
        "ON s.tournament_id = t.id WHERE s.email = ? ORDER BY t.created DESC").bind(me.email).all();
  return json({ tournaments: rows.results });
}

async function createTournament(req, env, me) {
  const name = String((await body(req)).name || "").trim().slice(0, 100);
  if (!name) throw new Http(400, "Give the tournament a name.");
  const id = randomToken(9), now = nowS();
  await env.DB.batch([
    env.DB.prepare("INSERT INTO tournaments (id, name, created_by, created) VALUES (?, ?, ?, ?)").bind(id, name, me.email, now),
    env.DB.prepare("INSERT INTO staff (tournament_id, email, role, courts, granted_by, created) VALUES (?, ?, 'director', '', ?, ?)")
      .bind(id, me.email, me.email, now),
  ]);
  return json({ id, name }, 201);
}

async function listStaff(env, id) {
  const r = await env.DB.prepare("SELECT email, role, courts FROM staff WHERE tournament_id = ? ORDER BY role, email").bind(id).all();
  return json({ staff: r.results });
}

async function putStaff(req, env, me, id) {
  const b = await body(req);
  const email = cleanEmail(b.email), role = String(b.role || "");
  if (!STAFF_ROLES.includes(role)) throw new Http(400, "Role must be director or captain.");
  const courts = String(b.courts || "").replace(/[^0-9,]/g, "").replace(/,+/g, ",").replace(/^,|,$/g, "");
  await env.DB.prepare("INSERT INTO staff (tournament_id, email, role, courts, granted_by, created) VALUES (?, ?, ?, ?, ?, ?) " +
    "ON CONFLICT(tournament_id, email, role) DO UPDATE SET courts = excluded.courts")
    .bind(id, email, role, courts, me.email, nowS()).run();
  return json({ ok: true });
}

async function deleteStaff(req, env, id) {
  const b = await body(req);
  const email = cleanEmail(b.email), role = String(b.role || "");
  if (role === "director") {
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM staff WHERE tournament_id = ? AND role = 'director' AND email != ?")
      .bind(id, email).first();
    if (n.n === 0) throw new Http(400, "A tournament needs at least one director.");
  }
  await env.DB.prepare("DELETE FROM staff WHERE tournament_id = ? AND email = ? AND role = ?").bind(id, email, role).run();
  return json({ ok: true });
}

// ------------------------------------------------------------------ permission checks

function need(me, ...roles) {
  if (!me.roles.some(r => roles.includes(r))) throw new Http(403, "You don't have permission to do that.");
}

/** Admins can do anything; otherwise the person needs one of the roles for this tournament. */
async function needStaff(env, me, id, ...roles) {
  const t = await env.DB.prepare("SELECT id FROM tournaments WHERE id = ?").bind(id).first();
  if (!t) throw new Http(404, "No such tournament.");
  if (me.roles.includes("admin")) return;
  const r = await env.DB.prepare("SELECT role FROM staff WHERE tournament_id = ? AND email = ?").bind(id, me.email).all();
  if (!r.results.some(x => roles.includes(x.role))) throw new Http(403, "You don't have permission to do that.");
}

// ------------------------------------------------------------------ helpers

function json(o, status = 200) {
  return new Response(JSON.stringify(o), { status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });
}

async function body(req) {
  try { return await req.json(); } catch { throw new Http(400, "Expected JSON."); }
}

function cleanEmail(e) {
  e = String(e || "").trim().toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(e) || e.length > 200) throw new Http(400, "Please enter a valid email address.");
  return e;
}

const nowS = () => Math.floor(Date.now() / 1000);

function randomInt() { return crypto.getRandomValues(new Uint32Array(1))[0]; }

function randomToken(bytes = 32) { return b64url(crypto.getRandomValues(new Uint8Array(bytes))); }

function b64url(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function sha256(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map(b => b.toString(16).padStart(2, "0")).join("");
}

async function sha256b64url(s) {
  return b64url(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s))));
}

function timingSafeEqual(a, b) {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

function jwtClaims(jwt) {
  const part = String(jwt || "").split(".")[1];
  if (!part) throw new Http(400, "Google sign-in failed.");
  return JSON.parse(atob(part.replace(/-/g, "+").replace(/_/g, "/")));
}
