# Notes for contributors and AI coding agents

- **Design standard: the USA Pickleball Official Rulebook (current year).** Tournament behaviour must match it;
  `RULES.md` maps every behaviour to its rule number and lists the few local choices. When the rulebook changes,
  update the code, the tests and `RULES.md` together, and cite the rule number in comments.
- Keep it simple: plain Java, no XML layouts, no third-party libraries. The users are volunteer tournament
  directors, often older adults: big text, big buttons, plain words, confirm before anything destructive.
- Tournament rules live in `android/app/src/main/java/org/pickleballpools/core/` and must stay free of Android code.
  Change a rule -> add a check to `CoreTest.java` and run `./test.sh`.
- UI is `app/MainActivity.java`; storage is one JSON file (`app/Store.java`). New fields need `optX` defaults
  when reading, so older saved tournaments still open.
- Build with `android/build.sh` (Docker) or Android Studio. Bump `versionCode` and `versionName` for every release.
- Never commit signing keys or `keystore.properties`.
