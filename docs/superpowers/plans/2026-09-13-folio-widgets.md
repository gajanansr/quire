# Home-Screen Widgets — Implementation Plan

> Gate on `./scripts/check.sh` before every commit. Never commit red.

**Goal:** two home-screen widgets. A **habit tracker** — streak, minutes read today
against the goal, and the week as a row of bars, the same three facts the Library's
`HabitCard` already shows — and a **stats** widget: books finished, chapters
finished, time read, and the book currently open with its progress. Both look like
Folio, both show only what is actually stored, and both open the app.

**Architecture.** `RemoteViews` + `AppWidgetProvider`. No Glance, no new dependency:
Glance would be one, and a widget this shape is four `TextView`s, seven `ImageView`s
and a `ProgressBar`.

A widget is the least testable surface in Android — it is inflated by another
process, on a home screen, and nothing about it can be driven from a JVM test. So the
same split the rest of the app uses applies here, harder than usual: **every decision
about what a widget says is a pure function** in `widget/WidgetState.kt`, tested
exhaustively, and the provider is a transcription of that state into `RemoteViews`
with no branching of its own. What is left over — resource ids, layout ids, manifest
entries, sizing metadata, the tap intent — is asserted under Robolectric, which can
read resources and inflate a `RemoteViews` even though it cannot render one.

Widgets cannot reach the Compose theme: they are inflated in the launcher's process,
long before `QuireTheme` exists, and the reader's chosen theme is a row in a database
that process cannot open. So the widget wears **Paper in light mode and Night in dark
mode**, mirrored into `res/values/` and `res/values-night/` as literals — and
`WidgetColorTest` converts the same OKLCH tokens through the same transform
`QuireColors` uses and asserts the literals still match, exactly as
`IcLauncherColorTest` does for the launcher icon. Literals drift; that test is what
stops them.

**Spec:** `docs/superpowers/specs/2026-09-11-folio-android-design.md`

## Global Constraints

- **No new dependencies.** `RemoteViews` and `AppWidgetProvider` are platform. Glance
  is explicitly excluded. No Compose UI test artifact.
- **Pinned versions unchanged.** Kotlin 2.3.21, AGP 9.4.0, Room 2.8.5, minSdk 26.
- **No network, ever.** `INTERNET` stays removed and `NoNetworkPermissionTest` holds
  the line. A widget adds three receivers and no permission; if the permission list
  moves, that test fails and the change is wrong.
- **Real data only.** Every number on a widget is read from Room. A fresh install
  gets an invitation, never an invented streak and never a placeholder figure. Zero
  is allowed where zero is the truth — a reader with three unfinished books really
  has finished none — and the tests say which is which.
- **Room off the main thread.** `onUpdate` arrives on the main thread of a broadcast
  receiver. Every provider takes `goAsync()` and does its reading on `Dispatchers.IO`.
- **The two-module trap.** All of this lives in `:app`, which is JUnit 4:
  `assertTrue(message, condition)` — message **first**. `:core`'s JUnit 5 puts it
  second. Nothing in this plan touches `:core`.
- **minSdk 26 and the API 31 sizing attributes.** `targetCellWidth`,
  `targetCellHeight`, `maxResizeWidth`, `maxResizeHeight` and `previewLayout` are
  API 31. They sit alongside `minWidth`/`minHeight`/`minResizeWidth`/
  `minResizeHeight`, which are what API 26–30 reads; neither set may be dropped.
- **The picker preview is not the reader's data.** `previewLayout` shows
  representative sample values, which is what the picker is for and the only thing
  that makes it useful. It is a static layout that no home screen ever inflates, and
  it carries a comment saying so. Recorded here so it reads as a decision rather than
  a leak of fake numbers into a surface that promised none.

---

### Task 1: The Paper and Night palettes, mirrored into resources

A widget is themed by resource qualifier, not by the reader's choice. Paper is the
light palette and Night is the dark one — the two the system can distinguish.

**Files:** `res/values/widget_colors.xml` (create),
`res/values-night/widget_colors.xml` (create),
`test/ui/theme/WidgetColorTest.kt` (create)

- [x] Test first: for each of `widget_bg`, `widget_bg_alt`, `widget_ink`,
      `widget_muted`, `widget_border`, `widget_accent`, the resource equals
      `QuirePalettes.Paper.<token>.toArgb()` in the default configuration and
      `QuirePalettes.Night.<token>.toArgb()` under `@Config(qualifiers = "night")`.
      Run it; it fails — the resources do not exist.
- [x] Add both files, each token's hex carrying the OKLCH token it came from in a
      comment, the way `ic_launcher_colors.xml` does.
- [x] Test: the light and dark values differ. A `values-night` file that silently
      failed to be picked up would otherwise pass everything above.

### Task 2: What a widget says, as a pure function

The whole of both widgets' copy and geometry, decided where JUnit can reach it.

**Files:** `widget/WidgetState.kt` (create), `test/widget/WidgetStateTest.kt` (create)

