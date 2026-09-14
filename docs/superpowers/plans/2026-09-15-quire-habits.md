# The Reading Habit — Implementation Plan

> Gate on `./scripts/check.sh` before every commit. Never commit red.

**Goal:** five things about the habit loop. A real clock for the reminder time and a
daily goal that is any number of minutes rather than one of four; a streak that
survives a missed day without lying about it; notification copy worth reading, with a
famous quotation about reading that changes each day; and a first-run guide that says
what Quire is and then gets out of the way.

**Architecture:** the same split every earlier plan used. The product questions —
*is this run still alive? which day was rested? which quote is today's? what does the
guide say?* — are pure functions with no Android imports, and the screens are a thin
`when` over their results. This is not a style preference: there is no Compose UI test
dependency and this plan does not add one, and almost every requirement here is about
something **not** happening — a streak that must not inflate, a guide that must not
appear twice, a notification that must not claim a day was consecutive when it was
not. A negative is invisible on a device. It is only checkable as an assertion.

**Spec:** `docs/superpowers/specs/2026-09-11-folio-android-design.md` (silent on all
five — this plan is new ground and says so), extending
`docs/superpowers/plans/2026-09-13-folio-notifications.md`.

## Global Constraints

- Pinned versions unchanged. **No new dependencies.** Material3 1.4.0 is already on
  the Compose BOM and ships `TimePicker`; nothing else is needed.
- **`INTERNET` stays removed.** The quotations ship in the binary as Kotlin source.
  Nothing is ever fetched, and `NoNetworkPermissionTest` remains the gate.
- **Nothing purchasable, ever.** Quire has no payments, no currency, no ads. Any
  streak mechanic that could be sold is out of scope by construction, not by choice.
- **Never invent a number.** The rule `ReminderCopyTest` already enforces extends to
  the streak: the figure on screen counts days the reader actually read, and no
  forgiveness may raise it.
- No guilt. The word list in `ReminderCopyTest` stands, and this plan adds to it.
- `:core` is JUnit 5 (`assertTrue(condition, message)`); `:app` is JUnit 4
  (`assertTrue(message, condition)`).
- `ui/reader/**` and `ui/share/**` belong to other agents this round. Not touched.

---

## Task 2's research: how other apps forgive a missed day, and what it costs them

Surveyed before choosing, because this is the one decision here that is a product
decision rather than an engineering one.

| Model | Mechanic | Purchasable? | Pressure | Honest? |
|---|---|---|---|---|
| **Duolingo** | daily, resets to zero; Streak Freeze (2 free, 5 in Streak Society, 200 gems); earn-back repair; Super auto-repair buys freezes with gems | **yes** | very high — 600+ experiments on the streak alone; the streak was deliberately *decoupled from the learning goal* to keep it alive | **no** — a frozen day counts as a day done |
| **Apple Fitness** | daily Move-ring streak; no rest days at all; Pause Rings stops the clock but credits nothing | no | high — documented "ring guilt", people closing rings while ill, escalating monthly challenges | **yes** — nothing is fake, and that is the one thing it gets right |
| **Oura** | **no streak.** Seven-day rolling windows: goals met 6–7×/wk is optimal, **no penalty until three misses**; rest days are *scored positively*; Rest Mode disables activity scoring entirely | no | very low by construction | **yes** — nothing is restored, only recomposed |
| **Headspace** | daily run streak, resets and stays reset; separate never-resetting totals | no | moderate; the company publicly tells readers to ignore the number | **yes** |
| **Snapchat** | mutual 24h; hourglass warning; one free restore then ~$0.99 | **yes** | severe, and best-evidenced — adolescents send black rectangles to keep a streak alive | **no, twice** — purchasable *and* hollowed out |
| **Strava / Nike Run Club** | **weekly**: one activity a week keeps it; a late upload of a real activity restores it | no | low | **yes** — the "restore" reflects something that genuinely happened |
| **Streaks (iOS)** | daily with a **2-Day Rule**: one miss is recorded as *skipped*, the chain survives; two in a row resets | no | low | **partly** — the day-level record is truthful, but the headline integer is inflated by one |

