# Reader Interaction — Implementation Plan

> Gate on `./scripts/check.sh` before every commit. Never commit red.

**Goal:** make the Reader feel like it belongs to the hand holding it. Two
complaints, both in the gesture layer: *"the text selection doesnt work well like
it works on other apps"*, and *"scroll down on right side should adjust the
brightness"*.

**Architecture:** there is no Compose UI test dependency and this plan does not add
one. Every decision that can be got wrong is pushed into a pure function JUnit can
reach — which end of a selection a drag moves, which handle a finger grabbed, which
block a touch landed in, which of four gestures a drag is, and what a drag does to
the screen's brightness. The composables stay thin enough to read as obviously
correct. That is the split `SelectionTest` and `ReaderStateTest` already use.

The selection *model* is not being replaced. `TextAnchor`, `TextSpan`,
`WordBoundary` and `Selection.portionOf` stay exactly as they are — `portionOf` in
particular encodes page-slice arithmetic that is right and was not cheap to get
right. Everything below is added around it.

**Spec:** `docs/superpowers/specs/2026-09-11-folio-android-design.md`

## Global Constraints

- Pinned versions unchanged. No new dependencies, and no Compose UI test artifact.
- **No network.** `INTERNET` stays removed; `NoNetworkPermissionTest` holds the line.
- `:core` is JUnit 5 (`assertTrue(condition, message)`), `:app` is JUnit 4
  (`assertTrue(message, condition)`). Backwards compiles and asserts nothing.
- **No Room migration.** Another agent owns the schema this round. Task 6 decides
  against persisting brightness on its own merits; the absence of a migration is a
  consequence, not the reason.
- **Files owned here:** `ui/reader/ReaderScreen.kt`, `ui/reader/PageTextMap.kt`,
  `core/reading/Selection.kt`, and new files. `ReaderHost.kt` pagination and
  `ReaderState.showsChapterHeader` belong to another agent; edits to `ReaderHost.kt`
  are limited to wiring new callbacks, and `showsChapterHeader` is not touched.
- Measurement and rendering must still agree: `MeasureMatchesRenderTest` is the gate.
  Handles and carets are drawn in an overlay, not in the text, so they change no
  metric and cannot move a line break.

---

### Task 1: Either end of a selection can move

Today the anchor is fixed at the long-press point and only the far end follows the
finger, so a selection can never be extended backwards from its start. Grabbing a
handle has to be able to pin *either* end.

**Files:** `core/reading/Selection.kt`, `core/reading/SelectionTest.kt`

- [x] `SelectionEdge { START, END }` — which end a drag is holding.
- [x] `Selection.movingEdge(span, edge, to)` returns the new span *and* which edge is
      now held. Dragging one handle past the other swaps the roles rather than
      stopping dead, which is what Compose's own handles do.
- [x] A drag that would collapse the span to nothing is refused, span unchanged. An
      empty span means `hasSelection` is false, which would make the handles and the
      action bar vanish mid-drag with the finger still down.
- [x] Test: each edge moves independently; the anchor never moves; crossing swaps;
      collapse is refused; a normalised span comes back however it was dragged.

### Task 2: Sweep by word, adjust by character

Android's long-press drag takes whole words as it sweeps and the handles then tune to
the character. One granularity for both is what makes selection feel either jumpy or
imprecise depending on which you pick.

**Files:** `core/reading/Selection.kt`, `core/reading/SelectionTest.kt`

- [x] `Selection.snappedToWord(blockTexts, at, towardsEnd)` — pushes an anchor out to
      the near edge of the word it is inside, in the direction of travel. Built on
      the existing `WordBoundary`, not a second word rule.
- [x] An anchor in whitespace or on a boundary is left where it is: snapping there
      would swallow the next word before the finger reached it.
- [x] Test: forward snaps to the word's end, backward to its start, whitespace is
      left alone, an offset past the end of a block is clamped.

### Task 3: A caret-accurate hit test

`PageTextMap.anchorAt` falls back to the block whose *centre* is nearest when a touch
lands between paragraphs. That is the wrong measure: a tall paragraph's centre can be
nearer than a short heading the finger is almost touching, so a drag through the gap
jumps to the wrong block.

