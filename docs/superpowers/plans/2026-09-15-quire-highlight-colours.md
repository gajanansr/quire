# Highlight Colours, Opacity and Options — Implementation Plan

> Gate on `./scripts/check.sh` before every commit. Never commit red.

**Goal:** a highlight a reader can choose the colour of, change afterwards, and
remove — and one that tints the page instead of staining it, so the words stay the
most legible thing on the line.

Asked for on a real phone: *"the highlighter should have options and less opacity and
colour options too."* Today there is one colour per theme, applied at full strength,
with no way to change or remove it once made.

**Architecture:** no Compose UI test dependency, and this plan does not add one.
Every decision lands in a pure function JUnit can reach — colour resolution and
contrast in `ui/theme/QuireHighlights.kt`, the tap-to-highlight hit test in
`ui/reader/Highlights.kt`, the mark-to-slice arithmetic already in `:core`'s
`Selection.portionOf`. The composables stay thin.

**Spec:** `docs/superpowers/specs/2026-09-11-folio-android-design.md`

## Global Constraints

- Pinned versions unchanged. No new dependencies, and no Compose UI test artifact.
- **No network.** `INTERNET` stays removed; `NoNetworkPermissionTest` holds the line.
- **E-ink has chroma exactly zero.** `QuireThemeTest`'s `e-ink has no colour at all`
  is not weakened, and the new palette is held to the same rule by a test of its own.
  An electrophoretic panel is greyscale hardware; a colour it cannot render is not a
  colour, it is a bug nobody can see.
- **Measurement and rendering must agree.** The paginator measures a chapter without
  knowing which parts of it are marked. That is safe only while a mark changes no
  metric. This plan adds a *second* property to a mark — the text colour of the
  highlighted run — so Task 2 re-proves the invariant rather than assuming it.
- **The reader's choice is stored, not the colour.** A highlight made on Paper and
  read on E-ink is the same highlight. The row holds `KEEP`; the theme resolves it.
- `ui/reader/ReaderHost.kt` is being restructured by another agent. Edits there are
  additive and confined to the one `ReaderScreen(...)` call and the one
  `observeHighlights` collector. Nothing in that file is reorganised.

---

## The palette

Five, because five is what a reader can tell apart at a glance and keep straight in
their head. Each has a job, because that is how people actually use colour in a book:

| | hue | what it is for |
|---|---|---|
| **Keep** | gold, 90° | Worth remembering. The default, and what every existing highlight becomes. |
| **Fact** | green, 150° | Something to be able to cite: a number, a date, a claim with evidence behind it. |
| **Doubt** | rose, 15° | A disagreement, or a claim to go and check. |
| **Look up** | sky, 230° | A word, a name or a reference to follow later. |
| **Lovely** | lavender, 300° | A phrase loved for its own sake rather than for what it says. |

**One pigment, resolved per theme.** A theme states a *wash* — a lightness, a chroma
and an alpha — and a colour states a hue. On Paper and Sepia the wash is a pale, wide
pigment at 42%; on Night and Black it is a brighter pigment at 26%, because a colour
mixed *into* a dark page lands dark rather than glaring. Every one of the five shares
its theme's lightness and chroma and differs only in hue, so no colour shouts louder
than another and proving one legible proves all five.

**E-ink: a colour becomes a tone.** The panel has one axis — how dark the pixel is —
so that is the axis the five choices vary along: five greys from `#D0D0D0` down to
`#858585`, evenly spaced. The trade is stated rather than hidden: on a colour page
the five marks are equal in weight and differ in hue; on E-ink they cannot be, so
they differ in weight instead and a reader learns "darker means further down my own
list". The band is bounded on both ends by arithmetic — the lightest is as light as
it can be while still being visibly not the page, the darkest as dark as it can be
while the ink on it still clears 4.5:1 — which is why there are five and not eight.

## Opacity

A highlight is a translucent wash over the page, not an opaque block: 42% on the
light themes, 26% on the dark ones, 55% on E-ink. Two consequences worth naming.

