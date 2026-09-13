package app.folio.android.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import app.folio.android.MainActivity
import app.folio.android.ui.FolioStrings
import app.folio.android.ui.theme.FolioIcons

/**
 * The notification Folio posts, and nothing else.
 *
 * Deliberately has no opinion about *whether* to post — [Reminders] holds that, and
 * keeping the two apart is what lets the rule be tested without an Android runtime.
 */
object FolioNotifier {

    const val CHANNEL_ID = "reading_reminders"

    /**
     * One id, reused for every reminder.
     *
     * This is the whole mechanism that keeps the shade from filling up: a second
     * reminder replaces the first instead of joining it, so a reader who ignored
     * Folio for a week comes back to one notification rather than seven. Seven
     * kind sentences stacked on top of each other still read as nagging.
     */
    const val NOTIFICATION_ID = 1001

    /**
     * Posts a reminder. Returns whether it actually reached the shade.
     *
     * A post without `POST_NOTIFICATIONS` throws, and inside a background worker an
     * uncaught throw is a crash in a job nobody is watching. The result comes back
     * as a value so the caller can decline to mark the day as reminded — otherwise
     * a failed delivery would silence tomorrow as well.
     */
    fun post(context: Context, copy: ReminderCopy): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        ensureChannel(manager)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(FolioIcons.Book)
            .setContentTitle(copy.title)
            .setContentText(copy.body)
            // The body is a full sentence naming a book, which is longer than a
            // collapsed notification shows. Expanding rather than eliding keeps the
            // specific part — the reader's own book — readable.
            .setStyle(Notification.BigTextStyle().bigText(copy.body))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openFolio(context))
            .build()

        return runCatching { manager.notify(NOTIFICATION_ID, notification) }.isSuccess
    }

    /**
     * Created on demand rather than at app start.
     *
     * A channel exists in system settings the moment it is created, so creating one
     * eagerly would put a "Reading reminders" switch in front of a reader who has
     * never been offered reminders and may never want them.
     */
    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                FolioStrings.REMINDER_CHANNEL,
                // DEFAULT makes a sound but does not peek over what the reader is
                // doing. HIGH would, and a reading reminder has not earned that.
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = FolioStrings.REMINDER_CHANNEL_EXPLAINER
                setShowBadge(false)
            }
        )
    }

    /**
     * Opens Folio where the reader left it.
     *
     * No action buttons. "Snooze" and "Dismiss" are both ways of asking the reader
     * to manage Folio's feelings; the two things they might want are to read, or to
     * ignore it, and both already work.
     */
    private fun openFolio(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        // Immutable because nothing should be able to rewrite where this goes, and
        // required outright from Android 12.
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * Whether the OS will actually deliver a notification right now.
 *
 * Three separate refusals, and the app has to honour all of them. Android only
 * enforces the second: a muted channel silently drops what is posted, so without
 * this check Folio would carry on "reminding" someone who had switched it off and
 * nothing would ever look wrong.
 */
object NotificationAccess {

    /** Android 13 is where posting became a runtime permission. */
    const val RUNTIME_PERMISSION_FROM = Build.VERSION_CODES.TIRAMISU

    /**
     * The rule itself, stated over the three facts the OS reports.
     *
     * Pure so every API level can be tested without a second emulated SDK image.
     */
    fun granted(
        sdkInt: Int,
        notificationsEnabled: Boolean,
        permissionGranted: Boolean,
        channelMuted: Boolean,
    ): Boolean = notificationsEnabled &&
        !channelMuted &&
        (sdkInt < RUNTIME_PERMISSION_FROM || permissionGranted)

    /** The same question, asked of a real device. */
    fun granted(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = manager.getNotificationChannel(FolioNotifier.CHANNEL_ID)
        return granted(
            sdkInt = Build.VERSION.SDK_INT,
            notificationsEnabled = manager.areNotificationsEnabled(),
            permissionGranted = context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
            // No channel yet is not a refusal — it is simply the first reminder,
            // and [FolioNotifier.post] creates it on the way past.
            channelMuted = channel != null &&
                channel.importance == NotificationManager.IMPORTANCE_NONE,
        )
    }
}
