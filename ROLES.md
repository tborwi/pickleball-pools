# Accounts and roles

Accounts are **optional**. Without a server the app works fully on one phone, as before. With the accounts server
(`server/`), people sign in with **Google** or a **6-digit code sent by email**, and each person has roles.

| Role | Given by | What they can do |
|---|---|---|
| **Player** | Everyone who signs in | Their own details: name, DUPR ID, phone, marketing-email choice |
| **Court captain** | A tournament's director, per tournament, with court numbers (e.g. 7, 8) | See that tournament and its staff list; enter scores for their courts (coming with live scoring) |
| **Treasurer** | An admin | See every tournament; registrations and payments (Zeffy/Stripe import) |
| **Tournament director** | An admin | Create tournaments, run them, add or remove court captains and co-directors |
| **Admin** | An admin | Everything, plus give and take away roles. There is always at least one admin. |

- Roles are given **by email address**, so they can be set before the person ever signs in. Google sign-in and an
  email code for the same address are the same account.
- **The first admin:** set `ADMIN_EMAIL` on the server. If it is empty, the first person to sign in becomes admin.
- Signing in lasts 90 days on a phone, or until **Sign out**.
- Apple sign-in is not included (it needs a paid Apple Developer membership); people without Google use the email code.
- Server checks for all of this: `server/test/run.sh`.
