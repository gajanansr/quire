# Chunked Pagination — Implementation Plan

> **For agentic workers:** steps use checkbox (`- [ ]`) syntax. Gate on
> `./scripts/check.sh` before every commit. Never commit red.

**Goal:** *The Love Hypothesis* — a 315-page PDF with no usable outline, imported as
**one chapter of 8,621 blocks and 565,896 characters** — must open at once and must
re-set itself at once when the reader steps the type size. Today both lay out the
whole chapter, every time.

**The one idea:** separate the two jobs `Chapter` does at once.

- A **chapter** is *semantic*: what a reader picks from Contents. It comes from the
  book's own outline or it does not exist. `ChapterDetector` is not touched, and no
  heuristic chapter detection returns. Contents keeps listing real chapters or
  nothing.
- A **chunk** is *mechanical*: how much text is laid out at once. The reader must
  never be able to tell one exists.

The reader's own words for it: *"can we just clip the books in chunks just for this
and attach in real time when reading"*. That is the design.

## The measurement this is built on

`ChunkProbe` (temporary, replaced by `ChunkedPaginationCostTest` in Task 6) lays out
a synthetic chapter of the real book's shape — 8,621 paragraph blocks, ~560k
characters — against a 1080×2400 phone at density 2.75, with the column and the
paddings `ReaderScreen` actually applies (26dp sides, 26dp top, 40dp bottom, system
bars):

| type size | column | lines/page | pages in the chapter | chars/page | chars measured to lay the chapter out |
|---|---|---|---|---|---|
| 15sp | 938px | 32 | 539 | 1,039 | 560,365 |
| 19sp | 938px | 25 | 719 | 779 | 607,035 |
| 24sp | 938px | 20 | 1,327 | 422 | 609,427 |

Three numbers matter.

1. **The chapter is 539–1,327 screens.** The footer currently reads "page 1 of 719".
2. **A full lay-out measures 560k–609k characters.** That is the number every open
   and every tap of A+ pays today, and the one to shrink.
3. **A page holds 422–1,039 characters** depending only on the type size. So a budget
   stated in *pages* costs a predictable amount of measuring, and a budget stated in
   *characters* does not — it would be 12 pages at 15sp and 30 at 24sp.

**The budget is therefore stated in pages.** Its cost in characters is bounded by the
worst case in the table, 1,039 chars/page at the smallest type.

## Where a chunk is cut, and why not at a paragraph

Paragraph boundaries are the obvious answer and they are the wrong one.

A chunk is cut **at a page boundary the paginator itself just produced** — which is
already a line boundary, because `Paginator` only ever splits at a line end the
measurer reported. So a cut is a `(blockIndex, charOffset)` pair that is *exactly*
where a page break was going to be anyway.

Three things follow, and they are the whole reason for this choice:

- **A single enormous block needs no special case.** The TXT with no blank lines and
  the PDF whose reflow merged everything — the two shapes that caused every previous
  performance bug here — are cut mid-block at a line end, like any other text. A
  chunk scheme cut at paragraph boundaries has nothing to cut in a chapter that is
  one paragraph.
- **The first page of a chunk is a continuation page, and already draws as one.**
  `Indentation.shouldIndent` returns false for `startChar > 0` and
  `ChapterOpening.opensChapter` requires `startChar == 0`, so a chunk that starts
  mid-paragraph gets no indent and no raised initial — which is what the second half
  of a paragraph carried over a page break looks like today.
- **No page is ever short.** `flush()` inside the layout loop is only ever called on
  a page that is full (or deliberately short for orphan control, which is the same
  decision whole-chapter pagination makes). The only short page is the last one of
  the *chapter*, flushed after the loop. So a chunk that stops immediately after an
  in-loop flush has emitted full pages only.

## Why a seam is invisible, stated as a theorem

> **Laying a chapter out as a sequence of chunks, each starting where the last one
> stopped, produces exactly the pages that laying the whole chapter out produces.**

The argument is that a fresh lay-out starting at a page boundary reproduces the state
whole-chapter pagination is in at that boundary:

