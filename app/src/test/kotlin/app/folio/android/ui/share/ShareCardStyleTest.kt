package app.folio.android.ui.share

import androidx.compose.ui.graphics.Color
import app.folio.android.ui.library.CoverGradient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Every card style is legible.
 *
 * A share card is the most public thing Folio makes, and its whole content is a
 * passage of someone's book. A style that puts pale type on a pale ground does not
 * look broken in the picker — the swatch is 40dp and the quote is not in it — so the
 * reader finds out after they have posted it. These fix the floor.
 *
 * The measure is WCAG's contrast ratio, the same one `FolioThemeTest` holds the app's
 * own palettes to. Body text needs 4.5:1; the secondary line — book, author, chapter
 * — is large and incidental enough for 3:1.
 */
class ShareCardStyleTest {

    /** WCAG relative luminance. */
    private fun Color.luminance(): Double {
        fun channel(v: Float): Double {
            val c = v.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    /** A translucent ink over its ground, as it will actually be seen. */
    private fun Color.over(ground: Color): Color = Color(
        red = red * alpha + ground.red * (1 - alpha),
        green = green * alpha + ground.green * (1 - alpha),
        blue = blue * alpha + ground.blue * (1 - alpha),
    )

    /** One book of each cover swatch, so every gradient in the set is exercised. */
    private fun oneBookPerCover(): List<String> {
        val found = mutableMapOf<Int, String>()
        var n = 0
        while (found.size < CoverGradient.size && n < 100_000) {
            val id = "book-$n"
            found.putIfAbsent(CoverGradient.indexOf(id), id)
            n++
        }
        assertEquals("could not reach every cover swatch", CoverGradient.size, found.size)
        return found.values.toList()
    }

    @Test
    fun `a quote clears 4,5 to 1 on every style and every book`() {
        val books = oneBookPerCover()
        ShareCardStyle.entries.forEach { style ->
            books.forEach { bookId ->
                val palette = style.palette(bookId)
                palette.background.forEach { ground ->
                    val ratio = contrast(palette.ink.over(ground), ground)
                    assertTrue(
                        "${style.label} on $bookId: the quote is ${"%.2f".format(ratio)}:1 " +
                            "against ${ground.hex()} — under 4.5:1, so the passage is " +
                            "hard to read in whatever feed it lands in",
                        ratio >= 4.5,
                    )
                }
            }
        }
    }

    @Test
    fun `the secondary line clears 3 to 1 on every style`() {
        val books = oneBookPerCover()
        ShareCardStyle.entries.forEach { style ->
            books.forEach { bookId ->
                val palette = style.palette(bookId)
                palette.background.forEach { ground ->
                    val ratio = contrast(palette.muted.over(ground), ground)
                    assertTrue(
                        "${style.label} on $bookId: the book and chapter line is " +
                            "${"%.2f".format(ratio)}:1, under 3:1",
                        ratio >= 3.0,
                    )
                }
            }
        }
    }

    @Test
    fun `a card with no book still has a style`() {
        // A reading streak belongs to no book, so Cover has no swatch to draw. It
        // must fall back rather than crash or invent one — a streak card is shared
        // from the habit screen, where there is no book in hand at all.
        ShareCardStyle.entries.forEach { style ->
            val palette = style.palette(null)
            assertTrue("${style.label} has no background", palette.background.isNotEmpty())
            palette.background.forEach { ground ->
                assertTrue(
                    "${style.label} with no book is unreadable",
                    contrast(palette.ink.over(ground), ground) >= 4.5,
                )
            }
        }
    }

    @Test
    fun `the scrim is what makes the palest cover readable`() {
        // The finding this exists to record: white on the lightest of the six
        // swatches measures 4.24:1 raw, which is under the floor. Five books would
        // have looked fine and the sixth would not, and it would have shipped.
        val worstRaw = oneBookPerCover().minOf { bookId ->
            val (start, end) = CoverGradient.colorsFor(bookId)
            listOf(start, end).minOf { contrast(Color.White, it) }
        }
        assertTrue(
            "the raw swatches now clear 4.5:1 on their own (worst " +
                "${"%.2f".format(worstRaw)}:1) — if that is a real change the scrim " +
                "can be reconsidered, but do not delete it on the strength of one book",
            worstRaw < 4.5,
        )
    }

    @Test
    fun `a flat style is a solid fill and a cover is a gradient`() {
        assertEquals(1, ShareCardStyle.PAPER.palette("b").background.size)
        assertEquals(2, ShareCardStyle.COVER.palette("b").background.size)
    }

    private fun Color.hex(): String =
        "#%02X%02X%02X".format((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
}
