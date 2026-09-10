# Folio Design System & Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the design system from the handoff — four themes, three type families, the Pill navigation — and the Library, Book Details, Add Book, and error screens on top of real persisted data.

**Architecture:** A `FolioTheme` composable supplies colour, typography and shape through `CompositionLocal`s taken verbatim from the handoff. Screens are stateless composables fed by ViewModels that read `BookRepository`. No screen touches Room or the filesystem directly.

**Tech Stack:** Jetpack Compose (BOM 2026.09.00), Material3 for bottom sheets only, `lifecycle-viewmodel-compose`, bundled OFL fonts.

**Spec:** `docs/superpowers/specs/2026-09-11-folio-android-design.md`
**Design source of truth:** `~/Downloads/design_handoff_folio_reading_app/README.md` and the extracted prototype markup.

## Global Constraints

- **All Plan 1 and 2 constraints still apply.** Kotlin 2.3.21; no `kotlin-android`
  plugin; compileSdk 37.2 / targetSdk 36; `:app` on JUnit 4; never commit red.
- **Do no work in constructors.** Building an ML Kit client eagerly once failed 42
  unrelated tests. ViewModels and the object graph stay lazy.
- **The design is fixed.** Colours, spacing, radii and copy come from the handoff
  unchanged. This plan implements it; it does not revise it.
- **Ship the Pill navigation only.** Tabs, Segmented and None are exploratory
  alternates in the prototype and are not built.
- **No network at runtime.** Fonts are bundled as resources, never downloaded.
  Downloadable Fonts would need Play Services and a connection.
- **E-ink is a filter, not a palette.** Per the handoff, it is the Light palette with
  a grayscale colour matrix over the content root — not a hand-tuned fifth theme.

### Verified colour reference

Computed independently from Björn Ottosson's OKLab specification. These are the
expected values the converter's tests assert against, so a wrong transform fails
loudly rather than shifting the whole app slightly.

| Token | Light OKLCH | Light sRGB | Dark OKLCH | Dark sRGB |
|---|---|---|---|---|
| bg | 0.985 0.006 70 | `#FDF9F6` | 0.17 0.014 255 | `#0B1015` |
| bgAlt | 0.955 0.013 65 | `#F7EFE7` | 0.24 0.016 255 | `#1A2027` |
| ink | 0.22 0.03 255 | `#111B28` | 0.92 0.01 255 | `#E0E5EB` |
| muted | 0.55 0.02 255 | `#6A727D` | 0.65 0.015 255 | `#899098` |
| border | 0.89 0.013 65 | `#E1D9D2` | 0.32 0.016 255 | `#2D333B` |
| accent | 0.42 0.09 250 | `#214F7C` | 0.72 0.09 250 | `#79A9DB` |
| accentSoft | 0.95 0.02 250 | `#E5F0FC` | 0.3 0.05 250 | `#192F46` |
| buttonBg | 0.2 0.02 255 | `#10171F` | 0.72 0.09 250 | `#79A9DB` |
| readerBg | 0.975 0.012 60 | `#FDF5EF` | 0.16 0.02 50 | `#140B06` |
| highlight | 0.85 0.13 95 | `#E8CD62` | 0.4 0.1 95 | `#594600` |
| errorBg | 0.94 0.03 25 | `#FFE4E1` | 0.3 0.06 25 | `#47211E` |
| errorText | 0.45 0.14 25 | `#932B2A` | 0.75 0.12 25 | `#F08F87` |

Pale theme tokens are in the handoff table and convert the same way.

---

## File Structure

```
app/src/main/kotlin/app/folio/android/ui/
  theme/Oklch.kt              OKLCH -> sRGB conversion
  theme/FolioColors.kt        the four palettes, generated from OKLCH
  theme/FolioTheme.kt         CompositionLocals, e-ink filter
  theme/FolioType.kt          Work Sans / Source Serif 4 / Lora
  theme/FolioShapes.kt        radii from the handoff
  nav/FolioNav.kt             Pill navigation, three destinations
  nav/FolioRoot.kt            top-level screen switch
  library/LibraryScreen.kt    empty + populated
  library/LibraryViewModel.kt
  library/BookCover.kt        gradient swatch, real cover when present
  details/BookDetailsScreen.kt
  details/BookDetailsViewModel.kt
  importing/AddBookSheet.kt   bottom sheet + SAF picker
  importing/ImportProgressScreen.kt
  common/ErrorState.kt        "We couldn't open this file."
  common/EmptyState.kt
app/src/main/res/font/        bundled OFL TTFs
```

