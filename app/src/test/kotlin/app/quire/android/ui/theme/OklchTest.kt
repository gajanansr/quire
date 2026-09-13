package app.quire.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Asserts the conversion against values computed independently from the OKLab
 * specification. Two implementations of the same spec agreeing is real evidence;
 * a conversion that only agrees with itself is not.
 */
class OklchTest {

    private fun assertHex(expected: String, l: Double, c: Double, h: Double) {
        val (r, g, b) = oklchToSrgb(l, c, h)
        val er = expected.substring(1, 3).toInt(16)
        val eg = expected.substring(3, 5).toInt(16)
        val eb = expected.substring(5, 7).toInt(16)
        val actual = "#%02X%02X%02X".format(r, g, b)
        // A rounding difference of one level per channel is acceptable; more is a
        // different colour.
        assertTrue(
            "oklch($l $c $h) expected $expected but was $actual",
            abs(r - er) <= 1 && abs(g - eg) <= 1 && abs(b - eb) <= 1,
        )
    }

    @Test
    fun `light palette matches the reference conversion`() {
        assertHex("#FDF9F6", 0.985, 0.006, 70.0)   // bg
        assertHex("#F7EFE7", 0.955, 0.013, 65.0)   // bgAlt
        assertHex("#111B28", 0.22, 0.03, 255.0)    // ink
        assertHex("#6A727D", 0.55, 0.02, 255.0)    // muted
        assertHex("#E1D9D2", 0.89, 0.013, 65.0)    // border
        assertHex("#214F7C", 0.42, 0.09, 250.0)    // accent
        assertHex("#E5F0FC", 0.95, 0.02, 250.0)    // accentSoft
        assertHex("#10171F", 0.2, 0.02, 255.0)     // buttonBg
        assertHex("#FDF5EF", 0.975, 0.012, 60.0)   // readerBg
        assertHex("#E8CD62", 0.85, 0.13, 95.0)     // highlight
        assertHex("#FFE4E1", 0.94, 0.03, 25.0)     // errorBg
        assertHex("#932B2A", 0.45, 0.14, 25.0)     // errorText
    }

    @Test
    fun `dark palette matches the reference conversion`() {
        assertHex("#0B1015", 0.17, 0.014, 255.0)   // bg
        assertHex("#1A2027", 0.24, 0.016, 255.0)   // bgAlt
        assertHex("#E0E5EB", 0.92, 0.01, 255.0)    // ink
        assertHex("#899098", 0.65, 0.015, 255.0)   // muted
        assertHex("#2D333B", 0.32, 0.016, 255.0)   // border
        assertHex("#79A9DB", 0.72, 0.09, 250.0)    // accent
        assertHex("#192F46", 0.3, 0.05, 250.0)     // accentSoft
        assertHex("#140B06", 0.16, 0.02, 50.0)     // readerBg
        assertHex("#594600", 0.4, 0.1, 95.0)       // highlight
        assertHex("#47211E", 0.3, 0.06, 25.0)      // errorBg
        assertHex("#F08F87", 0.75, 0.12, 25.0)     // errorText
    }

    @Test
    fun `pure black and white round trip`() {
        assertEquals(Triple(0, 0, 0), oklchToSrgb(0.0, 0.0, 0.0))
        assertEquals(Triple(255, 255, 255), oklchToSrgb(1.0, 0.0, 0.0))
    }

    @Test
    fun `an out of gamut colour clamps instead of producing NaN`() {
        // A negative linear channel raised to a fractional power is NaN, which
        // paints black; clamping must happen before gamma encoding.
        val (r, g, b) = oklchToSrgb(0.6, 0.5, 140.0)
        listOf(r, g, b).forEach {
            assertTrue("channel $it escaped 0..255", it in 0..255)
        }
    }

    @Test
    fun `lightness is monotonic at fixed chroma and hue`() {
        val values = (0..10).map { oklchToSrgb(it / 10.0, 0.02, 255.0).first }
        assertEquals(values.sorted(), values, )
    }

    @Test
    fun `the Color helper carries the converted channels`() {
        val color = oklch(0.42, 0.09, 250.0)
        assertEquals(0x21, (color.red * 255).toInt())
        assertEquals(0x4F, (color.green * 255).toInt())
        assertEquals(0x7C, (color.blue * 255).toInt())
    }
}
