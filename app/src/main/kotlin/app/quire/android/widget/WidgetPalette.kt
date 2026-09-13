package app.quire.android.widget

import androidx.compose.ui.graphics.toArgb
import app.quire.android.ui.theme.QuirePalettes
import app.quire.android.ui.theme.QuireThemeName

/**
 * The colours one widget is drawn in, as packed ARGB ints.
 *
 * Ints rather than Compose `Color`s because every one of them ends up in a
 * `RemoteViews` call — `setTextColor(id, colour)` or `setInt(id, "setColorFilter",
 * colour)` — and converting at the call site would put five `toArgb()`s in the
 * transcription layer, where a test cannot see them.
 *
 * Six colours, one job each. There is no `bgAlt` here: the widgets draw exactly one
 * surface now, so a second one would be a token nothing reads.
 */
data class WidgetPalette(
    /** The card. */
    val surface: Int,
    /**
     * The hairline round the card, and everything that means "not yet": an unread
     * day, the track under the progress fill, an unlit flame. In Quire "not yet" is
     * the border colour and "done" is the accent, in the app and on the home screen
     * alike.
     */
    val edge: Int,
    /** The headline, the stat values, the book's title. */
    val ink: Int,
    /** Every secondary line. */
    val muted: Int,
    /** A day that was read, the progress fill, a lit flame. */
    val accent: Int,
    /**
     * The Quire mark in the corner.
     *
     * Muted, not accent: the mark says whose widget this is, and a mark drawn in the
     * same colour as the data competes with it. Held as its own token rather than
     * spelled `muted` at the use site so that changing how loud the mark is stays a
     * one-line decision with a contrast test over it.
     */
    val mark: Int,
)

/**
 * The reader's theme, as a widget can use it.
 *
 * The whole of the widgets' theming is this function plus `setTextColor` and
 * `setColorFilter`. Nothing here is a literal: the tokens are `QuirePalettes`', so a
 * palette edited in the app reaches the home screen without anyone remembering to
 * copy it — which is the failure `WidgetColorTest` was written for and this avoids
 * having to guard against at all.
 */
fun widgetPalette(theme: QuireThemeName): WidgetPalette {
    val colors = QuirePalettes.of(theme)
    return WidgetPalette(
        surface = colors.bg.toArgb(),
        edge = colors.border.toArgb(),
        ink = colors.ink.toArgb(),
        muted = colors.muted.toArgb(),
        accent = colors.accent.toArgb(),
        mark = colors.muted.toArgb(),
    )
}
