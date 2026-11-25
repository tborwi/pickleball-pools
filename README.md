# Pickleball Pools

A free, simple Android app for running a doubles pickleball tournament with pool play, built for volunteer-run
non-profits. It replaces the clipboard-and-calculator work: seeding, pool sheets, standings and tiebreakers.

Originally written for the Heart of Wisconsin Pickleball Association (Plover, WI). Use it, change it, share it (MIT license).

## What it does

0. **Registrations (optional).** Every player registers and pays for themselves on your payment page
   (a Stripe Payment Link, PayPal, Square, Zeffy… anything that can export a spreadsheet), giving **their own DUPR ID**
   and **their partner's DUPR ID**. Load the export into the app and it makes a team when two players name each other
   (Pat lists Sam's ID as partner, and Sam lists Pat's), then sorts everyone into: teams ready, waiting on partner to pay,
   partners don't match, needs a partner, paid twice (refund?), and missing DUPR ID. You look up each player's
   DUPR rating, type it in (the date is saved), and add the ready teams to the tournament.
1. **Teams & seeding.** Enter each team's two players, their DUPR ratings and (optionally) DUPR IDs, or paste a list. A team
   is just its two players: no team names. Internally a team is its DUPR ID pair, which is unique.
   Teams are seeded by **combined DUPR** (5.56 + 5.51 = 11.07).
2. **Round 1 pools.** Teams are snaked into pools by seed: A, B, C, D, then D, C, B, A, and so on.
   So Pool A gets seeds 1, 8, 9, 16 and Pool D gets 4, 5, 12, 13. Each pool plays a round robin.
3. **Start.** When the pools look right, the director presses **Start the tournament**. That locks the teams and
   pools (no more redoing pools or adding teams; teams can still drop out) and opens score entry, including for
   court captains. **Unlock the pools** is there for real mistakes.
4. **Scores.** Each match is best 2 out of 3 games to 11, win by 2 (USA Pickleball). The app refuses impossible scores.
5. **Round 2.** The top 2 of each pool go to the **Championship** bracket (Pools A and B), the rest to the
   **Consolation** bracket (Pools C and D). Teams are re-seeded by Round 1 result (pool finish, then wins,
   point differential, points scored) and snaked again. Another round robin.
6. **Finals.** The top team of each Championship pool is shown as qualified for the Finals.
7. **Share.** One button sends all standings as text (email, text message, Facebook post…).
8. **Print sheets.** For each round, the app makes three Excel files to print the night before (or email / save to
   Drive): the **schedule** (teams and which match is on which court each round), one **court captain sheet** per
   pool (game scores and initials), and one **results grid** per pool (every team against every other, record,
   points, place). Set start times in Settings. Printed again later, they include the scores entered so far.
9. **Venue and courts.** Tap the 📍 line on the home screen to pick where it's played. Admins keep the club's
   venues and their courts (e.g. `1-8, 10`) under Account → **Venues and courts**; "Somewhere else" types the courts
   by hand. Pools take courts in order; if a round needs more courts than the venue has, the app warns and pools share
   courts. Without a venue, courts are numbered from the first court in Settings. The venue locks at **Start**.
10. **Several tournaments, backups.** **All tournaments** keeps any number of tournaments on the phone (switch, delete).
    **Back up this tournament** shares the whole tournament as a `.json` file (private: it has emails and DUPR IDs);
    **Import a backup file** opens it on any phone, for a new phone or another director taking over.
11. **Past tournaments.** **Publish results** puts a public copy on the club server: first names with last initial,
    pools, scores, standings and the Finals, never emails or DUPR IDs. Anyone can see them at `<server>/results`, each
    tournament at `<server>/results/<id>`, also as JSON (`<server>/results/<id>.json`, `<server>/results.json`).
12. **Singles.** A payment file with no "Partner DUPR ID" column makes the tournament singles (one player per entry);
    or tick **Singles** in Settings before adding players.

The user guide for directors and captains: **https://tborwi.github.io/pickleball-pools/** (`docs/index.html`).
Privacy policy: `docs/privacy.html`. Google Play: `play/README.md` (`android/build.sh play` builds the upload bundle).

All of these numbers can be changed in **Settings**: number of pools, teams that advance, finalists per pool,
points per game, and win-by. Data stays on the device; nothing is sent anywhere unless you share it or sign in.

## Accounts (optional)

Tournament staff sign in with Google or an emailed code and get roles: admin, tournament director, court captain and
treasurer. Court captains enter scores and photograph the paper score sheets. Players don't need accounts: they get
a live scores link and are emailed their score sheet photos and the results. It needs your organization's own small server (free on Cloudflare): see [ROLES.md](ROLES.md) and
[server/README.md](server/README.md). Without it, the app works exactly as before.

## When players or teams drop out

Open **Teams** and tap the team.

- **A player can't play but has a substitute:** change the player's name and DUPR. Before play starts, use
  **Redo Round 1 pools** on the home screen so the new combined DUPR is seeded.
- **A team drops out before its round starts:** tap **Withdraw**. The app offers to **redo the pools** right away
  (same seeding and snake order, without that team). Pools can end up one team smaller (a pool of 3 plays 3 matches).
- **A team drops out during pool play:** tap **Withdraw**. Following USA Pickleball rule 15.B.4.a, its match results
  in that pool no longer count for anyone, and it can't advance.