---

### [done] Task 1: OKLCH conversion

**Files:**
- Create: `app/src/main/kotlin/app/folio/android/ui/theme/Oklch.kt`
- Test: `app/src/test/kotlin/app/folio/android/ui/theme/OklchTest.kt`

**Interfaces:**
- Produces: `fun oklch(l: Double, c: Double, h: Double): Color`
- Produces: `internal fun oklchToSrgb(l: Double, c: Double, h: Double): Triple<Int, Int, Int>`

- [ ] **Step 1: Write the failing test** asserting every value in the table above,
  within ±1 per channel to allow for rounding. Include the out-of-gamut guard: a
  saturated colour must clamp into range rather than produce a negative channel.

- [ ] **Step 2: Run it, confirm it fails.**

- [ ] **Step 3: Implement** OKLCH → OKLab → LMS → linear sRGB → gamma-encoded sRGB,
  exactly per the OKLab specification, clamping after the linear stage.

- [ ] **Step 4: Run, verify pass.**
- [ ] **Step 5: Commit.**

---

### [done] Task 2: Palettes and theme

**Files:**
- Create: `theme/FolioColors.kt`, `theme/FolioTheme.kt`, `theme/FolioShapes.kt`
- Test: `app/src/test/kotlin/app/folio/android/ui/theme/FolioThemeTest.kt`

**Interfaces:**
- `enum class FolioThemeName { LIGHT, PALE, DARK, EINK }`
- `data class FolioColors(bg, bgAlt, ink, muted, border, accent, accentSoft, buttonBg, buttonText, readerBg, highlight, errorBg, errorText)`
- `val LocalFolioColors: ProvidableCompositionLocal<FolioColors>`
- `@Composable fun FolioTheme(theme: FolioThemeName, content: @Composable () -> Unit)`
- `object Folio { val colors: FolioColors @Composable get() }`

E-ink resolves to the Light palette and sets a grayscale `ColorMatrix` on the content
root via `Modifier.graphicsLayer { renderEffect = ... }`, per the handoff's
instruction to post-process rather than hand-tune a fifth palette.

- [ ] Steps as before. Tests assert each palette's tokens match the reference table
  and that EINK's palette is identical to LIGHT (the difference being the filter).

---

### [done] Task 3: Typography

**Files:**
- Create: `theme/FolioType.kt`, `app/src/main/res/font/*.ttf`
- Test: `app/src/test/kotlin/app/folio/android/ui/theme/FolioTypeTest.kt`

Fonts are bundled, not downloaded. Fetch once at setup from the Fontsource CDN
(OFL-1.1, redistribution permitted) and commit them:

```bash
BASE=https://cdn.jsdelivr.net/fontsource/fonts
curl -sL "$BASE/work-sans@latest/latin-400-normal.ttf"     -o app/src/main/res/font/work_sans_regular.ttf
curl -sL "$BASE/work-sans@latest/latin-500-normal.ttf"     -o app/src/main/res/font/work_sans_medium.ttf
curl -sL "$BASE/source-serif-4@latest/latin-400-normal.ttf" -o app/src/main/res/font/source_serif_regular.ttf
curl -sL "$BASE/source-serif-4@latest/latin-600-normal.ttf" -o app/src/main/res/font/source_serif_semibold.ttf
curl -sL "$BASE/lora@latest/latin-400-normal.ttf"          -o app/src/main/res/font/lora_regular.ttf
```

Add the OFL licence text to `app/src/main/res/raw/` and surface it in Settings.

- `val WorkSans: FontFamily`, `val SourceSerif: FontFamily`, `val Lora: FontFamily`
- `enum class ReaderFont { SERIF, LORA, SANS, SYSTEM }` with `fun family(): FontFamily`
- `val FolioTypography` — UI chrome in Work Sans, headlines and chapter titles in
  Source Serif 4, per the handoff.

