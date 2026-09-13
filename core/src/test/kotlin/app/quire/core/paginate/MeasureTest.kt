package app.quire.core.paginate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How wide the column of text is allowed to be.
 *
 * The measure is the oldest setting in typography: past roughly 66 characters the
 * eye loses its way back to the start of the next line.
 */
class MeasureTest {

    private fun settings(sizeSp: Float, pixelsPerSp: Float = 2.75f) =
        TypographySettings(fontSizeSp = sizeSp, pixelsPerSp = pixelsPerSp)

    @Test
    fun `a phone is already inside the limit and is left alone`() {
        // Worth stating rather than assuming: a 360dp column at 19sp sets about 40
        // characters. Capping it would narrow a measure that is already short, so
        // this rule must do nothing here.
        val phone = (360f - 52f) * 2.75f
        assertEquals(phone, Measure.widthPx(phone, settings(19f)))
    }

    @Test
    fun `a tablet is brought back to a readable measure`() {
        // A 10-inch tablet: about 800dp of width at 2x, so roughly 1500px of column
        // once margins are taken. At 19sp that is around 79 characters to a line.
        val tablet = (800f - 52f) * 2f
        val capped = Measure.widthPx(tablet, settings(19f, pixelsPerSp = 2f))
        assertTrue(capped < tablet, "a tablet kept the full width: $capped")

        // And what it is brought back to is still a full column, not a ribbon.
        assertTrue(capped > tablet * 0.6f, "the measure was cut too far: $capped")
    }

    @Test
    fun `the cap follows the type size`() {
        // Larger type means fewer characters in the same width, so the limit in
        // pixels has to grow with it or big type would be squeezed into a column
        // set for small type.
        val wide = 4000f
        assertTrue(Measure.widthPx(wide, settings(24f)) > Measure.widthPx(wide, settings(15f)))
    }

    @Test
    fun `a column that has not been measured yet passes through`() {
        assertEquals(0f, Measure.widthPx(0f, settings(19f)))
        assertEquals(-1f, Measure.widthPx(-1f, settings(19f)))
    }
}
