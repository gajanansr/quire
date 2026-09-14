package app.quire.android.ui.bookmarks

import app.quire.android.data.BookmarkEntity
import app.quire.android.ui.theme.HighlightColour
import app.quire.android.ui.theme.QuireHighlights
import app.quire.android.ui.theme.QuireThemeName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Bookmarks list and the page agree about colour.
 *
 * Two screens showing the same mark is two chances to resolve it differently: a
 * reader marks a passage Doubt, sees rose on the page and something else in the list,
 * and now neither one is trustworthy. Both go through [QuireHighlights.over], and
 * this is what says so.
 */
class BookmarkSwatchTest {

    private fun highlight(colour: HighlightColour) = BookmarkEntity(
        bookId = "b1", chapterIndex = 1, blockIndex = 2, charOffset = 10,
        endBlockIndex = 2, endCharOffset = 64, highlightColour = colour.name,
        snippet = "The words.", createdAt = 1,
    )

    private fun place() = BookmarkEntity(
        bookId = "b1", chapterIndex = 1, blockIndex = 2, charOffset = 10,
        snippet = "A saved place", createdAt = 1,
    )

    @Test
    fun `a highlight's swatch is the colour its page draws`() {
        QuireThemeName.entries.forEach { theme ->
            HighlightColour.entries.forEach { colour ->
                assertEquals(
                    "$theme ${colour.label}: the list and the page disagree",
                    QuireHighlights.over(theme, colour),
                    bookmarkSwatch(highlight(colour), theme),
                )
            }
        }
    }

    @Test
    fun `a plain bookmark has no swatch`() {
        // It marks a place and has no words to colour. A swatch there would invent a
        // category the reader never chose, and gold would be the obvious one to
        // invent — which is exactly the meaning "worth remembering" already has.
        QuireThemeName.entries.forEach { assertNull(bookmarkSwatch(place(), it)) }
    }

    @Test
    fun `a colour the app does not recognise still shows something`() {
        // A row from a later version, or a restored backup. An unresolvable name must
        // not leave a blank hole in the list where every other row has a colour.
        val strange = highlight(HighlightColour.KEEP).copy(highlightColour = "TEAL")
        assertEquals(
            QuireHighlights.over(QuireThemeName.PAPER, HighlightColour.DEFAULT),
            bookmarkSwatch(strange, QuireThemeName.PAPER),
        )
    }

    @Test
    fun `the swatch follows the theme the reader is in`() {
        // The stored value is a choice, not a colour. The same row is a cream wash on
        // Paper and a grey on E-ink, and that is the point of storing the choice.
        val doubt = highlight(HighlightColour.DOUBT)
        val onPaper = bookmarkSwatch(doubt, QuireThemeName.PAPER)
        val onEink = bookmarkSwatch(doubt, QuireThemeName.EINK)
        org.junit.Assert.assertNotEquals("the swatch ignored the theme", onPaper, onEink)
        assertEquals("e-ink's swatch is tinted", onEink!!.red, onEink.green)
    }
}
