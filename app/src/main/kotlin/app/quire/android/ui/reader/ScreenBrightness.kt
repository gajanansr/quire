package app.quire.android.ui.reader

/**
 * What a drag on the right edge does to the screen.
 *
 * The window's own brightness, never the system setting. A reading app has no
 * business changing the brightness of a device it is one app on — and writing the
 * system value would need `WRITE_SETTINGS`, a permission Quire will not ask for.
 * `WindowManager.LayoutParams.screenBrightness` applies to this window and is
 * released the moment the Reader goes away.
 *
 * **Not persisted, and that is a decision rather than an omission.** Brightness is
 * environmental, not preferential: the value that is right in bed at midnight is
 * wrong on a train at noon, so a restored value is wrong most of the times it would
 * be restored, and its failure mode is the worst one on offer — opening a book in
 * daylight onto a screen dimmed for a dark room, with the only cure a gesture the
 * reader cannot see to make. It holds for as long as the Reader is open, which covers
 * page turns, chapter loads and rotation, and is released on the way out.
 */
object ScreenBrightness {

    /**
     * `WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE`.
     *
     * Spelled here rather than imported so this file stays pure Kotlin and testable
     * off a device; [ReaderBrightness] is where it meets the real constant.
     */
    const val FOLLOW_SYSTEM = -1f

    /**
     * The dimmest Quire will go, and the whole reason this is a clamp and not a
     * coerceAtLeast(0f).
     *
     * A screen dragged to black has taken the device away from its owner: the gesture
     * that would undo it is invisible, and so is the back button, and so is every
     * other way out. Five percent is legible in a dark room and unmistakably still on.
     */
    const val FLOOR = 0.05f

    const val CEILING = 1f

    /**
     * Where the first drag starts when the real screen brightness cannot be trusted.
     *
     * Mid-bright rather than full: a first drag that blazes is worse in the dark than
     * one that is dim in daylight, and the reader is already mid-gesture and about to
     * correct it either way.
     */
    const val DEFAULT = 0.6f

    /**
     * [current], moved by a drag of [dragPx] down a track [trackPx] tall.
     *
     * Down dims, which is the convention every reader app shares and the one the
     * reader asked for. One sweep of the reading column covers the whole range: less
     * and dimming takes several strokes, more and a page-turn-sized wobble blacks the
     * screen out.
     *
     * A track of zero — the frame before the column is measured — changes nothing,
     * because the alternative is a NaN, which the window manager reads as "whatever
     * you like".
     */
    fun dragged(current: Float, dragPx: Float, trackPx: Float): Float {
        val from = if (current == FOLLOW_SYSTEM) DEFAULT else current
        if (trackPx <= 0f) return from.coerceIn(FLOOR, CEILING)
        return (from - dragPx / trackPx).coerceIn(FLOOR, CEILING)
    }

    /**
     * A level from the system's own `Settings.System.SCREEN_BRIGHTNESS`.
     *
     * Read once, so the first drag starts from roughly what is already on screen
     * rather than jumping. It is only roughly: that setting is nominally 0..255 but
     * not guaranteed to be, and on a device with adaptive brightness on it is not what
     * is lit at all. A wrong seed costs the reader one more drag; refusing to seed
     * costs them a jump on every first drag, which is worse. Anything unreadable falls
     * back to [DEFAULT].
     */
    fun seed(raw: Int, max: Int): Float {
        if (max <= 0 || raw < 0) return DEFAULT
        return (raw.toFloat() / max.toFloat()).coerceIn(FLOOR, CEILING)
    }
}
