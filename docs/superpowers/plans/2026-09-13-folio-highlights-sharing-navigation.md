# Highlights, Sharing and Navigation — Implementation Plan

> Gate on `./scripts/check.sh` before every commit. Never commit red.

**Goal:** make a saved passage a real thing a reader chooses, make Share actually
share, and make Back behave the way Android readers expect.

**Architecture:** the app has no Compose UI test dependency, and this plan does not
add one. Every decision here lands in a pure function that JUnit can reach —
selection arithmetic and highlight ranges in `:core`, the back-stack reducer and the
share intents in `:app` — and the composables stay thin enough to be obviously
correct. That is the same split `ReaderStateTest` and `BookDetailsStateTest` already
use.

**Spec:** `docs/superpowers/specs/2026-09-11-folio-android-design.md`

## Global Constraints

- Pinned versions unchanged. No new dependencies, and no Compose UI test artifact.
- **No network.** `INTERNET` stays removed; `NoNetworkPermissionTest` holds the line.
  The support link hands a URL to the browser with `ACTION_VIEW`, which needs no
  permission and moves no data of the reader's anywhere.
- Nothing about the reader leaves the device unless the reader taps Share and picks a
  destination themselves.
- Measurement and rendering must agree: `MeasureMatchesRenderTest` is still the gate.
  A highlight is a background span, which cannot move a line break — Task 7 proves it
  rather than assuming it.
- Reading position is character-based and must survive every change here.
- **Departure from the handoff.** The handoff draws a share sheet with four
  destination glyphs that look like apps. Task 4 replaces them with the four things
  that actually happen. Recorded so it reads as a decision, not drift.

---

### Task 1: Justified by default

The reader asked for justification to be the default. Changing the Kotlin default
only reaches new installs; the stored row on an existing install keeps `false`, which
nobody ever chose — it was the old default.

**Files:** `data/Entities.kt`, `data/FolioDatabase.kt`, `test/data/MigrationTest.kt`

- [x] `AppSettingsEntity.readerJustify` defaults to `true`.
- [x] `MIGRATION_3_4` sets `readerJustify = 1` on the existing settings row, database
      version 4. Justified: the stored `false` is an old default, not a choice.
- [x] Test: a version-3 database with `readerJustify = 0` opens at version 4 with it
      set, and the rest of the settings row is untouched.

### Task 2: The Library header loses its duplicate buttons

Bookmarks and Settings sit in the pill nav at the bottom of the same screen. The two
circular buttons in the header go to the same two places.

**Files:** `ui/library/LibraryScreen.kt`, `ui/nav/FolioRoot.kt`

- [x] Remove both `IconButtonBox` calls, the `IconButtonBox` composable, and the
      `onOpenBookmarks` / `onOpenSettings` parameters from `LibraryScreen`.
- [x] Drop the now-dead arguments at the call site in `FolioRoot`.
- [x] The header keeps the greeting and the subtitle, which is all it was for.

### Task 3: Back goes back, and only the Library asks to leave

Back currently closes the app from wherever the reader is standing.

**Files:** `ui/nav/FolioBack.kt` (create), `ui/nav/FolioRoot.kt`,
`test/ui/nav/FolioBackTest.kt` (create)

- [x] `NavSnapshot(readingBookId, readingOriginal, openBookId, habitScreen,
      destination)` and `fun back(snapshot: NavSnapshot): BackAction`, where
      `BackAction` is `Pop(next: NavSnapshot)` or `ConfirmExit`. Pure, no Compose.
- [x] Order, deepest first: the original-PDF view, the Reader, Book Details, a habit
      screen (Milestones and Level return to Streak, Streak closes), a non-Library
      destination, then the Library — which is the only `ConfirmExit`.
- [x] `FolioRoot` holds one `BackHandler` that applies `back(...)`, and an
      `AlertDialog` for the exit confirmation that calls `Activity.finish()`.
- [x] Test: from every state, back lands where the table says; only the Library asks;
      leaving the Reader keeps `openBookId` so Back returns to Book Details, not past
      it.

### Task 4: Sharing that actually shares

`onShare` and `onSaveImage` both close the sheet and do nothing. The four
destinations are generic glyphs that hand off nowhere.

**Files:** `share/Sharing.kt` (create), `res/xml/file_paths.xml` (create),
`AndroidManifest.xml`, `ui/share/ShareSheet.kt`,
`test/share/SharingTest.kt` (create)

- [x] `ShareIntents.text(...)`, `.image(uri, caption)`, `.view(url)` return
      `Intent`s and nothing else, so they can be asserted without a device.
