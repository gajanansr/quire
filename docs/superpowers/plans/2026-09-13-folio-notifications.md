# Reading Reminders — Implementation Plan

> Gate on `./scripts/check.sh` before every commit. Never commit red.

**Goal:** one quiet reminder a day, in words drawn from the book the reader is
actually in the middle of — and never on a day they have already read. Configurable,
silenced in one tap, and asked for only after the reader has shown they want it.

**Architecture:** the whole product question — *should anything be said right now,
and in what words?* — lands in one pure function, `Reminders.decide(facts)`, with no
Android imports at all. Everything around it is plumbing that can be read in one
sitting: a WorkManager job gathers the facts, calls the function, and posts whatever
it is handed. This is the same split `ReaderStateTest` and `FolioBackTest` already
use, and it is the only way to test this feature at all — there is no Compose UI test
dependency and this plan does not add one.

The reason the decision has to be pure is specific: every anti-nag rule in this plan
is a *negative* — the notification that must not appear. A negative is invisible on a
device. It is only checkable as an assertion.

**Spec:** `docs/superpowers/specs/2026-09-11-folio-android-design.md` (silent on
notifications — this plan is new ground and says so).

## Global Constraints

- Pinned versions unchanged. **No new dependencies**: WorkManager and Room are
  already here and are all this needs.
- **No exact alarms.** `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` are special-access
  permissions that Play polices and readers are right to distrust. WorkManager's
  one-shot-with-delay, rescheduled after each run, is the whole mechanism. The price
  is that delivery is approximate; Task 2 makes that a stated rule (a delivery
  window) rather than an accident.
- **No network, ever.** `INTERNET` stays removed. `POST_NOTIFICATIONS` is the one new
  permission and it is local-only; `NoNetworkPermissionTest`'s reviewed allowlist
  gains it with the reasoning, and a new test asserts it is *present*, so the feature
  cannot silently lose it either.
- **Nothing is generated.** Every line of copy is hand-written and lives in source
  where it can be diffed. Variation is a rotation over a small set, not a template
  engine.
- **Never invent a number.** Every digit in a delivered notification must be
  traceable to a stored value. Task 3 tests this as a property, not by inspection.
- No guilt. "You've broken your streak" and its family are forbidden, and the ban is
  a test with a word list rather than a note in a review.
- Off means off *immediately* (the pending job is cancelled) and *permanently* (the
  decision function refuses even if a stale job survives). Two locks, both tested.

---

## The shape of it

| Piece | What it is | Where |
|---|---|---|
| `Reminders.decide` | pure: facts in, `Silent(reason)` or `Notify(kind, copy)` out | `notify/Reminders.kt` |
| `ReminderCopy` | pure: the hand-written lines, chosen by kind and day | `notify/Reminders.kt` |
| `ReminderSchedule` | pure: delay to the next occurrence, and the delivery window | `notify/Reminders.kt` |
| `ReminderScheduler` | WorkManager enqueue / cancel | `notify/ReminderScheduler.kt` |
| `ReminderWorker` | gathers facts, calls `decide`, posts, reschedules | `notify/ReminderWorker.kt` |
| `FolioNotifier` | the channel, the notification, the tap target | `notify/FolioNotifier.kt` |
| `NotificationAccess` | whether Folio may post at all, right now | `notify/FolioNotifier.kt` |
| `ReminderPermission` | pure: when to ask, when to stop asking, when to deep-link | `notify/ReminderPermission.kt` |

### The two kinds, and why only two

- **Daily** — at the time the reader picked. Words about the book they are in.
- **Streak** — the same moment, different words, when a streak of 3 or more is live
  and today is unread. It *replaces* the daily line rather than joining it.

There is no third kind and no second notification in a day. A notification that
arrives when the reader has already read, or a second one after they ignored the
first, is the exact thing that makes people turn notifications off for good.

---

### Task 1: Somewhere to keep the answer — settings columns and migration 5 → 6

Seven new columns on the one settings row. Defaults are chosen so that a reader who
upgrades and never opens Settings is **not** notified: `remindersEnabled` starts at
0, and Task 7 is the only thing that turns it on, at the reader's own tap.

**Files:** `data/Entities.kt`, `data/FolioDatabase.kt`, `FolioApp.kt`,
`data/HabitRepository.kt`, `test/data/SettingsMigrationTest.kt`

