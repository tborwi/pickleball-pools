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
  // Public, no sign-in: players don't have accounts. Links are long random ids, shared by the director or emailed.
  if (p === "/account/delete" && m === "GET") return new Response(DELETE_HTML, { headers: { "Content-Type": "text/html; charset=utf-8" } });
  if ((p === "/results" || p === "/results.json") && m === "GET") return resultsIndex(env, p.endsWith(".json"));
  const rs = p.match(/^\/results\/([\w-]{1,64})(\.json)?$/);
  if (rs && m === "GET") return resultsPage(env, rs[1], !!rs[2]);
  const pub = p.match(/^\/(t|public|r)\/([\w-]+)$/);
  if (pub && m === "GET") return pub[1] === "t" ? livePage(env, pub[2]) : pub[1] === "public" ? publicLive(env, pub[2]) : receiptByToken(env, pub[2]);

  const me = await auth(req, env);
  if (p === "/auth/signout" && m === "POST") {
    await env.DB.prepare("DELETE FROM sessions WHERE token_hash = ?").bind(me.tokenHash).run();
    return json({ ok: true });
  }
  if (p === "/me" && m === "GET") return json(await profile(env, me));
  if (p === "/me" && (m === "PATCH" || m === "POST")) return updateMe(req, env, me);
  if (p === "/me/delete" && m === "POST") return deleteMe(env, me);

  if (p === "/admin/users" && m === "GET") { need(me, "admin"); return listUsers(env); }
  if (p === "/admin/roles" && m === "PUT") { need(me, "admin"); return grantRole(req, env, me); }
  // "/remove" (POST) does the same as DELETE, for clients that can't send a body with DELETE
  if ((p === "/admin/roles" && m === "DELETE") || (p === "/admin/roles/remove" && m === "POST")) { need(me, "admin"); return revokeRole(req, env, me); }
  if (p === "/admin/audit" && m === "GET") { need(me, "admin"); return getAudit(env, "", url); }

  if (p === "/club" && m === "GET") return getClub(env);
  if (p === "/club" && m === "PUT") { need(me, "admin"); return putClub(req, env, me); }
  if (p === "/results" && m === "POST") { need(me, "admin", "director"); return publishResults(req, env, me); }
  if (p === "/results/remove" && m === "POST") { need(me, "admin", "director"); return removeResults(req, env, me); }
  if (p === "/venues" && m === "GET") { need(me, "admin", "director"); return getVenues(env); }
  if (p === "/venues" && m === "PUT") { need(me, "admin"); return putVenue(req, env, me); }
  if (p === "/venues/remove" && m === "POST") { need(me, "admin"); return removeVenue(req, env, me); }
  if (p === "/zeffy/campaigns" && m === "GET") { need(me, "admin", "director"); return zeffyCampaigns(env); }
  const ro = p.match(/^\/rosters\/([\w-]{1,40})$/);
  if (ro && m === "GET") { need(me, "admin", "director"); return getRoster(env, ro[1]); }
  if (ro && m === "PUT") { need(me, "admin"); return putRoster(req, env, me, ro[1]); }

  if (p === "/tournaments" && m === "GET") return listTournaments(env, me);
  if (p === "/tournaments" && m === "POST") { need(me, "admin", "director"); return createTournament(req, env, me); }
  const t = p.match(/^\/tournaments\/([\w-]+)\/staff$/);
  const lv = p.match(/^\/tournaments\/([\w-]+)\/(live|scores)$/);
  if (lv && lv[2] === "live" && m === "PUT") { await needStaff(env, me, lv[1], "director"); return putLive(req, env, me, lv[1]); }
  if (lv && lv[2] === "live" && m === "GET") return getLive(env, me, lv[1], url); // checks access itself (staff or player)
  if (lv && lv[2] === "scores" && m === "POST") { await needStaff(env, me, lv[1], "director", "captain"); return postScore(req, env, me, lv[1]); }
  const rc = p.match(/^\/tournaments\/([\w-]+)\/receipts(?:\/([\w-]+))?$/);
  if (rc && !rc[2] && m === "POST") { await needStaff(env, me, rc[1], "director", "captain"); return postReceipt(req, env, me, rc[1], url); }
  if (rc && rc[2] && m === "GET") return getReceipt(env, me, rc[1], rc[2]);
  const au = p.match(/^\/tournaments\/([\w-]+)\/(audit|events)$/);
  if (au && au[2] === "audit" && m === "GET") { await needStaff(env, me, au[1], "director"); return getAudit(env, au[1], url); }
  const re = p.match(/^\/tournaments\/([\w-]+)\/results-email$/);
  if (re && m === "POST") { await needStaff(env, me, re[1], "director"); return resultsEmail(env, me, re[1], url); }
  if (au && au[2] === "events" && m === "POST") { await needStaff(env, me, au[1], "director"); return postEvent(req, env, me, au[1]); }
  const tr = p.match(/^\/tournaments\/([\w-]+)\/staff\/remove$/);
  if (tr && m === "POST") { await needStaff(env, me, tr[1], "director"); return deleteStaff(req, env, me, tr[1]); }
  if (t && m === "GET") { await needStaff(env, me, t[1], "director", "captain"); return listStaff(env, t[1]); }
  if (t && m === "PUT") { await needStaff(env, me, t[1], "director"); return putStaff(req, env, me, t[1]); }
  if (t && m === "DELETE") { await needStaff(env, me, t[1], "director"); return deleteStaff(req, env, me, t[1]); }

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
  if (!r.ok) {
    console.error("Resend refused the email:", r.status, (await r.text()).slice(0, 300)); // visible in `wrangler tail`
    throw new Http(502, "Couldn't send the email. Try again.");
  }
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
    // ADMIN_EMAIL may list several addresses, comma-separated (e.g. the same person's two accounts)
    const first = env.ADMIN_EMAIL ? env.ADMIN_EMAIL.split(",").map(x => x.trim().toLowerCase()).includes(email) : true;
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
    roles: u.roles, google: !!u.google_sub, tournaments: staff.results,
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
  await audit(env, "", me.email, "role", "", { email, role, change: "given" });
  return json({ ok: true });
}

