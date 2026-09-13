# Contributing to Quire

Thanks for looking. Quire is small and opinionated, and the rules below are what keep
it that way rather than bureaucracy for its own sake.

## Getting set up

```bash
./scripts/check.sh            # everything must be green before you start
./scripts/dev.sh              # builds, installs and launches on a device or emulator
./scripts/watch.sh            # rebuilds and reinstalls on every source change
```

JDK 21, Android SDK platform 36. Nothing else.

## The rules

**Never commit red.** `./scripts/check.sh` must exit 0. A failing gate means fix it or
revert, not push on.

**Never weaken a test to make it pass.** If a test is wrong, say why in the commit and
in `PROGRESS.md`. Several tests here exist because something shipped broken once; the
comment above each says what it caught.

**Tests first.** Write the failing test, watch it fail, then make it pass. Most of this
codebase's real bugs were found by a test written before the fix, and at least one was
found because the test failed for a *different* reason than expected.

**No new dependencies** without a reason worth writing down. Quire has no analytics,
no crash reporter, no image loader and no navigation library, and each of those is a
decision rather than an omission.

**No `INTERNET` permission, ever.** It is explicitly removed in the manifest and
`NoNetworkPermissionTest` fails if it comes back — including transitively, which is how
it got in the first time.

**The conservatism rule.** Extraction must never delete content it is not confident
about. A test asserting that something is removed must also assert that the surrounding
body text survived.

## The one trap that cannot be automated

`:core` runs **JUnit 5**, where the message comes *second*:

```kotlin
kotlin.test.assertTrue(condition, "message")
```

`:app` runs **JUnit 4**, where the message comes *first*:

```kotlin
org.junit.Assert.assertTrue("message", condition)
```

Getting this backwards compiles cleanly and asserts the wrong thing — a non-empty
string is truthy. Check which module you are in.

## Comments

Comments explain *why*, and name the concrete failure they prevent. "Increments the
counter" is noise; "counted per character rather than per word, because a 65pt drop cap
otherwise made a 20pt line read as a heading" is the house style.

## Pull requests

Say what changed and what you measured. If you fixed a bug, say how you reproduced it —
several fixes here were preceded by a wrong diagnosis that measurement overturned.
