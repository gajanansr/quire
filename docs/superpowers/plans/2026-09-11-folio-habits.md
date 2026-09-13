# Folio Habits & Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The reading-habit system — sessions, daily goals, streaks, XP, levels, milestones — plus Settings and the share sheets.

**Architecture:** All habit arithmetic is pure functions in `:core/habit`, so streak boundaries, timezone changes and midnight can be tested directly instead of by waiting a week. `:app` records sessions and renders.

**Spec:** `docs/superpowers/specs/2026-09-11-folio-android-design.md` §10

## Global Constraints

- All previous constraints hold.
- **No invented numbers.** The brief forbids fake streaks and static progress. A day
  with no reading shows no streak; a new user sees zeroes, not a flattering start.
- **No gamification chrome over reading content** (brief §11). XP and streaks belong
  on their own screens and the Library card, never on the page.
- **Sessions measure reading, not an open screen.** Pause on background and after
  `QuireConstants.IDLE_TIMEOUT_MINUTES` without a page turn.
- Goal options are exactly 5 / 10 / 20 / 30 minutes.

---

### [done] Task 1: Habit arithmetic in `:core`

**Files:**
- Create: `core/.../habit/ReadingDay.kt`, `habit/Streaks.kt`, `habit/Levels.kt`, `habit/Milestones.kt`
- Test: matching test files

**Interfaces:**
- `data class ReadingDay(epochDay: Long, minutes: Int, goalMinutes: Int)` with `metGoal`
- `object Streaks { fun current(days, today): Int; fun longest(days): Int }`
- `object Levels { fun xpFor(minutes, booksFinished): Int; fun levelFor(xp): Level; val levels: List<Level> }`
- `data class Level(index: Int, name: String, minXp: Int)`
- `object Milestones { fun achieved(stats): List<Milestone>; val all: List<Milestone> }`

Streaks count **consecutive days meeting the goal**, ending today or yesterday — a
streak should not break at midnight before the reader has had a chance to read.

- [ ] Failing tests first: an empty history has no streak; a single day today counts
  as one; a gap breaks it; yesterday still counts (today does not break it);
  a day below goal breaks it; longest survives after the current one ends;
  days are keyed by local epoch-day so a timezone change cannot double-count.

---

### [done] Task 2: Session recording

**Files:** `app/.../habit/SessionTracker.kt`, Room entities for `ReadingSession` / `ReadingDay`

Starts when the Reader opens, pauses on background or idle, commits minutes to the
day on stop. Rolls up into `ReadingDay`.

- [ ] Tests: a session under a minute records nothing; idle time is excluded; two
  sessions in one day sum; a session crossing midnight splits across days.

---

### [done] Task 3: Goal selection

The onboarding "How much would you like to read today?" screen and the Settings row.
5 / 10 / 20 / 30, with 5 marked Recommended per the handoff.

---

### [done] Task 4: Streak, milestones and level screens

The 4×7 heatmap, the milestone cards (achieved filled, locked outline, no numeric
badges), and the level list with the current one as a filled accent pill.

---

### [done] Task 5: Library habit card on real data

Replace the placeholder strip with the real 7-day history and streak.

---

### [done] Task 6: Settings

Grouped iOS-style cards — Reading / Library / About — on the soft gray canvas.
Default theme row cycles the global theme. Includes the bundled-font licence.

---

### [done] Task 7: Share sheets

The 9:16 story card for a quote and for a streak, caption row, and destination row
with generic glyphs only — the handoff is explicit that no brand logos are used and
none should be added without the real SDKs and brand guidelines.

---

### [done] Task 8: Device verification

Screenshots of every new screen in light and dark.

---

## Self-Review

**Spec coverage.** §10 habit tracking → Tasks 1–5. Settings → Task 6. Share sheets →
Task 7.

**Known gap.** Streaks are inherently time-dependent. Task 1 tests them by injecting
"today" rather than by sleeping, which covers the boundaries but not a real
month of use.