Sources: Duolingo's own blog on streaks, freezes, the Weekend Amulet and outage
repair; Apple's watchOS guide on per-day goals and Pause Rings, plus Macworld's
award rules ("no rest-day allowances exist"); Oura's Rest Mode post and Activity
Contributors documentation; Headspace's "building a meditation practice"; Snapchat's
own Streak Restore pricing page; Strava's Streaks support article; Nike Run Club's
weekly rationale; Crunchy Bagel's Streaks 10 release notes. On harm and evidence:
Silverman & Barasch, *"On or Off Track: How (Broken) Streaks Affect Consumer
Decisions"*, JCR 49(6) — an intact **logged** streak drives engagement independent of
actual past behaviour, and highlighting a break *accelerates abandonment*; Beshears,
Lee, Milkman, Mislavsky & Wisdom, *Management Science* 67(7), n=2,508 — flexible
incentives beat routinised ones both during and after the intervention; Hristova et
al., GamiFIN 2020 — Snapchat "streak snaps", including black pictures; van Ouytsel et
al. 2023, n=2,483 adolescents; Polivy & Herman's abstinence-violation effect, which is
the mechanism by which an all-or-nothing chain produces the abandonment it exists to
prevent; BJ Fogg against streak counting outright; James Clear's "never miss twice".
Worth noting that the Seinfeld "don't break the chain" story — the origin myth of the
whole genre — is a single second-hand anecdote that Seinfeld himself has disowned.

### What was rejected, and why

- **Streak freezes, of any kind — including free ones.** A freeze is an *object the
  reader owns*, and an object can be lost, hoarded and worried about; that is the
  whole difference between an allowance and a possession. It also makes the number a
  claim about a day nobody read. Duolingo's snowflake marker mitigates this at the day
  level while the headline figure stays inflated, which is a half-measure.
- **Paid or ad-gated repair.** Impossible here — Quire has no payments — and wrong
  anyway. Barasch's line is the argument: *"a direct source of money for the company
  for just a digital badge that is entirely inconsequential."*
- **Decay models** ("lose ten days per miss"). Gentle-sounding and *worse* on honesty:
  a decayed figure corresponds to nothing that happened.
- **A weekly streak** (Strava, Nike Run Club) — the survey's own first
  recommendation, and genuinely excellent. Rejected because Quire has already shipped
  a streak counted in days: the Library card, the streak screen, the share card, both
  home-screen widgets and the notification copy all say "day". Migrating that to weeks
  would silently restate every reader's history in a new unit and would give a new
  reader nothing at all for their first six days. The *idea* underneath it is kept:
  see below, where the allowance is a seven-day rolling window.
- **Apple's model — no forgiveness at all.** Honest, free, and the best-documented
  source of user harm in the survey. Honesty alone is not sufficient.
- **Any notification about a streak at risk, or about a rest day being available or
  spent.** Silverman & Barasch found that highlighting a break accelerates
  abandonment, and this is a non-nagging app: the right number of streak-anxiety
  notifications is zero. Enforced as a test.

### What was chosen: rest days

**A run survives a missed day. At most one missed day is spanned in any seven. The
number counts only days the reader actually read.**

Three properties, and each is an assertion in `RestDaysTest`:

1. **Forgiving.** Missing yesterday leaves the run standing today — the moment the
   reader actually experiences, and the moment at which forgiveness that only worked
   retroactively would be invisible.
2. **Bounded.** Two rest days must be seven days apart, so two missed days in a row
   always end a run ("never miss twice"), and an every-other-day reader does not
   accumulate an endless streak. That bound is Oura's rolling seven-day window wearing
   a streak's clothes: at most one miss per seven, no penalty for the first.
3. **Honest.** `daysRead` counts days the goal was met and nothing else. A run of
   thirty that carried two rest days says **thirty**, not thirty-two — which is
   strictly more honest than Streaks iOS, the closest comparable, and than every
   freeze mechanic in the table. The rest days come back from the same function and
   are drawn on the heatmap and named in words on the streak screen.

There is nothing to earn, spend, equip or lose. The allowance is a property of the
calendar, not an item in an inventory, so there is no state in which a reader is
holding something they might waste. That is the whole reason this does not become
pressure.

Two ideas from the survey are adopted alongside it: the **never-resetting number**
(Headspace's real insight is not its streak but that total days read survives a
break untouched) and the **heatmap as the record of truth**, which makes a missed day
visible without making it expensive.

---

## The shape of it

