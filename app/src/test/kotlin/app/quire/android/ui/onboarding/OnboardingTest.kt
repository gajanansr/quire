package app.quire.android.ui.onboarding

import app.quire.android.data.AppSettingsEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The opening guide.
 *
 * Almost everything worth asserting about a first-run flow is a negative: the guide
 * that must not appear twice, the guide that must not appear at all for a reader who
 * has had the app for months, the page that must not promise a feature Quire does
 * not have. None of those is visible on a device — a screen that correctly does not
 * appear looks exactly like a screen that failed to appear — so the rules live in
 * `OnboardingGuide` as functions and are checked here.
 */
class OnboardingTest {

    private val fresh = AppSettingsEntity()
    private val returning = AppSettingsEntity(guideSeen = true, onboarded = true)

    // ------------------------------------------------- when it is on screen

    @Test
    fun `a settings row that has not arrived yet shows nothing at all`() {
        // The live bug, as an assertion. `AppSettingsEntity()` defaults to an
        // un-onboarded reader, so treating "not loaded" as "new install" rendered
        // the welcome screen for a frame on every cold start — someone who had used
        // Quire for months was greeted with it each time they opened the app. Null
        // means "not known yet", and the screen for that is a blank page.
        assertFalse(
            "the guide was shown before the database answered",
            OnboardingGuide.guideVisible(settings = null, finishedThisSession = false),
        )
        assertFalse(
            "the goal picker was shown before the database answered",
            OnboardingGuide.goalVisible(settings = null, finishedThisSession = false),
        )
    }

    @Test
    fun `a fresh install sees the guide`() {
        assertTrue(OnboardingGuide.guideVisible(fresh, finishedThisSession = false))
    }

    @Test
    fun `a reader who has seen it never sees it again`() {
        assertFalse(
            "the guide came back for a reader who had already been through it",
            OnboardingGuide.guideVisible(returning, finishedThisSession = false),
        )
        // Nor at any point later in the same session, whatever else is going on.
        assertFalse(OnboardingGuide.guideVisible(returning, finishedThisSession = true))
    }

    @Test
    fun `finishing the guide takes it off screen before the write lands`() {
        // The database write is asynchronous. Without the session flag the reader's
        // last tap would leave the guide on screen for a frame or two, which reads
        // as a button that did not work and invites a second tap.
        assertFalse(OnboardingGuide.guideVisible(fresh, finishedThisSession = true))
    }

    @Test
    fun `the goal picker follows the guide rather than sharing the screen with it`() {
        // Exactly one of the two, never both and never neither, for every state a
        // stored row can be in.
        listOf(
            AppSettingsEntity(guideSeen = false, onboarded = false),
            AppSettingsEntity(guideSeen = true, onboarded = false),
            AppSettingsEntity(guideSeen = true, onboarded = true),
            AppSettingsEntity(guideSeen = false, onboarded = true),
        ).forEach { settings ->
            listOf(false, true).forEach { finished ->
                val guide = OnboardingGuide.guideVisible(settings, finished)
                val goal = OnboardingGuide.goalVisible(settings, finished)
                assertFalse(
                    "both the guide and the goal picker claimed the screen: $settings",
                    guide && goal,
                )
            }
        }
    }

    @Test
    fun `a reader who closed Quire between the guide and the goal sees the goal`() {
        // The whole reason guideSeen is its own column. `onboarded` is only set by
        // the goal picker, so a single flag would have meant showing the guide again
        // to anyone who did not finish the flow in one sitting.
        val half = AppSettingsEntity(guideSeen = true, onboarded = false)
        assertFalse(OnboardingGuide.guideVisible(half, finishedThisSession = false))
        assertTrue(OnboardingGuide.goalVisible(half, finishedThisSession = false))
    }

    // ---------------------------------------------------------- moving through

    @Test
    fun `next walks the pages once and then says it is done`() {
        var index = 0
        val visited = mutableListOf(0)
        while (true) {
            val next = OnboardingGuide.next(index) ?: break
            index = next
            visited += index
        }
        assertEquals(OnboardingGuide.pages.indices.toList(), visited)
        assertNull("the last page offers another", OnboardingGuide.next(OnboardingGuide.pages.lastIndex))
        assertTrue(OnboardingGuide.isLast(OnboardingGuide.pages.lastIndex))
        assertFalse(OnboardingGuide.isLast(0))
    }