async function revokeRole(req, env, me) {
  const b = await body(req);
  const email = cleanEmail(b.email), role = String(b.role || "");
  if (role === "admin") {
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM roles WHERE role = 'admin' AND email != ?").bind(email).first();
    if (n.n === 0) throw new Http(400, "There must always be at least one admin.");
  }
  await env.DB.prepare("DELETE FROM roles WHERE email = ? AND role = ?").bind(email, role).run();
  await audit(env, "", me.email, "role", "", { email, role, change: "removed" });
  return json({ ok: true });
}

// ------------------------------------------------------------------ tournaments and their staff

async function listTournaments(env, me) {
  const all = me.roles.some(r => ORG_ROLES.includes(r)); // admins, directors and treasurers see every tournament
  const rows = all
    ? await env.DB.prepare("SELECT id, name, created_by, created FROM tournaments ORDER BY created DESC").all()
    : await env.DB.prepare("SELECT DISTINCT t.id, t.name, t.created_by, t.created FROM tournaments t JOIN staff s " +
        "ON s.tournament_id = t.id WHERE s.email = ? ORDER BY t.created DESC").bind(me.email).all();
  const out = rows.results;
  return json({ tournaments: out });
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
  await audit(env, id, me.email, "staff", "", { email, role, courts, change: "set" });
  return json({ ok: true });
}

async function deleteStaff(req, env, me, id) {
  const b = await body(req);
  const email = cleanEmail(b.email), role = String(b.role || "");
  if (role === "director") {
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM staff WHERE tournament_id = ? AND role = 'director' AND email != ?")
      .bind(id, email).first();
    if (n.n === 0) throw new Http(400, "A tournament needs at least one director.");
  }
  await env.DB.prepare("DELETE FROM staff WHERE tournament_id = ? AND email = ? AND role = ?").bind(id, email, role).run();
  await audit(env, id, me.email, "staff", "", { email, role, change: "removed" });
  return json({ ok: true });
}

// ------------------------------------------------------------------ live scoring

/** The director's phone publishes the current pools. Scores already entered for matches that still exist are kept. */
async function putLive(req, env, me, id) {
  const d = (await body(req)).data;
  if (!d || !Array.isArray(d.matches) || d.matches.length > 2000) throw new Http(400, "Expected data.matches.");
  for (const x of d.matches)
    if (typeof x.key !== "string" || !x.key || x.key.length > 80) throw new Http(400, "Every match needs a key.");
  const text = JSON.stringify(d);
  if (text.length > 500000) throw new Http(400, "Too much data.");
  await env.DB.prepare("INSERT INTO live (tournament_id, data, updated) VALUES (?, ?, ?) " +
    "ON CONFLICT(tournament_id) DO UPDATE SET data = excluded.data, updated = excluded.updated").bind(id, text, nowS()).run();
  await audit(env, id, me.email, "publish", "", { matches: d.matches.length });
  const emailed = d.startedAt > 1 ? await startEmail(env, me, id, d, new URL(req.url)) : 0;
  return json({ ok: true, emailed });
}

/**
 * Once, when the pools first arrive with the tournament started: each team's players get the live scores link and
 * their own schedule (pool, round, court, opponent). Sent again only if it never went out (one "start" entry in History).
 */
async function startEmail(env, me, id, d, url) {
  const done = await env.DB.prepare("SELECT id FROM audit WHERE tournament_id = ? AND action = 'email' AND match_key = 'start' LIMIT 1").bind(id).first();
  if (done) return 0;
  const base = publicBase(env, url), name = d.name || "Tournament";
  let sent = 0, any = false;
  for (const team of Object.keys(d.who || {})) {
    const to = playerEmails(d, [team]);
    if (!to.length) continue;
    any = true;
    const mine = d.matches.filter(x => x.aId === team || x.bId === team).sort((x, y) => x.round - y.round);
    if (!mine.length) continue; // withdrawn team
    const label = mine[0].aId === team ? mine[0].a : mine[0].b;
    const lines = mine.map(x => `  Round ${x.round}, court ${x.court}: vs ${x.aId === team ? x.b : x.a}`).join("\n");
    sent += await mailEach(env, id, me.email, to, `${name} has started: live scores`,
      `${name} has started. Good luck!\n\nLive scores and standings (updates by itself):\n${base}/t/${id}\n\n` +
      `${d.singles ? "You" : "Your team"}: ${label} (${mine[0].stage}, ${mine[0].pool})\n${lines}\n\n` +
      `After each match you'll get an email with the photo of the score sheet.`, "start", d);
  }
  if (!any) await audit(env, id, me.email, "email", "start", { to: [], subject: "start (no player emails)", sent: 0 });
  return sent;
}

/** What staff may see: "all" (admin, director) or "courts" (court captain: their courts). Throws 403 otherwise. */
async function access(env, me, id) {
  const t = await env.DB.prepare("SELECT id FROM tournaments WHERE id = ?").bind(id).first();
  if (!t) throw new Http(404, "No such tournament.");
  if (me.roles.includes("admin")) return { kind: "all", courts: null };
  const r = (await env.DB.prepare("SELECT role, courts FROM staff WHERE tournament_id = ? AND email = ?").bind(id, me.email).all()).results;
  if (r.some(x => x.role === "director")) return { kind: "all", courts: null };
  if (r.some(x => x.role === "captain")) return { kind: "courts", courts: r.flatMap(x => x.courts.split(",")).filter(Boolean) };
  throw new Http(403, "You don't have permission to do that.");
}

function canSeeMatch(a, match) { return a.kind === "all" || (a.courts || []).includes(String(match.court)); }

/**
 * Pools + scores + receipts, for staff. Court captains get only the matches on their courts.
 * ?since=<seq> returns only newer scores. (Everyone else uses the public page, /t/<id>.)
 */
