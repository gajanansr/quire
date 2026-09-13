# Google Play Release — Implementation Plan

> Gate on `./scripts/check.sh` before every commit. Never commit red.

**Goal:** everything needed to submit Folio to Google Play, produced here rather than
typed into a web form at midnight — a signed-bundle path, the listing copy, a real
privacy policy, the Data Safety answers question by question, the two graphics Play
demands, and one ordered checklist that names every value a human still has to supply.

**Architecture:** the release is mostly *content*, and content that lives only in a
Play Console text box is content nobody can review or diff. So all of it lands in the
repo: fastlane's standard `fastlane/metadata/android/en-US/` layout for the listing,
`docs/` for the policy and the checklist, and `scripts/` for the graphics, which are
generated from the same geometry and the same two colours as the launcher icon rather
than drawn once by hand and left to drift.

The links are the one part that reaches into the app, and they go where `SupportLink`
already lives — `share/Links.kt`, next to `share/Sharing.kt`. There is no website and
no hosted policy yet, so the constants are *blank on purpose* and the Settings row
that surfaces them appears only when a real URL has been filled in. A dead link in a
shipped app is worse than a missing row.

**Spec:** `docs/superpowers/specs/2026-09-11-folio-android-design.md`

## Global Constraints

- Pinned versions unchanged: Kotlin 2.3.21, AGP 9.4.0, compileSdk 37, targetSdk 36.
  **targetSdk stays 36** because Robolectric 4.16 emulates no higher. If Play's
  target-API rule ever demands 37, that is a conflict to report, not to fix by
  bumping.
- **No new dependencies.** Not Gradle ones, and not tooling ones either: the graphics
  script runs on the JDK 21 the build already requires, so it needs no ImageMagick, no
  Python imaging library, and nothing installed to regenerate a logo.
- **No `INTERNET` permission, ever.** `NoNetworkPermissionTest` holds the line and
  everything written here must be true against it — the privacy policy in particular
  makes a claim that this permission list is the proof of.
- **No secret enters git.** Not a keystore, not a password, not a Play service-account
  JSON. `.gitignore` covers them before the signing config that reads them exists.
- The debug build must keep working for someone who has never created a keystore.
  Missing release credentials degrade the *release* tasks, not the whole build.
