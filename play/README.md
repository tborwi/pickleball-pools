# Google Play

Everything needed to put Pickleball Pools on Google Play. Graphics in this folder: `icon-512.png` (app icon),
`feature-graphic.png` (1024 x 500). Screenshots still need a real phone (2 to 8, portrait).

## Build the upload file

```
cd android && ./build.sh play     # -> android/PickleballPools.aab (signed with the upload key, no private practice roster)
```

Bump `versionCode` in `android/app/build.gradle` for every upload: Play refuses a number it has seen.
Keep the signing key (`~/.config/pickleball-pools-release.jks`) backed up: it is the **upload key**. On the first upload,
choose **Play App Signing** (Google holds the app signing key).

Phones that installed the app from a sent file must **uninstall it before installing from Play** (different signature).
Back up tournaments first (More -> Back up this tournament), then import them again after installing.

## Release path (personal developer account)

1. **Internal testing**: up to 100 testers by email, available within minutes, no review wait. Start here.
2. **Closed testing**: at least **12 testers opted in for 14 days in a row**, required before a personal account may
   apply for production. Recruit a few extra in case someone leaves.
3. **Production**: apply in Play Console after the 14 days; Google asks a few questions about the test.

Testers join through the opt-in link Play Console gives each track, then install from the Play Store.

## Store listing

- **App name:** Pickleball Pools
- **Short description (80):** Run round-robin pickleball tournaments: fair pools, live scores, printed sheets.
- **Category:** Sports
- **Contact email:** required, shown publicly (the publisher's choice)
- **Privacy policy:** https://tborwi.github.io/pickleball-pools/privacy.html
- **Full description:**

> Pickleball Pools runs round-robin doubles and singles pickleball tournaments for clubs.
>
> - Fair pools from DUPR ratings, with partners matched by DUPR ID
> - Standings and tiebreakers worked out by the USA Pickleball Official Rulebook
> - Round 2 Championship and Consolation pools, and the Finals
> - Court captains enter scores on their own phones; scores reach the director by themselves, even after a spell
>   without signal
> - Photo receipts: captains photograph the signed paper score sheet
> - Players need nothing: they get emails with their schedule, scores and results, and a live scores web page
> - Printable Excel sheets: schedule, court captain sheets and results grids
> - Venues with their courts, several tournaments on one phone, backups, and a public Past tournaments page
>
> Made for the Heart of Wisconsin Pickleball Association. Free and open source, with no ads.

## App content answers

| Play Console section | Answer |
|---|---|
| Privacy policy | https://tborwi.github.io/pickleball-pools/privacy.html |
| Ads | No ads |
| App access | Sign-in is needed for staff features. Give reviewers a test email they can receive codes at, or say: "Tournament features work without signing in; sign-in is only for court captain scoring." |
| Content rating | Questionnaire category: Utility/Productivity. No violence, gambling or chat. Users don't share content with each other (score sheet photos go only to staff and the match's players). Expected rating: Everyone |
| Target audience | 18 and over |
| News app | No |
| Government app | No |
| Financial features | None (payments happen on Zeffy, not in the app) |
| Health | None |
| Account deletion | Yes. In app: Account -> Delete my account. Web: https://pickleball-pools.tbor.workers.dev/account/delete |

## Data safety answers

Data is **encrypted in transit** (HTTPS). Users **can request deletion** (link above). No data is **sold**.
Cloudflare, Resend and Google act as service providers, which Play does not count as "sharing".

| Data type | Collected | Shared | Optional? | Purposes |
|---|---|---|---|---|
| Name | Yes | No | Required for staff accounts; players' names come from registration | App functionality, Account management |
| Email address | Yes | No | Required | App functionality, Account management, Developer communications (tournament emails) |
| Phone number | Yes | No | Optional | Account management |
| User IDs (DUPR ID) | Yes | No | Required for registration pairing | App functionality |
| Photos | Yes | No | Optional (score sheet photos) | App functionality |
| Other user-generated content (scores) | Yes | No | Required | App functionality |

Not collected: location, contacts, financial info, health, messages, audio, files, calendar, web history, app activity
analytics, device IDs, crash logs.
