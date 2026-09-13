# Play Console — Data Safety answers for Folio

Every answer to type into **Play Console → App content → Data safety**, in the order
the Console asks. Folio's answers are unusually short because the honest ones are all
"no", but each is justified here so a future version can be checked against it rather
than re-guessed.

> **Why this file exists.** The Data Safety form is a declaration Google holds you to,
> and it is re-confirmed on every release. Answering it from memory a year from now,
> after a library has been added, is how an app ends up making a false declaration by
> accident. Re-read this file and the permission list before each submission.

**Verify before every submission:**

```bash
./scripts/check.sh          # NoNetworkPermissionTest is part of this
grep -o 'uses-permission[^/]*' \
  app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml
```

The second command must print exactly five permissions and none of them `INTERNET`.
If it prints anything else, the answers below are no longer true.

---

## Section 1 — Data collection and security

**Q. Does your app collect or share any of the required user data types?**
→ **No**

Justification: Folio has no accounts and no server. It holds no `INTERNET`
permission, so it cannot transmit anything. Everything it writes — the book copies,
extracted text, reading position, bookmarks, habit minutes and settings — stays in
app-private storage on the device.

*Play's definition matters here:* "collected" means data transmitted off the device.
Data that only ever exists in app-private storage on the user's own device is **not**
collected, and is not declared. Folio meets that definition completely.

Answering **No** ends this section. The remaining questions in Section 1 (encryption
in transit, deletion requests, independent security review) do not appear.

> **If they do appear**, something else on the form was answered Yes by mistake — go
> back rather than inventing answers for them.

---

## Section 2 — Data types

No data types are selected. Not one of these applies:

| Category | Folio |
|---|---|
| Location (approximate, precise) | Not collected. Permission not requested |
| Personal info (name, email, user IDs, address, phone, race, political or religious beliefs, sexual orientation, other) | Not collected. No accounts exist |
| Financial info (payment info, purchase history, credit score, other) | Not collected. See *Financial features* below |
| Health and fitness | Not collected |
| Messages (emails, SMS, in-app messages) | Not collected |
| Photos and videos | Not collected. Folio can *write* a share image to your Pictures folder when you tap Save; it never reads your photos |
| Audio files (voice, music, recordings) | Not collected |
| Files and docs | **Not collected.** This is the one worth pausing on — see below |
| Calendar | Not collected |
| Contacts | Not collected |
| App activity (interactions, in-app search history, installed apps, other user-generated content, other) | Not collected |
| Web browsing history | Not collected |
| App info and performance (crash logs, diagnostics, performance) | Not collected. No crash reporting exists |
| Device or other IDs | Not collected. No advertising ID, no device identifier |

### "Files and docs" — why the answer is still No

Folio's whole purpose is opening a book file you choose, and that file is copied into
app-private storage so it stays readable. It is reasonable to wonder whether that
counts.

It does not. Play's Data Safety form asks about data **collected** (transmitted off
the device) or **shared** (transferred to a third party). The copy is local, private
to the app, and deleted with the app. It is never uploaded, and there is nothing to
upload it to.

The same applies to the share image: it is created only when the user taps Share, and
it is handed to an app the user picks in the system chooser. Play treats a
user-initiated hand-off through the system share sheet as an action by the user, not
as sharing by the app.

---

## Section 3 — Privacy policy

**Q. Privacy policy URL**
→ The public URL where `docs/privacy-policy.md` is hosted.

Required even though nothing is collected. **This must be filled in and live before
submission** — a missing or unreachable policy URL is one of the most common reasons
a first submission is rejected.

The same URL also goes in the **Store listing** section, and in
`FolioLinks.PRIVACY_POLICY` in the app.

---

## Section 4 — The rest of "App content", answered

Data safety is one card among several under **App content**. The rest, for
completeness, because they are all required before a release can go out:

| Section | Answer | Why |
|---|---|---|
| **Privacy policy** | The hosted URL | As above |
| **App access** | *All functionality is available without special access* | No login, no code, no region lock, no paywall. Nothing to give a reviewer |
| **Ads** | *No, my app does not contain ads* | No ad SDK, and no network to serve one over |
| **Content rating** | Complete the IARC questionnaire | Notes in `docs/release.md` |
| **Target audience and content** | Adult age groups only (18+, or 13+ at your discretion) | See the warning in `docs/release.md`: selecting a children's age group brings Folio under the Families policy, which restricts external payment links — and Folio has one |
| **News app** | No | |
| **COVID-19 contact tracing / status** | No | |
| **Data safety** | As above | |
| **Government apps** | No | |
| **Financial features** | *My app doesn't provide any financial features* | Folio takes no payments. It opens a donation page in the user's browser. Read the Payments-policy warning in `docs/release.md` before submitting — the declaration is not the risk, the link itself is |
| **Health apps** | No | |
| **Advertising ID** | Not declared — the app does not use one | If the Console asks, the answer is that no advertising ID is used |

---

## What would change these answers

Re-read this file if any of the following ever happens. Each one turns at least one
"No" above into a "Yes":

- The `INTERNET` permission comes back, for any reason.
- A crash reporter, analytics library or A/B testing SDK is added.
- Accounts, sync or a backup service is added.
- `android:allowBackup` is turned on — Android's own backup would then copy library
  data to Google Drive, which is data leaving the device.
- Payments move in-app, rather than out to a browser.
- Any library is added that bundles an advertising or attribution SDK.
