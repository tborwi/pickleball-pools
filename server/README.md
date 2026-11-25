# Accounts server

A small Cloudflare Worker with a D1 (SQLite) database: sign-in with Google or an emailed code, sessions, and roles
(see [../ROLES.md](../ROLES.md)). Plain JavaScript, no runtime dependencies. Free on Cloudflare's free plan for a
club-sized organization.

## Try it locally (Docker, nothing to install)

`test/run.sh` starts it with a local database and a stand-in for Google, and runs every check.

## Put it online (your organization's own Cloudflare account)

1. `npm install`, then `npx wrangler login`.
2. `npx wrangler d1 create pp`, and copy the `database_id` it prints into `wrangler.toml`.
3. `npx wrangler d1 execute pp --remote --file schema.sql`
4. In `wrangler.toml` set `ADMIN_EMAIL` (the first admin) and `ORG_NAME`.
5. **Email codes:** make a free [Resend](https://resend.com) account, verify your domain, then
   `npx wrangler secret put RESEND_API_KEY` and `npx wrangler secret put MAIL_FROM` (e.g. `HOWPA <noreply@yourclub.org>`).
6. **Google sign-in:** in Google Cloud Console create an OAuth client of type *Web application* with the redirect URI
   `https://<your worker address>/auth/google/callback`, then `npx wrangler secret put GOOGLE_CLIENT_ID` and
   `GOOGLE_CLIENT_SECRET`.
7. `npx wrangler deploy`. Give the worker address to your directors; they enter it in the app under Account.

Never put keys in `wrangler.toml` or in git; use `wrangler secret put`.