The first is the point of the exercise: the composite is a tint. Paper's Keep goes
from `#E8CD62` to `#EBDAB3` on a `#FDF5EF` page.

The second is the one that was actually broken. A blockquote is drawn in `muted`,
and `muted` sits at the 4.5:1 floor against the bare page — so **today a highlighted
blockquote measures 2.30:1 on Night**. The fix is not a gentler wash, it is that a
mark now carries a text colour as well as a background, and a highlighted run is
always set in `ink`. That makes the contrast proof a single number per theme instead
of one per block style, and it is why Task 2 has to re-prove the line-break
invariant.

---

### Task 1: The highlight palette, and the proof it is legible

**Files:** `ui/theme/QuireHighlights.kt` (new), `ui/theme/QuireColors.kt`,
`test/ui/theme/QuireHighlightsTest.kt` (new), `test/ui/theme/QuireThemeTest.kt`,
`test/ui/settings/ClockColorsTest.kt`

- [x] `HighlightColour` — `KEEP`, `FACT`, `DOUBT`, `LOOK_UP`, `LOVELY` — each with a
      hue and a label. Stored by `name`; `highlightColourNamed` resolves an unknown
      or missing value to `KEEP` the way `themeNamed` does, so a row written by a
      later version does not paint a highlight `Color.Unspecified`.
- [x] `QuireHighlights.tint(theme, colour)` returns the translucent wash the span is
      painted with; `QuireHighlights.over(theme, colour)` returns it composited on
      that theme's `readerBg`, which is what a swatch and a Bookmarks row draw.
- [x] `QuireColors.highlight` is deleted. It had exactly one use site and the palette
      now owns highlight colour; leaving a token nothing reads is how a comment ends
      up lying. `QuireThemeTest.tokensOf` and `ClockColorsTest.tokensOf` lose the
      entry — the e-ink chroma rule loses one token there and gains twenty-five in
      `QuireHighlightsTest`, so the property is asserted more widely, not less.
- [x] Test, the `ShareCardStyleTest` method: for all 5 themes × 5 colours, `ink`
      composited over the tint over `readerBg` clears **4.5:1**.
- [x] Test: every tint is *visible* — at least 0.09 of per-channel separation from
      its own page. Without this a 2% alpha would pass the contrast test and paint
      nothing.
- [x] Test: on any one theme no two colours are within 0.06 per-channel of each
      other. Five marks a reader cannot tell apart are one mark.
- [x] Test: every E-ink tint and every E-ink composite has `red == green == blue`.
- [x] Test: every pigment is inside the sRGB gamut — `oklchToSrgb` clamps, and a
      clamped channel shifts the hue silently, so Doubt and Keep would drift towards
      each other with nothing on screen to say why.

### Task 2: A mark carries its ink, and still cannot move a line break

**Files:** `ui/reader/ReaderTypography.kt`, `ui/reader/ReaderScreen.kt`,
`test/ui/reader/MeasureMatchesRenderTest.kt`

- [ ] `ReaderMark(range, background, ink)` replaces `Pair<IntRange, Color>`.
      `readerText` applies `SpanStyle(background = …, color = …)`.
- [ ] `BlockText` keeps drawing a blockquote in `muted`; the mark overrides it for
      the marked run only.
- [ ] Test: a paragraph and a blockquote break on exactly the same characters with
      and without a mark that sets **both** background and colour. Neither is a
      metric, but that is the assumption the whole paginator rests on and it is now
      an assumption about two properties rather than one.

### Task 3: A highlight remembers its colour

**Files:** `data/Entities.kt`, `data/QuireDatabase.kt`, `data/BookRepository.kt`,
`QuireApp.kt`, `test/data/SettingsMigrationTest.kt`, `test/data/BookRepositoryTest.kt`