- **A Championship team drops out before Round 2:** withdraw it and use **Redo Round 2 pools**. The next-best team
  from its Round 1 pool moves up into the Championship bracket.
- **Changed your mind?** Tap the team under "Dropped out" and **Bring back**.

Redoing a round's pools erases the scores already entered in that round; the app tells you how many first.
Earlier rounds are never changed. When a registered player is removed (for example after a refund) and their team is
already in the tournament, the app offers to withdraw the team.

## Rules: USA Pickleball

The app follows the **USA Pickleball Official Rulebook** (2026). [RULES.md](RULES.md) lists each rule it applies,
with rule numbers, plus the few choices the rulebook leaves to the tournament.

**Tiebreakers** (rule 15.B.4). Teams are ranked by matches won. Teams with the same number of wins are separated by,
in order:

1. Head to head (match record among the tied teams)
2. Point differential of all games played
3. Head-to-head point differential (games among the tied teams)
4. Point differential against the team(s) with the next-highest number of wins
5. Most points scored

When a tiebreaker separates some of the tied teams, the ones still tied **continue with the next tiebreaker**;
they don't start over. When a pool is finished, the standings show which tiebreaker placed each team. If all five
fail, the teams are shown in red, listed by DUPR, for the tournament director to decide.

**Scoring:** best 2 of 3 games to 11, **win by 2** (rule 15.C). Win by 1 can be set in Settings, but it isn't standard.

## Make it yours (logo and name)

Replace `branding/logo.png` with your organization's logo and edit `branding/branding.properties` (app name, icon
background colour), then rebuild. The logo becomes the app icon and appears at the top of the home screen.
Details: [branding/README.md](branding/README.md).

The default logo belongs to the Heart of Wisconsin Pickleball Association and is not covered by the MIT license.

## Install

Download `PickleballPools.apk` from the Releases page onto an Android phone or tablet (Android 8 or newer) and open it.
Android will ask you to allow installing from your browser or files app.

## Build it yourself

- **Docker, no Android Studio:** `android/build.sh` produces `android/PickleballPools.apk`.
- **Android Studio:** open the `android` folder.
- **Rules tests (just Java 17, no Android):** `./test.sh`

Signing: put `storeFile`, `storePassword`, `keyAlias`, `keyPassword` in `android/keystore.properties` (git-ignored).
Without it, the APK uses a debug key. Use the same key every time, or phones can't update the app.

## Code map (small on purpose)

| File | What |
|---|---|
| `core/Tournament.java` | Seeding, snake order, Round 1 → Round 2, finalists |
| `core/Standings.java` | Standings and the tiebreak procedure |
| `core/Pool.java`, `Match.java`, `Team.java` | Round-robin schedule, match result, team |
| `core/PrintSheets.java`, `Xlsx.java` | The three printable Excel sheets, and a tiny .xlsx writer (no library) |
| `core/Rules.java` | Score validation (to 11, win by 2, best of 3) |
| `core/Registration.java`, `Player.java` | Reading payment exports (CSV), pairing players by DUPR ID |
| `app/MainActivity.java` | All screens, built in code with big text and buttons |
| `app/RegistrationsScreen.java` | Registrations screen: import, column matching, ratings, payment link |
| `branding/` | Logo and app name, swapped in at build time |
| `app/AccountScreen.java`, `Api.java` | Sign-in, my details, people and roles, court captains (optional server) |
| `server/` | The accounts server (Cloudflare Worker + D1), with its own tests |
| `app/Store.java` | Saves each tournament as its own JSON file on the device |
| `app/Archive.java` | Backup files (export/import) and published results |
| `app/Venues.java` | Venues and their courts |
| `docs/index.html` | The user guide (GitHub Pages) |

`core/` is plain Java with no Android code, so the rules can be tested and reused (for example in a web version).

## Taking registrations and payments

The app never handles money or payment keys. Players pay on your own payment page; the app reads who paid.

**With Stripe (no server needed):**
1. In the Stripe dashboard create a **Payment Link** for one player's entry fee. Turn on collecting the customer's
   name and email, and add three custom fields: **Your DUPR ID**, **Partner name**, **Partner DUPR ID**.
   Stripe can also make a QR code for the link to post at your courts.
2. In the app: Registrations → **Set the payment link**, then **Share the payment link** to email or text it.
3. Whenever you want an update, export the payments from Stripe as a CSV file and use **Load a payment file**.
   The first time, check which column is which (the app guesses from the column names and remembers your choice).
   Loading the same file again is safe: payments already loaded are skipped, and refunded ones are ignored.

**Any other payment system or a sign-up spreadsheet** works the same way, as long as its export has a column for
each player's name and DUPR ID (and ideally the partner's DUPR ID, a payment ID and a status). Players who pay
cash can be added by hand.

Every player has their own DUPR ID, a permanent identity, so it's how the app tells who named whom as partner
(names get misspelled; IDs don't). DUPR ratings change, so you look
them up and enter them; the app records the date you checked.

## Ideas for later

- A small optional server (for example a free Cloudflare Worker) that receives Stripe's "payment completed"
  notifications, so registrations appear in the app without exporting a file. Other payment systems would plug in
  by offering the same simple list of registrations.
- Automatic DUPR rating lookup (needs DUPR partner API access).
- Court assignments and a printable pool sheet.
- Several phones scoring the same event at once (needs a shared server or cloud storage).
