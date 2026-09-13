package app.quire.android.ui.theme

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import app.quire.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The widget's first frame, pinned to the same tokens the app reads.
 *
 * These resources are no longer how a widget is themed — `widget/WidgetPalette.kt`
 * is, from the theme the reader actually chose. What is left to them is the moment
 * before that: the frame a launcher draws from `initialLayout` when a widget is
 * first dropped on a home screen, and the picker's previews, neither of which has a
 * database behind it. Light and dark is all a qualifier can distinguish, so Paper is
 * the light one and Night the dark one.
 *
 * They are still literals, and literals drift. A first frame in last year's accent
 * would flash the wrong colour on every home screen and nothing inside the app would
 * look wrong. This converts the same OKLCH tokens through the same transform
 * [QuireColors] uses and asserts they still match, exactly as [IcLauncherColorTest]
 * does for the launcher icon.
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
        val paper = QuirePalettes.Paper
        assertToken("widget_bg", paper.bg, R.color.widget_bg)
        assertToken("widget_ink", paper.ink, R.color.widget_ink)
        assertToken("widget_muted", paper.muted, R.color.widget_muted)
        assertToken("widget_border", paper.border, R.color.widget_border)
        assertToken("widget_accent", paper.accent, R.color.widget_accent)
    }

    @Test
    @Config(qualifiers = "night")
    fun `the dark widget wears Night`() {
        val night = QuirePalettes.Night
        assertToken("widget_bg", night.bg, R.color.widget_bg)
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
        val paper = QuirePalettes.Paper
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