- [ ] `AppSettingsEntity` gains `remindersEnabled = false`, `remindersAsked = false`,
      `reminderMinuteOfDay = 20 * 60`, `dailyReminderEnabled = true`,
      `streakReminderEnabled = true`, `reminderPermissionDenied = false`,
      `lastReminderDay = -1L`.
- [ ] `MIGRATION_5_6` adds all seven with those defaults; database version 6;
      registered in `FolioApp`. A real migration, not destructive fallback — the
      house rule, and a reader's library is not worth a reminder setting.
- [ ] `HabitRepository` gains `setRemindersEnabled`, `setReminderTime`,
      `setReminderKinds`, `markRemindersAsked`, `markPermissionDenied`,
      `recordReminderSent(epochDay)`.
- [ ] Test: a version-5 row survives the migration with every prior field intact and
      the seven new ones at their defaults — in particular `remindersEnabled = 0`,
      because an upgrade that starts notifying someone who never asked is the worst
      possible first impression of this feature.
- [ ] Test: `lastReminderDay` defaults to `-1`, which is before every real epoch day,
      so a fresh install is never treated as "already reminded today".

### Task 2: The rule — `Reminders.decide`

The heart. No Android imports; a plain JUnit 4 test class, no Robolectric.

**Files:** `notify/Reminders.kt` (create), `test/notify/RemindersTest.kt` (create)

- [ ] `ReminderFacts(remindersEnabled, dailyEnabled, streakEnabled, canPost, today,
      minutesToday, goalMinutes, currentStreak, lastReminderDay, minuteOfDay,
      reminderMinuteOfDay, book: BookInProgress?)`.
- [ ] `BookInProgress(title, chapterLabel, chapterNumber, percentRead)`.
- [ ] `ReminderDecision` is `Silent(reason: Silence)` or
      `Notify(kind: ReminderKind, copy: ReminderCopy)`.
- [ ] Rules, in this order, each returning a distinct `Silence` so a test can say
      *why* it was quiet rather than only that it was:
      1. `REMINDERS_OFF` — the master toggle. First, so off outranks everything.
      2. `CANNOT_POST` — no permission, or notifications disabled in system settings.
      3. `ALREADY_READ_TODAY` — `minutesToday > 0`. **The rule that matters most.**
         Deliberately *any* reading, not "goal met": a reader who managed four
         minutes of a ten-minute goal has read today, and the only notification that
         fits is the one telling them they are six minutes short — which is the
         nagging this feature exists to avoid.
      4. `ALREADY_SENT_TODAY` — `lastReminderDay >= today`. One a day, total.
      5. `NO_KIND_ENABLED` — both per-kind toggles off.
      6. `OUTSIDE_WINDOW` — before the chosen time, or more than
         `DELIVERY_WINDOW_MINUTES` (180) after it. WorkManager may run late; a
         reminder three hours stale is noise, and one that fires at 02:00 because
         the phone was dozing is worse than none.
      7. Otherwise `Notify`. Kind is `STREAK` when streak nudges are on and
         `currentStreak >= STREAK_MIN` (3), else `DAILY`.
- [ ] Test, at minimum: goal met is silent; *some* reading is silent; reading exactly
      at the goal is silent; zero minutes with everything on notifies; master off
      outranks a live streak; each toggle independently silences its own kind and
      only its own; a streak of 2 gets the daily line and a streak of 3 gets the
      streak line; the window's two edges and both sides of them; `lastReminderDay ==
      today` silences and `today - 1` does not; permission missing silences even with
      everything else perfect.
- [ ] Test: the precedence itself — construct facts that trip several rules at once
      and assert the *reason* reported is the earlier one. Precedence is the part
      that rots silently when a rule is added later.

### Task 3: The words

Hand-written, warm, specific, and true. Four registers: daily-with-a-book,
daily-with-no-book, streak-with-a-book, streak-with-no-book. Variation is
`epochDay % variants.size` — deterministic, so a test can read it, and it rotates
daily, which is as much variation as one-a-day needs.

**Files:** `notify/Reminders.kt`, `test/notify/ReminderCopyTest.kt` (create)

- [ ] Daily, book in progress (4):
      - "Still on the nightstand" / "{book} is open at {chapter}. {goal} quiet minutes?"
      - "Where you left off" / "You're {pct}% through {book}. Pick it up whenever."
      - "{chapter} is waiting" / "{book}, exactly where you stopped."
      - "A few pages?" / "{goal} minutes of {book} — whenever suits."
- [ ] Daily, no book (2):
      - "Your reading time" / "Nothing open yet — {goal} minutes is a good place to start."
      - "A quiet {goal} minutes" / "Folio is here whenever you'd like to begin."
- [ ] Streak, book in progress (3):
      - "{n} days running" / "{chapter} is next in {book}."
      - "{n} days, one after another" / "No rush — {book} will keep."
      - "You've read {n} days in a row" / "{book} is open at {chapter} whenever you are."
- [ ] Streak, no book (2):
      - "{n} days running" / "{goal} minutes whenever you'd like."
      - "{n} days, one after another" / "Folio is here when you are."
- [ ] Long titles are trimmed to `MAX_TITLE` (48) on a word boundary with an ellipsis,
      so a notification is a sentence rather than a wall.
- [ ] Test — **no invented numbers**: render every variant against facts whose title
      and chapter label carry no digits, strip the substituted title and chapter
      label from the result, and assert every remaining integer is one of
      `{goalMinutes, percentRead, currentStreak}`. This is the honesty rule as an
      assertion.
- [ ] Test — a numeric title survives verbatim ("1984", "Catch-22"): the rule bans
      numbers Folio made up, not numbers the author wrote.
- [ ] Test — **no guilt**: no line contains any of `broke`, `broken`, `lost`, `fail`,
      `missed`, `don't`, `should`, `last chance`, `hurry`, `streak is at risk`, and no
      line contains `!`.