- [x] `Sharing.writeCard(context, bitmap): Uri` writes a PNG under
      `cacheDir/shares/` and returns a `FileProvider` uri; authority
      `${applicationId}.shares`, `grantUriPermissions`, not exported.
- [x] The card is captured with `rememberGraphicsLayer()` and `toImageBitmap()` —
      the composable already on screen, not a second description of it.
- [x] Destinations become the four things that happen: **Share image**, **Share
      text**, **Copy**, **Save**. Save uses `MediaStore` on API 29+ and falls back to
      the chooser below it, because `WRITE_EXTERNAL_STORAGE` is not worth asking for.
- [x] Test: the text intent is `ACTION_SEND`/`text/plain` and carries the quote, the
      book and no reader identity; the image intent carries `FLAG_GRANT_READ_URI_
      PERMISSION`; `view` produces `ACTION_VIEW` with the exact url.

### Task 5: Share where it belongs

**Files:** `ui/reader/ReaderScreen.kt`, `ui/bookmarks/BookmarksScreen.kt`,
`ui/details/BookDetailsScreen.kt`, `ui/nav/FolioRoot.kt`

- [ ] Reader chrome gains a Share action beside Bookmark; it shares the current page,
      or the selection when there is one (Task 6).
- [ ] Each bookmark row gains Share and Remove. `removeBookmark` already exists.
- [ ] Book Details gains Share — title, author, and nothing else.
- [ ] Every one of them routes through the same `ShareCard`/`ShareSheet` path, so
      there is one description of what a shared thing looks like.

### Task 6: Select a passage

**Files:** `core/reading/Selection.kt` (create),
`core/src/test/.../reading/SelectionTest.kt` (create), `ui/reader/ReaderScreen.kt`

- [x] `TextAnchor(blockIndex, charOffset)`, `TextSpan(start, end)` normalised so
      `start <= end` however the reader dragged.
- [x] `WordBoundary.expand(text, offset): IntRange` — long-press takes the word, not
      the character, because nobody aims at a character.
- [x] `Selection.portionOf(span, blockIndex, sliceStart, sliceEnd): IntRange?` maps a
      selection onto the slice of a block a page actually draws, returning null when
      they do not overlap. This is the one piece of arithmetic that has to be right:
      a page draws part of a block, and a selection spans whole ones.
- [x] `Selection.textOf(blockTexts, span): String` rebuilds the selected text across
      blocks, joined the way paragraphs join.
- [x] Reader: long-press starts a selection, drag extends it, release shows an action
      bar. Page-turn gestures are disabled while a selection is live, so a drag
      extends the selection instead of turning the page.
- [x] Test: a span inside one block, a span across three, a span whose block is only
      half-drawn on this page, and a span that misses the page entirely.

### Task 7: Highlight it, and keep it

The `highlight` colour token exists in all five themes and is used nowhere. E-ink's
is neutral grey, which is what keeps that theme black and white.

**Files:** `data/Entities.kt`, `data/FolioDatabase.kt`, `data/BookRepository.kt`,
`ui/reader/ReaderTypography.kt`, `ui/reader/ReaderScreen.kt`,
`test/data/MigrationTest.kt`, `test/ui/reader/MeasureMatchesRenderTest.kt`

- [x] `BookmarkEntity` gains `endBlockIndex` and `endCharOffset`. A plain position
      bookmark has them equal to its start, so one table holds both and a bookmark is
      simply a highlight of no width.
- [x] `MIGRATION_4_5` adds the columns, seeded from the existing start values.
- [x] `readerText(text, style, marks: List<IntRange> = emptyList())` adds a
      background `SpanStyle` per mark. Defaulted so `ComposeTextMeasurer` keeps
      passing none — and it must, because the measurer has no highlights to know
      about.
- [x] Test: **a highlighted paragraph measures identically to the same paragraph
      unhighlighted.** A background span cannot move a line break; this is the test
      that proves it stays that way, and it belongs next to the drift it prevents.
- [ ] Test: a saved highlight reappears on the page it was made on, and the bookmark
      list shows its text rather than the page it happened to be on.

### Task 8: Support Folio

**Files:** `ui/settings/SettingsScreen.kt`, `ui/nav/FolioRoot.kt`,
`ui/FolioStrings.kt`

- [ ] An About row: **Show your support** → `https://razorpay.me/@gajanansr` via
      `ShareIntents.view`, opened in the browser.
- [ ] A footer line under the last group: *Made with love by Gajanan.*
- [ ] Test: the settings screen's support url is exactly that string — a donation
      link typed wrong sends money to a stranger.
