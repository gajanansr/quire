# Folio Reader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Reader — measured pagination, tap-to-toggle chrome, typography controls, table of contents, themes, a reading position that survives everything, and bookmarks and highlights.

**Architecture:** The pagination *algorithm* lives in `:core` behind a `TextMeasurer` interface, so it is JVM-testable with a deterministic fake; `:app` supplies the Compose-backed measurer. This is the same seam that made reflow and OCR testable, applied again.

**Spec:** `docs/superpowers/specs/2026-09-11-folio-android-design.md` §8

## Global Constraints

- All Plan 1–3 constraints hold. Kotlin 2.3.21, no `kotlin-android`, `:app` on JUnit 4,
  no work in constructors, never commit red.
- **Position is `(chapterIndex, blockIndex, charOffset)` and nothing else.** Pages are
  derived. If any persisted value ends up being a page number, the design has been
  broken — resume must survive a typography change.
- **Never paginate the whole book.** Only the current chapter, with neighbours
  prefetched. A 420-page book must not stall the UI.
- **No gamification chrome over reading content** (brief §11).

---

### [done] Task 1: The pagination algorithm in `:core`

**Files:**
- Create: `core/src/main/kotlin/app/folio/core/paginate/TextMeasurer.kt`
- Create: `core/src/main/kotlin/app/folio/core/paginate/Paginator.kt`
- Test: `core/src/test/kotlin/app/folio/core/paginate/PaginatorTest.kt`

**Interfaces:**
- `data class Viewport(widthPx: Float, heightPx: Float)`
- `data class TypographySettings(fontSizeSp: Float, lineHeightMultiple: Float, font: String, justify: Boolean)`
- `interface TextMeasurer { fun measure(text: String, style: BlockStyle, widthPx: Float): Measured }`
- `data class Measured(heightPx: Float, lineBreaks: List<Int>)` — character offsets ending each line
- `data class PageSlice(blockIndex: Int, startChar: Int, endChar: Int)`
- `data class Page(slices: List<PageSlice>)`
- `class Paginator(measurer: TextMeasurer) { fun paginate(chapter: Chapter, viewport, settings): List<Page> }`
- `fun List<Page>.pageContaining(position: ReadingPosition): Int`

- [ ] Failing tests first. Cover: a chapter shorter than one page yields one page;
  a long paragraph splits across pages at a line boundary, never mid-line; no
  character is lost or duplicated across the whole pagination; a heading is not
  left as the last line of a page (orphan control); an empty chapter yields one
  empty page rather than none; `pageContaining` finds the page holding a position;
  and re-paginating at a larger font produces more pages while the same character
  offset still resolves to a page.

---

### [done] Task 2: The Compose measurer in `:app`

**Files:** `app/.../reader/ComposeTextMeasurer.kt`
Adapts `androidx.compose.ui.text.TextMeasurer` to `:core`'s interface. Constructed off
composition with an explicit `Density` and font resolver so pagination can run in a
background coroutine.

---

### [done] Task 3: Reader state and position persistence

**Files:** `app/.../reader/ReaderState.kt`, `ReaderViewModel.kt`

Current chapter, position, progress, pagination cache, typography settings. Position
saves on page turn and on lifecycle pause, debounced. On open, resumes from
`repository.progressOf`.

- [ ] Tests: a page turn advances the position; a typography change keeps the same
  character offset; progress recomputes; the last page of a chapter turns into the
  first page of the next.

---

### [done] Task 4: The Reader screen

**Files:** `app/.../reader/ReaderScreen.kt`

Centred chapter label, serif chapter title, body text on the reader background. Tap
centre toggles top and bottom chrome. Any open overlay blocks the toggle until
dismissed, per the handoff.

---

### [done] Task 5: Typography sheet

Font (2×2: Serif/Lora/Sans/System), size stepper 15–24, alignment left/justify, and
the four theme swatches. Changes apply live and repaginate the current chapter first.

---

### [done] Task 6: Table of contents

Flat chapter list, current chapter in accent with a dot marker. Tapping jumps and
dismisses.

---

### [done] Task 7: Bookmarks (highlights deferred)

Bookmark the current position; highlight a selected sentence with the five colours
from the handoff. Both anchor to `ReadingPosition` plus a text snapshot so they
survive repagination.

---

### Task 8: PDF fallback viewer

For a book with `reflowFailed`, a `PdfRenderer`-backed page viewer, so a book Folio
could not reflow is still readable rather than rejected.

---

### Task 9: Device verification

The brief's §19 loop for every format: import → read → close → reopen → resume the
exact position. Screenshots of the Reader in all four themes.

---

## Self-Review

**Spec coverage.** §8 reading engine → Tasks 1–4. Typography and themes → Task 5.
Table of contents → Task 6. Bookmarks and highlights → Task 7. §12's "Read original
PDF" → Task 8. §19's resume loop → Task 9.

Deferred to Plan 5: sessions, goals, streaks, XP, milestones, share sheets, settings.

**Known gap.** Pagination fidelity — whether a page *looks* right — is not
assertable. Task 1 proves no character is lost and breaks land on line boundaries;
Task 9's screenshots are for human judgement.
