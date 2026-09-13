package app.folio.android.widget

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.folio.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import kotlin.math.abs

/**
 * The Folio mark, as a widget can wear it.
 *
 * The same folio the launcher draws — one sheet folded once — but not the same
 * drawable, and the two differences are the whole point of this file:
 *
 *  - **One colour.** The launcher's shadowed leaf is an 0.8-alpha page over a fixed
 *    navy ground. A widget has five grounds, and that leaf is mud on Black and
 *    invisible on E-ink. A flat single fill is tinted at the use site, the way every
 *    other icon in Folio is, and reads on all five.
 *  - **A wider fold.** The launcher spends its 108dp canvas on the adaptive-icon
 *    safe circle and tapers the fold to under a unit at mid-height. Scaled down to a
 *    16dp corner mark that fold closes up entirely and the mark becomes a blob. This
 *    one is drawn on a 24 grid with the gap held open.
 *
 * Checked by reading the path data back, the way [app.folio.android.ui.theme
 * .LauncherMarkTest] checks the safe circle. Control points are bounded rather than
 * the curve itself, which is conservative in the right direction: a Bézier never
 * leaves the hull of its control points, so a fold that is open here is open when
 * drawn.
 */
@RunWith(RobolectricTestRunner::class)
class FolioMarkTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private companion object {
        const val VIEWPORT = 24.0
        const val FOLD = VIEWPORT / 2

        /**
         * How much of the 24 grid the fold must keep for itself.
         *
         * The mark is drawn at 16dp, so two units is 1.33dp — four pixels at xxhdpi,
         * which is the least that still reads as a crease rather than as a seam.
         */
        const val MIN_FOLD_GAP = 2.0
    }

    /** Every `<path>` element's attributes, in document order. */
    private fun paths(drawable: Int): List<Map<String, String>> {
        val parser = app.resources.getXml(drawable)
        val found = mutableListOf<Map<String, String>>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "path") {
                found += (0 until parser.attributeCount).associate {
                    parser.getAttributeName(it) to parser.getAttributeValue(it)
                }
            }
            event = parser.next()
        }
        return found
    }

    /**
     * The coordinate pairs in a path.
     *
     * M, L, C and Z only, all of which take whole pairs. The assertion is there
     * because an edit reaching for H or V would silently break the pairing and every
     * bound below would then be measuring nonsense.
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

    private fun leaves(): Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>> {
        val drawn = paths(R.drawable.folio_mark)
        assertEquals(
            "a folio is two leaves meeting at a fold, so the mark is two paths",
            2, drawn.size,
        )
        return points(drawn[0]["pathData"]!!) to points(drawn[1]["pathData"]!!)
    }

    @Test
    fun `the mark inflates and is square`() {
        val drawable = app.getDrawable(R.drawable.folio_mark)
        assertNotNull("the mark does not inflate", drawable)
        assertEquals(
            "a square mark keeps its proportions wherever it is placed",
            drawable!!.intrinsicWidth, drawable.intrinsicHeight,
        )
    }

    @Test
    fun `every coordinate is inside the viewport`() {
        // A vector drawable does not clip: a stray control point outside the
        // viewport is drawn anyway, scaled, and the mark quietly grows a spur on the
        // side of whichever widget it lands on.
        val (left, right) = leaves()
        (left + right).forEach { (x, y) ->
            assertTrue(
                "($x, $y) is outside the ${VIEWPORT.toInt()}×${VIEWPORT.toInt()} viewport",
                x in 0.0..VIEWPORT && y in 0.0..VIEWPORT,
            )
        }
    }

    @Test
    fun `the fold stays open at widget scale`() {
        // The failure this exists for: a mark that looks like a folio at 108dp in a
        // viewer and like a rounded rectangle at 16dp on a home screen.
        val (left, right) = leaves()
        val leftEdge = left.maxOf { it.first }
        val rightEdge = right.minOf { it.first }
        assertTrue(
            "the leaves reach $leftEdge and $rightEdge, a fold of " +
                "${"%.2f".format(rightEdge - leftEdge)} units — under $MIN_FOLD_GAP, " +
                "so the crease closes up at 16dp",
            rightEdge - leftEdge >= MIN_FOLD_GAP,
        )
        assertTrue("the leaves are on the wrong sides of the fold", leftEdge < FOLD)
    }

    @Test
    fun `the two leaves are one sheet, folded`() {
        // Mirror symmetry is what makes it a folded sheet rather than two pages set
        // side by side, and it is the thing a hand edit to one leaf breaks without
        // looking wrong anywhere.
        val (left, right) = leaves()
        assertEquals("the leaves have different numbers of points", left.size, right.size)
        left.zip(right).forEachIndexed { index, (l, r) ->
            assertTrue(
                "point $index is not mirrored: $l against $r",
                abs((VIEWPORT - r.first) - l.first) < 0.001 && abs(r.second - l.second) < 0.001,
            )
        }
    }

    @Test
    fun `the mark is a single flat colour`() {
        // The whole reason this is not the launcher drawable. Two tones need a known
        // ground to sit on, and a widget's ground is whichever of five themes the
        // reader picked.
        val drawn = paths(R.drawable.folio_mark)
        assertEquals(
            "the two leaves are different colours, so the mark cannot be tinted " +
                "to one palette",
            drawn[0]["fillColor"], drawn[1]["fillColor"],
        )
        drawn.forEach {
            assertTrue(
                "a leaf carries fillAlpha, which reads as two tones once tinted",
                it["fillAlpha"] == null,
            )
        }
    }
}