async function getLive(env, me, id, url) {
  const row = await env.DB.prepare("SELECT data, updated FROM live WHERE tournament_id = ?").bind(id).first();
  let data = row ? JSON.parse(row.data) : null;
  const a = await access(env, me, id);
  const since = parseInt(url.searchParams.get("since") || "0", 10) || 0;
  const sc = await env.DB.prepare("SELECT match_key AS key, games, entered_by AS by, seq FROM scores " +
    "WHERE tournament_id = ? AND seq > ? ORDER BY seq").bind(id, since).all();
  const top = await env.DB.prepare("SELECT COALESCE(MAX(seq), 0) AS s FROM scores WHERE tournament_id = ?").bind(id).first();
  let scores = sc.results.map(r => ({ ...r, games: JSON.parse(r.games) }));
  let receipts = (await env.DB.prepare("SELECT id, match_key AS key, entered_by AS by, created FROM receipts " +
    "WHERE tournament_id = ? ORDER BY created").bind(id).all()).results;
  if (data) {
    const all = data.matches;
    const seeable = new Set(all.filter(x => canSeeMatch(a, x)).map(x => x.key));
    receipts = receipts.filter(r => seeable.has(r.key));
    if (a.kind !== "all") {
      const { who, ...rest } = data; // players' emails and DUPR IDs stay with the directors
      data = rest;
      if (a.kind === "courts") {
        data = { ...data, matches: all.filter(x => a.courts.includes(String(x.court))) };
        const keys = new Set(data.matches.map(x => x.key));
        scores = scores.filter(x => keys.has(x.key));
      }
    }
  } else receipts = [];
  return json({ data, updated: row ? row.updated : 0, scores, seq: top.s, courts: a.courts, receipts, view: a.kind });
}

async function postScore(req, env, me, id) {
  const b = await body(req);
  const row = await env.DB.prepare("SELECT data FROM live WHERE tournament_id = ?").bind(id).first();
  if (!row) throw new Http(400, "The director hasn't published the pools yet.");
  const d = JSON.parse(row.data);
  const match = d.matches.find(x => x.key === b.key);
  if (!match) throw new Http(400, "That match isn't in the current pools. Refresh and try again.");
  const a = await access(env, me, id);
  if (a.kind !== "all" && !(a.courts || []).includes(String(match.court)))
    throw new Http(403, "That match is on court " + match.court + ", not one of yours.");
  const games = checkGames(b.games, d.pointsToWin || 11, d.winBy || 2);
  const old = await env.DB.prepare("SELECT games FROM scores WHERE tournament_id = ? AND match_key = ?").bind(id, b.key).first();
  // seq: one more than the highest so far for this tournament (D1 runs statements one at a time per database)
  await env.DB.prepare("INSERT INTO scores (tournament_id, match_key, games, entered_by, seq, updated) " +
    "VALUES (?1, ?2, ?3, ?4, (SELECT COALESCE(MAX(seq), 0) + 1 FROM scores WHERE tournament_id = ?1), ?5) " +
    "ON CONFLICT(tournament_id, match_key) DO UPDATE SET games = excluded.games, entered_by = excluded.entered_by, " +
    "seq = (SELECT COALESCE(MAX(seq), 0) + 1 FROM scores WHERE tournament_id = ?1), updated = excluded.updated")
    .bind(id, b.key, JSON.stringify(games), me.email, nowS()).run();
  const s = await env.DB.prepare("SELECT seq FROM scores WHERE tournament_id = ? AND match_key = ?").bind(id, b.key).first();
  await audit(env, id, me.email, "score", b.key, { court: match.court, a: match.a, b: match.b,
    old: old ? JSON.parse(old.games) : [], new: games });
  return json({ ok: true, seq: s.seq });
}

// ------------------------------------------------------------------ photo receipts

const MAX_PHOTO = 4 * 1024 * 1024;

/** A court captain's photo of the paper score sheet (with initials) for one match. Body: the JPEG itself. */
async function postReceipt(req, env, me, id, url) {
  const key = url.searchParams.get("key") || "";
  const row = await env.DB.prepare("SELECT data FROM live WHERE tournament_id = ?").bind(id).first();
  if (!row) throw new Http(400, "The director hasn't published the pools yet.");
  const d = JSON.parse(row.data);
  const match = d.matches.find(x => x.key === key);
  if (!match) throw new Http(400, "That match isn't in the current pools.");
  const a = await access(env, me, id);
  if (a.kind !== "all" && !(a.courts || []).includes(String(match.court)))
    throw new Http(403, "That match is on court " + match.court + ", not one of yours.");
  if (!/^image\/jpeg/.test(req.headers.get("Content-Type") || "")) throw new Http(400, "Send the photo as a JPEG.");
  const bytes = await req.arrayBuffer();
  if (bytes.byteLength < 100 || bytes.byteLength > MAX_PHOTO) throw new Http(400, "The photo must be under 4 MB.");
  const u = new Uint8Array(bytes);
  if (u[0] !== 0xff || u[1] !== 0xd8) throw new Http(400, "That isn't a JPEG photo.");
  const rid = randomToken(12), token = randomToken(24);
  await putImage(env, id, rid, bytes);
  await env.DB.prepare("INSERT INTO receipts (id, tournament_id, match_key, entered_by, created, size, token) VALUES (?, ?, ?, ?, ?, ?, ?)")
    .bind(rid, id, key, me.email, nowS(), bytes.byteLength, token).run();
  await audit(env, id, me.email, "receipt", key, { receipt: rid, court: match.court, a: match.a, b: match.b });
  // Email the four players their receipt: the score and a link to the photo
  const sc = await env.DB.prepare("SELECT games FROM scores WHERE tournament_id = ? AND match_key = ?").bind(id, key).first();
  const games = sc ? JSON.parse(sc.games) : [];
  const to = playerEmails(d, [match.aId, match.bId]);
  const base = publicBase(env, url);
  const sent = await mailEach(env, id, me.email, to, `${d.name || "Tournament"}: ${match.a} vs ${match.b}`,
    `Score sheet for your match on court ${match.court} (${match.pool}, round ${match.round}):\n\n` +
    `${match.a}\n  vs\n${match.b}\n\n` + (games.length ? "Score: " + games.map(g => g.join("-")).join(", ") + "\n\n" : "") +
    `Photo of the score sheet, with the court captain's initials:\n${base}/r/${token}\n\n` +
    `Live scores and standings: ${base}/t/${id}\n\nQuestions about a score? Talk to the tournament director.`, key, d);
  return json({ ok: true, id: rid, emailed: sent }, 201);
}

