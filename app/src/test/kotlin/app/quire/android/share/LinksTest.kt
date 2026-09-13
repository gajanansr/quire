package app.quire.android.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The addresses Quire can send a reader to, and the version it claims to be.
 *
 * Plain JUnit 4 — nothing here needs Android, and these are the assertions that
 * catch the two ways a link goes wrong silently: a scheme left off, so `ACTION_VIEW`
 * finds no activity and the tap does nothing at all, and a placeholder surviving to
 * production because it looked like a real URL.
 */
class LinksTest {

    /**
     * The repository root, found by walking up rather than assuming a working
     * directory. Gradle's choice of working directory for unit tests is not part of
     * anyone's contract, and a test that silently reads nothing would pass.
     */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("no settings.gradle.kts above ${File("").absolutePath}")
    }

    @Test
    fun `every link that has been filled in is one the system can open`() {
        // A URL without a scheme is not a URL: Intent.ACTION_VIEW on "quire.app" finds
        // no activity, so the row taps and nothing happens — no crash, no message.
        QuireLinks.fillIns.filterValues { it.isNotBlank() }.forEach { (name, link) ->
            val openable = link.startsWith("https://") ||
                (name == "CONTACT_EMAIL" && link.contains('@') && !link.contains(' '))
            assertTrue(
                "$name is \"$link\", which nothing will open. " +
                    "Links need an https:// scheme; CONTACT_EMAIL needs a bare address.",
                openable,
            )
        }
    }

    @Test
    fun `the support link is the author's page and nothing else`() {
        // Duplicated from SharingTest on purpose. A mistyped donation link sends a
        // reader's money to whoever owns the handle they actually reached, and this
        // object is now a second place that string can be edited.
        assertEquals("https://razorpay.me/@gajanansr", QuireLinks.SUPPORT)
        assertEquals(SupportLink.URL, QuireLinks.SUPPORT)
    }

    @Test
    fun `blanksIn names every empty link and no filled one`() {
        val links = mapOf(
            "FILLED" to "https://example.org/policy",
            "EMPTY" to "",
            "WHITESPACE" to "   ",
        )
        assertEquals(listOf("EMPTY", "WHITESPACE"), QuireLinks.blanksIn(links))
    }

    @Test
    fun `unset reports what the release checklist still has to supply`() {
        // Not a fixed list: this stays true as each one is filled in, and goes empty
        // when they all are. It is here so `unset()` is exercised against the real
        // constants rather than only against a fixture.
        QuireLinks.unset().forEach { name ->
            assertTrue(
                "unset() named $name, but it has a value",
                QuireLinks.fillIns.getValue(name).isBlank(),
            )
        }
    }

    @Test
    fun `a settings row shows the host, not the whole address`() {
        assertEquals("razorpay.me", QuireLinks.displayHost(QuireLinks.SUPPORT))
        assertEquals("quire.app", QuireLinks.displayHost("https://quire.app/privacy"))
        assertEquals("quire.app", QuireLinks.displayHost("https://www.quire.app/"))
        assertEquals("github.com", QuireLinks.displayHost("https://github.com/x/y?tab=z"))
        assertEquals("hello@quire.app", QuireLinks.displayHost("mailto:hello@quire.app"))
        assertEquals("", QuireLinks.displayHost(""))
    }

    @Test
    fun `a mailto carries a findable subject`() {
        assertEquals(
            "mailto:hello@quire.app?subject=Quire%20on%20Android",
            QuireLinks.mailto("hello@quire.app", "Quire on Android"),
        )
    }

    @Test
    fun `the version in Settings is the version that was built`() {
        // The About row is where a reader checks before reporting a bug, and the
        // literal there has no connection to the one Gradle stamps into the APK.
        // Left alone, the two diverge at the first update and every bug report after
        // that names the wrong build.
        val build = File(repoRoot(), "app/build.gradle.kts").readText()
        val declared = Regex("""val defaultVersionName = "([^"]+)"""")
            .find(build)
            ?.groupValues
            ?.get(1)
        assertEquals(
            "app/build.gradle.kts no longer declares `val defaultVersionName = \"...\"`; " +
                "QuireRelease.VERSION_NAME can no longer be checked against it",
            true,
            declared != null,
        )
        assertEquals(
            "QuireRelease.VERSION_NAME drifted from the versionName in app/build.gradle.kts",
            declared,
            QuireRelease.VERSION_NAME,
        )
    }
}
