# Releasing Folio on Google Play

Clean checkout to published, in order. Every command is exact and every value you
have to supply is named.

> **On the policy statements in this file.** Play's requirements change, sometimes
> with a few months' notice. Anything about Google's *rules* — target API levels,
> testing requirements, payment policy — is marked **[verify]** and should be checked
> against the Play Console itself before you rely on it. Everything about *this
> repository* was verified on 13 September 2026 and is stated plainly.

---

## 0. What you must supply

Nothing in this list could be produced without you. Everything else is done.

| # | What | Where it goes |
|---|---|---|
| 1 | **An upload keystore** and its four values | `keystore.properties` at the repo root (gitignored), or the four environment variables. §2 |
| 2 | **A public URL for the privacy policy** | Play Console × 2, and `FolioLinks.PRIVACY_POLICY`. §5 |
| 3 | **A contact email** | `docs/privacy-policy.md` (replace `[CONTACT EMAIL]`), the Play store listing, and optionally `FolioLinks.CONTACT_EMAIL` |
| 4 | **At least 2 phone screenshots**, 4–6 recommended | `fastlane/metadata/android/en-US/images/phoneScreenshots/`. Instructions in the README there |
| 5 | **Play Console answers**: content rating questionnaire, target audience, app category, tags | Play Console. §7 |
| 6 | *(optional)* a website and a source URL | `FolioLinks.WEBSITE`, `FolioLinks.SOURCE` |

Items 1–5 are blocking. Item 6 is not: the app hides those Settings rows when the
links are blank, on purpose.

The app itself will tell you what is still missing:

```kotlin
FolioLinks.unset()   // ["PRIVACY_POLICY", "WEBSITE", "SOURCE", "CONTACT_EMAIL"]
```

All of them live in **one file**: `app/src/main/kotlin/app/folio/android/share/Links.kt`.

---

## 1. Before the first release

These are once-only and some of them take days, not minutes. Start them first.

- **A Play Console developer account.** One-time registration fee, paid to Google.
- **Identity verification.** Google verifies a personal developer's legal name,
  address and phone, and an organisation's D-U-N-S number. This can take several
  days and nothing can be published until it clears. **[verify]**
- **Closed testing before production. [verify]** Personal developer accounts created
  after late 2023 must run a closed test with a minimum number of testers (20 at the
  time of writing) for a continuous period (14 days) before they can apply for
  production access. **This is the single most likely thing to derail a release
  plan**, because it is measured in weeks and is invisible until you try to promote
  a build to production. Check the requirement in the Console under *Test and
  release* the day you create the app, not the day you want to ship.
- **Create the app** in the Console: default language English (United States) to
  match `fastlane/metadata/android/en-US/`, app (not game), free.

### The application id is permanent

`app.folio.android` can never change. Publishing under it fixes both the id and the
signing identity for the lifetime of the listing — a different id is a different app
with no reviews, no installs and no update path. Be sure before the first upload.

---

## 2. The upload keystore

Once. Losing this file, or its passwords, means you can never update the app again
unless you have enrolled in Play App Signing (see below).

```bash
source scripts/env.sh
keytool -genkeypair -v \
  -keystore ~/keys/folio-upload.jks \
  -storetype PKCS12 \
  -alias folio-upload \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=<your name>, O=<you or your org>, C=<country code>"
```

`-validity 10000` is about 27 years. Play rejects a key that expires before
22 October 2033, so do not shorten it. PKCS12 rather than the older JKS format,
which `keytool` itself now warns about.

**Put the keystore outside the repository.** `~/keys/` above, not the project
directory. `.gitignore` covers `*.jks`, `*.keystore`, `*.p12` and
`keystore.properties`, but the only way a secret cannot be committed is by not being
in the tree.

Then create `keystore.properties` at the repo root:

```properties
storeFile=/Users/you/keys/folio-upload.jks
storePassword=...
keyAlias=folio-upload
keyPassword=...
```

A relative `storeFile` is resolved against the repo root. `keystore.properties` is
gitignored — confirm it, every time, before you commit:

```bash
git check-ignore -v keystore.properties   # must print the .gitignore line
```

For CI, skip the file and export four variables instead:
`FOLIO_KEYSTORE`, `FOLIO_KEYSTORE_PASSWORD`, `FOLIO_KEY_ALIAS`, `FOLIO_KEY_PASSWORD`.

**Back it up** somewhere that is not this machine — a password manager holds both the
file and the passwords.

### Play App Signing

Play App Signing is on by default for new apps. Google holds the *app signing key*
and signs every install; your keystore above is only the **upload key**, which proves
the upload is from you.

The practical consequence: if you lose the upload key, Google can reset it and you
keep the app. Without Play App Signing, losing the key ends the listing. Leave it on.

---