- [ ] Steps as before. The test asserts each font resource resolves and that
  `ReaderFont` maps to four distinct families.

---

### [done] Task 4: Pill navigation

**Files:** `nav/FolioNav.kt`, `nav/FolioRoot.kt`
**Test:** `app/src/androidTest/.../NavigationTest.kt`

The floating glass pill: three destinations (Library, Bookmarks, Settings), the
active one expanding to show its label. Blur via `Modifier.blur` on API 31+, with a
translucent scrim below — the reason `minSdk` is 26.

- [ ] Compose UI tests assert the active destination shows a label, the others do
  not, and that tapping switches screens.

---

### Task 5: Library screen

**Files:** `library/LibraryScreen.kt`, `library/LibraryViewModel.kt`, `library/BookCover.kt`
**Test:** unit tests for the ViewModel; Compose UI tests for the screen.

Greeting header, avatar with notification dot, Bookmarks/Settings icon buttons, the
habit-tracker card with its 7-day strip, a Continue Reading card, and the 3-column
grid whose last cell is the dashed add tile. Empty state when there are no books.

Covers are the handoff's gradient swatches, replaced by a real cover image when the
book has one.

- [ ] The ViewModel exposes `LibraryState` from `repository.observeLibrary()`.
  Tests: empty library shows the empty state; a saved book appears with its progress;
  deleting removes it. **No static placeholder data anywhere.**

---

### [done] Task 6: Book Details

**Files:** `details/BookDetailsScreen.kt`, `details/BookDetailsViewModel.kt`

Gradient cover header with back/share/bookmark, title and author, genre chips, the
4-cell stat strip (% complete, chapter, pages, time left), pace estimate, last
highlight card, Synopsis/Details/Author tabs, Continue Reading, Contents/Bookmarks.

Stats come from real data. "Pages" is derived from characters at a nominal page size
until the Reader's paginator exists in Plan 4 — and is labelled in code as an
estimate so it is not mistaken for a measured value.

A book with `reflowFailed` shows **Read original PDF** instead of Continue Reading.

- [ ] Steps as before.

---

### [done] Task 7: Add Book and import progress

**Files:** `importing/AddBookSheet.kt`, `importing/ImportProgressScreen.kt`

The bottom sheet with its single "Choose from Files" row, opening the SAF picker via
`ActivityResultContracts.OpenDocument` for EPUB, PDF and TXT MIME types. Progress
follows the handoff's copy exactly — "Preparing your book…", "Extracting text",
"Detecting chapters", "Your book is ready.", "Read now" — mapped from
`ImportCoordinator`'s stages. No technical terminology reaches the user.

- [ ] Steps as before. A test asserts each pipeline stage maps to the designed copy
  and that no raw stage name is ever displayed.

---

### [done] Task 8: Error and empty states

**Files:** `common/ErrorState.kt`, `common/EmptyState.kt`

"We couldn't open this file." with the format explainer and "Try another file", plus
"Read original PDF" when a partial result exists. The Bookmarks empty state.

- [ ] A test asserts every `FailureReason` maps to designed copy — no enum name ever
  reaches the screen.

---

### [done] Task 9: Screenshot pass

**Files:** `app/src/androidTest/.../ScreenshotTest.kt`

Capture each screen in all four themes on the emulator and write the PNGs where they
can be reviewed. These are for **human** review of design fidelity: they prove the
screens render without crashing, not that they match the design. That judgement is
explicitly deferred to the morning review.

---

## Self-Review

**Spec coverage.** §11 theme system → Tasks 1–3. §16's Pill requirement → Task 4.
Library and Book Details → Tasks 5–6. §3 import flow UI → Task 7. §12 error state →
Task 8.

Deferred to Plans 4–5: the Reader, pagination, bookmarks and highlights, habits,
streaks, XP, milestones, and the share sheets.

**Type consistency.** `LibraryBook` comes from Plan 2 unchanged. `FolioColors` token
names match the handoff's table exactly, so a reader can compare them side by side.
`ImportProgress` from Plan 2 feeds Task 7 without reshaping.

**Known gap.** Design fidelity cannot be asserted by a test. Task 9 produces
screenshots for review, and the plan states plainly that passing tests here means
"renders and behaves", not "matches the design".
