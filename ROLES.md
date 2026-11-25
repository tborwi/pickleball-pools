# Accounts and roles

Accounts are **optional**. Without a server the app works fully on one phone, as before. With the accounts server
(`server/`), the people running tournaments sign in with **Google** or a **6-digit code sent by email**, and each has roles.

**Players don't have accounts.** They follow a public **live scores page** (a link or QR code the director shares,
no sign-in) and are **emailed** the photo of each score sheet from their matches and the final results.

| Role | Given by | What they can do |
|---|---|---|
| **Court captain** | A tournament's director, per tournament, with court numbers (e.g. 7, 8) | See that tournament and its staff list; enter scores and **take score sheet photos** for the matches on their courts only |
| **Treasurer** | An admin | See every tournament; registrations and payments (Zeffy/Stripe import) |
| **Tournament director** | An admin | Create tournaments, run them, add or remove court captains and co-directors; see the tournament's **History** (audit log) and every photo; share the live scores link; email the results to players |
| **Admin** | An admin | Everything, plus give and take away roles. There is always at least one admin. |

- Roles are given **by email address**, so they can be set before the person ever signs in. Google sign-in and an
  email code for the same address are the same account.
- **The first admin:** set `ADMIN_EMAIL` on the server. If it is empty, the first person to sign in becomes admin.
- Signing in lasts 90 days on a phone, or until **Sign out**.
- Apple sign-in is not included (it needs a paid Apple Developer membership); people without Google use the email code.

## Audit and receipts

- **History (audit log):** the server records every score entered, changed or cleared (old and new score, who, when),
  every score sheet photo, every time the pools are sent to captains, staff changes, Start and Unlock. Role changes
  are recorded too (admins). Directors see it under **History** on the home screen. Entries are never edited or deleted.
- **Score sheet photos (receipts):** after entering a match, the captain is asked to photograph the paper score sheet
  with their initials. Photos are shrunk to about 1600 px and stored in the server's photo storage (Cloudflare R2).
  Without signal they wait on the phone and send later.
- **Emailed receipts:** when a photo is sent, each player in that match with an email gets their own email with the
  score and a link to the photo. Emails come from the registration (Zeffy/Stripe export) or the team form.
  The photo link is long and random; only people who have the email can open it. Every email is in the History.
- **Who else sees photos:** the tournament's directors and admins, and the captain of that court, in the app.
- **Live scores page** (`/t/<tournament id>`): team names, pools, standings and scores, updated every 20 seconds.
  Players are shown by name only (team name, if any, plus both players). No emails, DUPR IDs, DUPR links or photos. Standings are worked out on the director's phone (same tiebreak rules).
- **Start email:** when the director presses Start, every player with an email gets the live scores link and their
  team's schedule (pool, and for each round the court and opponent). Sent once.
- **Results email:** the director's **Email the results to all players** sends standings, Finals qualifiers and the
  live page link, one email per player.

## Live scoring

1. The director signs in, opens **Account → Put this tournament online**, and adds each court captain's email and courts.
2. The director's phone publishes the pools (teams, matches, courts as on the printed schedule) whenever they change,
   and fetches captains' scores every 20 seconds while the app is open. Standings update by themselves.
3. Captains sign in and tap **Score my courts**. They see only their courts' matches, round by round, and enter
   each game. The server refuses impossible scores (same rules as the app) and records who entered each one.
4. No signal? Scores wait on the phone ("sending…") and go out by themselves when the connection is back.

If a director and a captain both change the same match, the last one saved wins.

- Server checks for all of this: `server/test/run.sh`.
