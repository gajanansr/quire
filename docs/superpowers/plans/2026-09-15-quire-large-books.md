# Large Books, and a Chapter Title Printed Twice — Implementation Plan

> **For agentic workers:** steps use checkbox (`- [ ]`) syntax. Gate on
> `./scripts/check.sh` before every commit. Never commit red.

**Goal:** a large book must respond to a page turn and a type-size change from the
moment it is on screen, and a chapter whose own first heading already says "Part 1"
must not have "Part 1" printed above it as well.

**Architecture:** three tracks, none of which changes the normalized `Book`
contract or the on-disk schema.

- **Track A — cost.** `ChapterOpening` is quadratic in block count and rebuilds a
  block's whole text on every call, on both the pagination path and the per-frame
  render path. Fixed in `:core`, with a counting test in the spirit of
  `PaginationCostTest`.
- **Track B — scheduling.** `ReaderHost` has two pagination paths that neither
  cancel each other nor agree on their cache key, runs the first one before the
  reader's saved typography has arrived, and silently drops a page turn requested
  while pagination is in flight. Collapsed into one keyed effect, with the decisions
  extracted as pure functions so a JVM test can assert them.
- **Track C — the doubled title.** `ReaderState.showsChapterHeader` looks only at
  `blocks.first()`, only if it is a `Heading`, and compares raw strings. Replaced by
  a pure `ChapterHeading` in `:core` tested against the shapes real books produce.

**Tech Stack:** unchanged. No new dependencies — every version stays pinned.

## Global Constraints

- Kotlin/AGP/SDK versions: never bump. No new dependencies. No `INTERNET`.
- `:core` is JUnit 5 — `assertTrue(condition, message)`, message **second**.
  `:app` is JUnit 4 — `assertTrue(message, condition)`, message **first**.
- No Compose UI test dependency. Everything asserted here is a pure function.
- **Measurement and rendering must agree.** `MeasureMatchesRenderTest` stays green.
  Anything that changes the first-page header inset changes what the paginator
  budgets, so it must change what the renderer draws in the same commit.
- **Reading position is character-based.** `ResumeLoopTest` stays green.
- Files owned here: `ui/reader/ReaderHost.kt`, `ui/reader/PageCache.kt`,
  `ui/reader/ReaderState.kt`, `core/paginate/**`, plus the two one-line call sites
  in `ReaderScreen.kt`'s page rendering. **Not** `ReaderScreen.kt`'s gesture or
  selection code. No Room migration.
- Comments say *why* and name the concrete failure they prevent.

---

## Track A — What pagination actually costs per block

### Task A1: `ChapterOpening.isChapterOpening` is quadratic in blocks

**Problem, measured before writing any fix.** `isChapterOpening` ends with

```kotlin
return blocks.take(index).none { it is ContentBlock.Paragraph }
```

`Paginator.paginate` calls it once per block, so block *i* copies and scans *i*
blocks: N²/2 block reads for a chapter of N blocks. A counting `List<ContentBlock>`
gives the number without a stopwatch:

| blocks | block reads | reads per block |
|---|---|---|
| 500 | 127,301 | 254 |
| 1,000 | 504,604 | 504 |
| 2,000 | 2,009,209 | 1,004 |
| 4,000 | 8,018,420 | 2,004 |

Reads per block is exactly N/2 + 4 — the same signature as the substring bug of
2026-09-12, moved from characters to blocks. A one-chapter book (a TXT, a PDF whose
reflow produced no headings) is where N gets large, which is the same book shape
that triggered the last one.

It is also the `plainText` mistake again: the same function does
`block.spans.joinToString("") { it.text }.isBlank()`, allocating the block's entire
text to ask whether it is blank — and `ReaderScreen` calls it per drawn slice per
recomposition, which is the per-frame allocation `Chapter.blockTexts` was added to
remove.

**Files:**
- Modify: `core/src/main/kotlin/app/quire/core/paginate/TextMeasurer.kt` (`ChapterOpening`)
- Modify: `core/src/main/kotlin/app/quire/core/paginate/Paginator.kt`
- Test: `core/src/test/kotlin/app/quire/core/paginate/PaginationCostTest.kt`

- [x] **Step 1: Write the failing cost test.** A `CountingBlocks` list counts
  `get`; the test asserts reads per block stays bounded and does not grow with N.
- [x] **Step 2: Run it and watch it fail** — 2,005 reads per block at N=4,000, and
  505 → 2,005 as the chapter goes from 1,000 blocks to 4,000.
- [x] **Step 3:** Give `ChapterOpening` an `openingIndex(blocks): Int` that stops at
  the first paragraph rather than walking the chapter, and asks its spans whether
  they hold a non-space character rather than joining them into a string to ask.
  `isChapterOpening` keeps its signature and answers from it — so the render call
  site in `ReaderScreen` needs no change, which keeps this out of a file another
  agent owns.
