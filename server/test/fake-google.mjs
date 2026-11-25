// Stand-in for Google's OAuth endpoints during tests: /auth approves at once, /token returns an ID token.
import http from "node:http";
const b64 = o => Buffer.from(JSON.stringify(o)).toString("base64url");
http.createServer((req, res) => {
  const u = new URL(req.url, "http://x");
  if (u.pathname === "/auth") {
    const back = new URL(u.searchParams.get("redirect_uri"));
    back.searchParams.set("code", "fake-code-" + (u.searchParams.get("login_hint") || "google.user@example.com"));
    back.searchParams.set("state", u.searchParams.get("state"));
    res.writeHead(302, { Location: back.toString() }).end();
  } else if (u.pathname === "/token") {
    let body = "";
    req.on("data", c => (body += c)).on("end", () => {
      const p = new URLSearchParams(body);
      const email = p.get("code").replace("fake-code-", "");
      const claims = { iss: "https://accounts.google.com", aud: p.get("client_id"), sub: "g-" + email, email,
        email_verified: true, name: "Google User", exp: Math.floor(Date.now() / 1000) + 600 };
      res.writeHead(200, { "Content-Type": "application/json" })
        .end(JSON.stringify({ id_token: b64({ alg: "none" }) + "." + b64(claims) + ".sig", verifier_seen: !!p.get("code_verifier") }));
    });
  } else res.writeHead(404).end();
}).listen(9999);
