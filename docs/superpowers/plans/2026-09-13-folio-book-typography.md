# Book Typography — Implementation Plan

> Gate on `./scripts/check.sh` before every commit. Never commit red.

**Goal:** make a reflowed page read like a printed book rather than text on a
screen.

**Architecture:** everything here changes how text is *set*, which means every
change must land in the one place both pagination and drawing already share —
`BlockStyles.of` and `readerTextStyle`. A typographic change described twice
drifts, and drift here clips text off the bottom of pages.

**Spec:** `docs/superpowers/specs/2026-09-11-folio-android-design.md`

## Global Constraints

- Pinned versions unchanged. No new dependencies.
- Measurement and rendering must agree: `MeasureMatchesRenderTest` is the gate.
- Reading position is character-based and must survive every change here.
- **Departures from the handoff.** The handoff draws paragraph gaps and
  full-width text. Tasks 1 and 3 deliberately overrule it, at the user's
  direction, because they asked for book typography and the handoff drew a
  web-style reader. Recorded so it reads as a decision, not drift.

---

### Task 1: First-line indent, no paragraph gaps

The convention that does most of the work. Books indent; web pages leave gaps.
A paragraph is indented **except** when it opens a chapter, follows a heading or
a scene break, or is continued from the previous page — those four are the real
rule, not exceptions to it.

- [x] Add `firstLineIndentPx` to `BlockStyle`; map it to `TextIndent` in
      `readerTextStyle` so both sides apply it.
- [x] Add `Indentation.shouldIndent(blocks, index, startChar)` in `:core`, so
      pagination and drawing ask rather than each decide.
- [x] Paragraph spacing above drops to zero; heading spacing stays.
- [x] Test: indented and unindented paragraphs measure as they draw; a
      continuation is never indented; the first paragraph after a heading is not.

### Task 2: A raised initial at a chapter opening

A true dropped cap — a letter set into three lines of text that wrap around it —
needs custom layout Compose does not provide, and would break the line model
pagination depends on. A raised initial is the achievable half of the same
convention and is what this builds.

- [ ] `ReaderText.annotated(...)` builds the `AnnotatedString` used by both
      measuring and drawing, so the initial cannot change one and not the other.
- [ ] `ComposeTextMeasurer` measures the annotated string.
- [ ] Test: the initial changes line breaking identically on both sides.

### Task 3: A measure worth reading

Lines currently run the full width of the screen. Around 66 characters is the
readability optimum; beyond it the eye loses the start of the next line.

- [x] Cap the text column and centre it, applied to the pagination viewport and
      the drawn column from one value.
- [x] Test: the capped width is what pagination measures against.

### Task 4: No stranded lines

A single line of a paragraph left at the foot of a page, or carried alone to the
top of the next, is the thing typesetters remove last and readers notice first.
Heading orphans are already handled; paragraphs are not.

- [x] Push a trailing fragment of fewer than two lines to the next page.
- [x] Test: conservation still holds — every character exactly once, in order.

### Task 5: A chapter opening that looks like one

- [ ] Space above the chapter title proportional to the page, not a fixed dp.
- [ ] The opening words set in spaced capitals.
- [ ] Test: the opening treatment is measured, and the header inset the
      paginator budgets matches what is drawn.