| loop state | whole-chapter, at a page boundary | fresh chunk start |
|---|---|---|
| `used` | `0f`, set by `flush()` | `0f` |
| `current` | empty, set by `flush()` | empty |
| `spacing` | `0f` mid-block; `spacingAbovePx(isFirstOnPage = true)` = `0f` between blocks | `spacingAbovePx(isFirstOnPage = true)` = `0f` |
| indent | suppressed for `startChar > 0` | same function, same arguments |
| raised initial | suppressed for `startChar > 0` | same function, same arguments |
| `charsPerLine` | learned | reset — changes how many `measure` calls, never where a line breaks |

Two edges need care and get it in Task 2:

- **Heading orphan control** moves a heading that ended a page onto the next page.
  Whole-chapter pagination can move a heading *out of* the page a chunk happens to
  end on; a chunk that cannot see the next page cannot. So a chunk that is not at the
  chapter's end strips trailing headings from its last page and hands them to the
  next chunk as part of the carry — the same slices, moved the same way.
- **The "not even one line fits" escape** is guarded by `pages.isEmpty()`, which is
  true at the start of every chunk rather than only at the start of the chapter. It
  is left exactly as it is: it exists to stop an infinite loop when the viewport is
  shorter than a single line, and removing it for chunks would hang the Reader
  instead of diverging on a viewport that is already unusable. Divergence is confined
  to viewports too small to draw one line.

`ChunkedPaginationTest` asserts the theorem directly: chunk the chapter at every
budget from 1 page to 40, and compare the concatenated page list to
`paginate(whole chapter)` — slice for slice.

**That is how I know a seam is invisible going forward: the pages are byte-identical
to the pages the app produces today.**

## The window, and the one case that is not identical

The Reader holds a **window**: a contiguous run of pages starting at a cursor, with a
carry to the next page after it.

- **Forward extension is a pure append.** Lay out from the carry, append the pages.
  The pages already in hand and the reader's page index do not move. Identical to
  whole-chapter by the theorem.
- **Trimming from the front is a pure drop.** Drop *k* pages, subtract *k* from the
  page index, set the window start to the new first page's start. Nothing the reader
  can see changes.
- **Backward extension re-tiles, and cannot not.** Pages tile the text: the pages
  before position *P* must end exactly at *P*, and a forward lay-out from an earlier
  cursor lands wherever it lands. The only exact prepend available would end in a
  deliberately short page at the seam — the one thing constraint 1 forbids. So
  extending backward re-lays the window out from an earlier cursor and places the
  reader by character offset.

  Everything a reader could notice is bounded: the reader is shown
  `pageContaining(P) - 1`, whose text ends at or before *P*, so **nothing is skipped
  and nothing is repeated on screen.** The tell is that tapping forward again returns
  a page starting up to one line before *P* rather than at it — a line re-read, in
  the one direction a reader is already re-reading. It happens once per
  `PAGES_BEHIND` pages of *backward* travel, and never on a forward turn, never on a
  type-size change, and never speculatively.

## The constants

| constant | value | why this number |
|---|---|---|
| `PAGES_AHEAD` | 12 | One lay-out run. 12 pages is 5,064–12,468 characters — under a fiftieth of the chapter — and about ten minutes of reading at 220 wpm, so a forward extension is rare as well as cheap. |
| `PAGES_BEHIND` | 8 | Backward room. 8 pages is as far back as a reader goes to pick up a lost thread; beyond it the re-tile above applies. Held rather than laid out, so it costs nothing until a repagination. |
| `PREFETCH_MARGIN` | 3 | Extend when this close to an edge. Three page turns of warning at a page a second is ample for a run that measures ~12k characters. |

Steady-state window: **≤ 23 pages** — 8 behind, up to 12 + 3 ahead. That is what a
type-size change re-lays out, and its cost is in Task 6's table.

## Already-imported books

**Nothing is stored, nothing is migrated, nothing is re-extracted.** A chunk is a
cursor into a `Chapter` that is already on disk, computed in memory at lay-out time.
The user's copy of *The Love Hypothesis* works untouched, and so does every other
book on the device. No Room migration, no schema change, no `FIXTURE_VERSION` bump.

Chunking at import was considered and rejected for exactly this reason, plus a
second: a stored chunk boundary would be a boundary chosen for one viewport and one
type size, and it would be wrong for every other.

## The page count