| Piece | What it is | Where |
|---|---|---|
| `Streaks.run` | pure: the forgiving walk — days read, rest days, start day | `core/habit/Habits.kt` |
| `Streaks.longestRun` | pure: the same rule applied to all of history | `core/habit/Habits.kt` |
| `Streaks.current` / `.longest` | the same walk with the allowance switched off | `core/habit/Habits.kt` |
| `Goals` | pure: bounds, presets and the stepper | `core/habit/Habits.kt` |
| `ReadingQuotes` | pure: the quotations, rotated by epoch day | `notify/ReadingQuotes.kt` |
| `quireClockColors` | pure: the fourteen `TimePicker` colours, from `QuirePalettes` | `ui/settings/SettingsScreen.kt` |
| `OnboardingGuide` | pure: the pages, and whether the guide is on screen at all | `ui/onboarding/Onboarding.kt` |

---

### Task 1: A clock, not eight chips

`Reminders.TIME_OPTIONS` is eight presets. The reader asked for a clock.

**Files:** `notify/Reminders.kt`, `ui/settings/SettingsScreen.kt`,
`test/notify/RemindersTest.kt`, `test/ui/settings/ClockColorsTest.kt` (create)

- [ ] `Reminders.minuteOfDay(hour, minute)` / `.hourOf` / `.minuteOf` — pure, clamped,
      and the only arithmetic between the picker and the stored `reminderMinuteOfDay`,
      which does not change shape.
- [ ] Material3's `TimePicker` in a `Dialog` Quire paints itself. **Not**
      `TimePickerDialog`: it draws its own title and buttons from `MaterialTheme`, and
      the lavender `AlertDialog` is a mistake this codebase has already made once.
- [ ] `is24HourFormat(context)` decides the dial, so a phone showing 20:00 is not
      handed an am/pm toggle. `QuireRoot` already reads it for `formatTime`.
- [ ] Test: for every one of the five themes, all fourteen `TimePickerColors` resolve
      to tokens from that theme's palette — asserted against the palette rather than
      against literals, so a token that changes cannot leave the clock behind.
- [ ] Test: the dial's selected and unselected text clears **4.5:1** on whatever it
      sits on, in every theme. E-ink in particular is near-black on near-white and
      must stay legible.
- [ ] Test: every minute of the day survives the round trip to hour/minute and back.

### Task 2: A forgiving streak

**Files:** `core/habit/Habits.kt`, `data/HabitRepository.kt`, `ui/habit/HabitScreens.kt`,
`notify/ReminderWords.kt`, `ui/library/LibraryScreen.kt`,
`core/test/habit/RestDaysTest.kt` (create), `test/notify/ReminderCopyTest.kt`

- [ ] `StreakRun(daysRead, restDays, startDay)` and `Streaks.run(days, today,
      restEveryDays = 7)`. `current` and `longest` become the same walk with
      `restEveryDays = 0` — one algorithm with a switch, rather than two that drift.
- [ ] `HabitSummary` carries `restDays`, `streakStart` and the never-resetting
      `daysRead`, so the number, the sentence under it and the heatmap all describe
      the same run.
- [ ] Test: **the number is never larger than the days actually read**, over seven
      history shapes. The load-bearing invariant.
- [ ] Test: two missed days in a row end the run; a second rest day inside the same
      week ends the run; an every-other-day reader does not accumulate an endless one.
- [ ] Test: missing yesterday leaves the run standing today, and today being unread
      is not itself a rest day.
- [ ] Test: a rest day is never reported outside the run it belongs to.
- [ ] The streak screen says what happened: the run, the rest days it carried in
      plain words, and the total days read, which never resets. The heatmap draws a
      rest day as the empty day it was, outlined so the reader can see which one the
      run carried.
- [ ] The handoff's "the streak resets, but the reading doesn't" line is now false and
      is replaced by an accurate statement of the rule.
- [ ] Milestone copy stops claiming consecutive calendar days, since a run may now
      span one.
- [ ] Test: **no streak notification claims the days were consecutive.** "in a row",
      "running", "one after another", "every day", "straight", "consecutive" — banned
      by word list, because a run that carried a rest day makes every one of them
      false, and warm-specific-and-wrong is the failure the copy rules exist to stop.
- [ ] Test: **no notification mentions a rest day at all** — not that one is
      available, not that one was spent, not that the streak is at risk. The research
      is unambiguous that pointing at a break accelerates leaving.

### Task 3: Notification copy, and a quotation a day

