-- Pickleball Pools accounts server (Cloudflare D1 / SQLite). Apply with: npx wrangler d1 execute pp --file schema.sql
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,          -- lower case; the identity roles are given to
  name TEXT NOT NULL DEFAULT '',
  google_sub TEXT UNIQUE,              -- Google account id, once they've used Google sign-in
  dupr_id TEXT NOT NULL DEFAULT '',
  phone TEXT NOT NULL DEFAULT '',
  marketing INTEGER NOT NULL DEFAULT 0, -- 1 = agreed to marketing email (separate from tournament messages)
  created INTEGER NOT NULL,
  last_login INTEGER
);
-- Organization-wide roles, given by email so they work before the person first signs in. Everyone is a player.
CREATE TABLE IF NOT EXISTS roles (
  email TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('admin', 'director', 'treasurer')),
  granted_by TEXT NOT NULL,
  created INTEGER NOT NULL,
  PRIMARY KEY (email, role)
);
CREATE TABLE IF NOT EXISTS tournaments (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  created_by TEXT NOT NULL,            -- email
  created INTEGER NOT NULL
);
-- Per-tournament jobs: the tournament's directors and its court captains (with their courts, e.g. "7,8").
CREATE TABLE IF NOT EXISTS staff (
  tournament_id TEXT NOT NULL REFERENCES tournaments(id) ON DELETE CASCADE,
  email TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('director', 'captain')),
  courts TEXT NOT NULL DEFAULT '',
  granted_by TEXT NOT NULL,
  created INTEGER NOT NULL,
  PRIMARY KEY (tournament_id, email, role)
);
CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY,         -- SHA-256 of the token; the token itself is only on the phone
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created INTEGER NOT NULL,
  expires INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS email_codes (
  email TEXT PRIMARY KEY,
  code_hash TEXT NOT NULL,
  expires INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  sent INTEGER NOT NULL DEFAULT 0,     -- codes sent in the current hour (rate limit)
  window_start INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS oauth_states (
  state TEXT PRIMARY KEY,
  verifier TEXT NOT NULL,
  created INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS handoffs (    -- one-time code the browser hands back to the app after Google sign-in
  code_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  expires INTEGER NOT NULL
);