- [x] **Step 4:** `opensChapter(openingIndex, index, startChar)` for callers looping
  over a whole chapter, and `Paginator` hoists the search out of the block loop.
- [x] **Step 5:** `./scripts/check.sh` green — 4 reads per block at N=4,000, flat.
  Commit.

---

## Track B — When pagination runs, and what it runs against

### Task B1: The first-page inset is computed from the chapter being left

**Problem.** `ReaderHost.loadChapter` calls `headerInsetPx()` *before* the loaded
chapter reaches `state`, and `headerInsetPx` reads `state.showsChapterHeader`, which
reads `state.chapter` and `state.pageIndex`. So the inset budgeted for chapter K is
decided by chapter K−1:

- Jumping to a chapter from page 5 of the previous one gives `pageIndex != 0`, so
  `showsChapterHeader` is false and the inset is 0 — while the renderer *does* draw
  the header on the new chapter's page 0. The paginator packs the header's height in
  extra lines and the renderer clips them. This is the `MeasureMatchesRender` failure
  mode, reached through the scheduler rather than through a style.
- The key `pagesFor` caches under is therefore not the key the next repagination
  computes, so the cached pages can never be hit and the chapter is laid out again.

**Files:**
- Modify: `app/src/main/kotlin/app/quire/android/ui/reader/ReaderState.kt`
- Modify: `app/src/main/kotlin/app/quire/android/ui/reader/ReaderHost.kt`
- Test: `app/src/test/kotlin/app/quire/android/ui/reader/ReaderStateTest.kt`

- [ ] **Step 1:** Failing test: the header decision for a chapter about to be opened
  must not depend on the page index of the chapter being left.
- [ ] **Step 2:** Extract `ReaderLayout.headerInsetPx(chapter, pageIndex, viewport,
  prefs, pixelsPerSp, pixelsPerDp)` — a pure function of the chapter it is for.
  `showsChapterHeader` becomes `showsHeaderFor(chapter, pageIndex, title)`.
- [ ] **Step 3:** `loadChapter` computes the inset for the chapter it just loaded.
- [ ] **Step 4:** `./scripts/check.sh` green (including `MeasureMatchesRenderTest`).
  Commit.

### Task B2: Opening a large book paginates it twice, at the wrong type size

**Problem.** Two independent faults with one symptom.

1. `LaunchedEffect(bookId, viewport)` restarts on every viewport change. The
   viewport is reported from inside `statusBarsPadding()`/`navigationBarsPadding()`,
   and window insets are not known at first layout — so the box reports one height,
   then a smaller one. Two different keys, two full paginations of the same chapter,
   the second cancelling the first only at its next suspension point.
2. `LaunchedEffect(bookId)` loads the reader's saved typography from the database,
   and nothing repaginates when it lands. If it lands after the first pagination the
   pages are laid out at the default 19sp serif while the renderer draws them at the
   saved size, and they stay that way until the reader touches the type stepper —
   which is exactly "after some time it settles", except that what settles it is the
   reader.
   It is also a lost update: `loadChapter` writes a `state.copy()` built from a
   snapshot taken before it suspended, so whichever of the two finishes last erases
   the other's field.

**Fix.** One repagination effect, keyed on everything page breaks depend on
(`bookId`, viewport, typography), gated on the saved typography having arrived.
`applyPreferences` stops paginating and only records the preference; the effect
reacts. Compose then cancels the superseded run by construction, and there is one
place where the cache key is built.

**Files:**
- Modify: `app/src/main/kotlin/app/quire/android/ui/reader/ReaderHost.kt`
- Modify: `app/src/main/kotlin/app/quire/android/ui/reader/ReaderState.kt`
- Test: `app/src/test/kotlin/app/quire/android/ui/reader/ReaderPaginationPlanTest.kt` (create)

- [ ] **Step 1:** Failing test: a scripted open — zero viewport, saved preferences
  arriving late, two viewport readings — must paginate the chapter once, at the
  saved type size, not twice and not at the default.
- [ ] **Step 2:** Extract the schedule as a pure `ReaderPagination.plan(...)`
  returning what to do (nothing / load / repaginate) so the test can drive it.
- [ ] **Step 3:** Rewire `ReaderHost` onto it; delete the second pagination path.
- [ ] **Step 4:** `./scripts/check.sh` green. Commit.

### Task B3: A page turn during pagination is thrown away

**Problem.** `turn(forward = true)` asks `ReaderTransitions.nextPage`, which with no
pages yet sees `pageIndex (0) >= pages.lastIndex (-1)` and returns null — "you are on
the last page". `turn` reads that as a chapter boundary, finds `chapterCount` is
still 0, and returns having done nothing. The tap is gone. On a small book
pagination finishes before a finger can land; on a large one there are seconds of
taps that do nothing, and the first one that works is the first one after pagination
finished — which is what "initially page change doesn't work at all" is.

