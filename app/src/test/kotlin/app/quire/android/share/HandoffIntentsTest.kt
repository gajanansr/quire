package app.quire.android.share

import android.content.Intent
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * How Quire translates and defines without being able to translate or define.
 *
 * Quire declares no `INTERNET` permission — `NoNetworkPermissionTest` fails the build
 * the day one reappears — so it cannot look a word up and cannot ask anyone to. What
 * it can do is hand the words the reader chose to an app the reader already has, the
 * same way Share does, and then get out of the way.
 *
 * That makes these intents the whole feature. There is no translation code to test
 * because there is none to write; what is testable is the envelope, which is a value,
 * and the rule for deciding whether anybody is there to open it.
 */
@RunWith(RobolectricTestRunner::class)
class HandoffIntentsTest {

    private val passage = "the weight of silence"

    /** Android 10, where ACTION_TRANSLATE exists. */
    private val q = Build.VERSION_CODES.Q

    /** Quire's floor, where it does not. */
    private val floor = Build.VERSION_CODES.O

    private fun actions(handoff: TextHandoff, sdkInt: Int): List<String?> =
        HandoffIntents.candidates(handoff, passage, sdkInt).map { it.action }

    // -------------------------------------------------- what is in the envelope

    @Test
    fun `translating asks a translator first and anything that takes text second`() {
        // Two routes, most specific first. ACTION_TRANSLATE names the job exactly and
        // goes straight to an app that does it; ACTION_PROCESS_TEXT is the route the
        // platform's own selection toolbar uses and is what translation apps actually
        // register for, but it is answered by every text-processing app on the phone,
        // so it is the fallback rather than the offer.
        assertEquals(
            listOf(Intent.ACTION_TRANSLATE, Intent.ACTION_PROCESS_TEXT),
            actions(TextHandoff.TRANSLATE, q),
        )
    }

    @Test
    fun `below Android 10 there is no translate action to ask for`() {
        // ACTION_TRANSLATE arrived in API 29 and minSdk is 26. Sending it to an older
        // phone is not an error the reader would ever see — it simply resolves to
        // nothing — which is exactly why the guard has to be a test: the symptom is a
        // feature that quietly never appears on older hardware.
        assertEquals(listOf(Intent.ACTION_PROCESS_TEXT), actions(TextHandoff.TRANSLATE, floor))
    }

    @Test
    fun `defining asks a dictionary first, on every version Quire runs on`() {
        // ACTION_DEFINE is API 23, below Quire's floor, so it needs no guard and must
        // not have one — a version check copied from the translate case would delete
        // the specific route on every phone that has it.
        assertEquals(
            listOf(Intent.ACTION_DEFINE, Intent.ACTION_PROCESS_TEXT),
            actions(TextHandoff.DEFINE, floor),
        )
    }

    @Test
    fun `the passage travels in the extra each action actually reads`() {
        // Three actions, two different extras. An intent carrying the wrong one opens
        // the right app on an empty screen, which reads as the other app being broken.
        val translate = HandoffIntents.candidates(TextHandoff.TRANSLATE, passage, q)
        assertEquals(passage, translate[0].getCharSequenceExtra(Intent.EXTRA_TEXT))
        assertEquals(passage, translate[1].getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT))

        val define = HandoffIntents.candidates(TextHandoff.DEFINE, passage, q)
        assertEquals(passage, define[0].getCharSequenceExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `a handed-off passage is read-only`() {
        // ACTION_PROCESS_TEXT lets the receiving app send a replacement back, and a
        // reader who taps Translate is not offering to have their book rewritten.
        // Quire could not honour the reply if it came — a book on disk is not an
        // editable field — so the honest thing is to say up front that this is a
        // reading, not an edit.
        HandoffIntents.candidates(TextHandoff.TRANSLATE, passage, q)
            .filter { it.action == Intent.ACTION_PROCESS_TEXT }
            .forEach {
                assertTrue(
                    "a processed passage was offered for editing",
                    it.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false),
                )
            }
    }

    @Test
    fun `nothing about the reader travels with the passage`() {
        // The same promise SharingTest makes about the share envelope, at the other
        // door out. Quire has no account and stores nothing about who is reading;
        // that has to stay true at every point where something leaves the device.
        TextHandoff.entries.forEach { handoff ->
            HandoffIntents.candidates(handoff, passage, q).forEach { intent ->
                val keys = intent.extras?.keySet().orEmpty()
                assertTrue(
                    "${intent.action} carries $keys",
                    keys.all {
                        it == Intent.EXTRA_TEXT ||
                            it == Intent.EXTRA_PROCESS_TEXT ||
                            it == Intent.EXTRA_PROCESS_TEXT_READONLY
                    },
                )
            }
        }
    }

    @Test
    fun `the generic route is typed and the specific ones are not`() {
        // Not cosmetic: the type is half of what an intent filter matches on, and the
        // <queries> element in the manifest has to describe the same shape or the
        // resolution below returns nothing on Android 11 and later. PROCESS_TEXT
        // handlers declare text/plain; ACTION_TRANSLATE and ACTION_DEFINE handlers
        // declare no data at all, and a type on those intents would match none of
        // them.
        TextHandoff.entries.forEach { handoff ->
            HandoffIntents.candidates(handoff, passage, q).forEach { intent ->
                if (intent.action == Intent.ACTION_PROCESS_TEXT) {
                    assertEquals("text/plain", intent.type)
                } else {
                    assertNull("${intent.action} was given a mime type", intent.type)
                }
            }
        }
    }