**Files:** `ui/reader/SelectionGeometry.kt` (new), `ui/reader/PageTextMap.kt`,
`test/ui/reader/SelectionGeometryTest.kt` (new)

- [x] `PageHitTest.blockFor(bands, y)` — containment first, then nearest *edge*, not
      nearest centre. Pure over (top, bottom) pairs so it is testable without a
      `TextLayoutResult`, which cannot be built off a device.
- [x] `PageTextMap.anchorAt` delegates to it.
- [x] `PageTextMap.caretAt(anchor, edge)` — the inverse: where on screen a given
      character sits, as a `CaretRect`. Needed to draw the handles at all. Uses the
      character's bounding box rather than a cursor rect, so the end caret stays on
      the line it ends rather than jumping to the start of the next one at a soft
      wrap.
- [x] Test: containment wins; a point in a gap takes the nearer edge and not the
      nearer centre; a point above or below every block clamps to the first or last.

### Task 4: Drag handles

The single most important part. Once the finger lifts today the selection is frozen;
every other Android app gives you two handles you can grab and move.

**Files:** `ui/reader/SelectionGeometry.kt`, `ui/reader/ReaderScreen.kt`,
`ui/reader/ReaderState.kt`, `test/ui/reader/SelectionGeometryTest.kt`,
`test/ui/reader/ReaderStateTest.kt`

- [x] `SelectionHandles.grabbed(down, start, end, radiusPx)` — which handle a press
      took, or none. The grab area is centred on the teardrop drawn *below* the caret
      and is far larger than the drawing; the drawn handle is the affordance, the
      touch target is what has to be thumb-sized. When both are in range the nearer
      wins, so the two handles on a one-word selection are still separable.
- [x] `ReaderState.selectionEdge` records which handle is being held, and is null
      when none is. `ReaderTransitions.handleGrabbed` / `handleMoved` / `handleReleased`.
- [x] `ReaderScreen` draws two handles and a caret bar at each end, inside an overlay
      that is not part of the text, and gives them their own innermost pointer input
      so a grab beats the page-turn and long-press detectors rather than racing them.
- [x] A handle whose anchor is on a block this page does not draw is simply not drawn
      — a selection can run off the page, and a handle pinned to the margin would be
      a lie about where the passage ends.
- [x] Test: the hit test; the state transitions; grabbing the start handle leaves the
      end where it was.

### Task 5: A selection that survives being looked at

*"Selection is lost on any stray tap, with no way back."* Three causes, three fixes.

**Files:** `ui/reader/ReaderState.kt`, `ui/reader/ReaderScreen.kt`,
`test/ui/reader/ReaderStateTest.kt`

- [x] A tap *inside* the selected passage is swallowed and the selection kept. With
      handles on screen the passage is exactly where the thumb goes, and clearing on
      that tap is the specific way the old behaviour lost work.
- [x] A tap outside clears, as it does everywhere else on Android.
- [x] Haptic feedback each time a drag crosses onto a new character, which is what
      Android gives you and the only "which character am I on" signal that works
      without a magnifier. See Task 4 notes for why not a magnifier.
- [x] The action bar moves out of the way: it sits at the top when the selection is
      in the lower half of the page and at the bottom otherwise, so it never covers
      the words it is offering to act on.
- [x] Test: an inside tap keeps, an outside tap clears, the bar's side is chosen from
      the selection's position.

### Task 6: Brightness on the right edge

**Files:** `ui/reader/ScreenBrightness.kt` (new), `ui/reader/ReaderBrightness.kt`
(new), `ui/reader/ReaderScreen.kt`, `test/ui/reader/ScreenBrightnessTest.kt` (new)

- [x] `ScreenBrightness.dragged(current, dragPx, trackPx)` — down dims, up brightens,
      one sweep of the reading column covers the range.
- [x] **Floor at 0.05, never 0.** A reading app that can be dragged to a black screen
      has taken the device away from its owner: the gesture that would undo it is
      invisible, and so is the back button. 5% is legible in a dark room.
- [x] `WindowManager.LayoutParams.screenBrightness` on the Reader's own window only.
      The system setting is never written — Quire has no business changing the
      brightness of a device it is one app on, and doing so would need
      `WRITE_SETTINGS`.
