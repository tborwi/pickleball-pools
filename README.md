# Pickleball Pools

A free, simple Android app for running a doubles pickleball tournament with pool play, built for volunteer-run
non-profits. It replaces the clipboard-and-calculator work: seeding, pool sheets, standings and tiebreakers.

Originally written for the Heart of Wisconsin Pickleball Association (Plover, WI). Use it, change it, share it (MIT license).

## What it does

0. **Registrations (optional).** Every player registers and pays for themselves on your payment page
   (a Stripe Payment Link, PayPal, Square, Zeffy… anything that can export a spreadsheet). Load the export into the
   app and it pairs partners by **DUPR ID**, then sorts everyone into: teams ready, waiting on partner to pay,
   partners don't match, needs a partner, paid twice (refund?), and missing DUPR ID. You look up each player's
   DUPR rating, type it in (the date is saved), and add the ready teams to the tournament.
1. **Teams & seeding.** Enter each team's two players and their DUPR ratings (or paste a list from a spreadsheet).
   Teams are seeded by **combined DUPR** (5.56 + 5.51 = 11.07).
2. **Round 1 pools.** Teams are snaked into pools by seed: A, B, C, D, then D, C, B, A, and so on.
   So Pool A gets seeds 1, 8, 9, 16 and Pool D gets 4, 5, 12, 13. Each pool plays a round robin.
3. **Scores.** Each match is best 2 out of 3 games, to 11, win by 1. The app refuses impossible scores (like 12-10).
4. **Round 2.** The top 2 of each pool go to the **Championship** bracket (Pools A and B), the rest to the
   **Consolation** bracket (Pools C and D). Teams are re-seeded by Round 1 result (pool finish, then wins,
   point differential, points scored) and snaked again. Another round robin.
5. **Finals.** The top team of each Championship pool is shown as qualified for the Finals.
6. **Share.** One button sends all standings as text (email, text message, Facebook post…).

All of these numbers can be changed in **Settings**: number of pools, teams that advance, finalists per pool,
points per game, and win-by. Data stays on the device; nothing is sent anywhere unless you share it.

## Tiebreakers

Teams are ranked by matches won. Teams with the same number of wins are separated by, in order:

1. Head to head (match wins among the tied teams only)
2. Overall point differential (all round robin games)
3. Head-to-head point differential (games among the tied teams only)
4. Point differential vs the next highest team (the team just above the tied group in the standings)
5. Total points scored

As soon as a step separates some of the tied teams, any teams still tied **start over at step 1**. For example,
a 3-way tie that becomes a 2-way tie is then settled by those two teams' head-to-head match. When a pool is
finished, the standings show which tiebreaker placed each team. If all five fail, the teams are shown in red,
listed by DUPR, for the tournament director to decide.

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
| `core/Rules.java` | Score validation (to 11, win by 1, best of 3) |
| `core/Registration.java`, `Player.java` | Reading payment exports (CSV), pairing players by DUPR ID |
| `app/MainActivity.java` | All screens, built in code with big text and buttons |
| `app/RegistrationsScreen.java` | Registrations screen: import, column matching, ratings, payment link |
| `branding/` | Logo and app name, swapped in at build time |
| `app/Store.java` | Saves everything to one JSON file on the device |

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

DUPR IDs are each player's permanent identity, so they're used to pair partners. DUPR ratings change, so you look
them up and enter them; the app records the date you checked.

## Ideas for later

- A small optional server (for example a free Cloudflare Worker) that receives Stripe's "payment completed"
  notifications, so registrations appear in the app without exporting a file. Other payment systems would plug in
  by offering the same simple list of registrations.
- Automatic DUPR rating lookup (needs DUPR partner API access).
- Court assignments and a printable pool sheet.
- Several phones scoring the same event at once (needs a shared server or cloud storage).