`state.pageCount` is the *window's* page count once this lands, so
`"38% · page 12 of 719"` cannot survive — the denominator would be 23.

It is replaced with a book-wide count in characters, which is honest, stable across a
type-size change, and already how `BookDetailsScreen` states length:

```
38% · about page 190 of 314
```

from `ReadingEstimates.currentPage/pageCount`, at 1,800 characters a page. For this
book that is **314 pages against the PDF's real 315** — the unit is the printed page,
which is the one the reader can check. The word "about" is not decoration: it is the
difference between an estimate and a claim.

**A real bug falls out of this.** `ReaderState.progress` adds
`currentPage.slices.first().startChar` — an offset *within a block* — to the
chapter's start offset. For a book of many small chapters it is nearly right; for
this book, one chapter of 8,621 blocks, it means progress never rises above **0.1%**
however far the reader gets, and the footer reads "0% · page 300 of 719". Task 3 adds
`Chapter.blockStarts` and progress becomes the real chapter-relative offset. The same
prefix sums are what let a window be anchored *n* characters behind the reader.

## Global Constraints

- Kotlin/AGP/SDK versions: never bump. No new dependencies. No `INTERNET`.
- `:core` is JUnit 5 — `assertTrue(condition, message)`, message **second**.
  `:app` is JUnit 4 — `assertTrue(message, condition)`, message **first**.
- No Compose UI test dependency. Everything asserted here is a pure function.
- `ResumeLoopTest` and `MeasureMatchesRenderTest` stay green, unweakened.
- No heuristic chapter detection. `core/structure/ChapterDetector.kt` is not edited.
- Comments say *why* and name the concrete failure they prevent.

---

## Task 1: a chapter can say where a character offset is

**Problem.** Nothing can convert between a `(blockIndex, charOffset)` position and a
chapter-relative character offset without summing block lengths, which is O(blocks) —
8,621 of them — and is asked on every page turn by `progress` and will be asked by
every window anchor.

`Chapter.progress` gets this wrong today rather than slowly: it uses `startChar`, the
offset inside its own block, as if it were the offset into the chapter. On a
one-chapter book the reader's progress bar never leaves zero.

**Files:**
- Modify: `core/src/main/kotlin/app/quire/core/model/Chapter.kt`
- Create: `core/src/test/kotlin/app/quire/core/model/ChapterOffsetsTest.kt`

**Steps:**
- [ ] Test first: `offsetOf` and `cursorAt` round-trip for every block boundary, for
      a chapter with empty blocks in it, and for an offset past the end.
- [ ] `Chapter.blockStarts`: lazy prefix sums over `blockTexts`, derived like
      `blockTexts` so it stays out of equality and out of the JSON.
- [ ] `Chapter.textLength`, `Chapter.offsetOf(TextAnchor)`,
      `Chapter.cursorAt(offset)` — binary search, so it is O(log blocks).
- [ ] Gate, commit.

## Task 2: the paginator lays out a window, not only a chapter

**Files:**
- Modify: `core/src/main/kotlin/app/quire/core/paginate/Paginator.kt`
- Create: `core/src/test/kotlin/app/quire/core/paginate/ChunkedPaginationTest.kt`

**Steps:**
- [ ] Test first — **the theorem**: for a chapter of paragraphs, a chapter that is
      one 400k-character block, a chapter of headings and paragraphs, and a chapter
      with empty blocks: chunking by carry at budgets 1, 2, 3, 5, 8, 13, 40 pages
      produces a page list equal, slice for slice, to `paginate(whole)`.
- [ ] Test: conservation — every character of the chapter appears exactly once across
      the chunk sequence, in order.
- [ ] Test: no page except the chapter's last is short — every chunk's pages are full
      pages.
- [ ] Test: a chunk that starts mid-block draws no indent and no raised initial.
- [ ] Test: heading orphan control across a seam — a heading that ends a chunk's last
      page is carried into the next chunk, not stranded.
- [ ] `PageWindow(start, pages, next)`; `Paginator.paginateWindow(...)` with
      `from: TextAnchor` and `maxPages: Int`.
- [ ] `paginate(...)` becomes `paginateWindow(from = TextAnchor(0, 0), maxPages =
      Int.MAX_VALUE).pages`, so every existing test and caller is unchanged.