- Every claim in the listing copy has to be findable in the code. Folio does not do
  OCR (that pipeline was removed — `PageRasterizer`'s own comment records it), has no
  sync, no accounts, and no way to delete a book. The copy says so by omission and
  never by implication.

---

### Task 1: Secrets cannot be committed

Done first, so the signing config in Task 2 is written into a repo that already
refuses to carry what it reads.

**Files:** `.gitignore`

- [x] Ignore `keystore.properties`, `*.jks`, `*.keystore`, `*.p12`, and
      `play-service-account*.json` / `fastlane/*.json`.
- [x] Ignore `*.hprof`. A 322 MB JVM heap dump (`java_pid90925.hprof`) is *already
      committed at HEAD*; remove it from the working tree and say plainly in the
      report that purging it from history is a separate, destructive decision.

### Task 2: A release build that can be signed, and a debug build that never needs to be

**Files:** `app/build.gradle.kts`, `app/proguard-rules.pro` (create)

- [x] `versionCode` / `versionName` become named values at the top of the android
      block, overridable with `-PfolioVersionCode` / `-PfolioVersionName` or the
      `FOLIO_VERSION_CODE` / `FOLIO_VERSION_NAME` environment variables, so a CI run
      can bump a build without a commit and a human can still read the default.
- [x] Signing credentials come from `keystore.properties` at the repo root if it
      exists, else from `FOLIO_KEYSTORE` / `FOLIO_KEYSTORE_PASSWORD` /
      `FOLIO_KEY_ALIAS` / `FOLIO_KEY_PASSWORD`. If neither supplies all four, the
      `release` signing config is simply **not created**.
- [x] `buildTypes.release`: not debuggable, signed with that config when it exists.
      `assembleRelease` / `bundleRelease` fail with an instruction naming the four
      values when it does not — loudly, at the release task, and nowhere else. A
      debug-signed release must never be producible by accident.
- [x] R8 off by default, behind `-PfolioMinify=true`. `proguard-rules.pro` is written
      anyway, with the keep rules Room, kotlinx.serialization, Compose and
      PdfBox-Android need, so enabling it later is one flag and a device test rather
      than a research project. The reasoning goes in `docs/release.md`, not in a
      commit message.
- [x] `./scripts/check.sh` is green, and `:app:bundleRelease` is proven to produce a
      signed `.aab` using a throwaway keystore created outside the repo and deleted
      afterwards.

### Task 3: One place for every external link

**Files:** `app/src/main/kotlin/app/folio/android/share/Links.kt` (create),
`app/src/main/kotlin/app/folio/android/ui/settings/SettingsScreen.kt`,
`app/src/main/kotlin/app/folio/android/ui/nav/FolioRoot.kt`,
`app/src/test/kotlin/app/folio/android/share/LinksTest.kt` (create)

- [x] `FolioLinks` holds `PRIVACY_POLICY`, `WEBSITE`, `SOURCE`, `CONTACT_EMAIL` — all
      blank, each marked `FILL IN` with what it is for — plus `SUPPORT`, which is
      `SupportLink.URL` and stays exactly as it is.
- [x] `FolioRelease.VERSION_NAME` lives beside them: the About row currently spells
      `"0.1.0"` inline, where nothing makes it follow `versionCode`.
- [x] Pure helpers a test can reach: `isSet`, `displayHost` (what the Settings row
      shows on the right), and `unset()` (the names still blank, so the checklist and
      a test agree on the list).
- [x] Settings grows a **Privacy** row in About. Tappable and opening the browser
      when a policy URL has been filled in; a plain, honest statement when it has
      not. Same for a Website row.
- [x] Tests (JUnit 4 — `assertTrue(message, condition)`): every non-blank link is
      `https://` or `mailto:`; `SUPPORT` is unchanged; `displayHost` strips scheme,
      `www.` and path; `unset()` names exactly the blanks; and `VERSION_NAME` equals
      the `versionName` actually declared in `app/build.gradle.kts`.

### Task 4: The privacy policy, and the Data Safety form answered in advance

**Files:** `docs/privacy-policy.md` (create), `docs/play-data-safety.md` (create)

- [x] The policy states what Folio collects (nothing), what it stores and where
      (this device, app-private storage), and lists **every** permission in the
      merged manifest with why it is there — including the four WorkManager ones,
      which look alarming in a list and are not.
- [x] It names the two moments data can move: the reader tapping Share, and the
      reader tapping the support link, both of which hand off to an app they chose.
- [x] It says Folio declares no `INTERNET` permission and that a test enforces it,
      because that is a stronger claim than a promise and Folio can actually make it.
- [x] `docs/play-data-safety.md` answers the Data Safety form question by question,
      in the Console's own order, with the exact radio button to pick.
- [x] Both record that Play requires the policy at a **public URL**, and that the
      same URL goes in three places: the store listing, the Data Safety section, and
      `FolioLinks.PRIVACY_POLICY`.

### Task 5: Listing copy, in fastlane's layout

**Files:** `fastlane/metadata/android/en-US/{title,short_description,full_description}.txt`,
`fastlane/metadata/android/en-US/changelogs/1.txt`,
`fastlane/metadata/android/en-US/images/phoneScreenshots/README.md`,
`scripts/check-listing.sh` (create)

- [x] Title ≤ 30, short description ≤ 80, full description ≤ 4000. Copy written for
      a quiet, private, offline reader — the pitch is that the books never leave the
      device, and every feature named is one that exists.
- [x] `changelogs/1.txt` matches `versionCode` 1.
- [x] The screenshots directory carries a README saying how many, what size, which
      screens, and in what order — Play needs a running app for these and this
      session must not touch the emulator.
- [x] `scripts/check-listing.sh` asserts the three length limits and the two graphic
      dimensions. A 31-character title is rejected at upload, after the build.

### Task 6: The two graphics Play requires, generated not drawn

**Files:** `scripts/generate-play-graphics.sh` (create),
`scripts/graphics/PlayGraphics.java` (create),
`fastlane/metadata/android/en-US/images/icon.png`,
`fastlane/metadata/android/en-US/images/featureGraphic.png`

- [x] A 512×512 listing icon and a 1024×500 feature graphic, rendered with Java2D
      from the same curves as `ic_launcher_foreground.xml` and the same two colours
      as `ic_launcher_colors.xml` (`#214F7C` ground, `#FDF9F6` page).
- [x] The mark is scaled up relative to the adaptive icon, deliberately: a launcher
      masks the 108dp canvas down to a circle and the 66dp safe zone exists for that
      mask. The Play icon is an unmasked square, so reusing the adaptive scale would
      leave the mark looking marooned. The new factor is a named constant with the
      arithmetic written next to it.
- [x] The feature graphic sets the wordmark in the repo's own Source Serif, loaded
      from `app/src/main/res/font/`, so the same command produces the same image on
      any machine instead of silently substituting a system font.
- [x] Both PNGs are committed, and regenerating them is one command.

### Task 7: The checklist

**Files:** `docs/release.md` (create), `PROGRESS.md`

- [x] Clean checkout to published, in order, with exact commands: keystore creation,
      `keystore.properties`, version bump, `./scripts/check.sh`, `bundleRelease`,
      where the artifact lands, what to upload.
- [x] The fill-in table: every value a human must supply, and every file or Console
      field it goes into.
- [x] Play Console sections in the order the Console asks for them — App access, Ads,
      Content rating (with the questionnaire answers Folio's features imply), Target
      audience, Data safety, Government apps, Financial features.
- [x] The R8 decision and how to revisit it, the target-API position, and the review
      risks that are specific to this app: the external donation link against Play's
      Payments policy, the "Folio" name, and the permanence of the application ID.
- [x] `PROGRESS.md` gets a log entry, appended, never rewritten.