- [x] **Not persisted, and this is a decision rather than an omission.** Brightness is
      environmental, not preferential: the value that is right in bed at midnight is
      wrong on a train at noon, so a restored value is wrong most of the times it is
      restored — and its failure mode is the worst one available, opening the Reader
      in daylight onto a screen dimmed for a dark room. It holds for the session, so
      it survives page turns, chapter loads and rotation, and it is released when the
      reader leaves. No settings column, and therefore no migration.
- [x] `BRIGHTNESS_OVERRIDE_NONE` is restored on dispose, by whatever route the reader
      leaves. Until the first drag Quire does not touch brightness at all, so a reader
      who never uses this gesture keeps adaptive brightness exactly as it was.
- [x] Test: down dims and up brightens; the floor and the ceiling hold; a drag from
      the floor still comes back up; the seed from a system value is clamped into
      range and survives a nonsense reading.

### Task 7: Four gestures on one surface, disambiguated on purpose

A tap on the right third turns the page, a horizontal drag turns the page, a long
press starts a selection, and now a vertical drag on the right edge changes
brightness. Three stacked `pointerInput` modifiers that each guess is how you get a
reader whose page turns when they meant to dim it.

**Files:** `ui/reader/ReaderGestures.kt` (new), `ui/reader/ReaderScreen.kt`,
`test/ui/reader/ReaderGesturesTest.kt` (new)

- [x] `ReaderGestures.intentOf(downX, widthPx, dx, dy, slopPx)` → `NONE`, `PAGE_TURN`
      or `BRIGHTNESS`. One pure decision, made once when the drag first crosses touch
      slop and then held for the rest of the gesture: re-deciding every frame is what
      makes a diagonal drag flicker between turning the page and dimming it.
- [x] Ties go to the page turn. It is the commoner intent and the recoverable one.
- [x] Tap, page-turn drag and brightness drag collapse into a single
      `awaitEachGesture` loop, so they cannot race each other.
- [x] The long press keeps its own detector, and the drag loop consumes as soon as it
      has classified anything — that is what keeps a page-turn or brightness drag from
      also starting a selection.

      **This bullet was written wrong and the code followed it.** It said the
      long-press detector self-cancels past touch slop. It does not:
      `awaitLongPressOrCancellation` in foundation 1.12.1 watches only consumption,
      out-of-bounds and pointer-up, and its timer is wall-clock, so it fires under a
      finger that has been sweeping for half a second. Nothing consumed a page-turn
      drag, so a slow swipe popped a selection mid-swipe and lost the page turn.
      Consumption is the cancellation the detector *does* listen for; a `dragging`
      flag says the same thing a second way. Recorded because the wrong version of
      this sentence was in three comments as well, and read as a mechanism.
- [x] Handle drags are innermost and consume, so they win over all of the above.
- [x] Test: the axis decision at the boundaries, the edge strip, the slop threshold,
      and that a drag beginning off the right edge never dims.

### Task 8: Quiet feedback

- [x] A thin level bar on the right edge while dragging brightness, which fades once
      the finger lifts. No number, no dialog, nothing that survives the gesture.
- [x] Test: the fade is time-driven and has nothing to assert; the level it draws is
      `ScreenBrightness`'s value, already tested.

### Task 9: The hit test was aiming a status bar too high (found while building)

Not planned, and the largest single cause of the complaint. A block registers
`positionInRoot()`; a touch arrives in the coordinates of the composable that caught
it. `QuireRoot` wraps the whole app in a `statusBarsPadding()`, so the Reader's
surface begins a status bar below the root and the two spaces were never the same.
Every touch was compared against text positions 70–140px taller than itself, so a
long press took a word one to three lines above the finger.

Handles cannot rescue a hit test that is aiming at the wrong line, which is why this
belongs at the front of the list rather than at the end of it.

**Files:** `ui/reader/PageTextMap.kt`, `ui/reader/ReaderScreen.kt`

- [x] `PageTextMap.origin` holds where the page sits in the root, set from the Box
      that catches the touches, and everything the map answers is stated in the page's
      own coordinates.
- [x] Setting it bumps `revision`, so a selection made before the origin was known is
      re-measured rather than left drawing its handles in the wrong place.
- [x] No test: there is no decision here to test, only two coordinate spaces that had
      to be made one. It is first on the list of things to check with a finger.
