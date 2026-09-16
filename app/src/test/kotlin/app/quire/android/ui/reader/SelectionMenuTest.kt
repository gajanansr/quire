package app.quire.android.ui.reader

import app.quire.android.share.TextHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a reader is offered when they choose a passage, and where each thing sits.
 *
 * Six actions is a lot to put over the words someone is trying to read, and this app
 * has already been told once that a screen was cluttered — the fix then was to take
 * things away. So the split is a rule rather than a layout, and the rule is stated
 * here where it can be asserted:
 *
 * **What stays in Quire is on the bar. What leaves it is behind More.**
 *
 * Highlight, Note and Copy all finish inside the app or inside the reader's own
 * clipboard, they work on every phone, and they are what a selection is usually for.
 * Share, Translate and Dictionary all end with another app opening — Quire's part is
 * over — and only they can be missing, which is the second reason they are grouped:
 * the bar is then three buttons wide on every device, and the one list whose contents
 * depend on what is installed is a list the reader deliberately opened.
 */
class SelectionMenuTest {

    @Test
    fun `the bar is three actions wide, and the same three on every phone`() {
        // Its width cannot depend on what the reader has installed. The bar is drawn
        // over the passage itself and is positioned against it; a bar that is four
        // buttons on one phone and six on another is a different obstruction on each.
        assertEquals(
            listOf(SelectionAction.HIGHLIGHT, SelectionAction.NOTE, SelectionAction.COPY),
            SelectionMenu.PRIMARY,
        )
    }

    @Test
    fun `nothing on the bar needs another app to exist`() {
        // The load-bearing half of the split. Every action on the bar is answered by
        // Quire or by the platform clipboard, so no button there can ever be dead —
        // which is what lets the bar be fixed while the list below it is not.
        SelectionMenu.PRIMARY.forEach {
            assertTrue("${it.name} is on the bar but hands off to another app", SelectionMenu.handoff(it) == null)
        }
        assertFalse(
            "Share is on the bar, but it ends with a chooser and another app",
            SelectionAction.SHARE in SelectionMenu.PRIMARY,
        )
    }

    @Test
    fun `every action is in exactly one of the two places`() {
        // A third list, or an action in both, is how an action ends up drawn twice or
        // not at all.
        val all = SelectionAction.entries.toSet()
        assertEquals(all, (SelectionMenu.PRIMARY + SelectionMenu.SECONDARY).toSet())
        assertEquals(
            "an action is in both the bar and the overflow",
            all.size,
            SelectionMenu.PRIMARY.size + SelectionMenu.SECONDARY.size,
        )
    }

    @Test
    fun `the overflow always has something in it`() {
        // More must never open on an empty sheet. Share is what guarantees it: the
        // system chooser is part of Android rather than an app that can be absent,
        // so the sheet has at least one live entry on the barest phone there is.
        val barest = SelectionMenu.overflow(emptySet())
        assertTrue("More opens on nothing", barest.isNotEmpty())
        assertEquals(SelectionAction.SHARE, barest.first().action)
        assertTrue("Share came back disabled", barest.first().enabled)
    }

    @Test
    fun `an action nothing can answer is shown, and shown as unavailable`() {
        // Shown rather than hidden, deliberately, and this is the judgement call in
        // the file. Hiding is tidier and it is also silent: the reader never learns
        // the feature exists, and the sheet is a different sheet on every phone, so
        // nobody can be told where anything is. A greyed row with a sentence behind
        // it says what is true — Quire cannot do this itself and nothing here can
        // either — which is a thing the reader can act on.
        val marooned = SelectionMenu.overflow(emptySet())
        assertEquals(
            "an action vanished instead of explaining itself",
            SelectionMenu.SECONDARY,
            marooned.map { it.action },
        )
        marooned.filter { SelectionMenu.handoff(it.action) != null }
            .forEach { assertFalse("${it.action.name} claims to work", it.enabled) }
    }

    @Test
    fun `an action something can answer is live`() {
        val withTranslation = SelectionMenu.overflow(setOf(TextHandoff.TRANSLATE))
        assertTrue(
            "a translation app is installed and Translate is still greyed",
            withTranslation.single { it.action == SelectionAction.TRANSLATE }.enabled,
        )
        assertFalse(
            "Dictionary went live on the strength of a translation app",
            withTranslation.single { it.action == SelectionAction.DICTIONARY }.enabled,
        )
    }

    @Test
    fun `the order of the overflow does not change with what is installed`() {
        // Muscle memory. A list that reorders itself as apps are installed and
        // removed means the reader has to read it every time.
        val orders = listOf(
            emptySet(),
            setOf(TextHandoff.TRANSLATE),
            setOf(TextHandoff.DEFINE),
            setOf(TextHandoff.TRANSLATE, TextHandoff.DEFINE),
        ).map { available -> SelectionMenu.overflow(available).map { it.action } }

        assertEquals("the overflow reorders itself", 1, orders.distinct().size)
    }

    @Test
    fun `each hand-off action asks for exactly one job`() {
        assertEquals(TextHandoff.TRANSLATE, SelectionMenu.handoff(SelectionAction.TRANSLATE))
        assertEquals(TextHandoff.DEFINE, SelectionMenu.handoff(SelectionAction.DICTIONARY))
        // Share is a hand-off too, but not one that can be missing, so it is not
        // resolved and never appears disabled.
        assertEquals(null, SelectionMenu.handoff(SelectionAction.SHARE))
    }

    @Test
    fun `a tap on an unavailable action says something true`() {
        // Never nothing. A tap that does nothing reads as a broken app, and this is
        // the case the developer's own phone cannot show them.
        listOf(SelectionAction.TRANSLATE, SelectionAction.DICTIONARY).forEach {
            val said = SelectionMenu.unavailable(it)
            assertTrue("${it.name} has nothing to say for itself", said.isNotBlank())
            assertTrue("${it.name} does not say the app is the reader's own", "app" in said)
        }
    }

    @Test
    fun `the explanation names no app and sends nobody shopping`() {
        // Quire ships no brand marks and recommends no product — the same rule the
        // share destinations follow. It also must not imply Quire could do this if
        // only the reader paid or connected: it cannot, by construction, and saying
        // so is the honest half of the offline promise rather than an apology.
        listOf(SelectionAction.TRANSLATE, SelectionAction.DICTIONARY).forEach {
            val said = SelectionMenu.unavailable(it).lowercase()
            listOf("google", "chrome", "play store", "download", "install").forEach { word ->
                assertFalse("the explanation for ${it.name} mentions \"$word\"", word in said)
            }
        }
    }
}
