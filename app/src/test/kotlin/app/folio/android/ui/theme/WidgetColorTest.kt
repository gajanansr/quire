package app.folio.android.ui.theme

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import app.folio.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The widget palette, pinned to the same tokens the app reads.
 *
 * A home-screen widget is inflated in the launcher's process, long before
 * `FolioTheme` exists and with no way to open the database that holds the reader's
 * chosen theme. Its colours are therefore literals in `res/values` and
 * `res/values-night`, and literals drift — the widget would keep last year's accent
 * on every home screen while the app moved on, and nothing inside the app would look
 * wrong. This converts the same OKLCH tokens through the same transform
 * [FolioColors] uses and asserts the literals still match, exactly as
 * [IcLauncherColorTest] does for the launcher icon.
 *
 * Paper is the light palette and Night the dark one because those are the two states
 * a resource qualifier can tell apart. Sepia, E-ink and Black are choices a reader
 * makes inside the app; the launcher has no way to know about them.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetColorTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    /** Hex rather than an int, so a failure reads as the value to paste. */
    private fun hex(argb: Int): String = String.format("#%08X", argb)

    private fun assertToken(name: String, expected: Color, id: Int) {
        assertEquals(
            "R.color.$name drifted from the palette",
            hex(expected.toArgb()),
            hex(app.getColor(id)),
        )
    }

    @Test
    fun `the light widget wears Paper`() {
        val paper = FolioPalettes.Paper
        assertToken("widget_bg", paper.bg, R.color.widget_bg)
        assertToken("widget_bg_alt", paper.bgAlt, R.color.widget_bg_alt)
        assertToken("widget_ink", paper.ink, R.color.widget_ink)
        assertToken("widget_muted", paper.muted, R.color.widget_muted)
        assertToken("widget_border", paper.border, R.color.widget_border)
        assertToken("widget_accent", paper.accent, R.color.widget_accent)
    }

    @Test
    @Config(qualifiers = "night")
    fun `the dark widget wears Night`() {
        val night = FolioPalettes.Night
        assertToken("widget_bg", night.bg, R.color.widget_bg)
        assertToken("widget_bg_alt", night.bgAlt, R.color.widget_bg_alt)
        assertToken("widget_ink", night.ink, R.color.widget_ink)
        assertToken("widget_muted", night.muted, R.color.widget_muted)
        assertToken("widget_border", night.border, R.color.widget_border)
        assertToken("widget_accent", night.accent, R.color.widget_accent)
    }

    @Test
    @Config(qualifiers = "night")
    fun `the night qualifier is actually reached`() {
        // Every assertion above would still pass if values-night were never picked
        // up and Night happened to equal Paper — it does not, so a file in the wrong
        // directory, or a typo in its name, shows up here rather than as a white
        // widget in a dark launcher.
        val paper = FolioPalettes.Paper
        listOf(
            Triple("widget_bg", paper.bg, R.color.widget_bg),
            Triple("widget_ink", paper.ink, R.color.widget_ink),
            Triple("widget_accent", paper.accent, R.color.widget_accent),
        ).forEach { (name, light, id) ->
            assertNotEquals(
                "R.color.$name is the light value under the night qualifier",
                hex(light.toArgb()),
                hex(app.getColor(id)),
            )
        }
    }
}
