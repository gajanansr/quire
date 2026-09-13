# A Mark of Its Own, and Passages Worth Sharing — Implementation Plan

> Gate on `./scripts/check.sh` before every commit. Never commit red.

**Goal:** give Folio a logo that means something, and make a chosen passage leave the
app as a picture the reader has styled rather than a line of plain text.

**Architecture:** the share card is already one composable rendered into a
`GraphicsLayer` and captured — so making it themeable means giving that one composable
a palette, not drawing a second version of it. The palettes come from `QuireColors.of`,
the same five the app already ships, so the card cannot drift from the product's own
colours.

**Spec:** `docs/superpowers/specs/2026-09-11-folio-android-design.md`

## Global Constraints

- Pinned versions unchanged. No new dependencies.
- `INTERNET` stays removed. Nothing leaves the device except through a share the
  reader starts and a destination they pick.
- The launcher palette stays as it is. `IcLauncherColorTest` ties those two literals
  to the same OKLCH tokens the app's accent and page colours come from; the mark is
  what needs work, not the colour.
- Measurement and rendering still agree: `MeasureMatchesRenderTest` is the gate.

---

### Task 1: A mark of its own

The icon is a serif "F" on a blue ground. At 48dp that is any app beginning with F,
and it says nothing about what this one does.

A **folio** is a leaf of a book — a single sheet folded once, which is where both the
word and the book format come from. Two leaves meeting at a fold is therefore the
literal picture of the name, reads instantly as an open book at launcher size, and is
not the stack-with-a-spine every other reading app draws.

**Files:** `res/drawable/ic_launcher_foreground.xml`,
`res/mipmap-anydpi-v26/ic_launcher.xml`,
`test/ui/theme/LauncherMarkTest.kt` (create)

- [x] Two leaf shapes mirrored about a fold at x=54, drawn in the page colour on the
      accent ground. The right leaf at full strength, the left a little darker — the
      gutter shadow, which is what makes it read as paper rather than a symbol.
- [x] Test: **every coordinate in the mark lies inside the 66dp safe circle.** An
      adaptive icon is masked differently by every launcher, and a mark that strays
      outside gets its corner shaved on somebody's phone and nowhere else. Bounding by
      control points is conservative and exactly right: a Bézier never leaves the
      convex hull of its own points.
- [x] Test: the monochrome layer is declared, so Android 13+ themed icons work.

### Task 2: A chosen passage shares as a card

Selecting a passage and tapping Share fires a plain-text intent. The share sheet with
the card — the thing a reader would actually post — is reachable only from the
bookmark list, which is the wrong end of the journey.

**Files:** `ui/reader/ReaderHost.kt`, `ui/reader/ReaderState.kt`,
`ui/nav/QuireRoot.kt`

- [x] `ReaderHost` gains `onShareQuote: (ShareCard.Quote) -> Unit`; `QuireRoot`
      supplies it by setting the same `shareCard` state the bookmark list sets.
- [x] Both the selection's Share and the chrome's Share route through it, so the
      reader gets the same sheet whether they chose words or shared the page.
- [x] `ReaderState` carries the author, since a card names one and the Reader did not
      have it.

### Task 3: The card's look can be switched

**Files:** `ui/share/ShareCardStyle.kt` (create), `ui/share/ShareSheet.kt`,
`test/ui/share/ShareCardStyleTest.kt` (create)

- [x] `ShareCardStyle` — Cover, plus Folio's five palettes. Cover keeps the book's own
      gradient; the rest resolve through `QuireColors.of`, so the card is never a
      sixth palette invented for one screen.
- [x] A row of swatches under the card; picking one restyles it, and the capture takes
      whatever is on screen, so the picture and the preview cannot disagree.
- [x] Test: every style resolves to a background and an ink colour that clear
      **4.5:1** contrast. A quote nobody can read is not a share card, and E-ink's
      palette in particular is near-black on near-white and must stay that way.

### Task 4: The card invites the reader

**Files:** `ui/share/ShareSheet.kt`, `ui/QuireStrings.kt`

- [x] The card's footer becomes the wordmark plus a call to action, in one constant so
      it can become a real store link the day there is one to point at.
- [x] The same line travels on the text share, so a passage sent as words and a
      passage sent as a picture say the same thing.
