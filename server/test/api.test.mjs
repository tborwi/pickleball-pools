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

// Live scoring
const live = { pointsToWin: 11, winBy: 2, matches: [
  { key: "r1:A:t1:t4", court: 7, round: 1, pool: "Pool A", aId: "t1", bId: "t4", a: "1 Ortiz/Moore", b: "4 Ba/Bruckbauer" },
  { key: "r1:A:t2:t3", court: 9, round: 1, pool: "Pool A", aId: "t2", bId: "t3", a: "2 Demski/Weisbrot", b: "3 Lohman/Zygarlicke" }],
  who: { t1: { email: ["player1@example.com"], dupr: ["AAA111"], players: [{ name: "Pat L. (2)", email: "player1@example.com", number: 2 }] },
         t2: { email: [], dupr: ["BBB222"] } } };
check((await call("POST", `/tournaments/${tid}/scores`, dir, { key: "r1:A:t1:t4", games: [[11, 5], [11, 9]] })).status === 400, "no scores before pools are published");
check((await call("PUT", `/tournaments/${tid}/live`, cap, { data: live })).status === 403, "captain can't publish pools");
check((await call("PUT", `/tournaments/${tid}/live`, dir, { data: live })).data.emailed === 0, "director publishes pools (not started: no email)");
live.startedAt = 1795874400000;
check((await call("PUT", `/tournaments/${tid}/live`, dir, { data: live })).data.emailed === 1, "on Start, players with an email get the live link");
check((await call("PUT", `/tournaments/${tid}/live`, dir, { data: live })).data.emailed === 0, "start email goes out only once");
const st = (await call("GET", `/tournaments/${tid}/audit`, dir)).data.entries.find(e => e.key === "start");
check(st && st.detail.to[0] === "player1@example.com" && st.detail.text.includes(`/t/${tid}`) && st.detail.text.includes("Round 1, court 7: vs 4 Ba/Bruckbauer"),
  "start email has the live link and the team's own schedule");
check((st.detail.notes["player1@example.com"] || "").includes('listed as "Pat L. (2)"'), "a numbered player's email tells them their number");
let lv = await call("GET", `/tournaments/${tid}/live`, cap);
check(lv.data.data.matches.length === 1 && lv.data.data.matches[0].court === 7, "captain of courts 7,8 sees only the court 7 match");
check((await call("POST", `/tournaments/${tid}/scores`, cap, { key: "r1:A:t2:t3", games: [[11, 5], [11, 9]] })).status === 403, "captain can't score court 9");
check((await call("POST", `/tournaments/${tid}/scores`, cap, { key: "r1:A:t1:t4", games: [[11, 10], [11, 9]] })).status === 400, "11-10 refused (win by 2)");
check((await call("POST", `/tournaments/${tid}/scores`, cap, { key: "r1:A:t1:t4", games: [[11, 5]] })).status === 400, "unfinished match refused");
check((await call("POST", `/tournaments/${tid}/scores`, cap, { key: "r1:A:t1:t4", games: [[11, 5], [9, 11], [12, 10]] })).status === 200, "captain enters 11-5, 9-11, 12-10");
lv = await call("GET", `/tournaments/${tid}/live?since=0`, dir);
check(lv.data.scores.length === 1 && lv.data.scores[0].by === "google.user@example.com" && lv.data.scores[0].games[2][0] === 12, "director sees the captain's score and who entered it");
check(lv.data.data.matches.length === 2 && lv.data.courts === null, "director sees every court");
const seq = lv.data.seq;
check((await call("GET", `/tournaments/${tid}/live?since=${seq}`, dir)).data.scores.length === 0, "since=seq returns only newer scores");
check((await call("POST", `/tournaments/${tid}/scores`, dir, { key: "r1:A:t2:t3", games: [[11, 3], [11, 4]] })).status === 200, "director can score any court");
check((await call("GET", `/tournaments/${tid}/live?since=${seq}`, dir)).data.scores.length === 1, "the new score has a higher seq");
check((await call("PUT", `/tournaments/${tid}/live`, dir, { data: live })).status === 200
  && (await call("GET", `/tournaments/${tid}/live`, dir)).data.scores.length === 2, "republishing keeps scores");
check((await call("POST", `/tournaments/${tid}/scores`, dir, { key: "r1:A:t2:t3", games: [] })).status === 200, "a score can be cleared");

// Photo receipts, player view, audit
const jpeg = new Uint8Array(2000); jpeg[0] = 0xff; jpeg[1] = 0xd8; jpeg[2] = 0xff;
async function upload(token, key, bytes, type = "image/jpeg") {
  const r = await fetch(`${BASE}/tournaments/${tid}/receipts?key=${encodeURIComponent(key)}`,
    { method: "POST", headers: { Authorization: "Bearer " + token, "Content-Type": type }, body: bytes });
  return { status: r.status, data: await r.json() };
}
const rc = await upload(cap, "r1:A:t1:t4", jpeg);
check(rc.status === 201, "captain uploads a photo receipt for a court 7 match");
check((await upload(cap, "r1:A:t2:t3", jpeg)).status === 403, "captain can't add a receipt for court 9");
check((await upload(cap, "r1:A:t1:t4", new Uint8Array(2000), "image/jpeg")).status === 400, "non-JPEG refused");
lv = await call("GET", `/tournaments/${tid}/live`, cap);
check(!lv.data.data.who && lv.data.receipts.length === 1, "captain sees the receipt, not players' emails/DUPR IDs");
check(rc.data.emailed === 1, "the match's players with an email get the receipt (1 of them has one)");

