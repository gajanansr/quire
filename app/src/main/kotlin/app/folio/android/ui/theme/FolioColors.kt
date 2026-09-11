package app.folio.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

enum class FolioThemeName { LIGHT, PALE, DARK, EINK }

/**
 * The handoff's colour tokens, named exactly as the handoff names them so the two
 * can be read side by side.
 */
@Immutable
data class FolioColors(
    val bg: Color,
    val bgAlt: Color,
    val ink: Color,
    val muted: Color,
    val border: Color,
    val accent: Color,
    val accentSoft: Color,
    val buttonBg: Color,
    val buttonText: Color,
    val readerBg: Color,
    val highlight: Color,
    val errorBg: Color,
    val errorText: Color,
)

/**
 * Palettes written in OKLCH, exactly as the handoff specifies them, and converted at
 * construction. Nothing here is a pasted hex value: the handoff stays the source of
 * truth and [oklch] does the arithmetic.
 */
object FolioPalettes {

    val Light = FolioColors(
        bg = oklch(0.985, 0.006, 70.0),
        bgAlt = oklch(0.955, 0.013, 65.0),
        ink = oklch(0.22, 0.03, 255.0),
        muted = oklch(0.55, 0.02, 255.0),
        border = oklch(0.89, 0.013, 65.0),
        accent = oklch(0.42, 0.09, 250.0),
        accentSoft = oklch(0.95, 0.02, 250.0),
        buttonBg = oklch(0.2, 0.02, 255.0),
        buttonText = Color.White,
        readerBg = oklch(0.975, 0.012, 60.0),
        highlight = oklch(0.85, 0.13, 95.0),
        errorBg = oklch(0.94, 0.03, 25.0),
        errorText = oklch(0.45, 0.14, 25.0),
    )

    val Pale = FolioColors(
        bg = oklch(0.965, 0.01, 260.0),
        bgAlt = oklch(0.925, 0.012, 260.0),
        ink = oklch(0.38, 0.025, 255.0),
        muted = oklch(0.58, 0.02, 255.0),
        border = oklch(0.88, 0.012, 260.0),
        accent = oklch(0.5, 0.07, 250.0),
        accentSoft = oklch(0.91, 0.02, 250.0),
        buttonBg = oklch(0.4, 0.03, 255.0),
        buttonText = Color.White,
        readerBg = oklch(0.94, 0.015, 70.0),
        highlight = oklch(0.82, 0.1, 95.0),
        errorBg = oklch(0.9, 0.025, 25.0),
        errorText = oklch(0.48, 0.13, 25.0),
    )

    val Dark = FolioColors(
        bg = oklch(0.17, 0.014, 255.0),
        bgAlt = oklch(0.24, 0.016, 255.0),
        ink = oklch(0.92, 0.01, 255.0),
        muted = oklch(0.65, 0.015, 255.0),
        border = oklch(0.32, 0.016, 255.0),
        accent = oklch(0.72, 0.09, 250.0),
        accentSoft = oklch(0.3, 0.05, 250.0),
        buttonBg = oklch(0.72, 0.09, 250.0),
        buttonText = oklch(0.15, 0.02, 255.0),
        readerBg = oklch(0.16, 0.02, 50.0),
        highlight = oklch(0.4, 0.1, 95.0),
        errorBg = oklch(0.3, 0.06, 25.0),
        errorText = oklch(0.75, 0.12, 25.0),
    )

    /**
     * E-ink is the Light palette. The handoff is explicit that it is a grayscale
     * rendering of the live theme rather than a fifth set of colours, so the
     * difference lives in a filter over the content root, not here.
     */
    val Eink = Light

    fun of(theme: FolioThemeName): FolioColors = when (theme) {
        FolioThemeName.LIGHT -> Light
        FolioThemeName.PALE -> Pale
        FolioThemeName.DARK -> Dark
        FolioThemeName.EINK -> Eink
    }
}

/**
 * What a theme is called on screen.
 *
 * Here rather than in either screen that shows it: Settings cycles through these
 * names and the reader's picker labels its previews with them, and two copies would
 * eventually disagree about whether the fourth one is "E-ink" or "Eink".
 */
fun FolioThemeName.label(): String = when (this) {
    FolioThemeName.LIGHT -> "Light"
    FolioThemeName.PALE -> "Pale"
    FolioThemeName.DARK -> "Dark"
    FolioThemeName.EINK -> "E-ink"
}