    // ------------------------------------------------- whether anybody is there

    @Test
    fun `an action nothing on the phone handles is not offered`() {
        // The case the developer's own phone will never show them, and the reason
        // availability is a value rather than a guess: a reader with no translation
        // app must not be given a button that does nothing.
        assertEquals(emptySet<TextHandoff>(), HandoffIntents.available(q) { false })
    }

    @Test
    fun `an action only the generic route handles is still offered`() {
        // A phone with no app claiming ACTION_TRANSLATE but plenty claiming
        // PROCESS_TEXT is the common case, not the odd one — that is how the
        // platform's own Translate entry is registered.
        val onlyProcessText = { intent: Intent -> intent.action == Intent.ACTION_PROCESS_TEXT }
        assertEquals(
            setOf(TextHandoff.TRANSLATE, TextHandoff.DEFINE),
            HandoffIntents.available(q, onlyProcessText),
        )
    }

    @Test
    fun `the intent Quire starts is the most specific one that resolves`() {
        val everything = { _: Intent -> true }
        assertEquals(
            Intent.ACTION_TRANSLATE,
            HandoffIntents.chosen(TextHandoff.TRANSLATE, passage, q, everything)?.action,
        )
        assertEquals(
            Intent.ACTION_PROCESS_TEXT,
            HandoffIntents.chosen(TextHandoff.TRANSLATE, passage, q) {
                it.action == Intent.ACTION_PROCESS_TEXT
            }?.action,
        )
    }

    @Test
    fun `there is no intent to start when nothing resolves`() {
        // Null rather than a best guess. Starting an unresolvable intent throws
        // ActivityNotFoundException, and a crash is a worse answer than a sentence
        // explaining why nothing happened.
        assertNull(HandoffIntents.chosen(TextHandoff.TRANSLATE, passage, q) { false })
    }

    @Test
    fun `availability is decided on the same intents that get started`() {
        // A probe that differs from the real thing is worse than no probe: the button
        // appears and the tap throws. Set equality in both directions, so neither an
        // intent probed but never started nor one started but never probed survives.
        //
        // Compared by shape rather than by identity, because the probe carries no
        // passage — resolution reads the action, the type and the categories and
        // never looks at an extra.
        val probed = mutableListOf<Intent>()
        HandoffIntents.available(q) { probed += it; false }
        val started = TextHandoff.entries.flatMap { HandoffIntents.candidates(it, passage, q) }

        assertEquals(
            "the probe and the launch do not ask for the same thing",
            started.map { it.action to it.type }.toSet(),
            probed.map { it.action to it.type }.toSet(),
        )
    }

    // --------------------------------------------------------- package visibility

    /**
     * The manifest Quire actually ships, found by walking up from wherever the test
     * runner happens to have been started.
     *
     * Comments are stripped, because this file explains itself at length and a test
     * that greps the prose is testing the prose. What is asserted below has to be
     * what the merger will read.
     */
    private fun manifest(): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
                .map { File(dir, it) }
                .firstOrNull { it.exists() }
                ?.let {
                    return it.readText()
                        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
                }
            dir = dir.parentFile
        }
        throw IllegalStateException("AndroidManifest.xml not found from ${File(".").absolutePath}")
    }

    @Test
    fun `every action Quire probes for is declared in queries`() {
        // The one that can only fail on a real phone. From Android 11 a package is
        // invisible unless the manifest says it is being looked for, so
        // queryIntentActivities returns an empty list and Quire concludes — wrongly,
        // and only on API 30 and above — that the reader has no translation app.
        // Nothing in Robolectric reproduces that, and the symptom is a feature that
        // is simply missing on newer hardware with nothing in any log to say why.
        //
        // Read off the candidates rather than a literal list, so a fourth action
        // added to HandoffIntents without a matching <queries> entry fails here.
        val text = manifest()
        val declared = Regex("<queries>(.*?)</queries>", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1)
        assertTrue("the manifest declares no <queries> at all", declared != null)

        TextHandoff.entries.forEach { handoff ->
            HandoffIntents.candidates(handoff, passage, q).forEach { intent ->
                assertTrue(
                    "${intent.action} is probed for but never declared in <queries>, " +
                        "so it resolves to nothing on Android 11 and later",
                    declared!!.contains("\"${intent.action}\""),
                )
            }
        }
    }

    @Test
    fun `seeing other apps is not bought with a permission`() {
        // QUERY_ALL_PACKAGES is the lazy way out of the visibility rules and Play
        // treats it as sensitive. A <queries> element asks for three named actions
        // and nothing else; NoNetworkPermissionTest would also catch this, and it is
        // worth saying here too because this is the change that would tempt anyone.
        assertFalse(
            "Quire asks to see every app on the phone",
            manifest().contains("QUERY_ALL_PACKAGES"),
        )
    }
}