- [ ] Gate, commit.

## Task 3: progress is the offset into the chapter

**Files:**
- Modify: `app/src/main/kotlin/app/quire/android/ui/reader/ReaderState.kt`
- Modify: `app/src/test/kotlin/app/quire/android/ui/reader/ReaderStateTest.kt`

**Steps:**
- [ ] Test first: on a chapter of many blocks, progress at the last page is near 1.0
      and strictly increases page by page. Confirmed to fail before the fix.
- [ ] `progress` uses `chapter.offsetOf(position)`.
- [ ] Gate, commit.

## Task 4: the Reader holds a window

**Files:**
- Modify: `app/src/main/kotlin/app/quire/android/ui/reader/ReaderState.kt`
- Modify: `app/src/main/kotlin/app/quire/android/ui/reader/ReaderLayout.kt`
- Modify: `app/src/main/kotlin/app/quire/android/ui/reader/PageCache.kt`
- Create: `app/src/test/kotlin/app/quire/android/ui/reader/ReaderWindowTest.kt`

**Steps:**
- [ ] Test first: `ReaderWindow.openAt` anchors `PAGES_BEHIND` pages before the
      reader and places them on the page holding their character.
- [ ] Test: a forward extension leaves every existing page and the page index alone.
- [ ] Test: trimming the front moves the page index by exactly the number dropped and
      leaves the reader on the same page.
- [ ] Test: a backward re-anchor shows the page immediately before the reader's
      position — no character skipped, no short page.
- [ ] Test: the window start is sticky across a type-size change, so stepping up and
      back down returns the same tiling.
- [ ] `ReaderState.windowStart`, `windowNext`; `atChapterEnd`, `atChapterStart`.
- [ ] `PaginationRequest` and `PageCache.Key` carry the window start and the budget.
- [ ] Gate, commit.

## Task 5: the Reader turns pages across seams

**Files:**
- Modify: `app/src/main/kotlin/app/quire/android/ui/reader/ReaderHost.kt`
- Modify: `app/src/main/kotlin/app/quire/android/ui/reader/ReaderScreen.kt` (footer)

**Steps:**
- [ ] Forward past the window's last page extends the window; only a window with no
      carry crosses into the next chapter.
- [ ] Backward before the window's first page re-anchors; only a window starting at
      the chapter's first character crosses into the previous chapter.
- [ ] Prefetch within `PREFETCH_MARGIN` pages of the forward edge, in the keyed
      effect so it is cancelled with everything else. **Forward only** — a
      speculative backward re-anchor would move the page under a reader who did not
      ask for it.
- [ ] Entering a chapter backwards opens its window at its last character.
- [ ] Footer: `about page N of M`, book-wide, from `ReadingEstimates`.
- [ ] Gate, commit.

## Task 6: the cost, as a number in the repository

**Files:**
- Create: `core/src/test/kotlin/app/quire/core/paginate/ChunkedPaginationCostTest.kt`
- Delete: `core/src/test/kotlin/app/quire/core/paginate/ChunkProbe.kt`

**Steps:**
- [ ] A chapter of the real book's shape — 8,621 blocks, 565,896 characters — laid
      out whole and laid out as a 23-page window, counting measured characters as
      `PaginationCostTest` does.
- [ ] Assert the window costs less than a fortieth of the whole chapter at 19sp, and
      that the ratio does not fall as the chapter grows (a window's cost must be
      independent of the book's length — that is the property, not the constant).
- [ ] Assert a forward extension costs no more than one chunk.
- [ ] Gate, commit.

## Task 7: the loop still closes

**Files:**
- Modify: `app/src/test/kotlin/app/quire/android/ResumeLoopTest.kt`

**Steps:**
- [ ] A book resumed deep in a long single chapter lands on the same sentence, with a
      window that does not start at the chapter's first character.
- [ ] The same across a type-size change, which is `ResumeLoopTest`'s hard case with
      the window in the way.
- [ ] Gate, commit.

## Task 8: record it

- [ ] `PROGRESS.md` entry: the design, the numbers, what a device still has to
      answer.
- [ ] Tick every box above.
- [ ] Gate, commit.