/** Emails of the players on these teams (data.who, from the director's phone). */
function playerEmails(d, teamIds) {
  const out = new Set();
  for (const t of teamIds) for (const e of ((d.who || {})[t] || {}).email || []) if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(e)) out.add(String(e).toLowerCase());
  return [...out];
}

function publicBase(env, url) { return (env.PUBLIC_URL || url.origin).replace(/\/+$/, ""); }

/**
 * Sends one email to each address (each player gets their own, so addresses aren't shared). Every send is in the
 * audit log. Without RESEND_API_KEY (local testing) nothing is sent but the log still records it.
 */
async function mailEach(env, tid, by, to, subject, text, key = "", d = null) {
  let sent = 0;
  const base = text, notes = {};
  for (const addr of to) {
    const note = numberNote(d, addr);
    if (note) notes[addr] = note.trim();
    text = base + note;
    let status = "dev";
    if (env.RESEND_API_KEY) {
      const r = await fetch("https://api.resend.com/emails", {
        method: "POST",
        headers: { Authorization: "Bearer " + env.RESEND_API_KEY, "Content-Type": "application/json" },
        body: JSON.stringify({ from: env.MAIL_FROM, to: [addr], subject, text }),
      });
      status = r.ok ? "sent" : "failed " + r.status;
    }
    if (status !== "dev" && status !== "sent") continue;
    sent++;
  }
  if (to.length) await audit(env, tid, by, "email", key, { to, subject, text: base.slice(0, 2000), notes, sent });
  return sent;
}

/** Players who share a full name with another player are numbered ("Pat L. (2)"); their emails say so. */
function numberNote(d, addr) {
  for (const w of Object.values((d && d.who) || {}))
    for (const p of w.players || [])
      if (p.number > 0 && p.email === addr)
        return `\n\nNote: another player in this tournament has the same name as you, so you're listed as "${p.name}".`;
  return "";
}

// ------------------------------------------------------------------ public: live page, receipt links, results

/** What anyone with the link may see: names, pools, standings and scores. No emails or DUPR IDs. */
async function publicData(env, id) {
  const t = await env.DB.prepare("SELECT name FROM tournaments WHERE id = ?").bind(id).first();
  if (!t) throw new Http(404, "No such tournament.");
  const row = await env.DB.prepare("SELECT data, updated FROM live WHERE tournament_id = ?").bind(id).first();
  const d = row ? JSON.parse(row.data) : null;
  const sc = (await env.DB.prepare("SELECT match_key AS key, games FROM scores WHERE tournament_id = ?").bind(id).all()).results;
  const scores = {};
  for (const x of sc) scores[x.key] = JSON.parse(x.games);
  return {
    name: (d && d.name) || t.name, updated: row ? row.updated : 0, scores,
    standings: (d && d.standings) || [], finalists: (d && d.finalists) || [],
    matches: d ? d.matches.map(({ key, court, round, stage, pool, a, b }) => ({ key, court, round, stage, pool, a, b })) : [],
  };
}

async function publicLive(env, id) {
  const r = json(await publicData(env, id));
  r.headers.set("Access-Control-Allow-Origin", "*");
  return r;
}

async function receiptByToken(env, token) {
  const r = await env.DB.prepare("SELECT id, tournament_id FROM receipts WHERE token = ?").bind(token).first();
  if (!r) throw new Http(404, "No such photo.");
  const img = await getImage(env, r.tournament_id, r.id);
  return new Response(img, { headers: { "Content-Type": "image/jpeg", "Cache-Control": "private, max-age=86400" } });
}

/** Emails every player the final (or current) standings. ?dry=1 only counts the recipients. */
async function resultsEmail(env, me, id, url) {
  const row = await env.DB.prepare("SELECT data FROM live WHERE tournament_id = ?").bind(id).first();
  if (!row) throw new Http(400, "The pools aren't online yet.");
  const d = JSON.parse(row.data);
  const to = playerEmails(d, Object.keys(d.who || {}));
  if (url.searchParams.get("dry") === "1") return json({ recipients: to.length });
  let text = `Results: ${d.name || "Tournament"}\n`;
  for (const s of d.standings || []) {
    text += `\n${s.stage} · ${s.title}\n`;
    for (const r of s.rows || []) text += `${r.place}. ${r.label}  ${r.w}-${r.l}  ${r.diff > 0 ? "+" : ""}${r.diff}\n`;
  }
  if ((d.finalists || []).length) text += `\nQualified for the Finals:\n` + d.finalists.map(f => "- " + f).join("\n") + "\n";
  text += `\nAll scores: ${publicBase(env, url)}/t/${id}\n\nThank you for playing!`;
  const sent = await mailEach(env, id, me.email, to, `${d.name || "Tournament"}: results`, text, "", d);
  return json({ ok: true, recipients: to.length, sent });
}

