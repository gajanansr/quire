# Widget Redesign — Implementation Plan

> Gate on `./scripts/check.sh` before every commit. Never commit red.

**Goal:** the two widgets Folio already ships — Reading Streak and Reading Stats —
made good. Three things change and nothing else does: they wear **the reader's own
theme** rather than a light/dark approximation of it, they carry **the Folio mark**,
and they are **laid out to fill the box they are given** instead of centring a small
block of content in it.

**What does not change.** The copy, and every rule that chooses it: `WidgetState.kt`
is untouched apart from one new field on the snapshot, and the ~24 tests over it keep
passing unedited. The tap behaviour, `MainActivity`'s launch mode, the intent flags,
`updatePeriodMillis`, and the `onDataChanged` refresh path are all left exactly as
they are. No new dependency, no Glance, `RemoteViews` only.

**Spec:** `docs/superpowers/specs/2026-09-11-folio-android-design.md`
**Previous plan:** `docs/superpowers/plans/2026-09-13-folio-widgets.md`

## The three problems, and the technique each one needs

### 1. A widget wears a resource qualifier, and Folio has five themes

The first plan said this plainly: a widget is inflated in the launcher's process, so
`QuireTheme` does not exist there and a resource qualifier is the only theming it
gets — light or dark, and nothing else. Paper for one, Night for the other; Sepia,
E-ink and Black stayed inside the app.

That reasoning was about *resources*. It is not about *colours*. The provider already
opens the database on every update — that is where the streak comes from — and
`AppSettingsEntity.themeName` is one column over from the numbers it already reads.
So the theme rides along in the same single pass, and every colour is set on the
views at update time rather than resolved from a qualifier.

**The technique, and why, given minSdk 26.** `RemoteViews.setColorStateList` is API
31 and therefore unavailable. What is available everywhere is:

- `setTextColor(id, colour)` for every piece of text. First-class `RemoteViews`
  action, API 1.
- `setInt(id, "setColorFilter", colour)` on an `ImageView` holding a **white** shape,
  for everything that is a tinted surface: the card, its hairline, the seven day
  bars, the progress track and fill, the flame, the mark. `ImageView.setColorFilter`
  blends `SRC_ATOP`, which over white reproduces the colour exactly and leaves the
  shape's transparent parts — its rounded corners — transparent. This is already how
  the day bars are drawn today, so it is a technique this app has run on a device
  rather than one being tried for the first time.

`setInt(id, "setBackgroundColor", …)` is the other API-26-safe option and is not used
here: it paints square corners, and every themed surface on these widgets is rounded.

`setInt` dispatches by **reflection at apply() time against a method annotated
`@RemotableViewMethod`**, and a method that is not annotated is a silent no-op — the
exact failure the brief warns about. So a test reads the annotations off
`ImageView.setColorFilter`, `setImageAlpha` and `setImageLevel` by name and fails if
any of them is not remotable, and every widget test then asserts the effect on the
inflated view. That is the proof this works on API 26: the annotation check is the
same one the platform makes, and it has been there since the class existed.

**E-ink stays neutral.** Nothing here introduces a colour; the palette is
`QuirePalettes.of(theme)`, whose E-ink tokens all have chroma exactly zero, and a
test asserts the six colours a widget actually uses are still grey.

### 2. The progress bar cannot be themed, so it stops being a ProgressBar

`ProgressBar` has no remotable method for its colours before API 31 —
`setProgressTintList` needs a `ColorStateList` and therefore `setColorStateList`. A
themed widget with an untinted progress bar would be wrong on three palettes out of
five.

It becomes two `ImageView`s instead: a track, and a fill whose drawable is a `<clip>`
driven by `setInt(id, "setImageLevel", percent * 100)`. `ClipDrawable` is what the
current `widget_progress.xml` already uses internally, so the bar looks the same —
rounded at the left, cut square at the fill edge — and both halves take a colour
filter. The percentage on screen is unchanged; only the view class is.

### 3. Compact, and using the full size

References, for density and hierarchy only — nothing of anyone's visual design is
copied. Google Fit, Apple's Activity widgets, Duolingo's streak and Things all do the
same four things, and the current widgets do none of them:

- **One dominant number**, top-left, with everything else subordinate to it.
- **One line of context** under it, small and muted.
- **One piece of data that grows with the widget** and fills the remaining height.
- **Tight padding.** Android's own widget guidance asks for padding between the frame
  and the content and warns that content near the corners is cropped at the system
  radius; it does not ask for 16dp of it on a 110dp-tall box.

So: padding 16dp → 12dp, the week strip takes a `layout_weight` and grows from 30dp
at the declared minimum to 80dp at a full 4×2 cell, and the type is set on a proper
scale instead of three sizes within 5sp of each other.

