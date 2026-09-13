package app.quire.android.share

/**
 * Every address Quire can send a reader to.
 *
 * One object, because these strings are the app's whole surface to the outside
 * world and they are otherwise the easiest thing in a codebase to scatter: a URL in
 * a composable here, one in a string resource there, and the day the domain changes
 * three of the four get updated. [SupportLink] already made this argument for the
 * donation link; this is the rest of it.
 *
 * **Most of these are deliberately blank.** Quire has no website and no hosted
 * privacy policy yet, and a shipped app that opens the browser onto a 404 is worse
 * than one that has no link at all. So the UI asks [isSet] before it offers a row,
 * and nothing here is a plausible-looking placeholder that could survive to
 * production by being mistaken for a real address.
 *
 * Filling these in is step one of `docs/release.md`. Play needs the privacy policy
 * at a public URL regardless of what the app does with it, so that one is not
 * optional — it is only optional *here*.
 */
object QuireLinks {

    /**
     * The published privacy policy.
     *
     * Generated from `docs/privacy-policy.md` and deployed by the Site workflow, so
     * the page behind this URL is the document in the repository. The same string
     * goes in two more places and all three must agree: the Play store listing and
     * the Data Safety section of the Console.
     */
    val PRIVACY_POLICY: String = "https://gajanansr.github.io/quire/privacy.html"

    val WEBSITE: String = "https://gajanansr.github.io/quire/"

    val SOURCE: String = "https://github.com/gajanansr/quire"

    /**
     * FILL IN — the address a reader can write to.
     *
     * Still blank on purpose: Quire's own contact route is the issue tracker, which
     * [SOURCE] already reaches. Play requires a real mailbox on the store listing,
     * and that is an address only the author can supply — so this stays empty rather
     * than inventing one, and the Settings row it would drive stays hidden.
     */
    val CONTACT_EMAIL: String = ""

    /**
     * Where "Show your support" goes. Real, and unchanged.
     *
     * Declared here so this object is the complete list, and delegated to
     * [SupportLink] so there is still exactly one literal.
     */
    val SUPPORT: String = SupportLink.URL

    /** The fill-ins, by the name `docs/release.md` calls each of them. */
    val fillIns: Map<String, String>
        get() = mapOf(
            "PRIVACY_POLICY" to PRIVACY_POLICY,
            "WEBSITE" to WEBSITE,
            "SOURCE" to SOURCE,
            "CONTACT_EMAIL" to CONTACT_EMAIL,
        )

    /** Whether a link has been filled in and can be offered to a reader. */
    fun isSet(link: String): Boolean = link.isNotBlank()

    /** The names still blank, so the checklist and the app agree on what is missing. */
    fun unset(): List<String> = blanksIn(fillIns)

    /** Split out from [unset] so a test can exercise it against a known map. */
    fun blanksIn(links: Map<String, String>): List<String> =
        links.filterValues { it.isBlank() }.keys.toList()

    /**
     * What a Settings row shows on the right.
     *
     * The host alone, not the whole URL: a settings row is 40 characters wide and a
     * truncated address reads as a broken one. Strips the scheme, a leading `www.`
     * and everything from the first slash on.
     */
    fun displayHost(link: String): String = link
        .substringAfter("://")
        .removePrefix("mailto:")
        .removePrefix("www.")
        .substringBefore('/')
        .substringBefore('?')

    /**
     * A `mailto:` URI with the subject already filled in.
     *
     * The subject matters more than it looks: a mail arriving with "Quire" in it can
     * be found again, and one arriving with an empty subject from an address the
     * author has never seen cannot.
     */
    fun mailto(address: String, subject: String): String =
        "mailto:$address?subject=" + subject.replace(" ", "%20")
}

/**
 * What Quire says its version is.
 *
 * Here rather than in a `BuildConfig` field because `:app` does not enable the
 * buildConfig feature, and here rather than inline in Settings because the About
 * row is the one place a reader looks before writing "I'm on the latest version".
 * `LinksTest` reads `app/build.gradle.kts` back and fails if the two drift, which
 * they otherwise would at exactly the moment it matters — the first update.
 */
object QuireRelease {
    const val VERSION_NAME = "0.1.0"
}
