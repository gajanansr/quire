# The Share Card, as a Picture — Implementation Plan

> Gate on `./scripts/check.sh` before every commit. Never commit red.

**Goal:** make "share as a picture" actually send a picture, make that picture look
composed rather than magnified, and set the passage in the face the reader chose.

**Reported from a real phone:** *"on sharing the image is not being shared, the text is
being shared. that image being formed is too much zoomed and not at all responsive.
font size should be less and same font that is selected while reading."*

Three separate faults behind one sentence.

**Architecture:** the card is one composable captured into a `GraphicsLayer`, so all
three fixes land in one drawing of it — there is never a second description of the card
to keep in step. Two of them are arithmetic (`QuoteFit`, `CardMetrics`), which means
they are pure objects with plain JUnit tests and no Compose UI test dependency, which
is the pattern `ShareCardStyle` already set.

**Spec:** `docs/superpowers/specs/2026-09-11-folio-android-design.md`

## Global Constraints

- Pinned versions unchanged. No new dependencies. No Compose UI test dependency.
- `INTERNET` stays removed. A shared card carries nothing identifying the reader;
  `SharingTest` asserts the text intent's extras *exactly* and that assertion stays.
- `:core` is JUnit 5 (message second), `:app` is JUnit 4 (message first).
- `ShareCardStyleTest` keeps passing — the WCAG contrast check that caught a real
  4.24:1 failure is not to be touched.
- No `adb`, no emulator. Everything asserted here is asserted on the JVM; the human
  holds the only device and verifies the share itself.
- Edits to `ReaderHost.kt` / `QuireRoot.kt` stay minimal — other agents are in both.

---

### Task 1: An image share is unambiguously an image share

`ShareIntents.image` builds `ACTION_SEND` with `type = "image/png"`, `EXTRA_STREAM`,
**and** `EXTRA_TEXT` whenever there is a caption. That is the bug the reader hit.

An `ACTION_SEND` carrying both is ambiguous by construction, and the tie is broken by
the receiver, not by us. Android's own Sharesheet builds its preview from
`EXTRA_TEXT` first and only falls back to `EXTRA_STREAM`, so the reader is shown a
wall of text where they expected their card — which is exactly what "the text is being
shared" looks like from the outside. Past the chooser, receivers that treat
`text/*` and `image/*` as one `ACTION_SEND` handler commonly read `EXTRA_TEXT` and
never look at the stream. The image is present in the envelope and silently dropped.

The rule, and `SharingTest` states it: **an image intent never carries `EXTRA_TEXT`.**
The uri travels in `EXTRA_STREAM` *and* in an explicit `ClipData`, because receivers
read one or the other and the caller does not get to know which. The caption, when
there is one, travels only as that `ClipData` item's text — invisible to the preview
and to the `EXTRA_TEXT` readers that drop images, available to anything that handles a
uri and a caption together.

Setting `ClipData` ourselves is also what stops the platform putting the caption back:
`Intent.migrateExtraStreamToClipData` synthesises a `ClipData` from `EXTRA_STREAM` and
`EXTRA_TEXT` on the way out of the process, and it bails the moment a `ClipData` is
already there.

**Files:** `share/Sharing.kt`, `test/share/SharingTest.kt`

- [x] Test (red): an image intent with a caption has no `EXTRA_TEXT`.
- [x] Test (red): the uri is in `EXTRA_STREAM` and in `ClipData` item 0, and the clip
      describes itself as `image/png`.
- [x] Test (red): a caption rides on `ClipData` item 0's text and nowhere else.
- [x] Test: a blank caption produces a clip with a uri and no text.
- [x] Test: the read grant is on the inner intent and on the chooser (kept).
- [x] Confirm the `FileProvider` authority still matches the manifest after the rename
      to `app.quire.android` — the existing manifest-vs-code test covers it; check it
      is actually exercising the renamed id.

### Task 2: The card is drawn in proportions, not in fixed dp

Every measurement on the card is an absolute dp or sp: `padding(18.dp)`, `labelSmall`
at 11sp, and `QuoteFit`'s four tiers at 23/19/16/13sp. They were tuned by eye against
the sheet's preview, which is ~230dp wide. The exported PNG is 604px on the same
phone and is then viewed **full-bleed**, about 1.8x the size it was tuned at. Nothing
in the card knows that, so the type arrives magnified — "too much zoomed" — and
nothing adapts when the card is drawn at any other width, which is "not at all
responsive."