- [ ] Test — never a false plural: `STREAK_MIN` is 3, so no streak line can read
      "1 days". Assert across the whole reachable range rather than trusting the
      constant.
- [ ] Test — every variant is non-blank, the title fits a notification's first line
      (≤ 48 chars after substitution with a realistic title), and consecutive days
      produce different lines.
- [ ] Test — a book with no detected chapter title still gets a true label
      ("Chapter 7" from the index), never an empty quote or a null leaking through.

### Task 4: When — the delay arithmetic

Pure, because "what time is it tomorrow" is where off-by-a-day bugs live.

**Files:** `notify/Reminders.kt`, `notify/ReminderScheduler.kt` (create),
`test/notify/ReminderScheduleTest.kt` (create)

- [ ] `ReminderSchedule.minutesUntil(nowMinuteOfDay, targetMinuteOfDay)` — the same
      day when the target is still ahead, tomorrow otherwise. Equal means tomorrow:
      turning reminders on at exactly 8:00 pm must not buzz the phone in that
      instant.
- [ ] `ReminderSchedule.inWindow(minuteOfDay, target)` — `[target, target + 180]`,
      clamped to the end of the day so a 11:00 pm reminder is not silently
      impossible.
- [ ] `ReminderScheduler.schedule(context, minuteOfDay, now, policy)` enqueues unique
      one-time work named `folio-reminder` with that initial delay;
      `cancel(context)` cancels it. `REPLACE` when the reader changes something,
      `KEEP` on app start so a launch does not push the reminder back a day.
- [ ] Test: every hour of the clock against every hour of the target; midnight
      crossing; equality; the window's edges; that a 23:00 target still has a window.

### Task 5: The notification itself

**Files:** `notify/FolioNotifier.kt` (create), `AndroidManifest.xml`,
`ui/FolioStrings.kt`, `test/notify/FolioNotifierTest.kt` (create),
`test/NoNetworkPermissionTest.kt`

- [ ] One channel, `reading_reminders`, `IMPORTANCE_DEFAULT` — a sound, no heads-up.
      A reminder that shoves itself in front of what the reader is doing is the
      opposite of this feature.
- [ ] One notification id, reused, `setAutoCancel(true)`. A reused id means a second
      reminder replaces the first instead of stacking, so the shade can never hold a
      pile of Folio.
- [ ] Tapping opens `MainActivity` (`FLAG_IMMUTABLE`). No action buttons: "Snooze"
      and "Dismiss" are both ways of asking the reader to manage Folio's feelings.
- [ ] `NotificationAccess.granted(context)` — API 33+ needs `POST_NOTIFICATIONS`
      *and* `areNotificationsEnabled()`; below 33 the permission is implicit. Either
      way the channel must not be `IMPORTANCE_NONE`: a reader who muted the channel
      in system settings has said no, and Folio must treat that as no.
- [ ] `POST_NOTIFICATIONS` declared, and added to `NoNetworkPermissionTest`'s
      reviewed allowlist with its reasoning — it is local only and cannot move a byte
      off the device.
- [ ] Test: the channel exists with the expected id and importance; posting puts one
      notification in the shade carrying the given title and body; posting twice
      leaves exactly one; the content intent targets `MainActivity`; `granted` is
      false when the channel is muted.