// Players have no accounts: public page + emailed links only
const p1 = await emailSignIn("player1@example.com");
check((await call("GET", "/tournaments", p1)).data.tournaments.length === 0, "a player who signs in sees no tournaments");
check((await call("GET", `/tournaments/${tid}/live`, p1)).status === 403, "players can't use the staff live view");
const pub = await call("GET", `/public/${tid}`);
check(pub.status === 200 && pub.data.matches.length === 2 && pub.data.scores["r1:A:t1:t4"]?.length === 3, "public live scores, no sign-in");
check(!JSON.stringify(pub.data).includes("player1@example.com") && !JSON.stringify(pub.data).includes("AAA111") && !("aId" in pub.data.matches[0]),
  "public data has no emails, DUPR IDs or team ids");
const page = await fetch(`${BASE}/t/${tid}`);
check(page.status === 200 && (await page.text()).includes("Autumn Paddle Showdown"), "public live page (HTML)");
check((await call("GET", "/public/nosuchtournament")).status === 404, "unknown tournament link: 404");
let au0 = (await call("GET", `/tournaments/${tid}/audit`, dir)).data.entries;
const mail = au0.find(e => e.action === "email" && e.key === "r1:A:t1:t4");
check(mail && mail.detail.to[0] === "player1@example.com" && mail.detail.text.includes("11-5, 9-11, 12-10"), "receipt email has the score and goes to the player");
const link = mail.detail.text.match(/\/r\/([\w-]+)/)[1];
const img = await fetch(`${BASE}/r/${link}`);
check(img.status === 200 && img.headers.get("content-type") === "image/jpeg" && (await img.arrayBuffer()).byteLength === 2000, "photo opens from the emailed link, no sign-in");
check((await fetch(`${BASE}/r/wrong-token`)).status === 404, "a made-up photo link doesn't work");
check((await fetch(`${BASE}/tournaments/${tid}/receipts/${rc.data.id}`, { headers: { Authorization: "Bearer " + p1 } })).status === 403, "players can't fetch photos by id");
check((await call("POST", `/tournaments/${tid}/results-email?dry=1`, dir)).data.recipients === 1, "results email: count recipients first");
check((await call("POST", `/tournaments/${tid}/results-email`, cap)).status === 403, "captain can't send the results email");
const res = await call("POST", `/tournaments/${tid}/results-email`, dir);
check(res.data.sent === 1, "director emails the results");

check((await call("POST", `/tournaments/${tid}/events`, dir, { action: "started" })).status === 200, "director logs Start");
const au = await call("GET", `/tournaments/${tid}/audit`, dir);
const acts = au.data.entries.map(e => e.action);
const sc1 = au.data.entries.find(e => e.action === "score" && e.key === "r1:A:t2:t3" && e.detail.new.length === 0);
check(["started", "receipt", "score", "publish", "staff"].every(x => acts.includes(x)), "audit has start, receipt, score, publish, staff");
check(sc1 && sc1.detail.old.length === 2 && sc1.email === "dir@example.com", "audit keeps the old score and who changed it");
check((await call("GET", `/tournaments/${tid}/audit`, cap)).status === 403, "captains can't read the audit log");
check((await call("GET", "/admin/audit", admin)).data.entries.some(e => e.action === "role"), "admin sees role changes in the audit log");

const cash = await emailSignIn("cash@example.com");
check((await call("GET", `/tournaments/${tid}/live`, cash)).status === 403, "treasurer isn't staff of this tournament (no live scores)");
me = await call("GET", "/me", cash);
check(me.data.roles.includes("treasurer"), "treasurer role waits for first sign-in");
check((await call("GET", "/tournaments", cash)).data.tournaments.length === 1, "treasurer sees all tournaments");
check((await call("POST", "/tournaments", cash, { name: "Y" })).status === 403, "treasurer can't create a tournament");

const users = await call("GET", "/admin/users", admin);
check(users.data.users.length === 5, "admin lists 5 users");
check((await call("DELETE", "/admin/roles", admin, { email: "admin@example.com", role: "admin" })).status === 400, "can't remove the last admin");
check((await call("POST", `/tournaments/${tid}/staff/remove`, dir, { email: "dir@example.com", role: "director" })).status === 400, "tournament keeps a director");

check((await call("PUT", "/admin/roles", admin, { email: "temp@example.com", role: "treasurer" })).status === 200
  && (await call("POST", "/admin/roles/remove", admin, { email: "temp@example.com", role: "treasurer" })).status === 200
  && (await call("GET", "/admin/users", admin)).data.users.length === 5, "admin removes a role (POST /remove)");

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
