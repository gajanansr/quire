package app.quire.android.ui.reader

import app.quire.android.share.TextHandoff
import app.quire.android.ui.QuireStrings

/** One thing a reader can do with the passage they have chosen. */
enum class SelectionAction { HIGHLIGHT, NOTE, COPY, SHARE, TRANSLATE, DICTIONARY }

/** An entry in the overflow, and whether anything on this phone would answer it. */
data class SelectionMenuItem(val action: SelectionAction, val enabled: Boolean)

/**
 * What a reader is offered when they choose a passage, and where each thing sits.
 *
 * Six actions is a lot to put over the words someone is trying to read. This app has
 * already been told once that a screen was cluttered, and the fix then was to take
 * things away — so the split is a rule rather than a layout, and the rule is here
 * where it can be asserted instead of in a composable where it can only be eyeballed:
 *
 * > **What stays in Quire is on the bar. What leaves it is behind More.**
 *
 * Highlight, Note and Copy finish inside the app or inside the reader's own
 * clipboard. They are what a selection is usually for, they are the three the
 * reference the reader showed puts on its top row, and — the part that makes the rule
 * hold — none of them can be missing. Share, Translate and Dictionary all end with
 * another app opening and Quire's part being over.
 *
 * That second property is the real reason for the line. Only a hand-off can be
 * unavailable, so grouping them keeps the bar exactly three buttons wide on every
 * phone, and the one list whose contents depend on what is installed is a list the
 * reader deliberately opened. A bar that is four buttons on one device and six on
 * another is a different obstruction over the text on each.
 */
object SelectionMenu {

    /** On the bar, in this order, on every phone. */
    val PRIMARY: List<SelectionAction> = listOf(
        SelectionAction.HIGHLIGHT,
        SelectionAction.NOTE,
        SelectionAction.COPY,
    )

    /**
     * Behind More, in this order, on every phone.
     *
     * Share first and always live: the system chooser is part of Android rather than
     * an app that can be absent, which is what guarantees More never opens on an
     * empty sheet.
     */
    val SECONDARY: List<SelectionAction> = listOf(
        SelectionAction.SHARE,
        SelectionAction.TRANSLATE,
        SelectionAction.DICTIONARY,
    )

    /**
     * The job [action] asks another app to do, or null when nothing needs resolving.
     *
     * Share returns null on purpose. It is a hand-off, but not one that can fail to
     * find a taker, so it is never drawn as unavailable.
     */
    fun handoff(action: SelectionAction): TextHandoff? = when (action) {
        SelectionAction.TRANSLATE -> TextHandoff.TRANSLATE
        SelectionAction.DICTIONARY -> TextHandoff.DEFINE
        else -> null
    }

    /**
     * The overflow, with each entry marked live or not.
     *
     * Every entry is present whatever is installed, and this is the judgement call.
     * Hiding the ones nothing answers is tidier and it is also silent: the reader
     * never learns the action exists, and the sheet is a different sheet on every
     * phone, so nobody can be told where anything is. A greyed row with a sentence
     * behind it says what is actually true — Quire cannot do this itself, and nothing
     * here can either — which is something a reader can act on.
     *
     * The order never changes with availability, because a list that reshuffles as
     * apps come and go has to be read every time instead of remembered.
     */
    fun overflow(available: Set<TextHandoff>): List<SelectionMenuItem> =
        SECONDARY.map { action ->
            val needs = handoff(action)
            SelectionMenuItem(action, enabled = needs == null || needs in available)
        }

    /**
     * What a tap on a greyed entry says.
     *
     * Never nothing: a tap that does nothing reads as a broken app, and this is the
     * case the developer's own phone cannot show them. It names no product and sends
     * nobody shopping — the same rule the share destinations follow — and it says the
     * limitation is Quire's by construction rather than an apology for a missing
     * feature.
     */
    fun unavailable(action: SelectionAction): String = when (action) {
        SelectionAction.TRANSLATE -> QuireStrings.NO_TRANSLATOR
        SelectionAction.DICTIONARY -> QuireStrings.NO_DICTIONARY
        else -> QuireStrings.NO_HANDLER
    }

    /** What an action is called on screen and to a screen reader. */
    fun label(action: SelectionAction): String = when (action) {
        SelectionAction.HIGHLIGHT -> QuireStrings.HIGHLIGHT
        SelectionAction.NOTE -> QuireStrings.NOTE
        SelectionAction.COPY -> QuireStrings.COPY
        SelectionAction.SHARE -> QuireStrings.SHARE
        SelectionAction.TRANSLATE -> QuireStrings.TRANSLATE
        SelectionAction.DICTIONARY -> QuireStrings.DICTIONARY
    }
}