### Task 6: The worker

**Files:** `notify/ReminderWorker.kt` (create), `work/FolioWorkerFactory.kt` or a
sibling factory, `FolioApp.kt`, `test/notify/ReminderWorkerTest.kt` (create)

- [ ] `ReminderWorker` reads settings and the habit summary, finds the most recently
      opened book and its chapter, builds `ReminderFacts`, and calls `decide`.
- [ ] On `Notify`: post, then `recordReminderSent(today)` — written *after* the post
      so a failed post does not burn the day.
- [ ] Reschedules the next occurrence on every run, except when the decision was
      `REMINDERS_OFF`: off is permanent, and a job that reschedules itself forever
      after the reader said no is the bug this clause exists to prevent.
- [ ] Wired through a factory alongside `FolioWorkerFactory` — via
      `DelegatingWorkerFactory`, so `ImportWorker`'s existing construction and its
      tests are untouched.
- [ ] Test (Robolectric + `TestListenableWorkerBuilder`): a day with reading posts
      nothing; a clean day posts one; a second run the same day posts nothing more;
      `lastReminderDay` is written; with the master toggle off nothing is posted and
      no work is left pending.

### Task 7: Asking at the right moment

Not on first launch. The reader is asked once, after a reading session that actually
recorded minutes — the first moment there is evidence they want to come back.

**Files:** `notify/ReminderPermission.kt` (create),
`ui/notify/ReminderInviteScreen.kt` (create), `ui/nav/FolioRoot.kt`,
`MainActivity.kt`, `ui/FolioStrings.kt`,
`test/notify/ReminderPermissionTest.kt` (create)

- [ ] `ReminderPermission.shouldInvite(settings, creditedMinutes)` — onboarded, never
      asked before, and minutes were just recorded. Pure.
- [ ] `shouldRequestSystemPrompt(...)` — only on API 33+, only when not granted, and
      only when the reader has not already denied. **Never twice.**
- [ ] `shouldOpenSystemSettings(...)` — once denied, or below 33 with notifications
      switched off, the only honest route is the system screen. Folio asks once and
      then gets out of the way.
- [ ] `ReminderInviteScreen`: the offer in the app's own voice, two buttons — "Yes,
      remind me" and "No thanks". Either answer sets `remindersAsked`, so the offer
      never appears again whichever way it went.
- [ ] `MainActivity` holds the permission launcher; a denial writes
      `reminderPermissionDenied` and turns `remindersEnabled` back off, because a
      toggle that reads "on" while the OS refuses to deliver is a lie.
- [ ] Test: not offered before onboarding, not offered on a session that recorded
      nothing, not offered twice; the prompt is never raised after a denial; below
      API 33 the prompt is never raised at all; after a denial the deep link is what
      is offered instead.

### Task 8: The controls

**Files:** `ui/settings/SettingsScreen.kt`, `ui/FolioStrings.kt`,
`ui/nav/FolioRoot.kt`, `FolioApp.kt`, `test/ui/FolioStringsTest.kt`

- [ ] A **Reminders** group in Settings: a master toggle row; below it, only while it
      is on, the time and the two per-kind toggles. Controls for something switched
      off are clutter.
- [ ] Time is chosen from preset chips in the existing option-tile style — the same
      control the goal picker uses. Rendered through a pure
      `Reminders.formatTime(minuteOfDay, use24Hour)` fed by the system's own 24-hour
      setting, so the time a reader sees is written the way their phone writes times.
- [ ] Toggling the master switch schedules or cancels immediately — the reader should
      not have to trust that something happens later.
- [ ] When Folio cannot post, the group says so plainly and the row opens system
      settings rather than pretending the toggle works.
- [ ] `FolioApp` re-syncs the scheduled job on start with `KEEP`, so a reminder
      survives an app-data quirk without being pushed back a day on every launch.
- [ ] Test: `formatTime` across the whole clock in both 12- and 24-hour form —
      midnight and noon are where this is always wrong; every preset formats to
      something a person would write; no new string leaks an enum name (the existing
      `FolioStringsTest` rule, extended to the reminder strings).

---

## Deliberately not built

- **Snooze, or "remind me in an hour".** It turns one notification into two.
- **A notification when a book finishes importing.** Imports are watched on screen;
  a notification for something the reader is already looking at is noise.
- **Per-book reminders.** More configuration, no more reading.
- **A weekly summary.** It would be a second notification whose job is to tell the
  reader about the first one.