## 3. Build the release

```bash
source scripts/env.sh          # JDK 21; the default JDK breaks AGP

# 1. The gate. Never ship red.
./scripts/check.sh             # 568 tests, must exit 0

# 2. The bundle Play wants.
./gradlew :app:bundleRelease
```

The artifact:

```
app/build/outputs/bundle/release/app-release.aab
```

Verify it is actually signed before you spend twenty minutes uploading it:

```bash
jarsigner -verify app/build/outputs/bundle/release/app-release.aab
# -> "jar verified."
```

If the build stops with *"Cannot sign the release: no keystore credentials"*, §2 is
not done. That failure is deliberate: without it, Gradle happily writes an unsigned
bundle that Play rejects at the end of the upload, with a message about the artifact
rather than about the four values you never set. Only the release tasks fail — the
debug build and the whole test suite work on a machine that has never seen a
keystore.

### An APK instead

Only for sideloading and manual testing. Play takes the bundle.

```bash
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

### Why a bundle, concretely

Measured on this app, 13 September 2026:

| | |
|---|---|
| `app-release.aab` | **17 MB** |
| The debug APK PROGRESS.md records | 67 MB, all four ABIs |

Play splits the bundle per device, so a phone downloads one ABI and one screen
density rather than all of them. Most of Folio's size is PdfBox-Android's bundled
fonts and BouncyCastle, which every device does need.

---

## 4. Version numbers

Both live in `app/build.gradle.kts`, at the top:

```kotlin
val defaultVersionCode = 1
val defaultVersionName = "0.1.0"
```

- **`versionCode`** is an integer Play uses to order releases. It must increase on
  every upload. Play permanently refuses a code it has already seen, even from a
  bundle you deleted.
- **`versionName`** is what people read. It also has to be edited in one other
  place — `FolioRelease.VERSION_NAME` in `share/Links.kt`, which is what the About
  row in Settings shows. `LinksTest` fails the build if the two drift, so you cannot
  forget; you can only be reminded.

Then add the release notes, named after the version code:

```
fastlane/metadata/android/en-US/changelogs/<versionCode>.txt   # 500 characters max
```

For a one-off build without a commit — a CI run, or a second upload after a rejected
one:

```bash
./gradlew :app:bundleRelease -PfolioVersionCode=2 -PfolioVersionName=0.1.1
# or FOLIO_VERSION_CODE / FOLIO_VERSION_NAME in the environment
```

---

## 5. The store listing

All of it is in the repo, in fastlane's standard layout, so it can be diffed and
reviewed instead of living only in a web form. Copy and paste, or wire up fastlane's
`supply` later — the directory names are the ones it expects.

```
fastlane/metadata/android/en-US/
├── title.txt                        -> Store listing > App name          (27/30)
├── short_description.txt            -> Store listing > Short description (73/80)
├── full_description.txt             -> Store listing > Full description  (3542/4000)
├── changelogs/1.txt                 -> Release > Release notes           (470/500)
└── images/
    ├── icon.png            512x512  -> Store listing > App icon
    ├── featureGraphic.png 1024x500  -> Store listing > Feature graphic
    └── phoneScreenshots/            -> Store listing > Phone screenshots  ← YOU
