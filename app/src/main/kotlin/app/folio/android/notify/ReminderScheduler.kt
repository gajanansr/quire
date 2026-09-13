package app.folio.android.notify

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Keeps exactly one reminder pending, and cancels it the moment the reader says no.
 *
 * One-time work with a delay, re-enqueued after each run, rather than periodic work
 * or an alarm. Periodic work has a fifteen-minute floor and drifts against the wall
 * clock, which is wrong for something a reader set to a particular time; an exact
 * alarm would deliver precisely but needs a special-access permission that an
 * offline reading app has no business asking for. The cost of this choice is that
 * delivery is approximate, and [Reminders.inWindow] is where that cost is paid
 * deliberately instead of accidentally.
 */
object ReminderScheduler {

    /** Unique, so there can only ever be one reminder pending. */
    const val WORK_NAME = "folio-reminder"

    /**
     * Schedules the next reminder.
     *
     * [replaceExisting] is the difference between the reader changing something —
     * where the new time has to win — and the app simply starting, where replacing
     * would reset the delay on every launch and could push a reminder past its own
     * window, day after day, for anyone who opens Folio each morning.
     */
    fun schedule(
        context: Context,
        reminderMinuteOfDay: Int,
        nowMinuteOfDay: Int,
        replaceExisting: Boolean,
    ) {
        val delay = Reminders.minutesUntil(nowMinuteOfDay, reminderMinuteOfDay)
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay.toLong(), TimeUnit.MINUTES)
                .build(),
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
