package app.folio.android.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.folio.android.MainActivity

/**
 * The intents a widget hands to the system.
 *
 * Built here rather than inline so they can be asserted without a device, the same
 * way `ShareIntents` is. A widget's tap target is untestable on a home screen and
 * unforgiving when wrong: the only symptom of a bad intent is a tap that does
 * nothing.
 */
object FolioWidgets {

    /**
     * Opens Folio.
     *
     * `NEW_TASK` because a broadcast receiver has no task of its own to start an
     * activity in. `SINGLE_TOP` so a reader who already has Folio open gets the
     * activity they left rather than a second copy of it.
     */
    fun openIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /**
     * [requestCode] separates the two widgets' pending intents. Without it the
     * second one built would silently reuse the first one's extras, because
     * `PendingIntent` treats intents differing only in extras as the same.
     */
    fun pendingOpen(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            // Immutable because nothing outside Folio has any business editing it,
            // and required outright from API 31.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Distinct request codes, so the two widgets cannot share a pending intent. */
    const val REQUEST_HABIT = 1
    const val REQUEST_STATS = 2
}