**Fix.** Remember the turn and apply it when the pages land.

**Files:**
- Modify: `app/src/main/kotlin/app/quire/android/ui/reader/ReaderState.kt`
- Modify: `app/src/main/kotlin/app/quire/android/ui/reader/ReaderHost.kt`
- Test: `app/src/test/kotlin/app/quire/android/ui/reader/ReaderStateTest.kt`

- [ ] **Step 1:** Failing test: a turn requested with no pages is honoured when
  pages arrive; three turns forward land on page 3; a turn back from page 0 is not
  queued into a negative index.
- [ ] **Step 2:** `pendingTurns` on `ReaderState`, `queuedTurn` and an application
  inside `repaginated`/`openedChapter`.
- [ ] **Step 3:** `ReaderHost.turn` queues instead of dropping while
  `pages.isEmpty()`.
- [ ] **Step 4:** `./scripts/check.sh` green. Commit.

### Task B4: A superseded pagination runs to the end anyway

**Problem.** `paginate` is an ordinary function called inside
`withContext(Dispatchers.Default)`. Cancelling the coroutine does not stop it; it
finishes laying out a chapter whose result will be discarded, competing for the same
cores as the run that replaced it. With B2 there is at most one supersession per
open, but a reader dragging the type stepper makes one per step.

**Files:**
- Modify: `core/src/main/kotlin/app/quire/core/paginate/Paginator.kt`
- Modify: `app/src/main/kotlin/app/quire/android/ui/reader/ReaderHost.kt`
- Test: `core/src/test/kotlin/app/quire/core/paginate/PaginationCostTest.kt`

- [ ] **Step 1:** Failing test: a pagination told it is superseded after one page
  stops there, rather than measuring the rest of the chapter.
- [ ] **Step 2:** An `isActive: () -> Boolean = { true }` probe checked once per
  page, throwing `CancellationException` — never returning partial pages, because a
  short page list would be indistinguishable from a short chapter and would move the
  reader's position.
- [ ] **Step 3:** `ReaderHost` passes the coroutine's own liveness.
- [ ] **Step 4:** `./scripts/check.sh` green. Commit.

---

## Track C — "Part 1" printed twice

### Task C1: Find the heading, and compare it the way a reader would

**Problem.** `showsChapterHeader` asks `blocks.firstOrNull()`, requires it to be a
`Heading`, and compares `trim()`-ed strings case-insensitively. Real books defeat
all three:

- The heading is not block 0. EPUB chapters routinely open with a page break, an
  empty paragraph, an anchor that reflows to nothing, or a stray running page number
  before the `<h1>`. `first !is Heading` → header drawn → title twice.
- The heading is not a `Heading`. Plenty of books set the chapter title as a styled
  paragraph, so it arrives as `ContentBlock.Paragraph`.
- The strings differ by things a reader cannot see: a non-breaking space in
  `Part&nbsp;1`, a doubled space, a soft hyphen, a trailing full stop or colon.
- The chapter has no title, the header falls back to drawing `Chapter N`, and the
  block says `Chapter N` too.

**The rule that must not break:** a heading that genuinely differs from the title
keeps its header. Every relaxation here is a relaxation of *how the same string is
written*, never of *whether it is the same string*.

**Files:**
- Create: `core/src/main/kotlin/app/quire/core/paginate/ChapterHeading.kt`
- Modify: `app/src/main/kotlin/app/quire/android/ui/reader/ReaderState.kt`
- Test: `core/src/test/kotlin/app/quire/core/paginate/ChapterHeadingTest.kt` (create)
- Test: `app/src/test/kotlin/app/quire/android/ui/reader/ReaderStateTest.kt`

- [ ] **Step 1:** Failing tests over fixture chapters with each real shape above,
  plus the negative cases: a heading that differs must keep its header, and a
  chapter whose first words merely *start* with the title must keep it too.
- [ ] **Step 2:** `ChapterHeading.repeatsTitle(blockTexts, blocks, title, label)`:
  find the first block carrying a letter, normalise both sides (Unicode
  whitespace → space, collapse runs, drop zero-width and soft hyphens, strip
  surrounding punctuation, case-fold), compare for equality.
- [ ] **Step 3:** `showsChapterHeader` delegates.
- [ ] **Step 4:** `./scripts/check.sh` green. Commit.

---

## Done when

- `./scripts/check.sh` exits 0.
- `PaginationCostTest` states the per-block cost as a property, and the numbers
  before and after are recorded in `PROGRESS.md`.
- `MeasureMatchesRenderTest` and `ResumeLoopTest` are green and unweakened.
- `PROGRESS.md` carries the entry.