```

Check every limit before you start typing:

```bash
./scripts/check-listing.sh
```

It counts **characters, not bytes** — the em dash in the title is one character and
three bytes, and `wc -c` would pass a title Play rejects.

### Also on the store listing page

| Field | Value |
|---|---|
| App category | **Books & Reference** |
| Tags | Reading, Books, Offline. Pick from Play's fixed list; they affect where the app is surfaced |
| Email address | Required and shown publicly. Item 3 |
| Website | Optional. Leave blank until one exists |
| Phone | Optional. Leave blank |
| **Privacy policy** | Required. Item 2 |

### The graphics

Both are generated from the app's own launcher mark and colours:

```bash
./scripts/generate-play-graphics.sh
```

Re-run it after any change to `res/drawable/ic_launcher_foreground.xml` or
`res/values/ic_launcher_colors.xml`, and commit the PNGs.

### The screenshots — the one thing still missing

Play will not publish a listing without at least two. They need a running app on a
real screen. Full instructions, including which six screens and in what order, are in
`fastlane/metadata/android/en-US/images/phoneScreenshots/README.md`.

---

## 6. Privacy policy

Host `docs/privacy-policy.md` at a public URL. Any static host will do; it must be
reachable without a login and must stay reachable, because Play re-checks it.

Before hosting, replace `[CONTACT EMAIL]` in that file with a real address.

The URL goes in **three** places and they must match:

1. Play Console → Store listing → *Privacy policy*
2. Play Console → App content → Data safety → *Privacy policy URL*
3. `FolioLinks.PRIVACY_POLICY` in `share/Links.kt` — this is what turns the Settings
   row from a plain statement into a tappable link. Rebuild after setting it.

---

## 7. App content

Every card here must be green before a release can go out. The full answers, with
reasoning, are in **`docs/play-data-safety.md`** — including the Data Safety form
question by question. The short version:

| Card | Answer |
|---|---|
| Privacy policy | The hosted URL |
| App access | All functionality available without special access |
| Ads | No ads |
| Content rating | Questionnaire — see below |
| Target audience | **Adults only (18+, or 13+).** See the warning below |
| News app | No |
| COVID-19 apps | No |
| Data safety | **No data collected or shared.** See `docs/play-data-safety.md` |
| Government apps | No |
| Financial features | None |
| Health | No |

### Content rating questionnaire

Run through IARC's questionnaire under *Utility, Productivity, Communication or
Other*. Folio's honest answers:

- Violence, sexuality, profanity, controlled substances, gambling, horror — **no**
  to all. Folio contains no content of its own; it displays files the user supplies.
- **Does the app allow users to interact or exchange content?** — **No.** There is
  no in-app social layer, no comments, no user-to-user anything. Sharing a passage
  hands it to Android's system chooser, which is the operating system's feature.
- **Does the app share the user's location?** — No.
- **Does the app allow purchases?** — **No** for in-app purchases. Folio sells
  nothing and contains no billing. The donation link is a web address opened in the
  browser; read the Payments-policy warning below before deciding how to describe it.
- **Does the app collect or transmit personal information?** — No.

The expected outcome is the lowest rating in every region (ESRB Everyone, PEGI 3,
and equivalents).

### Why not a children's age group

Selecting an under-13 audience puts Folio under Google Play's **Families policy**,
which adds requirements around ads, data and payments — including restrictions on
sending children to external payment pages. Folio has exactly such a link in
Settings. Unless you intend to remove it and take on the Families requirements,
select adult age groups.

---

## 8. Target API level

| | |
|---|---|
| `compileSdk` | 37 |
| `targetSdk` | **36** |
| `minSdk` | 26 — Android 8.0 and up |

**targetSdk is 36 on purpose and must not be raised casually.** Robolectric 4.16
emulates no API above 36, and `:app`'s entire test suite runs on Robolectric.
Raising it silently disables the tests that hold every promise in this release — the
permission list included.

**[verify]** Play requires new apps and updates to target a recent API level, and
raises the bar annually. 36 satisfies the requirement as of this writing. Check the
current rule in the Console under *Test and release → App bundle explorer*, or in
Play's policy pages, before you submit.

**If Play ever demands 37**, that is a genuine conflict between this repository's
test strategy and Play's rules, and it is a decision for a human:

- Raise `targetSdk` to 37 and accept that `:app`'s Robolectric tests break, or
- Upgrade Robolectric — which is a pinned version, and pinned versions are not bumped
  in this project without a decision.

Do not resolve it by quietly changing the number.

### 16 KB page sizes

**[verify]** Play requires apps targeting recent Android versions to support 16 KB
memory pages on 64-bit devices.

Folio ships exactly one native library, `libandroidx.graphics.path.so`, pulled in by
Compose. Its `LOAD` segments were checked in the release bundle on 13 September 2026
and are aligned to 16384 bytes. Nothing to do — but if a dependency with native code
is ever added, check it:

```bash
unzip -l app/build/outputs/bundle/release/app-release.aab | grep '\.so$'
```

---

## 9. R8 / minification — the decision

**R8 is off.** `isMinifyEnabled = false` unless `-PfolioMinify=true` is passed.

This is a considered choice, not an oversight, and `app/proguard-rules.pro` is
written and committed so it can be revisited in an afternoon.

**What was measured** on 13 September 2026:

| | |
|---|---|
| `bundleRelease` | 17 MB, builds clean |
| `bundleRelease -PfolioMinify=true` | **11 MB**, also builds clean |

So R8 works and saves about a third. **What was not measured is the only thing that
matters:** whether a shrunk Folio still imports a book. This session could not use
the emulator or a device, and PdfBox-Android is precisely the library where that
question is not rhetorical — it resolves fonts, CMaps and codecs by class name out
of its own bundled assets, and none of those names appear in bytecode. A wrongly
shrunk PdfBox does not crash. It returns an empty text layer, and a PDF imports
"successfully" as a book with no words in it. Room and kotlinx.serialization have the
same shape of failure: the generated implementation or serializer goes missing and
the app fails at first launch or when opening a book saved last week, not at build
time.

Shipping that untested, to save 6 MB on an app that is free and has no ads, is a bad
trade. An untested `minifyEnabled true` is how an app gets its first one-star review.

**To turn it on** — the whole procedure:

1. `./gradlew :app:assembleRelease -PfolioMinify=true`
2. Install that APK on a real device.
3. Import one of each: an EPUB, a text PDF, a **scanned** PDF, and a TXT file.
4. Open each one, turn pages, check the chapter list is populated and the text is
   there.
5. Force-stop and relaunch; confirm your place was kept and the library still loads
   (that exercises Room's generated implementation and the JSON on disk).
6. Highlight a passage and share it as an image.
7. If all of that works, flip the default in `app/build.gradle.kts` and note the
   device and date in `PROGRESS.md`.

Resource shrinking follows the same flag. It is worth much less here than code
shrinking — Folio's bulk is PdfBox's assets, which resource shrinking does not
touch.

---

## 10. Upload and roll out

1. Play Console → **Test and release** → choose a track.
   - **Internal testing** first, always. It is available immediately, takes up to
     100 testers, and is the fastest way to find out the bundle installs.
   - Then **Closed testing** — and note the tester-count requirement in §1. **[verify]**
   - Then **Production**.
2. **Create new release** → upload `app-release.aab`.
3. Paste `changelogs/<versionCode>.txt` into the release notes.
4. Complete the store listing (§5) and every App content card (§7).
5. **Review release**, then roll out. A staged rollout — 20%, then wider — lets you
   halt if something surfaces.
6. First reviews take longer than later ones. Expect days, not hours, and do not
   plan around a same-night publish.

### After the upload, before rolling out

- **Pre-launch report** (Console → Test and release → Pre-launch report). Google runs
  the app on real devices and reports crashes, ANRs, accessibility findings and
  security warnings. It is free and it catches launch-time crashes on hardware you do
  not own. Read it before rolling out to production.
- **App bundle explorer** → check the delivered download size per device, and that
  the permission list is the five in `docs/privacy-policy.md` and nothing more.
- **Android vitals** after release, for ANRs and crashes on real devices.

---

## 11. Review risks specific to Folio

Read this before submitting. None of it is hypothetical.

### The donation link **[verify]** — the highest risk on the list

*Settings → Show your support* opens `https://razorpay.me/@gajanansr` in the browser.