    @Test
    fun `an index past the end is still the end rather than a crash`() {
        assertNull(OnboardingGuide.next(99))
        assertTrue(OnboardingGuide.isLast(99))
    }

    // ------------------------------------------------------------ what it says

    @Test
    fun `the guide is short`() {
        // Short is the requirement, not an aspiration. An introduction long enough
        // to skip is an advert, and this app's voice is restraint.
        assertTrue(
            "the guide has grown to ${OnboardingGuide.pages.size} pages",
            OnboardingGuide.pages.size <= OnboardingGuide.MAX_PAGES,
        )
        assertTrue("the guide has no pages", OnboardingGuide.pages.isNotEmpty())
        OnboardingGuide.pages.forEach { page ->
            assertTrue("a page with no headline", page.headline.isNotBlank())
            assertTrue("\"${page.headline}\" says nothing", page.lines.isNotEmpty())
            assertTrue(
                "\"${page.headline}\" has ${page.lines.size} paragraphs",
                page.lines.size <= 2,
            )
            page.lines.forEach { line ->
                assertTrue("a blank line under \"${page.headline}\"", line.isNotBlank())
                assertTrue(
                    "a paragraph of ${line.length} characters under \"${page.headline}\"",
                    line.length <= 160,
                )
            }
        }
    }

    @Test
    fun `the guide covers what a new reader actually needs to know`() {
        // The five facts the guide exists for. Asserted rather than reviewed, so a
        // rewrite for tone cannot quietly drop the one about the device.
        val whole = OnboardingGuide.pages
            .joinToString(" ") { "${it.headline} ${it.lines.joinToString(" ")}" }
            .lowercase()

        listOf(
            "where books come from" to listOf("epub", "files"),
            "that nothing leaves the phone" to listOf("device"),
            "how to turn a page" to listOf("tap", "swipe"),
            "where typography lives" to listOf("type size", "typefaces"),
            "where the themes live" to listOf("themes"),
        ).forEach { (fact, words) ->
            assertTrue(
                "the guide never says $fact",
                words.all { it in whole },
            )
        }
    }

    @Test
    fun `no page promises something Quire does not do`() {
        // The guide is the first thing a reader is told and the easiest place to
        // write a cheque the app cannot cash. Quire has no account, no sync and no
        // network — NoNetworkPermissionTest holds the manifest to the last of those
        // — so none of them may be offered here.
        //
        // A blunt word list, and it trips on *denials* as well as offers: the first
        // draft of page one said "nothing to subscribe to" and failed here. That is
        // the right outcome rather than a false positive. Denying a feature in its
        // own marketing vocabulary still puts the feature in the reader's head, and
        // a calm app does not need to tell anyone what it is not.
        val forbidden = listOf(
            "sync", "cloud", "sign in", "sign up", "log in", "backup",
            "subscribe to", "premium", "upgrade", "free trial",
        )
        OnboardingGuide.pages.forEach { page ->
            val text = "${page.headline} ${page.lines.joinToString(" ")}".lowercase()
            forbidden.forEach { promise ->
                assertFalse(
                    "\"${page.headline}\" offers \"$promise\", which Quire does not have",
                    promise in text,
                )
            }
        }
    }

    @Test
    fun `nothing in the guide is a number nobody can check`() {
        // The same honesty rule the notification copy is held to. A guide that says
        // "five themes" is a guide that is wrong the day a sixth is added, and
        // nobody re-reads onboarding copy.
        OnboardingGuide.pages.forEach { page ->
            val text = "${page.headline} ${page.lines.joinToString(" ")}"
            assertFalse(
                "\"${page.headline}\" contains a number: $text",
                text.any { it.isDigit() },
            )
        }
    }

    @Test
    fun `the guide does not shout`() {
        OnboardingGuide.pages.forEach { page ->
            val text = "${page.headline} ${page.lines.joinToString(" ")}"
            assertFalse("\"${page.headline}\" shouts", '!' in text)
            assertTrue(
                "\"${page.headline}\" is not a sentence",
                page.headline.first().isUpperCase() && page.headline.endsWith("."),
            )
        }
    }
}
