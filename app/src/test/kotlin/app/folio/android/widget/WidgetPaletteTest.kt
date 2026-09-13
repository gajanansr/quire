package app.folio.android.widget

import androidx.compose.ui.graphics.toArgb
import app.folio.android.ui.theme.FolioPalettes
import app.folio.android.ui.theme.FolioThemeName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The widget palette is legible in every theme a reader can choose.
 *
 * A widget is the one Folio surface nobody can proof-read: it is drawn in the
 * launcher's process, on someone else's home screen, in whichever of five themes
 * that reader picked months ago. Pale ink on a pale card does not look broken in a
 * screenshot of the theme the author happened to be using — it looks broken on the
 * fifth theme, on a phone this code never sees.
 *
 * The measure is WCAG's contrast ratio, exactly as
 * [app.folio.android.ui.share.ShareCardStyleTest] applies it to the share card,
 * which is where this approach caught a real failure at 4.24:1. Text needs 4.5:1;
 * the secondary line and the graphics that carry meaning — the day bars, the
 * progress fill, the mark — need 3:1, the floor WCAG 1.4.11 sets for non-text.
 */
class WidgetPaletteTest {

    /** WCAG relative luminance, from a packed ARGB int. */
    private fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrast(a: Int, b: Int): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun hex(argb: Int): String = String.format("#%08X", argb)

    private fun assertContrast(theme: FolioThemeName, what: String, ink: Int, floor: Double) {
        val surface = widgetPalette(theme).surface
        val ratio = contrast(ink, surface)
        assertTrue(
            "$theme: $what is ${"%.2f".format(ratio)}:1 (${hex(ink)} on ${hex(surface)}), " +
                "under ${"%.1f".format(floor)}:1 — unreadable on that reader's home screen",
            ratio >= floor,
        )
    }

    @Test
    fun `every theme's text clears 4,5 to 1 on its own card`() {
        FolioThemeName.entries.forEach { theme ->
            assertContrast(theme, "the headline", widgetPalette(theme).ink, 4.5)
        }
    }

    @Test
    fun `every theme's secondary text and graphics clear 3 to 1`() {
        FolioThemeName.entries.forEach { theme ->
            val palette = widgetPalette(theme)
            assertContrast(theme, "the detail line", palette.muted, 3.0)
            assertContrast(theme, "a day that was read", palette.accent, 3.0)
            assertContrast(theme, "the Folio mark", palette.mark, 3.0)
        }
    }

    @Test
    fun `the palette is the app's own, not a second copy of it`() {
        // The failure this prevents is the one WidgetColorTest was written for: a
        // literal pasted into the widget, and an accent that moves in the app and
        // not on the home screen.
        FolioThemeName.entries.forEach { theme ->
            val colors = FolioPalettes.of(theme)
            val palette = widgetPalette(theme)
            assertEquals("$theme surface", hex(colors.bg.toArgb()), hex(palette.surface))
            assertEquals("$theme edge", hex(colors.border.toArgb()), hex(palette.edge))
            assertEquals("$theme ink", hex(colors.ink.toArgb()), hex(palette.ink))
            assertEquals("$theme muted", hex(colors.muted.toArgb()), hex(palette.muted))
            assertEquals("$theme accent", hex(colors.accent.toArgb()), hex(palette.accent))
        }
    }

    @Test
    fun `E-ink stays grey, on the widget as everywhere else`() {
        // The defining property of the theme: a Paperwhite's panel is greyscale
        // hardware and cannot render a tint at all. One coloured token would undo
        // it, and an accent is exactly the token somebody would reach for.
        val palette = widgetPalette(FolioThemeName.EINK)
        listOf(
            "surface" to palette.surface,
            "edge" to palette.edge,
            "ink" to palette.ink,
            "muted" to palette.muted,
            "accent" to palette.accent,
            "mark" to palette.mark,
        ).forEach { (name, argb) ->
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            assertTrue(
                "E-ink's $name is ${hex(argb)}, which is not neutral",
                r == g && g == b,
            )
        }
    }

    @Test
    fun `every theme gets its own card`() {
        // A `when` with a branch copy-pasted and not edited returns the wrong
        // palette and nothing else notices: the widget simply wears someone else's
        // theme.
        val surfaces = FolioThemeName.entries.map { widgetPalette(it).surface }
        assertEquals(
            "two themes draw the same card: ${surfaces.map(::hex)}",
            FolioThemeName.entries.size,
            surfaces.distinct().size,
        )
    }

    @Test
    fun `the card is opaque`() {
        // A translucent widget card would composite against the wallpaper, and
        // every contrast number above would be a measurement of a colour nobody
        // actually sees.
        FolioThemeName.entries.forEach { theme ->
            val surface = widgetPalette(theme).surface
            assertTrue(
                "$theme's card is ${hex(surface)}, which is not fully opaque",
                ((surface shr 24) and 0xFF) == 0xFF,
            )
        }
    }
}
