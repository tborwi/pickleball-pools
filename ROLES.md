# Accounts and roles

Accounts are **optional**. Without a server the app works fully on one phone, as before. With the accounts server
(`server/`), people sign in with **Google** or a **6-digit code sent by email**, and each person has roles.

| Role | Given by | What they can do |
|---|---|---|
| **Player** | Everyone who signs in | Their own details: name, DUPR ID, phone, marketing-email choice |
| **Court captain** | A tournament's director, per tournament, with court numbers (e.g. 7, 8) | See that tournament and its staff list; enter scores for the matches on their courts only |
| **Treasurer** | An admin | See every tournament; registrations and payments (Zeffy/Stripe import) |
| **Tournament director** | An admin | Create tournaments, run them, add or remove court captains and co-directors |
| **Admin** | An admin | Everything, plus give and take away roles. There is always at least one admin. |

- Roles are given **by email address**, so they can be set before the person ever signs in. Google sign-in and an
  email code for the same address are the same account.
- **The first admin:** set `ADMIN_EMAIL` on the server. If it is empty, the first person to sign in becomes admin.
- Signing in lasts 90 days on a phone, or until **Sign out**.
- Apple sign-in is not included (it needs a paid Apple Developer membership); people without Google use the email code.

## Live scoring

1. The director signs in, opens **Account → Put this tournament online**, and adds each court captain's email and courts.
2. The director's phone publishes the pools (teams, matches, courts as on the printed schedule) whenever they change,
   and fetches captains' scores every 20 seconds while the app is open. Standings update by themselves.
3. Captains sign in and tap **Score my courts**. They see only their courts' matches, round by round, and enter
   each game. The server refuses impossible scores (same rules as the app) and records who entered each one.
4. No signal? Scores wait on the phone ("sending…") and go out by themselves when the connection is back.

If a director and a captain both change the same match, the last one saved wins.

- Server checks for all of this: `server/test/run.sh`.
