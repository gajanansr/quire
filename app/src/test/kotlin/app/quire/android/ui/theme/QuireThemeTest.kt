package app.quire.android.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuireThemeTest {

    private fun Color.hex(): String =
        "#%02X%02X%02X".format((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

    private fun tokensOf(c: QuireColors) = mapOf(
        "bg" to c.bg, "bgAlt" to c.bgAlt, "ink" to c.ink, "muted" to c.muted,
        "border" to c.border, "accent" to c.accent, "accentSoft" to c.accentSoft,
        "buttonBg" to c.buttonBg, "buttonText" to c.buttonText,
        "readerBg" to c.readerBg, "highlight" to c.highlight,
        "errorBg" to c.errorBg, "errorText" to c.errorText,
    )

    /** WCAG relative luminance. */
    private fun Color.luminance(): Double {
        fun channel(v: Float): Double {
            val c = v.toDouble()
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `paper keeps the handoff's Light tokens exactly`() = with(QuirePalettes.Paper) {
        assertEquals("#FDF9F6", bg.hex())
        assertEquals("#F7EFE7", bgAlt.hex())
        assertEquals("#111B28", ink.hex())
        assertEquals("#6A727D", muted.hex())
        assertEquals("#214F7C", accent.hex())
        assertEquals("#FDF5EF", readerBg.hex())
    }

    @Test
    fun `night is grey rather than black, with text muted below white`() {
        // Pure white on pure black produces the strongest halation, and around half
        // of people have some astigmatism. The published guidance is a ground near
        // #1C1C1E and text near #D4D4D4 rather than #FFFFFF; these bounds keep the
        // theme inside it. True black is a separate theme for people who want it.
        val night = QuirePalettes.Night
        assertTrue("night bg is too close to black (${night.bg.hex()})", night.bg.red > 0.06f)
        assertTrue("night bg is too light (${night.bg.hex()})", night.bg.red < 0.18f)
        assertTrue("night ink is pure white (${night.ink.hex()})", night.ink.red < 0.92f)
        assertTrue("night ink is too dim (${night.ink.hex()})", night.ink.red > 0.70f)
    }

    @Test
    fun `black is true black, and dims its text further than night does`() {
        val black = QuirePalettes.Black
        assertEquals("#000000", black.bg.hex())
        assertEquals("#000000", black.readerBg.hex())
        // The halation this theme invites is worst at the extremes, and dimming the
        // text is the part that helps.
        assertTrue(
            "black's ink should be dimmer than night's",
            black.ink.luminance() < QuirePalettes.Night.ink.luminance(),
        )
    }

    @Test
    fun `e-ink has no colour at all`() {
        // The defining property of the theme, and the reason it is asserted rather
        // than eyeballed: an electrophoretic panel is greyscale hardware — a Kindle
        // Paperwhite cannot render sepia or green — so a single tinted token would
        // undo the whole theme, and would be easy to introduce by copying a value
        // from a neighbouring palette.
        val eink = QuirePalettes.Eink
        tokensOf(eink).forEach { (name, c) ->
            assertEquals("e-ink $name is tinted (${c.hex()})", c.red, c.green)
            assertEquals("e-ink $name is tinted (${c.hex()})", c.green, c.blue)
        }
    }

    @Test
    fun `e-ink sits in the contrast range of a real panel`() {
        // E Ink Carta 1200 measures roughly 15:1 to 17:1, and its white is a
        // reflective off-white rather than an emitted #FFFFFF. Pure black on pure
        // white would be 21:1 — brighter and harsher than the thing it imitates.
        val eink = QuirePalettes.Eink
        val onPage = contrast(eink.ink, eink.readerBg)
        assertTrue("e-ink reads at %.1f:1, outside a panel's range".format(onPage),
            onPage in 14.0..18.0)
        assertTrue("e-ink's page is pure white", eink.readerBg.red < 0.99f)
        assertTrue("e-ink's ink is pure black", eink.ink.red > 0.02f)
    }

    @Test
    fun `every theme resolves to a palette`() {
        QuireThemeName.entries.forEach { assertTrue(QuirePalettes.of(it).bg != Color.Unspecified) }
    }

    @Test
    fun `no two themes occupy the same band`() {
        // The set this replaced had Light at L=0.985 and Pale at L=0.965 — close
        // enough that a reader switching between them saw no change, which is the
        // failure this guards. Pages a hair apart in luminance are the same theme
        // wearing two names.
        // Measured as the largest per-channel difference rather than by luminance:
        // Sepia and E-ink sit at almost identical brightness and are told apart by
        // hue alone, while Night and Black differ in brightness at a point where
        // luminance is so compressed that the numbers stop meaning anything. One
        // metric has to catch both kinds of difference.
        val pages = QuireThemeName.entries.map { it to QuirePalettes.of(it).readerBg }
        pages.forEach { (a, pageA) ->
            pages.forEach { (b, pageB) ->
                if (a != b) {
                    val separation = maxOf(
                        kotlin.math.abs(pageA.red - pageB.red),
                        kotlin.math.abs(pageA.green - pageB.green),
                        kotlin.math.abs(pageA.blue - pageB.blue),
                    )
                    assertTrue(
                        "$a and $b are the same theme wearing two names " +
                            "(channels differ by at most %.3f)".format(separation),
                        separation > 0.06f,
                    )
                }
            }
        }
    }

    @Test
    fun `body text clears WCAG AA on every theme`() {
        // The reading page is the one surface where this cannot be negotiable: it is
        // what someone looks at for an hour. 4.5:1 is the AA floor for body text.
        QuireThemeName.entries.forEach { name ->
            val c = QuirePalettes.of(name)
            val onPage = contrast(c.ink, c.readerBg)
            assertTrue(
                "$name body text is %.1f:1, below the 4.5:1 floor".format(onPage),
                onPage >= 4.5,
            )
            val muted = contrast(c.muted, c.bg)
            assertTrue(
                "$name secondary text is %.1f:1, below the 4.5:1 floor".format(muted),
                muted >= 4.5,
            )
        }
    }

    @Test
    fun `light themes put ink on a page and dark themes invert it`() {
        fun page(n: QuireThemeName) = QuirePalettes.of(n).let { it.readerBg.luminance() to it.ink.luminance() }
        listOf(QuireThemeName.PAPER, QuireThemeName.SEPIA, QuireThemeName.EINK).forEach {
            val (bg, ink) = page(it)
            assertTrue("$it should be dark ink on a light page", bg > ink)
        }
        listOf(QuireThemeName.NIGHT, QuireThemeName.BLACK).forEach {
            val (bg, ink) = page(it)
            assertTrue("$it should be light ink on a dark page", bg < ink)
        }
    }

    @Test
    fun `a theme saved under an old name survives the rename`() {
        // The stored value is a plain string and the set it can hold changed.
        // Matching on the enum alone would quietly reset an upgrading reader to the
        // default — small, but they chose it once and would have to choose again.
        assertEquals(QuireThemeName.PAPER, themeNamed("LIGHT"))
        assertEquals(QuireThemeName.SEPIA, themeNamed("PALE"))
        assertEquals(QuireThemeName.NIGHT, themeNamed("DARK"))
        assertEquals(QuireThemeName.EINK, themeNamed("EINK"))
        QuireThemeName.entries.forEach { assertEquals(it, themeNamed(it.name)) }
        assertEquals(QuireThemeName.PAPER, themeNamed(null))
        assertEquals(QuireThemeName.PAPER, themeNamed("nonsense"))
    }

    @Test
    fun `button text contrasts with button background in every theme`() {
        QuireThemeName.entries.forEach { name ->
            val c = QuirePalettes.of(name)
            fun Color.luma() = 0.2126 * red + 0.7152 * green + 0.0722 * blue
            val delta = kotlin.math.abs(c.buttonBg.luma() - c.buttonText.luma())
            assertTrue("$name button text is unreadable (delta $delta)", delta > 0.3)
        }
    }

    @Test
    fun `reader fonts map to four distinct families`() {
        val families = ReaderFont.entries.map { it.family() }
        assertEquals(4, families.toSet().size)
        assertEquals(listOf("Serif", "Lora", "Sans", "System"), ReaderFont.entries.map { it.label })
    }

    @Test
    fun `every theme has a human name, and no two share one`() {
        // The picker labels its previews with these and Settings cycles through
        // them, so a missing or duplicated name is a control the reader cannot
        // read. "EINK" reaching the screen is the specific failure worth naming.
        val labels = QuireThemeName.entries.map { it.label() }
        labels.forEach { label ->
            assertTrue("a raw enum name reached the UI: $label", label != label.uppercase())
            assertTrue("empty theme name", label.isNotBlank())
        }
        assertEquals(
            "two themes share a name: $labels",
            QuireThemeName.entries.size,
            labels.distinct().size,
        )
    }

}