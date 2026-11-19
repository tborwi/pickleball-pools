# Tournament rules the app follows

The **USA Pickleball Official Rulebook** is the design standard for this app. When a rule changes, the app changes
to match. This page lists every rule the app applies, with its rule number in the **2026** rulebook
(https://usapickleball.org/docs/rules/USAP-Official-Rulebook.pdf), so it can be checked each year.

## From the rulebook

| What the app does | Rule (2026) |
|---|---|
| Round robin: every team in a pool plays every other team; ranked by matches won | 15.B.4 |
| Pool play: teams are divided into pools that each play a round robin | 15.B.5 |
| Matches are best 2 of 3 games to 11 | 15.C.1.a |
| Games are **win by 2** by default (win by 1 is only allowed for team play) | 15.C |
| A team that **withdraws, retires or is forfeited**: its match results don't count in the standings, and it can't advance to a playoff or medal match | 15.B.4.a |
| Tiebreaker 1: record in head-to-head matches among the tied teams | 15.B.4.b |
| Tiebreaker 2: point differential of all games played | 15.B.4.c |
| Tiebreaker 3: point differential of head-to-head games among the tied teams | 15.B.4.d |
| Tiebreaker 4: point differential against the team(s) with the next-highest number of matches won (for a tie for second, against the first-place team) | 15.B.4.e |
| Tiebreaker 5: most points scored across all round robin matches | 15.B.4.f |
| The tiebreaker that breaks the tie ranks **all** the tied teams. Teams still level on it continue with the **next** tiebreaker among themselves; they don't go back to tiebreaker 1 | 15.B.4 |
| Point differential and points scored are **not** compared across pools with different numbers of teams | 15.B.4.c, 15.B.4.f |
| Seeding is done by the tournament (the app seeds by combined DUPR) | 15.E |

Example of continuing to the next tiebreaker: three teams are tied and their head-to-head results go in a circle.
Tiebreaker 2 (point differential) puts one team first. The other two are then ranked by tiebreaker 3
(head-to-head point differential), not by who won their match.

## Choices the rulebook leaves to the tournament

These are this app's choices (made with the Heart of Wisconsin Pickleball Association). Change them in code if your
event does it differently, and update this list.

- **Pools by snake seeding** of combined DUPR: A, B, C, D, then D, C, B, A, and so on.
- **Two rounds of pools.** The top 2 of each Round 1 pool go to Championship pools, the rest to Consolation pools.
  Round 2 seeding: Round 1 pool finish, then share of matches won, then (only if all Round 1 pools are the same
  size) point differential and points scored, then DUPR.
- **Withdrawals only change the round the team is playing in.** Results from an earlier, finished round stay.
- **Redoing pools** after drop-outs is allowed before or during a round; the app warns how many scores it erases.
- **Still tied after all five tiebreakers:** listed by DUPR and shown in red for the tournament director to decide.
- **Win by 1** can still be chosen in Settings for local events that want shorter games, but it is not standard.