**Every text view gets a fixed height and `autoSizeTextType="uniform"`.** This is the
part that makes the layout safe to change without a device: the height of the widget
becomes a sum of constants rather than a function of the font, so the budget below is
arithmetic and not an estimate — and at a 1.3× system font scale the text shrinks to
fit instead of overflowing the card, which is the failure `WidgetLayoutTest` exists
to catch and cannot be run here.

### The height budget, in dp, at the declared minimum of 250×110

Both widgets: 12dp padding, so 86dp of content.

| Reading Streak | | Reading Stats | |
|---|---:|---|---:|
| header row (flame, headline 24sp, detail 12sp, mark) | 48 | tiles (value 19sp, label 10sp) | 36 |
| gap | 8 | gap | 8 |
| week strip (weighted) | 30 | title 13sp | 17 |
| | | gap | 5 |
| | | progress row (5dp bar, 11sp percent) | 13 |
| **total** | **86** | **total** | **79** |

At a full 4×2 cell (330×160) the streak's week strip grows to 80dp and the stats
content centres with 28dp of air above and below.

### What `WidgetLayoutTest` measures, and the trap in it

The device test's `contentBottom` adds `child.top` to a value that already includes
it, so it over-reports by roughly the depth-weighted sum of the tops — a leaf at
`top=48` in a 110dp box is charged 110. That makes it strict rather than wrong, and
it is not this branch's job to change a test it cannot run. Both layouts are
therefore arranged so that **no leaf sits deep in a shallow tree**: the headline and
detail are nested in a text block so their tops are measured from the block and not
from the card, and the current-book and prompt blocks share a wrapper for the same
reason.

A new Robolectric test replays that exact function at the same three sizes, so the
device test's result is known before it is run, and a second one measures the real
content bottom in every state the device test's fixed XML visibilities never reach.

## Global Constraints

- **No new dependencies, no Glance, no Compose UI test artifact.** `RemoteViews` only.
- **Pinned versions unchanged.** Kotlin 2.3.21, AGP 9.4.0, Room 2.8.5, minSdk 26,
  Robolectric 4.16.
- **No `INTERNET` permission, ever.** `NoNetworkPermissionTest` holds it.
- **minSdk 26.** `setColorStateList`, `setChronometer`-era conveniences and anything
  else API 31+ is unavailable. Every remotable method called through `setInt` is
  asserted to carry `@RemotableViewMethod`.
- **`:app` is JUnit 4** — `assertTrue(message, condition)`, message **first**.
  Nothing here touches `:core`.
- **The copy does not change.** `WidgetState.kt` gains one field on `WidgetSnapshot`
  and nothing else; `WidgetStateTest` is not edited.
- **The tap does not change.** `QuireWidgets`, the manifest and `WidgetLaunchTest`
  are untouched.
- **`updatePeriodMillis` and `onDataChanged` do not change.** 1800000 for the streak,
  0 for the stats.
- **Colours are tested, not eyeballed**, the way `ShareCardStyleTest` does it: every
  theme's text clears 4.5:1 against that theme's own widget surface and its secondary
  text clears 3:1.
- **The picker previews keep the static palette.** The widget picker has no reader and
  no database, so `widget_*_preview.xml` and `res/values{,-night}/widget_colors.xml`
  stay exactly as they are in kind — and so do the static colours on the real layouts,
  which are what the launcher draws from `initialLayout` in the moment before the
  first update lands.

---

### Task 1: The palette a widget draws with, and the proof it is legible

**Files:** `widget/WidgetPalette.kt` (create),
`test/widget/WidgetPaletteTest.kt` (create)

- [x] Test first: `widgetPalette(theme)` returns the six colours a widget uses —
      `surface`, `edge`, `ink`, `muted`, `accent`, `mark` — as ARGB ints taken
      from `QuirePalettes.of(theme)`. For all five themes: ink ≥ 4.5:1 against
      surface, muted ≥ 3:1, accent ≥ 3:1, mark ≥ 3:1, by the same WCAG arithmetic
      `ShareCardStyleTest` uses. Run it; it fails — the function does not exist.
- [x] Test: E-ink's six colours are strictly neutral (r == g == b for every one), so
      the widget cannot smuggle an accent into the one theme whose defining property
      is that it has none.
- [x] Test: no two themes produce the same surface, which is what a `when` with a
      copy-pasted branch would otherwise do silently.
- [x] Write `WidgetPalette.kt`.

### Task 2: The reader's theme reaches the widget

**Files:** `widget/WidgetState.kt` (edit), `widget/WidgetData.kt` (edit),
`test/widget/WidgetDataTest.kt` (edit)

- [x] Test first: a snapshot loaded after `habits.setTheme("SEPIA")` carries
      `QuireThemeName.SEPIA`; a fresh install carries `PAPER`; a row holding one of
      the retired names — `"DARK"` — carries `NIGHT`, because `themeNamed` is the one
      place that mapping lives and the widget must not reset a reader's theme.
- [x] `WidgetSnapshot` gains `theme: QuireThemeName = PAPER`. `WidgetData.load`
      fills it from the settings row it already reads — no second query.