- [ ] `BookmarkEntity.highlightColour: String = "KEEP"`.
- [ ] `MIGRATION_7_8`, database version 8: adds the column with default `'KEEP'`.
      Every existing highlight becomes Keep, which is not a guess — gold is the only
      colour highlights have ever had, so nothing on screen changes for anyone.
- [ ] Registered in `QuireApp.kt`.
- [ ] Test, raw SQLite the way the rest of `SettingsMigrationTest` works: a
      version-7 bookmarks table with a highlight and a plain bookmark in it comes out
      at version 8 with both at `KEEP` and every other column untouched; and the
      migrated `bookmarks` matches the table Room builds from the entity.
- [ ] `addHighlight` takes a colour. **Two highlights of the same passage in
      different colours are one row, not two:** the six-coordinate dedupe stays, and
      a second highlight of an already-highlighted passage *recolours* it. Two rows
      would put two entries in Bookmarks for one passage and stack two washes on one
      run, where only the last one drawn is visible — a list that disagrees with the
      page.
- [ ] `recolourHighlight(id, colour)` for the same change made deliberately.
- [ ] `observeHighlights` returns `SavedHighlight(id, span, colour)` rather than a
      bare `TextSpan`: the page needs the id to know what a tap landed on, and the
      colour to paint it.

### Task 4: Changing and removing a highlight

Reached by the obvious gesture — **tapping the highlight in the page**. The selection
action bar stays at three buttons: it sits over the words the reader is looking at,
and a colour row there would double its width for a decision most highlights do not
need.

Colour is therefore chosen *after* the mark, and inherited *before* it: picking a
swatch also sets the colour the next highlight is made in, so a reader who
colour-codes sets it once. Tapping Highlight stays one tap.

**Files:** `ui/reader/Highlights.kt` (new), `ui/reader/ReaderState.kt`,
`ui/reader/ReaderScreen.kt`, `ui/reader/ReaderHost.kt`,
`test/ui/reader/HighlightsTest.kt` (new), `test/ui/reader/ReaderStateTest.kt`

- [ ] `Highlights.at(highlights, anchor)` — pure. Returns the **shortest** highlight
      containing the anchor, so a phrase marked inside a marked paragraph is still
      reachable; null when the tap missed.
- [ ] `ReaderState.editingHighlightId` and `highlightColour` (the last colour used,
      session state, defaulting to `KEEP`). Transitions: open, dismiss, recolour.
- [ ] A tap with no live selection resolves to an anchor first: on a highlight it
      opens the options, anywhere else it turns the page or toggles the chrome as
      before. On a highlight the tap must win over the page-turn zones, or a
      highlight in the outer quarter of the page could never be opened.
- [ ] `HighlightOptions` — five swatches with the current one ringed, and Remove.
      Placed by the same `SelectionActionBar.prefersTop` the action bar uses, off the
      tapped highlight's own caret, so it never sits on the words it is about.
- [ ] Any tap while it is open dismisses it; a tap on another highlight opens that
      one instead.
- [ ] `ReaderHost` edits, and no others: `onHighlight` gains the colour parameter,
      the highlights collector takes `SavedHighlight`s, and three lambdas are added
      — open/dismiss, recolour, remove.
- [ ] Tests: the hit test (inside, outside, on the boundary, nested, empty list); the
      state transitions; that recolouring sets the colour the next highlight takes.

### Task 5: The Bookmarks list agrees with the page

**Files:** `ui/bookmarks/BookmarksScreen.kt`, `ui/nav/QuireRoot.kt`

- [ ] A highlight's row carries its colour as a bar down its leading edge, drawn with
      `QuireHighlights.over(theme, colour)` — the colour the page draws, composited
      once, so the two cannot disagree.
- [ ] A plain bookmark has no colour and gets no bar. It is a place, not a passage.

### Task 6: Record it

**Files:** `PROGRESS.md`

- [ ] The colours and what each is for, the contrast table for all five themes, what
      a colour becomes on E-ink and why, the migration and what existing highlights
      became, and what only a device can judge.