- [x] `WidgetSnapshot(habits: HabitSummary, booksFinished, chaptersFinished,
      libraryCount, currentBook: CurrentBook?)` — the input, a plain data class with
      no Android types in it. `totalMinutes` is derived from the recorded days rather
      than carried beside them, so there is one source of truth for it.
- [x] `habitWidget(snapshot): HabitWidgetState(headline, detail, week: List<DayBar>,
      lit: Boolean)`, where `DayBar(filled: Boolean, alpha: Int)` mirrors
      `HabitCard`'s `0.3f + 0.7f * ratio` ramp and `lit` is whether the flame is
      drawn in the accent colour or the border colour — the same rule `StreakScreen`
      uses, so a reader is not congratulated on day zero.
- [x] `statsWidget(snapshot): StatsWidgetState(tiles, current, prompt, empty)`, with
      `Tile(value, label)` and a `prompt` standing in for the current book when the
      library has nothing open.
- [x] Tests, all of them naming a real reader: a fresh install (no days, no books)
      gets the invitation and seven empty bars, not a streak; a broken streak reads
      as an invitation to start again and not as a zero; a met goal says so rather
      than reciting minutes; **"1 day" and "2 days", "1 book" and "2 books",
      "1 chapter" and "2 chapters", "1 book waiting"**; a goal of zero does not
      divide by it; a day over the goal does not push a bar past full; time reads
      "45m" under an hour, "3h 20m" over it and "2h" rather than "2h 0m"; a library
      with books but nothing finished shows real zeroes rather than the empty state;
      the week is always seven bars, oldest first, including days with nothing in
      them. 23 tests.

### Task 3: Reading the data, off the main thread

Moved ahead of the two widgets: both providers need a snapshot before either can
draw anything, and writing it twice and extracting it afterwards would be worse.

**Files:** `widget/WidgetData.kt` (create), `test/widget/WidgetDataTest.kt` (create)

- [x] `WidgetData.load(books: BookRepository, habits: HabitRepository):
      WidgetSnapshot` — a suspend function over the repositories, taking them as
      parameters so a test can hand it an in-memory database instead of the graph.
- [x] Test: against an in-memory Room database, a fresh install loads the empty
      snapshot; recorded minutes, a finished book and a part-read book each reach the
      snapshot they belong to; the current book is the most recently opened one that
      is started and not finished, which is the rule the Library's Continue Reading
      card already uses.

### Task 4: The habit widget

**Files:** `res/layout/widget_habit.xml`, `res/layout/widget_habit_preview.xml`,
`res/drawable/widget_card.xml`, `res/drawable/widget_day_bar.xml`,
`res/values/strings.xml` (create), `res/xml/widget_habit_info.xml`,
`widget/QuireWidgets.kt`, `widget/WidgetViews.kt`, `widget/QuireWidgetProvider.kt`,
`widget/HabitWidgetProvider.kt`, `AndroidManifest.xml`,
`test/widget/AppWidgetInfoAssertions.kt` (create, shared with Task 5),
`test/widget/HabitWidgetTest.kt` (create)

- [x] Test first: the provider is declared and exported with an
      `APPWIDGET_UPDATE` filter and an `android.appwidget.provider` meta-data; the
      referenced `widget_habit_info` declares `minWidth`, `minHeight`,
      `minResizeWidth`, `minResizeHeight`, `targetCellWidth`, `targetCellHeight`,
      `maxResizeWidth`, `maxResizeHeight`, `previewLayout`, `initialLayout`,
      `widgetCategory`, `resizeMode` and `updatePeriodMillis`; `RemoteViews` built
      from a known `HabitWidgetState` inflates and carries that state's headline,
      detail and seven day bars.
- [x] A shared `QuireWidgetProvider` base: `goAsync()`, load on `Dispatchers.IO`,
      apply, and finish the broadcast in a `finally` — a `PendingResult` that is
      never finished is an ANR and then a dropped update.
- [x] Layout: a rounded `widget_card` ground in `widget_bg`, a flame, the
      headline, the detail line, and seven `ImageView` bars with fixed ids
      `widget_day_0`…`widget_day_6`. Each bar is tinted by
      `setInt(id, "setColorFilter", …)` and `setInt(id, "setImageAlpha", …)` — both
      remotable `ImageView` methods, which is the only reason seven bars can be
      drawn without `addView`.
- [x] `updatePeriodMillis` 1800000. The habit widget is the one whose content changes
      with nothing happening: at midnight "minutes today" resets and a streak can
      break. Half an hour is the platform floor and the right backstop; Task 7 is
      what makes it current the rest of the time.
- [x] Sizes: `targetCellWidth` 4, `targetCellHeight` 2, `minWidth` 250dp,
      `minHeight` 110dp, resizable both ways down to 180×110dp and up to 360×180dp.

### Task 5: The stats widget

**Files:** `res/layout/widget_stats.xml`, `res/layout/widget_stats_preview.xml`,
`res/drawable/widget_progress.xml`, `res/xml/widget_stats_info.xml`,
`widget/StatsWidgetProvider.kt`, `AndroidManifest.xml`, `res/values/strings.xml`,
`test/widget/StatsWidgetTest.kt` (create)