/** The live scores page: plain HTML that refreshes itself every 20 seconds. */
async function livePage(env, id) {
  const t = await env.DB.prepare("SELECT name FROM tournaments WHERE id = ?").bind(id).first();
  if (!t) return new Response("No such tournament.", { status: 404 });
  const html = LIVE_HTML.replace("__ID__", id).replace("__NAME__", escapeHtml(t.name));
  return new Response(html, { headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" } });
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

const DELETE_HTML = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>Delete your account</title>
<style>:root{--bg:#fff;--fg:#111;--muted:#555;--accent:#1b5e20;--bad:#b71c1c}
@media (prefers-color-scheme:dark){:root{--bg:#121412;--fg:#eee;--muted:#aaa;--accent:#81c784;--bad:#ef9a9a}}
body{margin:0;background:var(--bg);color:var(--fg);font:19px/1.5 system-ui,sans-serif}main{max-width:620px;margin:0 auto;padding:16px}
h1{color:var(--accent);font-size:26px}input,button{font:inherit;padding:10px 12px;margin:6px 0;width:100%;box-sizing:border-box}
button{background:var(--accent);color:var(--bg);border:0;border-radius:8px}button.bad{background:var(--bad)}.muted{color:var(--muted)}
[hidden]{display:none}</style></head><body><main>
<h1>Delete your Pickleball Pools account</h1>
<p>This is for tournament staff (directors, court captains, admins). Players have no account.</p>
<p>Deleting removes your name, phone, roles and court captain places, and signs you out everywhere. Scores you entered and
the tournament history stay with the tournament's record. You can also do this in the app: Account &rarr; Delete my account.</p>
<div id="s1"><label>Your email<input id="em" type="email" autocomplete="email"></label><button id="b1">Email me a code</button></div>
<div id="s2" hidden><label>6-digit code from the email<input id="cd" inputmode="numeric" maxlength="6"></label>
<button id="b2" class="bad">Delete my account</button></div>
<p id="msg" class="muted"></p>
<script>
const $=id=>document.getElementById(id),say=t=>$("msg").textContent=t;
async function post(u,b,tok){const r=await fetch(u,{method:"POST",headers:Object.assign({"Content-Type":"application/json"},tok?{Authorization:"Bearer "+tok}:{}),body:JSON.stringify(b||{})});
const d=await r.json().catch(()=>({}));if(!r.ok)throw new Error(d.error||"Something went wrong.");return d;}
$("b1").onclick=async()=>{try{await post("/auth/email/start",{email:$("em").value.trim()});$("s2").hidden=false;say("Check your email (and the junk folder) for the code.");}catch(e){say(e.message)}};
$("b2").onclick=async()=>{if(!confirm("Delete your account for good?"))return;try{const v=await post("/auth/email/verify",{email:$("em").value.trim(),code:$("cd").value.trim()});
await post("/me/delete",{},v.token);$("s1").hidden=true;$("s2").hidden=true;say("Your account is deleted.");}catch(e){say(e.message)}};
</script></main></body></html>`;

const RESULTS_HTML = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>__TITLE__</title>
<style>
:root{--bg:#fff;--fg:#111;--muted:#555;--card:#f4f6f4;--accent:#1b5e20;--done:#e8f5e9;--todo:#fff8e1}
@media (prefers-color-scheme:dark){:root{--bg:#121412;--fg:#eee;--muted:#aaa;--card:#1e221e;--accent:#81c784;--done:#1f3321;--todo:#33301f}}
body{margin:0;background:var(--bg);color:var(--fg);font:18px/1.4 system-ui,sans-serif}a{color:var(--accent)}
main{max-width:760px;margin:0 auto;padding:16px}h1{color:var(--accent);font-size:26px;margin:0 0 4px}
h2{font-size:21px;margin:24px 0 8px}.muted{color:var(--muted);font-size:15px}
table{width:100%;border-collapse:collapse;background:var(--card);border-radius:10px;overflow:hidden}
td{padding:8px 10px;border-bottom:1px solid rgba(128,128,128,.2)}td.n{text-align:right;white-space:nowrap}
.m{background:var(--todo);border-radius:10px;padding:10px 12px;margin:6px 0}.m.done{background:var(--done)}
.fin{font-weight:600;color:var(--accent)}a.t{display:block;background:var(--card);border-radius:10px;padding:12px 14px;margin:8px 0;color:var(--fg);text-decoration:none}
</style></head><body><main>__BODY__</main></body></html>`;

const LIVE_HTML = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>__NAME__ – live scores</title>
<style>
:root{--bg:#fff;--fg:#111;--muted:#555;--card:#f4f6f4;--accent:#1b5e20;--done:#e8f5e9;--todo:#fff8e1}
@media (prefers-color-scheme:dark){:root{--bg:#121412;--fg:#eee;--muted:#aaa;--card:#1e221e;--accent:#81c784;--done:#1f3321;--todo:#33301f}}
body{margin:0;background:var(--bg);color:var(--fg);font:18px/1.4 system-ui,sans-serif}
main{max-width:760px;margin:0 auto;padding:16px}h1{color:var(--accent);font-size:26px;margin:0 0 4px}
h2{font-size:21px;margin:24px 0 8px}.muted{color:var(--muted);font-size:15px}
table{width:100%;border-collapse:collapse;background:var(--card);border-radius:10px;overflow:hidden}
td{padding:8px 10px;border-bottom:1px solid rgba(128,128,128,.2)}td.n{text-align:right;white-space:nowrap}
.m{background:var(--todo);border-radius:10px;padding:10px 12px;margin:6px 0}.m.done{background:var(--done)}
.fin{font-weight:600;color:var(--accent)}
</style></head><body><main><h1>__NAME__</h1><div class="muted" id="u">Loading…</div><div id="c"></div></main>
<script>
const id="__ID__",e=s=>String(s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
async function load(){try{const r=await fetch("/public/"+id,{cache:"no-store"});const d=await r.json();let h="";
if(d.finalists.length)h+="<h2>Qualified for the Finals</h2>"+d.finalists.map(f=>'<div class="fin">🏆 '+e(f)+"</div>").join("");
for(const s of d.standings){h+="<h2>"+e(s.stage+" · "+s.title)+"</h2><table>"+s.rows.map(r=>"<tr><td>"+e(r.place)+". "+e(r.label)+
'</td><td class="n">'+r.w+"-"+r.l+'</td><td class="n">'+(r.diff>0?"+":"")+r.diff+"</td></tr>").join("")+"</table>";
for(const m of d.matches.filter(m=>m.stage===s.stage&&m.pool===s.title)){const g=d.scores[m.key]||[];
h+='<div class="m'+(g.length?" done":"")+'"><span class="muted">Round '+m.round+" · court "+e(m.court)+"</span><br>"+e(m.a)+"<br>vs "+e(m.b)+
"<br><b>"+(g.length?g.map(x=>x.join("-")).join(", "):'<span class="muted">not played yet</span>')+"</b></div>";}}
if(!d.standings.length)h='<p class="muted">The pools aren\'t posted yet.</p>';
document.getElementById("c").innerHTML=h;document.getElementById("u").textContent="Live · updated "+new Date().toLocaleTimeString([], {hour:"numeric",minute:"2-digit"});
}catch(x){document.getElementById("u").textContent="Can't reach the server; trying again…";}}
load();setInterval(load,20000);
</script></body></html>`;

async function getReceipt(env, me, id, rid) {
  const r = await env.DB.prepare("SELECT match_key FROM receipts WHERE id = ? AND tournament_id = ?").bind(rid, id).first();
  if (!r) throw new Http(404, "No such receipt.");
  const row = await env.DB.prepare("SELECT data FROM live WHERE tournament_id = ?").bind(id).first();
  const d = row ? JSON.parse(row.data) : { matches: [] };
  const a = await access(env, me, id);
  const match = d.matches.find(x => x.key === r.match_key);
  if (a.kind !== "all" && !(match && canSeeMatch(a, match))) throw new Http(403, "You can only see receipts of your own courts.");
  const img = await getImage(env, id, rid);
  return new Response(img, { headers: { "Content-Type": "image/jpeg", "Cache-Control": "private, max-age=86400" } });
}

/** Photo storage: an R2 bucket if one is bound (RECEIPTS), otherwise the database. */
async function putImage(env, tid, rid, bytes) {
  if (env.RECEIPTS) await env.RECEIPTS.put(`${tid}/${rid}.jpg`, bytes, { httpMetadata: { contentType: "image/jpeg" } });
  else await env.DB.prepare("INSERT INTO receipt_images (id, data) VALUES (?, ?)").bind(rid, bytes).run();
}

async function getImage(env, tid, rid) {
  if (env.RECEIPTS) {
    const obj = await env.RECEIPTS.get(`${tid}/${rid}.jpg`);
    if (obj) return obj.body;
  }
  const row = await env.DB.prepare("SELECT data FROM receipt_images WHERE id = ?").bind(rid).first();
  if (!row) throw new Http(404, "Photo missing.");
  return new Uint8Array(row.data);
}

// ------------------------------------------------------------------ account deletion (Google Play policy)
// Deletes the person's account: profile, roles, tournament staff places, sessions and sign-in codes. Kept: scores they
// entered and the tournament history (who changed what), which belong to the tournament's record.
async function deleteMe(env, me) {
  const email = me.email;
  await env.DB.batch([
    env.DB.prepare("DELETE FROM sessions WHERE user_id = ?").bind(me.id),
    env.DB.prepare("DELETE FROM handoffs WHERE user_id = ?").bind(me.id),
    env.DB.prepare("DELETE FROM roles WHERE email = ?").bind(email),
    env.DB.prepare("DELETE FROM staff WHERE email = ?").bind(email),
    env.DB.prepare("DELETE FROM email_codes WHERE email = ?").bind(email),
    env.DB.prepare("DELETE FROM users WHERE id = ?").bind(me.id),
  ]);
  await audit(env, "", "(deleted account)", "account", "", { change: "deleted" });
  return json({ ok: true });
}

// ------------------------------------------------------------------ published results (public archive)
// Only these fields are kept, whatever the phone sends, so nothing private (emails, DUPR IDs, team ids) is published.
const str = (v, n = 120) => String(v == null ? "" : v).slice(0, n);
function cleanResults(r) {
  const games = g => (Array.isArray(g) ? g : []).slice(0, 5).map(x => [Math.max(0, Math.min(99, +x[0] || 0)), Math.max(0, Math.min(99, +x[1] || 0))]);
  const scores = {};
  for (const [k, g] of Object.entries(r.scores || {}).slice(0, 2000)) if (/^[\w:+.-]{1,200}$/.test(k)) scores[k] = games(g);
  return {
    name: str(r.name, 100) || "Tournament", day: str(r.day, 40), venue: str(r.venue, 80), singles: !!r.singles,
    pointsToWin: +r.pointsToWin || 11, winBy: +r.winBy || 2,
    finalists: (r.finalists || []).slice(0, 50).map(x => str(x)),
    standings: (r.standings || []).slice(0, 60).map(s => ({ stage: str(s.stage, 30), title: str(s.title, 60),
      rows: (s.rows || []).slice(0, 40).map(x => ({ place: str(x.place, 4), label: str(x.label), w: +x.w || 0, l: +x.l || 0, diff: +x.diff || 0 })) })),
    matches: (r.matches || []).slice(0, 2000).map(x => ({ key: str(x.key, 200), court: str(x.court, 10), round: +x.round || 1,
      stage: str(x.stage, 30), pool: str(x.pool, 60), a: str(x.a), b: str(x.b) })),
    scores,
  };
}
async function publishResults(req, env, me) {
  const b = await body(req);
  const id = /^[\w-]{8,64}$/.test(b.id || "") ? b.id : crypto.randomUUID();
  const d = cleanResults(b.results || {});
  if (!d.standings.length) throw new Http(400, "There are no pools to publish yet.");
  await env.DB.prepare("INSERT INTO results (id, name, day, venue, singles, data, published, published_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
    "ON CONFLICT(id) DO UPDATE SET name = excluded.name, day = excluded.day, venue = excluded.venue, singles = excluded.singles, " +
    "data = excluded.data, published = excluded.published, published_by = excluded.published_by")
    .bind(id, d.name, d.day, d.venue, d.singles ? 1 : 0, JSON.stringify(d), nowS(), me.email).run();
  await audit(env, "", me.email, "results", "", { id, name: d.name });
  return json({ id, url: "/results/" + id });
}
async function removeResults(req, env, me) {
  const id = String((await body(req)).id || "");
  const r = await env.DB.prepare("SELECT name FROM results WHERE id = ?").bind(id).first();
  if (!r) throw new Http(404, "Those results aren't published.");
  await env.DB.prepare("DELETE FROM results WHERE id = ?").bind(id).run();
  await audit(env, "", me.email, "results", "", { id, name: r.name, change: "removed" });
  return json({ ok: true });
}
const page = (title, body) => new Response(RESULTS_HTML.replace(/__TITLE__/g, escapeHtml(title)).replace("__BODY__", body),
  { headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "public, max-age=60" } });
const cors = r => { r.headers.set("Access-Control-Allow-Origin", "*"); return r; };
async function resultsIndex(env, asJson) {
  const rows = (await env.DB.prepare("SELECT id, name, day, venue, singles, published FROM results ORDER BY published DESC").all()).results;
  if (asJson) return cors(json({ results: rows.map(r => ({ ...r, singles: !!r.singles, url: "/results/" + r.id })) }));
  const list = rows.map(r => `<a class="t" href="/results/${escapeHtml(r.id)}"><b>${escapeHtml(r.name)}</b><br><span class="muted">` +
    escapeHtml([r.day, r.venue, r.singles ? "Singles" : "Doubles"].filter(Boolean).join(" · ")) + "</span></a>").join("");
  return page("Past tournaments", `<h1>Past tournaments</h1>${list || '<p class="muted">Nothing published yet.</p>'}`);
}
async function resultsPage(env, id, asJson) {
  const r = await env.DB.prepare("SELECT data FROM results WHERE id = ?").bind(id).first();
  if (!r) return asJson ? cors(json({ error: "Not published" }, 404)) : new Response("No such results.", { status: 404 });
  const d = JSON.parse(r.data);
  if (asJson) return cors(json(d));
  let h = `<p class="muted"><a href="/results">&larr; Past tournaments</a></p><h1>${escapeHtml(d.name)}</h1><p class="muted">` +
    escapeHtml([d.day, d.venue, d.singles ? "Singles" : "Doubles", `games to ${d.pointsToWin}, win by ${d.winBy}`].filter(Boolean).join(" · ")) + "</p>";
  if (d.finalists.length) h += "<h2>Qualified for the Finals</h2>" + d.finalists.map(f => `<div class="fin">🏆 ${escapeHtml(f)}</div>`).join("");
  for (const s of d.standings) {
    h += `<h2>${escapeHtml(s.stage + " · " + s.title)}</h2><table>` + s.rows.map(x => `<tr><td>${escapeHtml(x.place)}. ${escapeHtml(x.label)}</td>` +
      `<td class="n">${x.w}-${x.l}</td><td class="n">${x.diff > 0 ? "+" : ""}${x.diff}</td></tr>`).join("") + "</table>";
    for (const m of d.matches.filter(m => m.stage === s.stage && m.pool === s.title)) {
      const g = d.scores[m.key] || [];
      h += `<div class="m${g.length ? " done" : ""}"><span class="muted">Round ${m.round} · court ${escapeHtml(m.court)}</span><br>` +
        `${escapeHtml(m.a)}<br>vs ${escapeHtml(m.b)}<br><b>${g.length ? g.map(x => x.join("-")).join(", ") : '<span class="muted">not played</span>'}</b></div>`;
    }
  }
  h += `<p class="muted"><a href="/results/${escapeHtml(id)}.json">Download these results (JSON)</a></p>`;
  return page(d.name, h);
}

// ------------------------------------------------------------------ venues
// Courts are a list like "1-8, 10, 12" (numbers 1-99). Stored as typed, normalized.
function courtList(text) {
  const out = [];
  for (const part of String(text || "").split(/[,\s]+/).filter(Boolean)) {
    const m = part.match(/^(\d{1,2})(?:-(\d{1,2}))?$/);
    if (!m) throw new Http(400, "Courts: use numbers and ranges, like 1-8, 10");
    const a = +m[1], b = m[2] ? +m[2] : a;
    if (a < 1 || b < a) throw new Http(400, "Courts: use numbers and ranges, like 1-8, 10");
    for (let c = a; c <= b; c++) if (!out.includes(c)) out.push(c);
  }
  if (!out.length) throw new Http(400, "Give the venue at least one court.");
  return out;
}
async function getVenues(env) {
  const rows = (await env.DB.prepare("SELECT id, name, address, courts FROM venues ORDER BY name").all()).results;
  return json({ venues: rows.map(r => ({ ...r, courts: r.courts.split(",").filter(Boolean).map(Number) })) });
}
async function putVenue(req, env, me) {
  const v = (await body(req)).venue || {};
  const name = String(v.name || "").trim().slice(0, 80);
  if (!name) throw new Http(400, "Give the venue a name.");
  const courts = courtList(Array.isArray(v.courts) ? v.courts.join(",") : v.courts);
  const id = /^[\w-]{1,40}$/.test(v.id || "") ? v.id : crypto.randomUUID();
  await env.DB.prepare("INSERT INTO venues (id, name, address, courts, updated, updated_by) VALUES (?, ?, ?, ?, ?, ?) " +
    "ON CONFLICT(id) DO UPDATE SET name = excluded.name, address = excluded.address, courts = excluded.courts, updated = excluded.updated, updated_by = excluded.updated_by")
    .bind(id, name, String(v.address || "").trim().slice(0, 200), courts.join(","), nowS(), me.email).run();
  await audit(env, "", me.email, "venue", "", { name, courts: courts.length });
  return getVenues(env);
}
async function removeVenue(req, env, me) {
  const id = String((await body(req)).id || "");
  const r = await env.DB.prepare("SELECT name FROM venues WHERE id = ?").bind(id).first();
  if (!r) throw new Http(404, "No such venue.");
  await env.DB.prepare("DELETE FROM venues WHERE id = ?").bind(id).run();
  await audit(env, "", me.email, "venue", "", { name: r.name, change: "removed" });
  return getVenues(env);
}

// ------------------------------------------------------------------ Zeffy (registration)
// The club's Zeffy campaigns, so a director can make a tournament from one. The API key (ZEFFY_API_KEY secret)
// never leaves the server. Only ticketing/event campaigns that aren't archived are listed, newest date first.
async function zeffyCampaigns(env) {
  if (!env.ZEFFY_API_KEY) throw new Http(501, "Zeffy isn't connected on this server yet (ZEFFY_API_KEY).");
  const base = env.ZEFFY_API_URL || "https://api.zeffy.com/api/v1";
  const out = [];
  let cursor = "";
  for (let page = 0; page < 10; page++) {
    const r = await fetch(base + "/campaigns?limit=100" + (cursor ? "&starting_after=" + encodeURIComponent(cursor) : ""),
      { headers: { Authorization: "Bearer " + env.ZEFFY_API_KEY } });
    if (!r.ok) { console.error("Zeffy", r.status, await r.text()); throw new Http(502, "Zeffy didn't answer (" + r.status + ")."); }
    const d = await r.json();
    for (const c of d.data || []) {
      if (c.is_archived || c.deleted_at) continue;
      if (c.type && !["ticketing", "event"].includes(c.type)) continue;
      out.push({ id: c.id, title: c.title || "", status: c.status || "", start: c.start_date || 0, end: c.end_date || 0, url: c.url || "" });
    }
    if (!d.has_more || !d.next_cursor) break;
    cursor = d.next_cursor;
  }
  out.sort((a, b) => b.start - a.start);
  return json({ campaigns: out });
}

// ------------------------------------------------------------------ club settings and rosters

const CLUB_KEYS = ["name", "contactEmail", "paymentLink", "poolCount", "advancePerPool", "finalistsPerPool", "pointsToWin", "winBy", "firstCourt"];

async function getClub(env) {
  const rows = (await env.DB.prepare("SELECT key, value FROM club").all()).results;
  const club = {};
  for (const r of rows) if (CLUB_KEYS.includes(r.key)) club[r.key] = r.value;
  return json({ club });
}

async function putClub(req, env, me) {
  const b = (await body(req)).club || {};
  for (const k of Object.keys(b)) {
    if (!CLUB_KEYS.includes(k)) throw new Http(400, "Unknown setting: " + k);
    const v = String(b[k] ?? "").trim().slice(0, 300);
    if (["poolCount", "advancePerPool", "finalistsPerPool", "pointsToWin", "winBy", "firstCourt"].includes(k) && v !== "" && !/^\d{1,2}$/.test(v))
      throw new Http(400, k + " must be a whole number.");
    if (v === "") await env.DB.prepare("DELETE FROM club WHERE key = ?").bind(k).run();
    else await env.DB.prepare("INSERT INTO club (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").bind(k, v).run();
  }
  await audit(env, "", me.email, "club", "", { changed: Object.keys(b) });
  return getClub(env);
}

async function getRoster(env, name) {
  const r = await env.DB.prepare("SELECT players, updated, updated_by FROM rosters WHERE name = ?").bind(name).first();
  return json({ name, players: r ? r.players.split("\n") : [], updated: r ? r.updated : 0, by: r ? r.updated_by : "" });
}

async function putRoster(req, env, me, name) {
  const players = (await body(req)).players;
  if (!Array.isArray(players) || players.length > 400) throw new Http(400, "Expected a list of players.");
  const text = players.map(x => String(x).replace(/[\r\n]/g, " ").trim().slice(0, 80)).filter(Boolean).join("\n");
  await env.DB.prepare("INSERT INTO rosters (name, players, updated, updated_by) VALUES (?, ?, ?, ?) " +
    "ON CONFLICT(name) DO UPDATE SET players = excluded.players, updated = excluded.updated, updated_by = excluded.updated_by")
    .bind(name, text, nowS(), me.email).run();
  await audit(env, "", me.email, "roster", "", { name, players: text.split("\n").length });
  return getRoster(env, name);
}

// ------------------------------------------------------------------ audit

async function audit(env, tid, email, action, key, detail) {
  await env.DB.prepare("INSERT INTO audit (tournament_id, at, email, action, match_key, detail) VALUES (?, ?, ?, ?, ?, ?)")
    .bind(tid, nowS(), email, action, key || "", JSON.stringify(detail || {})).run();
}

/** Newest first, 200 at a time; ?before=<id> for older entries. tid '' = organization (roles), admins only. */
async function getAudit(env, tid, url) {
  const before = parseInt(url.searchParams.get("before") || "0", 10) || 2147483647;
  const r = await env.DB.prepare("SELECT id, at, email, action, match_key AS key, detail FROM audit " +
    "WHERE tournament_id = ? AND id < ? ORDER BY id DESC LIMIT 200").bind(tid, before).all();
  return json({ entries: r.results.map(x => ({ ...x, detail: JSON.parse(x.detail || "{}") })) });
}

/** Things only the director's phone knows about: started, unlocked. */
async function postEvent(req, env, me, id) {
  const action = String((await body(req)).action || "");
  if (!["started", "unlocked"].includes(action)) throw new Http(400, "Unknown event.");
  await audit(env, id, me.email, action, "", {});
  return json({ ok: true });
}

/** Same rules as the app (core/Rules.java): best 2 of 3, each game to `to`, win by `by` (USA Pickleball 15.C). */
function checkGames(games, to, by) {
  if (!Array.isArray(games) || games.length > 3) throw new Http(400, "Enter up to 3 games.");
  let wa = 0, wb = 0;
  for (const g of games) {
    if (!Array.isArray(g) || g.length !== 2 || !g.every(n => Number.isInteger(n) && n >= 0 && n <= 99))
      throw new Http(400, "Scores must be whole numbers.");
    if (wa === 2 || wb === 2) throw new Http(400, "The match was already over after 2 wins.");
    const [x, y] = g, hi = Math.max(x, y), lo = Math.min(x, y);
    if (hi < to || hi - lo < by || (hi > to && hi - lo !== by))
      throw new Http(400, `${x}-${y} isn't a finished game (to ${to}, win by ${by}).`);
    if (x > y) wa++; else wb++;
  }
  if (games.length > 0 && wa < 2 && wb < 2) throw new Http(400, "Enter games until one team has won 2.");
  return games;
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