Google Play's Payments policy governs how money is collected in and around an app,
and external payment links have been a recurring cause of enforcement and rejection.
The treatment of donations has also changed over time, and it differs by region and
by whether the developer is a registered charity.

**Read the current Payments policy text before submitting**, and decide deliberately
between:

- Leaving the link as it is, having checked it is permitted for your case;
- Removing it for the first release and adding it back once the app is live and
  policy is confirmed — one line in `share/Links.kt` and the Settings row;
- Moving to Google Play Billing, which would mean a new dependency and is a much
  larger change than it sounds.

Whatever you choose, do not describe the link as a purchase, an upgrade, or anything
that unlocks a feature. It unlocks nothing, which is the strongest position to be in.

### The name

"Folio" is a common word and other apps use it. Play requires that a title not
infringe a trademark and not be confusingly similar to another app's. Search Play for
"Folio" before you commit to the name — and remember §1: the application id cannot be
changed afterwards, even if the display name can.

### Claims in the listing

The description states that Folio has no internet permission. That is true and
verifiable, and a reviewer can confirm it from the manifest in seconds — which is
exactly why it must stay true. If a dependency ever reintroduces `INTERNET`, the
listing becomes a false claim and the app becomes a misrepresentation case, not
merely a bug. `NoNetworkPermissionTest` is what stands between those two outcomes;
never weaken it.

### Things a reviewer may notice that are not policy problems

Honest, and worth knowing before a reviewer or a user finds them:

- **A book cannot be deleted from the library.** There is no remove action anywhere.
  Recorded as still open in `PROGRESS.md`. It is a genuine usability gap and the most
  likely subject of the first bad review.
- **A book keeps whatever extraction it was imported with.** Improvements to text
  extraction do not reach books already imported; they have to be re-imported.
- **Truncated PDFs import silently** as if whole — PDFBox's lenient parser recovers
  them and reports a plausible page count. Recorded under open questions in
  `PROGRESS.md`.

---

## 12. Every subsequent release, in short

```bash
source scripts/env.sh

# 1. Bump both, in app/build.gradle.kts:
#      defaultVersionCode  (must increase)
#      defaultVersionName
#    and FolioRelease.VERSION_NAME in share/Links.kt to match.

# 2. Release notes:
#      fastlane/metadata/android/en-US/changelogs/<versionCode>.txt

# 3. Verify.
./scripts/check.sh
./scripts/check-listing.sh

# 4. Build and confirm the signature.
./gradlew :app:bundleRelease
jarsigner -verify app/build/outputs/bundle/release/app-release.aab

# 5. Upload app-release.aab, paste the release notes, re-confirm Data safety,
#    read the pre-launch report, roll out.
```