- [x] Test first, the same shape as Task 4: declaration, sizing metadata, and a
      `RemoteViews` built from a known `StatsWidgetState` carrying three tiles, the
      current book's title and its percentage — and, for an empty library, the
      invitation with the tiles hidden rather than three zeroes.
- [x] Three stat tiles across the top, a hairline, then the current book: title on
      one line, a `ProgressBar` (`setProgressBar`, the only way a `RemoteViews` can
      draw one) and the percentage.
- [x] `updatePeriodMillis` 0. Nothing on this widget changes while the app is closed
      — a book is not finished by the clock — so a periodic wake-up would cost
      battery to redraw identical numbers. Task 7 is its only refresh, deliberately.
- [x] Sizes: `targetCellWidth` 4, `targetCellHeight` 2, `minWidth` 250dp,
      `minHeight` 110dp, resizable both ways down to 180×110dp and up to 360×250dp.

### Task 6: A tap that lands somewhere

**Files:** `widget/QuireWidgets.kt`, `MainActivity.kt`, `ui/nav/QuireRoot.kt`,
`test/widget/WidgetIntentTest.kt` (create)

- [x] `QuireWidget` names the two widgets and what each one opens, and
      `QuireWidgets.openIntent(context, widget)` returns an `Intent` for
      `MainActivity` and nothing else — so which widget opens what is data a test can
      read, the same shape as `ShareIntents`.
- [x] The habit widget deep-links to the streak screen; the stats widget opens the
      Library. `QuireRoot` gains a `pendingHabitScreen` it takes once and hands back,
      and `MainActivity` reads the extra in `onNewIntent` as well as `onCreate`: the
      activity is usually already alive, and a destination treated as a fixed
      starting point would work exactly once per process.
- [x] `FLAG_ACTIVITY_SINGLE_TOP` on the `PendingIntent` rather than a manifest
      `launchMode` change, so the existing task is reused and `onNewIntent` is what
      delivers the extra.
- [x] Test: the intent targets `MainActivity`, carries the streak extra for the habit
      widget and no extra for stats, `habitScreenOf` reads back what `openIntent`
      wrote, and a name Folio no longer has is ignored rather than thrown — a widget
      pinned by an older version keeps its intent for as long as it sits there.

### Task 7: Refreshing when reading data changes, not on a timer

**Files:** `data/HabitRepository.kt`, `data/BookRepository.kt`, `QuireApp.kt`,
`widget/QuireWidgets.kt`, `test/widget/WidgetRefreshTest.kt` (create)

- [x] Test first: recording minutes, changing the daily goal, finishing a book,
      finishing a chapter, saving a book, opening one, saving progress and deleting a
      book each notify exactly once — and a session that credited nothing notifies
      nothing. A widget that is right only after half an hour is a widget nobody
      trusts.
- [x] Both repositories take `onDataChanged: () -> Unit = {}`, defaulted so every
      existing caller and test is untouched, and call it after the write commits —
      after, because a refresh that races the transaction reads the old row and looks
      like the widget simply did not update. It goes **before** the clock parameter
      in both, not after: `BookRepository(db, store) { clock }` passes the clock as a
      trailing lambda, and a `() -> Unit` in the last position would have silently
      swallowed it and left several tests running on the wall clock.
- [x] `QuireGraph` wires both to `QuireWidgets.refresh(app)`, which broadcasts
      `ACTION_APPWIDGET_UPDATE` to both providers with their installed ids. No
      widgets installed means no ids and nothing sent. The graph dispatches it off
      the caller's thread: the Reader persists from a main-thread coroutine, and
      asking the AppWidgetManager what is pinned is a binder call.
- [x] Test: `QuireWidgets.refresh` with no widgets installed sends nothing and throws
      nothing — the common case, since most readers will install neither.

### Task 8: The providers and their renderers, joined

Found in review. `WidgetStateTest` proves the wording, `HabitWidgetTest` and
`StatsWidgetTest` prove each renderer fills the right views, and nothing at all
proves that `HabitWidgetProvider` calls the habit renderer. Swapping the two
providers' bodies would pass every test in the plan so far and put the wrong widget
on both home screens.

**Files:** `widget/QuireWidgetProvider.kt`, `test/widget/HabitWidgetTest.kt`,
`test/widget/StatsWidgetTest.kt`

- [x] Test first: each provider, given a snapshot, produces a `RemoteViews` carrying
      that widget's own views — the habit headline for one, the stat tiles for the
      other.
- [x] `views` becomes `internal` rather than `protected` so a test in the same module
      can reach it. The broadcast path around it — `goAsync`, the coroutine, the
      graph — is still not reachable from a JVM test and stays that way.
- [x] The graph is read inside the `try`, so an unlikely failure to reach it is a
      widget that keeps its last content rather than a crash in a receiver.

### Task 9: Record it

**Files:** `PROGRESS.md`, this plan

- [x] Tick every box, append a log entry naming what was learned, and state plainly
      which parts are held by tests and which still need a home screen to confirm.