**Files:** `notify/ReadingQuotes.kt` (create), `notify/ReminderWords.kt`,
`notify/QuireNotifier.kt`, `notify/ReminderWorker.kt`, `ui/habit/HabitScreens.kt`,
`test/notify/ReadingQuotesTest.kt` (create)

- [ ] `ReadingQuotes.all` — hand-entered, in source, every one verified against a
      primary text rather than a quote site, every author long out of copyright.
      Rotated by `epochDay.mod(size)` so it is the same quotation all day and a bug
      report can be reproduced.
- [ ] The quotation rides in the notification's expanded `BigTextStyle` under the
      personal line, so the collapsed shade still shows the reader's own book.
- [ ] Test: no duplicates, by text **and** by author, so one writer cannot quietly
      take two of the fourteen slots.
- [ ] Test: every entry has a non-blank author.
- [ ] Test: one sentence each, and **no digits anywhere** — the same honesty rule the
      reminder copy is held to, applied to an epigraph.
- [ ] Test: deterministic for a day, different from the next day, all reachable.

### Task 4: A first-run guide

**Files:** `ui/onboarding/Onboarding.kt` (create), `ui/nav/QuireRoot.kt`,
`data/Entities.kt`, `data/QuireDatabase.kt`, `QuireApp.kt`,
`test/ui/onboarding/OnboardingTest.kt` (create), `test/data/SettingsMigrationTest.kt`

- [ ] Three pages, then the goal picker that already exists. Not five: this app's
      voice is restraint, and a tour is not a product.
- [ ] `guideSeen` column, **migration 6 → 7**, seeded `UPDATE app_settings SET
      guideSeen = onboarded` — an upgrade must not greet a reader of six months with a
      tour of an app they have been using for months. Separate from `onboarded`, which
      is only set once a goal is chosen, so a reader who closes Quire between the guide
      and the goal picker does not see the guide again.
- [ ] Test: the migrated table is the one Room builds from the entity (the existing
      comparison test, extended) — a mismatch is an `IllegalStateException` at launch
      on every upgrading device.
- [ ] Test: **a settings row that has not loaded yet never shows onboarding.** This is
      the live bug: `QuireRoot` used to render the welcome screen for a frame on every
      cold start because `AppSettingsEntity()` defaults to `onboarded = false`. The fix
      is in place and this states it as an assertion so it stays in place.
- [ ] Test: skip from any page ends the guide; the guide is never shown when
      `guideSeen`; every page has a headline and a line; no page promises anything
      Quire does not do (no sync, no cloud, no account).

### Task 5: A daily goal that is any number of minutes

Four hard-coded values cycled by tapping a row, and `setDailyGoal` threw on anything
else. A reader who wants fifteen minutes could not have it.

**Files:** `core/habit/Habits.kt`, `data/HabitRepository.kt`,
`ui/settings/SettingsScreen.kt`, `ui/habit/HabitScreens.kt`,
`core/test/habit/GoalsTest.kt` (create), `test/data/HabitRepositoryTest.kt`

- [ ] `Goals.MIN = 1`, `Goals.MAX = 120`. One minute because a goal of zero can never
      be met — `ReadingDay.metGoal` is false when the goal is zero — and because a
      minute is the smallest total the session accumulator records. Two hours because
      a daily goal here is a floor to clear rather than a target to fail, and a goal
      nobody keeps is a streak that never starts.
- [ ] `Goals.step` — a minute at a time below thirty, five above, so fine control
      lives where a minute is a tenth of the goal and two hours is not ninety taps.
- [ ] `setDailyGoal` clamps instead of throwing. The old `require` would now be an
      uncaught exception in a coroutine launched from a composable — a crash on a tap.
- [ ] The presets stay as one-tap chips beside the stepper, in Settings and in the
      onboarding goal picker. Most people do want "10 min" without counting to it.
- [ ] Test: every minute in range is stored exactly; out of range is clamped, not
      thrown; stepping up and back returns to where it started; every preset is
      reachable by stepping. **Replaces** `only the handoff's four goals are accepted`,
      which asserted the behaviour this task exists to remove.
- [ ] Test: **changing the goal does not rewrite history.** `ReadingDayEntity` stores
      the goal in force on its day, so whether a past day met its goal — and therefore
      the streak — is fixed once recorded. This property already held; it is now load
      bearing for a reader who can move the goal to any number, and it gets a test.
