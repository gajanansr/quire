package app.folio.android.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Converts the design handoff's OKLCH colours into sRGB.
 *
 * The handoff specifies every token in OKLCH and Compose has no OKLCH literal, so
 * the palette has to be converted somewhere. Doing it here, in one tested function,
 * rather than by pasting hex values means the source of truth stays the handoff and
 * a mistake fails a test instead of shifting every colour in the app very slightly —
 * the kind of error that is nearly invisible in review and wrong on every screen.
 *
 * Implements Björn Ottosson's OKLab transform: OKLCH → OKLab → LMS → linear sRGB →
 * gamma-encoded sRGB.
 */
internal fun oklchToSrgb(l: Double, c: Double, h: Double): Triple<Int, Int, Int> {
    val hRad = Math.toRadians(h)
    val a = c * cos(hRad)
    val b = c * sin(hRad)

    // OKLab to non-linear LMS.
    val lCube = l + 0.3963377774 * a + 0.2158037573 * b
    val mCube = l - 0.1055613458 * a - 0.0638541728 * b
    val sCube = l - 0.0894841775 * a - 1.2914855480 * b

    val lms0 = lCube * lCube * lCube
    val lms1 = mCube * mCube * mCube
    val lms2 = sCube * sCube * sCube

    // LMS to linear sRGB.
    val rLinear = 4.0767416621 * lms0 - 3.3077115913 * lms1 + 0.2309699292 * lms2
    val gLinear = -1.2684380046 * lms0 + 2.6097574011 * lms1 - 0.3413193965 * lms2
    val bLinear = -0.0041960863 * lms0 - 0.7034186147 * lms1 + 1.7076147010 * lms2

    return Triple(encode(rLinear), encode(gLinear), encode(bLinear))
}

/**
 * Gamma-encodes one linear channel to 0..255.
 *
 * Clamping happens before encoding: OKLCH describes colours outside the sRGB gamut,
 * and a negative channel raised to a fractional power is NaN, which paints black.
 */
private fun encode(linear: Double): Int {
    val clamped = linear.coerceIn(0.0, 1.0)
    val encoded =
        if (clamped <= 0.0031308) 12.92 * clamped
        else 1.055 * clamped.pow(1.0 / 2.4) - 0.055
    return Math.round(encoded * 255).toInt().coerceIn(0, 255)
}

/** A handoff token, written the way the handoff writes it. */
fun oklch(l: Double, c: Double, h: Double): Color {
    val (r, g, b) = oklchToSrgb(l, c, h)
    return Color(red = r, green = g, blue = b)
}
