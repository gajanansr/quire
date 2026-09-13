package app.quire.android.ui.theme

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.quire.android.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import kotlin.math.hypot

/**
 * The launcher mark stays where every launcher can show it.
 *
 * An adaptive icon is masked by the launcher, not by Quire: a circle here, a squircle
 * there, a rounded square somewhere else. Android reserves a 66dp circle inside the
 * 108dp canvas as the only region guaranteed to survive all of them. A mark that
 * strays outside is not wrong on the machine you built it on — it is wrong on one
 * phone in five, with a shaved corner nobody reports.
 *
 * Checked by reading the drawable's own path data back and bounding every coordinate,
 * control points included. That is conservative in the right direction: a Bézier
 * curve never leaves the convex hull of its control points, so a mark that passes
 * here cannot be clipped even where the curve bulges hardest.
 */
@RunWith(RobolectricTestRunner::class)
class LauncherMarkTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private companion object {
        const val CANVAS = 108.0
        const val CENTRE = CANVAS / 2
        /** Android's guaranteed-visible region: 66dp across, centred. */
        const val SAFE_RADIUS = 66.0 / 2
    }

    /** Every `android:pathData` string in a vector drawable. */
    private fun pathData(drawable: Int): List<String> {
        val parser = app.resources.getXml(drawable)
        val paths = mutableListOf<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "path") {
                (0 until parser.attributeCount)
                    .firstOrNull { parser.getAttributeName(it) == "pathData" }
                    ?.let { paths += parser.getAttributeValue(it) }
            }
            event = parser.next()
        }
        return paths
    }

    /**
     * The coordinate pairs in a path.
     *
     * Only M, L, C and Z are used in this drawable, and all three of the drawing
     * commands take whole coordinate pairs — so every number pairs off cleanly. A
     * later edit reaching for H or V would break that assumption, which is why the
     * pairing is asserted rather than assumed.
     */
    private fun points(path: String): List<Pair<Double, Double>> {
        val commands = path.filter { it.isLetter() }.uppercase().toSet()
        assertTrue(
            "the mark uses a command that does not take coordinate pairs: $commands",
            commands.all { it in setOf('M', 'L', 'C', 'Z') },
        )
        val numbers = Regex("-?\\d+(?:\\.\\d+)?").findAll(path)
            .map { it.value.toDouble() }
            .toList()
        assertTrue("odd number of coordinates in $path", numbers.size % 2 == 0)
        return numbers.chunked(2).map { it[0] to it[1] }
    }

    @Test
    fun `the mark stays inside the safe circle`() {
        val paths = pathData(R.drawable.ic_launcher_foreground)
        assertTrue("the launcher mark has no paths", paths.isNotEmpty())

        paths.forEach { path ->
            points(path).forEach { (x, y) ->
                val distance = hypot(x - CENTRE, y - CENTRE)
                assertTrue(
                    "($x, $y) is ${"%.1f".format(distance)} from the centre, outside " +
                        "the ${SAFE_RADIUS}dp safe radius — a launcher with a circular " +
                        "mask will clip it",
                    distance <= SAFE_RADIUS,
                )
            }
        }
    }

    @Test
    fun `the mark is drawn on the canvas it declares`() {
        // A coordinate outside 0..108 means the viewport and the artwork disagree,
        // which scales the whole mark wrongly rather than clipping one corner.
        pathData(R.drawable.ic_launcher_foreground).forEach { path ->
            points(path).forEach { (x, y) ->
                assertTrue("($x, $y) is off the 108dp canvas", x in 0.0..CANVAS)
                assertTrue("($x, $y) is off the 108dp canvas", y in 0.0..CANVAS)
            }
        }
    }

    @Test
    fun `the mark is two leaves with a fold between them`() {
        // The shape is the point: a quire is folded sheets, and this is the fold. Two paths that
        // do not meet, mirrored about the centre — if a later edit merges them into
        // one silhouette the fold is gone and so is the idea.
        val paths = pathData(R.drawable.ic_launcher_foreground)
        assertTrue("expected two leaves, found ${paths.size}", paths.size == 2)

        val (left, right) = paths.map { points(it) }
        assertTrue("the left leaf crosses the fold", left.all { it.first <= CENTRE })
        assertTrue("the right leaf crosses the fold", right.all { it.first >= CENTRE })
        assertTrue(
            "the leaves touch, so there is no fold",
            right.minOf { it.first } - left.maxOf { it.first } > 1.0,
        )
    }

    @Test
    fun `a themed icon has a monochrome layer to tint`() {
        // Android 13+ recolours the monochrome layer to match the wallpaper. Without
        // one the launcher falls back to the full-colour icon, which is the single
        // odd tile on an otherwise themed home screen.
        val parser = app.resources.getXml(R.mipmap.ic_launcher)
        val layers = mutableSetOf<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) layers += parser.name
            event = parser.next()
        }
        assertTrue("no monochrome layer: $layers", "monochrome" in layers)
        assertTrue("no background layer: $layers", "background" in layers)
        assertTrue("no foreground layer: $layers", "foreground" in layers)
    }
}
