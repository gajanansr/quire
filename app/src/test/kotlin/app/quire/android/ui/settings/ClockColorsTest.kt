package app.quire.android.ui.settings

import androidx.compose.ui.graphics.Color
import app.quire.android.ui.theme.QuireColors
import app.quire.android.ui.theme.QuirePalettes
import app.quire.android.ui.theme.QuireThemeName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The clock, in Quire's colours.
 *
 * Material paints `TimePicker` from its own scheme unless every one of fourteen
 * colours is supplied, and the app has already made that mistake once — the exit
 * dialog arrived in the platform's lavender on a paper-coloured page. A clock in
 * Material purple on the E-ink theme would be the one surface in Quire that ignores
 * the theme the reader picked, and it is not something a unit test can see. What it
 * can see is where each colour came from, which is what this file checks.
 *
 * The measure for legibility is WCAG's contrast ratio, the same one `QuireThemeTest`
 * and `ShareCardStyleTest` use.
 */
class ClockColorsTest {

    private fun Color.hex(): String =
        "#%02X%02X%02X".format((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

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

    private fun tokensOf(c: QuireColors) = setOf(
        c.bg, c.bgAlt, c.ink, c.muted, c.border, c.accent, c.accentSoft,
        c.buttonBg, c.buttonText, c.readerBg, c.errorBg, c.errorText,
    )

    @Test
    fun `every colour on the clock comes from the reader's own theme`() {
        // The rule, as an assertion rather than as a note in a review. Compared
        // against the palette rather than against literals, so a token that changes
        // carries the clock with it instead of leaving it behind.
        QuireThemeName.entries.forEach { theme ->
            val palette = QuirePalettes.of(theme)
            val allowed = tokensOf(palette)
            quireClockColors(palette).all().forEach { colour ->
                assertTrue(
                    "$theme's clock uses ${colour.hex()}, which is not one of its tokens",
                    colour in allowed,
                )
            }
        }
    }

    @Test
    fun `the clock is painted fourteen times over, with nothing left to Material`() {
        // Material's `colors()` fills any argument that is not supplied from its own
        // scheme, silently. Fourteen is the whole surface, and a count here is what
        // catches a colour added to `TimePickerColors` in a later Compose release
        // and not added here — the failure would otherwise be one purple ring on a
        // sepia page that nobody notices until a reader reports it.
        assertEquals(14, quireClockColors(QuirePalettes.Paper).all().size)
    }

    @Test
    fun `the numbers on the dial can be read in every theme`() {
        // A clock nobody can read is not a picker. E-ink is the one that gets close:
        // it is near-black on near-white by design, and its accent is another
        // near-black, so the selected number sitting on the selector is the pair
        // most at risk.
        QuireThemeName.entries.forEach { theme ->
            val clock = quireClockColors(QuirePalettes.of(theme))

            val unselected = contrast(clock.clockDialUnselectedContent, clock.clockDial)
            assertTrue(
                "$theme's unselected clock numbers are ${"%.2f".format(unselected)}:1",
                unselected >= 4.5,
            )
            val selected = contrast(clock.clockDialSelectedContent, clock.selector)
            assertTrue(
                "$theme's selected clock number is ${"%.2f".format(selected)}:1",
                selected >= 4.5,
            )
            val chosen = contrast(clock.timeSelectorSelectedContent, clock.timeSelectorSelectedContainer)
            assertTrue(
                "$theme's chosen hour is ${"%.2f".format(chosen)}:1",
                chosen >= 4.5,
            )
            val other = contrast(clock.timeSelectorUnselectedContent, clock.timeSelectorUnselectedContainer)
            assertTrue(
                "$theme's unchosen hour is ${"%.2f".format(other)}:1",
                other >= 4.5,
            )
        }
    }

    @Test
    fun `the am pm toggle can be read in every theme`() {
        QuireThemeName.entries.forEach { theme ->
            val clock = quireClockColors(QuirePalettes.of(theme))
            listOf(
                "selected" to (clock.periodSelectorSelectedContent to
                    clock.periodSelectorSelectedContainer),
                "unselected" to (clock.periodSelectorUnselectedContent to
                    clock.periodSelectorUnselectedContainer),
            ).forEach { (which, pair) ->
                val ratio = contrast(pair.first, pair.second)
                assertTrue(
                    "$theme's $which period label is ${"%.2f".format(ratio)}:1",
                    ratio >= 4.5,
                )
            }
        }
    }

    @Test
    fun `the selected state is visibly different from the unselected one`() {
        // Colour-blind readers aside, a selector the same colour as the dial is a
        // picker with no feedback at all. Measured as contrast between the two
        // grounds rather than as inequality, since two near-identical colours are
        // technically different and indistinguishable in practice.
        QuireThemeName.entries.forEach { theme ->
            val clock = quireClockColors(QuirePalettes.of(theme))
            val ratio = contrast(clock.selector, clock.clockDial)
            assertTrue(
                "$theme's selector barely separates from the dial (${"%.2f".format(ratio)}:1)",
                ratio >= 2.0,
            )
        }
    }
}
