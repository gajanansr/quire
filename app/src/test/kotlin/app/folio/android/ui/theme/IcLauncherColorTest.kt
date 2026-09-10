package app.folio.android.ui.theme

import androidx.test.core.app.ApplicationProvider
import android.app.Application
import app.folio.android.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The launcher icon's colours, pinned to the handoff's tokens.
 *
 * A launcher icon is resolved by the system before any Folio code runs, so its
 * colours cannot come from [FolioColors] at runtime — they are literals in
 * `ic_launcher_colors.xml`. Literals drift. This converts the same OKLCH tokens
 * through the same transform the rest of the palette uses and asserts the icon
 * still matches, so a change to the accent colour cannot silently leave the icon
 * behind on every home screen.
 */
@RunWith(RobolectricTestRunner::class)
class IcLauncherColorTest {

    private fun resource(id: Int): Int =
        ApplicationProvider.getApplicationContext<Application>().getColor(id)

    private fun token(l: Double, c: Double, h: Double): Int {
        val (r, g, b) = oklchToSrgb(l, c, h)
        return android.graphics.Color.argb(255, r, g, b)
    }

    @Test
    fun `the icon ground is the Light accent`() {
        assertEquals(
            "ic_launcher_background drifted from oklch(0.42 0.09 250)",
            token(0.42, 0.09, 250.0),
            resource(R.color.ic_launcher_background),
        )
    }

    @Test
    fun `the letter is the Light page colour`() {
        assertEquals(
            "ic_launcher_ink drifted from oklch(0.985 0.006 70)",
            token(0.985, 0.006, 70.0),
            resource(R.color.ic_launcher_ink),
        )
    }
}
