// End-to-end checks of the accounts server. Run against `wrangler dev` (see README): node test/api.test.mjs
const BASE = process.env.BASE || "http://127.0.0.1:8787";
let failures = 0;
const check = (ok, what) => { console.log((ok ? "ok   " : "FAIL ") + what); if (!ok) failures++; };

async function call(method, path, token, body) {
  const r = await fetch(BASE + path, {
    method, redirect: "manual",
    headers: { ...(token ? { Authorization: "Bearer " + token } : {}), ...(body ? { "Content-Type": "application/json" } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  let data = null;
  try { data = await r.json(); } catch {}
  return { status: r.status, data, location: r.headers.get("location") };
}

async function emailSignIn(email) {
  const s = await call("POST", "/auth/email/start", null, { email });
  const v = await call("POST", "/auth/email/verify", null, { email, code: s.data.devCode });
  return v.data.token;
}

async function googleSignIn() {
  const start = await call("GET", "/auth/google/start");
  check(start.status === 302 && start.location.includes("code_challenge_method=S256"), "Google start redirects with PKCE");
  const fake = await fetch(start.location, { redirect: "manual" });
  const cb = await fetch(fake.headers.get("location"), { redirect: "manual" });
  const appLink = cb.headers.get("location") || "";
  check(appLink.startsWith("pickleballpools://signin?code="), "Google callback hands a one-time code to the app");
  const code = new URL(appLink.replace("pickleballpools://", "http://x/")).searchParams.get("code");
  const ex = await call("POST", "/auth/exchange", null, { code });
  const again = await call("POST", "/auth/exchange", null, { code });
  check(again.status === 400, "one-time code can't be used twice");
  return ex.data.token;
}

const admin = await emailSignIn("Admin@Example.com");
let me = await call("GET", "/me", admin);
check(me.data.roles.includes("admin") && me.data.email === "admin@example.com", "first user becomes admin (email lower-cased)");

const dir = await emailSignIn("dir@example.com");
check(!(await call("GET", "/me", dir)).data.roles.includes("admin"), "second user is just a player");
check((await call("POST", "/tournaments", dir, { name: "X" })).status === 403, "player can't create a tournament");
check((await call("PUT", "/admin/roles", dir, { email: "dir@example.com", role: "admin" })).status === 403, "player can't give roles");
check((await call("PUT", "/admin/roles", admin, { email: "dir@example.com", role: "director" })).status === 200, "admin makes a director");
check((await call("PUT", "/admin/roles", admin, { email: "cash@example.com", role: "treasurer" })).status === 200, "admin gives treasurer before first sign-in");
check((await call("PUT", "/admin/roles", admin, { email: "x@example.com", role: "king" })).status === 400, "unknown role refused");

const t = await call("POST", "/tournaments", dir, { name: "Autumn Paddle Showdown" });
check(t.status === 201, "director creates a tournament");
const tid = t.data.id;
check((await call("PUT", `/tournaments/${tid}/staff`, dir, { email: "google.user@example.com", role: "captain", courts: "7, 8" })).status === 200, "director adds a court captain");

const cap = await googleSignIn();
me = await call("GET", "/me", cap);
check(me.data.google && me.data.name === "Google User" && me.data.tournaments[0]?.courts === "7,8", "captain signed in with Google sees courts 7,8");
check((await call("GET", "/tournaments", cap)).data.tournaments.length === 1, "captain sees their tournament");
check((await call("GET", `/tournaments/${tid}/staff`, cap)).status === 200, "captain can see the staff list");
check((await call("PUT", `/tournaments/${tid}/staff`, cap, { email: "a@example.com", role: "captain" })).status === 403, "captain can't add staff");
check((await call("GET", "/admin/users", cap)).status === 403, "captain can't list users");

const cash = await emailSignIn("cash@example.com");
me = await call("GET", "/me", cash);
check(me.data.roles.includes("treasurer"), "treasurer role waits for first sign-in");
check((await call("GET", "/tournaments", cash)).data.tournaments.length === 1, "treasurer sees all tournaments");
check((await call("POST", "/tournaments", cash, { name: "Y" })).status === 403, "treasurer can't create a tournament");

const users = await call("GET", "/admin/users", admin);
check(users.data.users.length === 4, "admin lists 4 users");
check((await call("DELETE", "/admin/roles", admin, { email: "admin@example.com", role: "admin" })).status === 400, "can't remove the last admin");
check((await call("POST", `/tournaments/${tid}/staff/remove`, dir, { email: "dir@example.com", role: "director" })).status === 400, "tournament keeps a director");

check((await call("PUT", "/admin/roles", admin, { email: "temp@example.com", role: "treasurer" })).status === 200
  && (await call("POST", "/admin/roles/remove", admin, { email: "temp@example.com", role: "treasurer" })).status === 200
  && (await call("GET", "/admin/users", admin)).data.users.length === 4, "admin removes a role (POST /remove)");

const p = await call("PATCH", "/me", cap, { duprId: "l8ew8w", phone: "715-555-0100", marketing: true });
check(p.data.duprId === "L8EW8W" && p.data.marketing === true, "player updates DUPR ID and marketing opt-in");

await call("POST", "/auth/email/start", null, { email: "dir@example.com" });
for (let i = 0; i < 5; i++) await call("POST", "/auth/email/verify", null, { email: "dir@example.com", code: "000000" });
check((await call("POST", "/auth/email/verify", null, { email: "dir@example.com", code: "000000" })).status === 429, "5 wrong codes lock the code");
check((await call("POST", "/auth/email/start", null, { email: "not-an-email" })).status === 400, "bad email refused");
check((await call("GET", "/me")).status === 401, "no token -> 401");

check((await call("POST", "/auth/signout", cash)).status === 200, "sign out");
check((await call("GET", "/me", cash)).status === 401, "token no longer works after sign out");

console.log(failures === 0 ? "ALL PASSED" : failures + " FAILED");
process.exit(failures ? 1 : 0);
