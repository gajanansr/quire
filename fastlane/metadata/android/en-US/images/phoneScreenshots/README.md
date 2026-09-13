# Phone screenshots — what to capture

**These are the one piece of the store listing that cannot be generated.** Play
requires them, they need a running app on a real screen, and the agent that prepared
this release was not allowed near the emulator. Everything else in `fastlane/` is
done; this directory is the gap.

## What Play requires

| | |
|---|---|
| Minimum | **2** phone screenshots. The listing cannot be submitted with fewer |
| Maximum | 8 |
| Recommended | **4–6**. Play shows the first 3–4 without scrolling, so those carry the listing |
| Format | PNG or JPEG, no transparency |
| Aspect ratio | Between 16:9 and 9:16 |
| Each side | Between 320 px and 3840 px, and the long side no more than twice the short side |
| Practical target | **1080 × 2400** portrait — a normal modern phone screenshot, straight off the device, no frame, no marketing text, no drop shadow |

Play also asks for a 7-inch and a 10-inch tablet set. They are **optional** — without
them the listing publishes, but Play marks the app as "not designed for tablets" and
will not feature it on large screens. Skip them for the first release.

## Naming

fastlane sorts by filename, and Play shows them in that order. Use a numeric prefix:

```
1_en-US_library.png
2_en-US_reader.png
3_en-US_themes.png
4_en-US_typography.png
5_en-US_highlight.png
6_en-US_streak.png
```

## The six to capture, in order

Order matters more than count: the first two are what most people will ever see.

1. **The Library, with three or four real books in it.** The greeting header, covers,
   the Continue Reading card, the habit strip. This is the shelf — it has to look like
   somebody's actual books, not one placeholder.
2. **The Reader, mid-chapter, Paper theme.** A full page of justified prose. This is
   the whole product; give it a paragraph that reads well and no UI chrome on top.
3. **The theme picker open, or the same page in Night or Sepia.** The five-theme
   choice is a real differentiator and it photographs well.
4. **The typography sheet open over a page**, showing the size and typeface controls.
5. **A highlighted passage with the action bar up** (Highlight · Share · Copy), or the
   share card being composed.
6. **The streak screen**, with a week filled in and a goal set.

## Capturing them

There is already a helper in the repo:

```bash
source scripts/env.sh
./scripts/dev.sh                 # builds, installs and launches
./scripts/shot.sh library        # writes /tmp/folio-library.png
```

Then copy each one in with its numbered name:

```bash
cp /tmp/folio-library.png fastlane/metadata/android/en-US/images/phoneScreenshots/1_en-US_library.png
```

Check them before uploading:

```bash
./scripts/check-listing.sh
```

## Two things that get a listing rejected

- **No added text, borders, device frames or badges.** Play's policy is that a
  screenshot shows the app. A mocked-up phone frame with a tagline across it is a
  promotional graphic, and it is rejected as a screenshot.
- **No placeholder or debug content.** "Test Book 1", a lorem-ipsum page or a visible
  debug overlay will be read as an unfinished app. Import real books first.
