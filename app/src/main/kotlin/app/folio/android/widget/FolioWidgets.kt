package app.folio.android.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.folio.android.MainActivity
import app.folio.android.ui.nav.HabitScreen

/**
 * The two widgets, and where each one's tap lands.
 *
 * Data rather than a branch in each provider: which screen a widget opens is the one
 * thing about it that a test can check, and the one thing whose failure — a tap that
 * opens the app on the wrong page — nothing inside Folio can notice.
 *
 * The request codes must differ. `PendingIntent` treats two intents that differ only
 * in their extras as the same intent, so a shared request code would have the second
 * widget built silently inherit the first one's destination.
 */
enum class FolioWidget(val requestCode: Int, val opens: HabitScreen?) {
    HABIT(requestCode = 1, opens = HabitScreen.STREAK),
    STATS(requestCode = 2, opens = null),
}

/**
 * The intents a widget hands to the system.
 *
 * Built here rather than inline so they can be asserted without a device, the same
 * way `ShareIntents` is.
 */
object FolioWidgets {

    /** The destination, carried as a name because an enum is not a launcher's to know. */
    const val EXTRA_HABIT_SCREEN = "app.folio.android.extra.HABIT_SCREEN"

    /**
     * Opens Folio.
     *
     * `NEW_TASK` because a broadcast receiver has no task of its own to start an
     * activity in. `SINGLE_TOP` so a reader who already has Folio open gets the
     * activity they left rather than a second copy of it — and so the destination
     * arrives through `onNewIntent`, which is the usual case rather than the rare
     * one.
     */
    fun openIntent(context: Context, widget: FolioWidget): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { widget.opens?.let { putExtra(EXTRA_HABIT_SCREEN, it.name) } }

    fun pendingOpen(context: Context, widget: FolioWidget): PendingIntent =
        PendingIntent.getActivity(
            context,
            widget.requestCode,
            openIntent(context, widget),
            // Immutable because nothing outside Folio has any business editing it,
            // and required outright from API 31.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * The destination an intent names, if Folio still has one by that name.
     *
     * `valueOf` would be the obvious read and is the wrong one: a widget pinned by
     * an older version of Folio keeps the intent it was created with for as long as
     * it sits on the home screen, so a renamed screen would crash the app on a tap,
     * months later, for the one reader who never removed it.
     */
    fun habitScreenOf(intent: Intent?): HabitScreen? {
        val name = intent?.getStringExtra(EXTRA_HABIT_SCREEN) ?: return null
        return HabitScreen.entries.firstOrNull { it.name == name }
    }
}
