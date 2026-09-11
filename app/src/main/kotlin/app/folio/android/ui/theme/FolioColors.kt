package app.folio.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The five themes, ordered light to dark as the picker shows them.
 *
 * Each occupies a band no other one covers. The set the handoff shipped had Light
 * at L=0.985 and Pale at L=0.965 — two percent apart, which is why they read as the
 * same theme — so Pale is gone and Sepia takes the warm slot it was reaching for.
 */
enum class FolioThemeName { PAPER, SEPIA, EINK, NIGHT, BLACK }

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

    /**
     * Paper: the handoff's Light palette, unchanged.
     *
     * The one theme the handoff got right and the one nobody complained about, so
     * its tokens are exactly as specified. Warm off-white, cool near-black ink,
     * 16:1 on the reading page.
     */
    val Paper = FolioColors(
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

    /**
     * Sepia: the warm tan page every e-reader ships and most readers settle on.
     *
     * Warmth carries all the way through, accent included — a blue accent on a tan
     * page is the tell that a theme is a background swap rather than a palette. The
     * muted tone sits at 4.9:1 on the page, which is the floor this theme is allowed
     * to reach: warm greys lose contrast faster than neutral ones as they lighten.
     */
    val Sepia = FolioColors(
        bg = oklch(0.930, 0.028, 80.0),
        bgAlt = oklch(0.885, 0.035, 78.0),
        ink = oklch(0.30, 0.035, 60.0),
        muted = oklch(0.50, 0.028, 65.0),
        border = oklch(0.845, 0.030, 78.0),
        accent = oklch(0.45, 0.08, 55.0),
        accentSoft = oklch(0.885, 0.040, 70.0),
        buttonBg = oklch(0.30, 0.035, 60.0),
        buttonText = oklch(0.95, 0.020, 82.0),
        readerBg = oklch(0.925, 0.030, 80.0),
        highlight = oklch(0.82, 0.10, 90.0),
        errorBg = oklch(0.88, 0.030, 30.0),
        errorText = oklch(0.44, 0.13, 28.0),
    )

    /**
     * E-ink: an actual electrophoretic panel, not a desaturated screen.
     *
     * Every token has chroma exactly zero. That is the defining property — a Kindle
     * Paperwhite cannot render sepia or green at all, because the panel is greyscale
     * hardware — and it is asserted in a test rather than eyeballed, since a single
     * tinted token would undo the whole theme.
     *
     * Not pure black on pure white either. E Ink Carta 1200 measures around 15:1 to
     * 17:1, and its white is a reflective off-white rather than an emitted #FFFFFF.
     * #E8E8E8 against #141414 lands at 15.0:1, inside that range. Pure #000 on #FFF
     * would be 21:1 — brighter and harsher than the thing it is imitating.
     *
     * The accent is near-black with no hue, so emphasis comes from weight and
     * contrast the way it does in print.
     */
    val Eink = FolioColors(
        bg = oklch(0.935, 0.0, 0.0),
        bgAlt = oklch(0.890, 0.0, 0.0),
        ink = oklch(0.19, 0.0, 0.0),
        muted = oklch(0.50, 0.0, 0.0),
        border = oklch(0.820, 0.0, 0.0),
        accent = oklch(0.25, 0.0, 0.0),
        accentSoft = oklch(0.860, 0.0, 0.0),
        buttonBg = oklch(0.19, 0.0, 0.0),
        buttonText = oklch(0.960, 0.0, 0.0),
        readerBg = oklch(0.930, 0.0, 0.0),
        highlight = oklch(0.780, 0.0, 0.0),
        errorBg = oklch(0.860, 0.0, 0.0),
        errorText = oklch(0.30, 0.0, 0.0),
    )

    /**
     * Night: dark grey, deliberately not black.
     *
     * Pure white on pure black produces the strongest halation — the glow that makes
     * text look blurred and leaves a trailing afterimage while scrolling. Roughly
     * half of all people have some degree of astigmatism, for whom it is not a
     * preference but a wall. The guidance is a dark grey ground near #1C1C1E with
     * text muted to around #D4D4D4 rather than #FFFFFF, which is what these are.
     * True black lives in its own theme, for people who want it.
     */
    val Night = FolioColors(
        bg = oklch(0.225, 0.004, 260.0),
        bgAlt = oklch(0.275, 0.005, 260.0),
        ink = oklch(0.86, 0.003, 260.0),
        muted = oklch(0.62, 0.005, 260.0),
        border = oklch(0.35, 0.006, 260.0),
        accent = oklch(0.72, 0.09, 250.0),
        accentSoft = oklch(0.32, 0.05, 250.0),
        buttonBg = oklch(0.72, 0.09, 250.0),
        buttonText = oklch(0.16, 0.01, 260.0),
        readerBg = oklch(0.235, 0.004, 260.0),
        highlight = oklch(0.42, 0.09, 95.0),
        errorBg = oklch(0.32, 0.06, 25.0),
        errorText = oklch(0.76, 0.12, 25.0),
    )

    /**
     * Black: true #000000, for OLED and for reading in the dark.
     *
     * An OLED panel switches black pixels off entirely, so this is the only theme
     * that actually saves battery, and the only one that disappears completely in a
     * dark room. Its ink is muted further than Night's — #C4C4C4 rather than
     * #D0D1D3 — because the halation this theme invites is worst at the extremes,
     * and dimming the text is the part that helps most.
     */
    val Black = FolioColors(
        bg = Color.Black,
        bgAlt = oklch(0.16, 0.0, 0.0),
        ink = oklch(0.82, 0.0, 0.0),
        muted = oklch(0.58, 0.0, 0.0),
        border = oklch(0.27, 0.0, 0.0),
        accent = oklch(0.70, 0.08, 250.0),
        accentSoft = oklch(0.26, 0.04, 250.0),
        buttonBg = oklch(0.70, 0.08, 250.0),
        buttonText = Color.Black,
        readerBg = Color.Black,
        highlight = oklch(0.38, 0.08, 95.0),
        errorBg = oklch(0.28, 0.06, 25.0),
        errorText = oklch(0.74, 0.12, 25.0),
    )

    fun of(theme: FolioThemeName): FolioColors = when (theme) {
        FolioThemeName.PAPER -> Paper
        FolioThemeName.SEPIA -> Sepia
        FolioThemeName.EINK -> Eink
        FolioThemeName.NIGHT -> Night
        FolioThemeName.BLACK -> Black
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
    FolioThemeName.PAPER -> "Paper"
    FolioThemeName.SEPIA -> "Sepia"
    FolioThemeName.EINK -> "E-ink"
    FolioThemeName.NIGHT -> "Night"
    FolioThemeName.BLACK -> "Black"
}

/**
 * Resolves a theme name read back from the database.
 *
 * The stored value is a plain string, and the set it can hold changed: the handoff's
 * LIGHT / PALE / DARK / EINK became PAPER / SEPIA / EINK / NIGHT / BLACK. Anyone
 * upgrading has one of the old names saved, and matching on the enum alone would
 * silently reset them to the default — a small thing, but the reader chose it once
 * and would have to choose again for no reason they could see.
 *
 * PALE maps to SEPIA rather than PAPER because Pale was the warm-alternative slot;
 * Sepia is what it was reaching for.
 */
fun themeNamed(stored: String?): FolioThemeName = when (stored) {
    null -> FolioThemeName.PAPER
    "LIGHT" -> FolioThemeName.PAPER
    "PALE" -> FolioThemeName.SEPIA
    "DARK" -> FolioThemeName.NIGHT
    else -> FolioThemeName.entries.firstOrNull { it.name == stored } ?: FolioThemeName.PAPER
}
