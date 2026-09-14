package app.quire.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * A highlight has to be readable *through*.
 *
 * The passage under a highlight is the whole reason the reader made it, so the mark
 * must tint the page rather than stain it. A colour that looks right on the swatch
 * row and swallows the words is not visibly broken — the swatch is 24dp and the
 * sentence is not in it — so nobody finds out until they are reading with it.
 *
 * The measure is WCAG's contrast ratio, the same one [QuireThemeTest] holds the
 * palettes to and the same method `ShareCardStyleTest` used to catch the share card
 * at 4.24:1. Four properties, and all four have to hold at once: the mark is legible,
 * the mark is *visible*, no two marks look alike, and on E-ink no mark has any colour
 * in it at all.
 */
class QuireHighlightsTest {

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

    /** The largest per-channel difference between two colours. */
    private fun separation(a: Color, b: Color): Float =
        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    private fun Color.hex(): String =
        "#%02X%02X%02X".format((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

    private fun pageOf(theme: QuireThemeName) = QuirePalettes.of(theme).readerBg
    private fun inkOf(theme: QuireThemeName) = QuirePalettes.of(theme).ink

    @Test
    fun `text on a highlight clears WCAG AA on every theme and every colour`() {
        // The floor the whole feature stands on. A highlighted run is drawn in the
        // theme's own ink — see ReaderMark, which is why a blockquote's muted grey
        // does not appear here — so this is the one number that has to hold.
        QuireThemeName.entries.forEach { theme ->
            HighlightColour.entries.forEach { colour ->
                val ground = QuireHighlights.over(theme, colour)
                val ratio = contrast(inkOf(theme), ground)
                assertTrue(
                    "$theme ${colour.label}: text is ${"%.2f".format(ratio)}:1 on " +
                        "${ground.hex()} — under 4.5:1, so the passage the reader " +
                        "marked is the hardest thing on the page to read",
                    ratio >= 4.5,
                )
            }
        }
    }

    @Test
    fun `a highlight is visible against its own page`() {
        // The other half of the contrast test, and the one that keeps it honest: an
        // alpha of 0.02 would clear 4.5:1 on every theme by painting nothing at all.
        // Measured per channel rather than by luminance because most of these differ
        // from the page in hue, where a luminance ratio says almost nothing.
        QuireThemeName.entries.forEach { theme ->
            val page = pageOf(theme)
            HighlightColour.entries.forEach { colour ->
                val ground = QuireHighlights.over(theme, colour)
                val apart = separation(ground, page)
                assertTrue(
                    "$theme ${colour.label}: ${ground.hex()} is %.3f from the page ".format(apart) +
                        "(${page.hex()}) — too faint to read as a mark",
                    apart >= 0.09f,
                )
            }
        }
    }

    @Test
    fun `no two highlight colours look alike on any one theme`() {
        // Five marks a reader cannot tell apart are one mark with extra steps. This
        // is the constraint that decides how many colours there can be: on E-ink,
        // where they differ only in tone, the band between "visible" and "still
        // legible" is what caps the set at five.
        QuireThemeName.entries.forEach { theme ->
            val grounds = HighlightColour.entries.map { it to QuireHighlights.over(theme, it) }
            grounds.forEach { (a, ga) ->
                grounds.forEach { (b, gb) ->
                    if (a != b) {
                        val apart = separation(ga, gb)
                        assertTrue(
                            "$theme: ${a.label} (${ga.hex()}) and ${b.label} " +
                                "(${gb.hex()}) are %.3f apart — the same mark ".format(apart) +
                                "wearing two names",
                            apart >= 0.06f,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `e-ink highlights have no colour at all`() {
        // The same rule QuireThemeTest holds the E-ink palette to, extended to the
        // twenty-five values that palette does not cover. An electrophoretic panel
        // is greyscale hardware: a tinted highlight is not a subtle highlight there,
        // it is one the reader's device cannot draw. Asserted on both the wash and
        // the composite, since a neutral wash over a neutral page is the only way
        // the mark that actually lands stays neutral.
        HighlightColour.entries.forEach { colour ->
            val tint = QuireHighlights.tint(QuireThemeName.EINK, colour)
            assertEquals("e-ink ${colour.label} wash is tinted (${tint.hex()})", tint.red, tint.green)
            assertEquals("e-ink ${colour.label} wash is tinted (${tint.hex()})", tint.green, tint.blue)
            val ground = QuireHighlights.over(QuireThemeName.EINK, colour)
            assertEquals("e-ink ${colour.label} is tinted (${ground.hex()})", ground.red, ground.green)
            assertEquals("e-ink ${colour.label} is tinted (${ground.hex()})", ground.green, ground.blue)
        }
    }

    @Test
    fun `e-ink tells its highlights apart by tone, in order`() {
        // What a colour *becomes* on a panel that has no colour. The five keep their
        // identity by darkness instead of hue, and the order is part of the design —
        // a reader learns the ramp, so a tone that jumped its place in the row would
        // silently swap two of their categories over.
        val tones = HighlightColour.entries.map {
            QuireHighlights.over(QuireThemeName.EINK, it).luminance()
        }
        assertEquals(
            "e-ink's tones are not in declaration order, lightest first: $tones",
            tones.sortedDescending(),
            tones,
        )
    }

    @Test
    fun `every wash is translucent, so a highlight tints the page rather than replacing it`() {
        // The reader's actual complaint. An opaque mark is a block of colour with
        // words on it; a wash is the page seen through a colour. It also makes the
        // dark themes work without a second palette — a bright pigment mixed into a
        // near-black page lands dark on its own.
        QuireThemeName.entries.forEach { theme ->
            HighlightColour.entries.forEach { colour ->
                val alpha = QuireHighlights.tint(theme, colour).alpha
                assertTrue("$theme ${colour.label} is opaque", alpha < 1f)
                assertTrue("$theme ${colour.label} is invisible (alpha $alpha)", alpha > 0.1f)
            }
        }
    }

    @Test
    fun `every pigment is inside the sRGB gamut`() {
        // OKLCH describes colours sRGB cannot show, and [oklchToSrgb] clamps rather
        // than failing. A clamped channel shifts the hue, so a chroma chosen one step
        // too high quietly pulls Doubt and Keep towards each other with nothing on
        // screen to say why — and the "no two look alike" test above would then be
        // measuring colours nobody chose.
        QuireThemeName.entries.forEach { theme ->
            HighlightColour.entries.forEach { colour ->
                val (l, c, h) = QuireHighlights.pigmentOf(theme, colour)
                val (r, g, b) = oklchToLinearSrgb(l, c, h)
                listOf(r, g, b).forEach { channel ->
                    assertTrue(
                        "$theme ${colour.label} is outside sRGB at " +
                            "oklch($l, $c, $h) — channel %.4f".format(channel),
                        channel >= -1e-6 && channel <= 1.0 + 1e-6,
                    )
                }
            }
        }
    }

    @Test
    fun `a colour saved by a version that knows more of them still paints`() {
        // The stored value is a plain string, exactly as the theme name is. An
        // unreadable one has to resolve to a real colour: the alternative is a span
        // painted Color.Unspecified, which is a highlight the reader made and can no
        // longer see.
        HighlightColour.entries.forEach { assertEquals(it, highlightColourNamed(it.name)) }
        assertEquals(HighlightColour.KEEP, highlightColourNamed(null))
        assertEquals(HighlightColour.KEEP, highlightColourNamed(""))
        assertEquals(HighlightColour.KEEP, highlightColourNamed("TEAL"))
    }

    @Test
    fun `every colour has a name, and no two share one`() {
        val labels = HighlightColour.entries.map { it.label }
        labels.forEach { label ->
            assertTrue("a raw enum name reached the UI: $label", label != label.uppercase())
        }
        assertEquals(
            "two highlight colours share a name: $labels",
            HighlightColour.entries.size,
            labels.distinct().size,
        )
    }

    @Test
    fun `gold is the default, because it is the only colour a highlight has ever been`() {
        // Tied to the migration: MIGRATION_7_8 seeds every existing row with this
        // name, and the point of that choice is that nothing on anybody's page
        // changes colour on the update.
        assertEquals(HighlightColour.KEEP, HighlightColour.DEFAULT)
        assertEquals(HighlightColour.KEEP, HighlightColour.entries.first())
    }
}