Worse, the old tiers do not fit their own budgets even at the reference width. At
23sp on a 230dp card the passage gets about **17 characters to a line**; tier 2's 260
characters need 12.4 lines and are given 11. The largest tier is simultaneously too
big to read as a quotation and too big to hold the passage it is for.

So sizes stop being absolute. A tier names a **measure** — the characters it wants on
a line — and the size falls out of the card's own width. The card is then a true scale
model of itself at any size, and the preview cannot disagree with the export.

**Files:** `ui/share/CardMetrics.kt` (create), `ui/share/QuoteFit.kt`,
`test/ui/share/CardMetricsTest.kt` (create), `test/ui/share/QuoteFitTest.kt`

- [x] `CardMetrics.of(widthDp)` — margin, label size, wordmark size, content width and
      the height the passage may occupy, each a fraction of the card's width. The
      fractions are chosen so the frame is unchanged at today's ~230dp preview; only
      the quote shrinks, and now everything scales.
- [x] Test: every measurement is proportional — a card at 2x the width returns 2x
      every measurement.
- [x] `QuoteFit` tiers carry a measure (22/26/32/38/44 characters) rather than an sp,
      and `of(passage, cardWidthDp)` derives both size and line budget from the card.
- [x] Test (red): **no tier sets a line shorter than 20 characters.** This is the
      "too zoomed" assertion — the old largest tier gives 17.
- [x] Test (red): **every tier's longest passage fits the lines that tier is given.**
      Three of the four old tiers fail this.
- [x] Test: sizes are proportional to card width — the same passage on a card twice as
      wide is set twice as large.
- [x] Every existing `QuoteFitTest` assertion still holds, unchanged.

### Task 3: A six-word passage and a six-hundred-character one both look deliberate

The tiers alone do not finish it. `QuoteCard` lays out with `SpaceBetween` and gives
the quote `weight(1f, fill = false)`, so a short passage hangs directly under the
title with all the air below it, and a long one presses on the footer. The card has to
read as composed at both ends.

**Files:** `ui/share/ShareSheet.kt`

- [x] The card measures itself with `BoxWithConstraints` and takes its margins, label
      sizes and quote size from `CardMetrics`/`QuoteFit` at that width.
- [x] The passage sits in a weighted box that is **optically centred** between the
      header and the footer. A short quote is then centred in its own field with air
      on both sides; a long one fills the field. Both are deliberate; neither is
      hanging.
- [x] The streak card takes the same proportional frame, so the two cards cannot drift
      apart.

### Task 4: The passage is set in the reader's own face

`ShareSheet` hardcodes `SourceSerif` in three places. The reader picks Serif, Lora,
Sans or System in the typography sheet, and a quote card is *their* passage — it
should be in the face they were reading it in.

The wordmark stays Source Serif deliberately. That is Quire's mark, not the reader's
text, and a card where the brand line changes typeface with a preference is a card
with no brand line.

The slant needs the same care: Source Serif ships a real italic, Lora and Work Sans do
not, and Compose fills the gap by shearing the upright. At card sizes a synthetic
oblique reads as a rendering fault, so the passage is only italic in the face that has
an italic.

**Files:** `ui/share/ShareSheet.kt`, `ui/share/QuoteFit.kt`,
`ui/reader/ReaderHost.kt`, `test/ui/share/QuoteFitTest.kt`

- [x] `ShareCard.Quote` carries the `ReaderFont` it was chosen in, defaulting to
      `SERIF` for the routes that have no reader — a book's description, a bookmark.
- [x] `ReaderHost.quoteOf` fills it from `state.preferences.font`. One line; nothing
      else in `ReaderHost` or `QuireRoot` moves.
- [x] Test: `QuoteFit.isItalic` is true only for the face that ships an italic.

### Task 5: Save still behaves

Save shares the capture path, so it has to be re-checked after Tasks 1–3, and
`saveToPictures` has a hole of its own: if `openOutputStream` returns null it returns
null having already inserted the `MediaStore` row, which leaves a zero-byte image in
the reader's gallery *and* sends them to the chooser as though nothing was written.

**Files:** `share/Sharing.kt`, `test/share/SharingTest.kt`

- [ ] A failed write deletes the row it inserted before falling back.
- [ ] Confirm the API-29 fallback still reaches `ShareIntents.image`, which now sends
      an image rather than a caption.

---

## Verification

`./scripts/check.sh` exits 0. No new dependency in any `build.gradle.kts`, no
`INTERNET` permission, no Compose UI test artifact. The share itself is verified on
the phone by the human — the plan cannot assert what a receiving app does with a
well-formed intent, only that the intent is well-formed.