### Task 3: The Folio mark, in one colour

**Files:** `res/drawable/folio_mark.xml` (create),
`test/widget/QuireMarkTest.kt` (create)

- [x] The same folio the launcher draws — one sheet folded once, two leaves meeting
      at a fold — redrawn on a 24dp grid for widget scale: the launcher's version
      spends most of its 108dp viewport on the adaptive-icon safe circle, which at
      14dp on a home screen leaves a mark half the size it looks.
- [x] **One colour, not the launcher's two.** The launcher's shadowed leaf is an
      0.8-alpha page on a fixed navy ground. A widget has five grounds, and on Black
      that leaf is mud. A single flat fill reads on all five, and is how every other
      icon in Folio is treated — `ic_flame` carries a placeholder colour and is
      tinted at the use site.
- [x] The fold is widened relative to the launcher's — 3.2 units of 24 at the head
      and tail, tapering to about 2.1 at mid-height rather than to 0.6. The taper is
      what makes it one creased sheet instead of two rectangles, and at 14dp the
      launcher's taper closes the fold up entirely.
- [x] Test: the drawable inflates, is 24×24 with a 24×24 viewport, and every path
      coordinate lies inside the viewport.

### Task 4: The streak widget, redrawn

**Files:** `res/drawable/widget_surface.xml`, `res/drawable/widget_surface_stroke.xml`
(create), `res/drawable/widget_day_bar.xml` (edit),
`res/layout/widget_habit.xml` (rewrite), `res/layout/widget_habit_preview.xml`
(rewrite), `widget/WidgetViews.kt` (edit), `widget/HabitWidgetProvider.kt` (edit),
`test/widget/HabitWidgetTest.kt` (edit)

- [x] Test first: every method the provider reaches through `setInt` —
      `setColorFilter`, `setImageAlpha`, `setImageLevel` — carries
      `@RemotableViewMethod`. This is the API 26 guarantee, and a rename or a typo in
      one of those strings is otherwise a no-op nobody sees.
- [x] Test: rendered against `SEPIA`, the card is Sepia's `bg`, the headline is
      Sepia's `ink`, the detail is its `muted`, a read day is its `accent` and an
      unread day its `border` — and rendered against `NIGHT`, none of those is the
      Sepia value.
- [x] Test: the mark is present, is drawn in `muted`, and is the same in every theme.
- [x] Rewrite the layout: a `FrameLayout` root carrying the surface and hairline as
      full-bleed `ImageView`s under the content, 12dp padding, a 48dp header row of
      flame / (headline + detail) / mark, and a weighted week strip.
- [x] `habitViews(context, state, palette)`. The existing tests move to the new
      signature; nothing they assert about the copy changes.

### Task 5: The stats widget, redrawn

**Files:** `res/drawable/widget_progress_track.xml`,
`res/drawable/widget_progress_fill.xml` (create), `res/drawable/widget_panel.xml`
(delete), `res/layout/widget_stats.xml` (rewrite),
`res/layout/widget_stats_preview.xml` (rewrite), `widget/WidgetViews.kt` (edit),
`test/widget/StatsWidgetTest.kt` (edit)

- [x] Test first: at 42% the fill drawable's level is 4200 and the track is the
      theme's `border` while the fill is its `accent`. A `ClipDrawable` left at its
      default level 0 draws nothing, which is what a silent `setImageLevel` failure
      would look like, so the level is asserted rather than assumed.
- [x] Test: 0% and 100% are levels 0 and 10000, and neither is clamped wrong.
- [x] Test: the three existing state tests — stocked, empty, nothing open — keep
      passing with the same words and the same visibilities.
- [x] Rewrite the layout: the same `FrameLayout` surface, 12dp padding, the mark
      overlaid top-end where it costs no height, three tiles, and a footer wrapper
      holding the current book and the prompt. The empty invitation loses its inner
      panel and is simply centred on the card — one themed surface is enough, and
      `widget_panel.xml` goes with it.

### Task 6: The height budget, measured rather than asserted

**Files:** `test/widget/WidgetLayoutBudgetTest.kt` (create)

- [x] Test: replaying `WidgetLayoutTest`'s own `contentBottom` on both layouts at
      250×110, 180×110 and 330×160 under Robolectric, with the same XML visibilities
      the device test inflates, nothing exceeds the box. This is the device test,
      run where it can be run.
- [x] Test: measuring the true content bottom of the rendered `RemoteViews` in every
      state — streak lit and unlit, stats stocked, empty and prompt — at the same
      three sizes plus the declared maximum, nothing exceeds the box and at least
      6dp of slack remains at the minimum.
- [x] Test: at a 1.3× font scale the layouts still fit, because the text autosizes.

### Task 7: Record it

**Files:** `PROGRESS.md` (edit)

- [x] What changed, what was proved on the JVM, and the explicit list of what only a
      home screen can answer.
