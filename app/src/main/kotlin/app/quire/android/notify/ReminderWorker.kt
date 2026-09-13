package app.quire.android.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import app.quire.android.data.BookRepository
import app.quire.android.data.HabitRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Gathers the facts, asks [Reminders], and posts whatever it is handed.
 *
 * Deliberately has no judgement of its own. Every "should we?" lives in the pure
 * rule and every "what would we say?" in the hand-written copy, which leaves this
 * class short enough that the wiring between them can be checked by reading it.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
    private val habits: HabitRepository,
    private val books: BookRepository,
    private val nowMs: () -> Long,
    private val zone: () -> ZoneId,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = habits.settings()
        val summary = habits.observeSummary().first()
        val today = habits.todayEpochDay()
        val localTime = Instant.ofEpochMilli(nowMs()).atZone(zone()).toLocalTime()
        val minuteOfDay = localTime.hour * 60 + localTime.minute

        val facts = ReminderFacts(
            remindersEnabled = settings.remindersEnabled,
            dailyEnabled = settings.dailyReminderEnabled,
            streakEnabled = settings.streakReminderEnabled,
            canPost = NotificationAccess.granted(applicationContext),
            today = today,
            minutesToday = summary.minutesToday,
            goalMinutes = summary.goalMinutes,
            currentStreak = summary.currentStreak,
            lastReminderDay = settings.lastReminderDay,
            minuteOfDay = minuteOfDay,
            reminderMinuteOfDay = settings.reminderMinuteOfDay,
            minutesSinceLastPageTurn = books.lastPageTurnAt()
                // Coerced at zero because a clock that moved backwards would
                // otherwise produce a negative age, which reads as "long ago" and
                // would let a reminder through to someone mid-chapter.
                ?.let { ((nowMs() - it) / 60_000L).coerceAtLeast(0L).toInt() },
            book = bookInProgress(),
        )

        when (val decision = Reminders.decide(facts)) {
            is ReminderDecision.Notify -> {
                val delivered = QuireNotifier.post(
                    applicationContext, ReminderWords.pick(decision.kind, facts),
                )
                // Recorded only on a real delivery. Marking the day either way would
                // mean a notification the OS refused — a revoked permission, a muted
                // channel — silenced tomorrow as well as today.
                if (delivered) habits.recordReminderSent(today)
            }

            is ReminderDecision.Silent -> Unit
        }

        // Re-read rather than reusing the snapshot taken at the top of this method.
        // Gathering the facts, deciding and posting all take time, and a reader who
        // changes something during that window would otherwise have it undone by the
        // enqueue below — reminders switched off leaving a job alive to wake the
        // device tomorrow, or a newly chosen time re-enqueued at the old one.
        val current = habits.settings()
        if (current.remindersEnabled) {
            ReminderScheduler.schedule(
                applicationContext,
                reminderMinuteOfDay = current.reminderMinuteOfDay,
                nowMinuteOfDay = minuteOfDay,
                replaceExisting = true,
            )
        } else {
            // The second lock on "off". Switching reminders off cancels the pending
            // job immediately, but a job already in flight, or one that survived a
            // reinstall of the schedule, still arrives here — and it takes itself
            // out rather than waiting to be cancelled again. Rescheduling instead
            // would make "off" mean "off until the next run", which is not what the
            // switch says.
            ReminderScheduler.cancel(applicationContext)
        }

        // Always success: there is nothing here worth retrying. A missed evening is
        // gone, and a retry would arrive at a time the reader never chose.
        return Result.success()
    }

    /**
     * The book the reader is part-way through, or null.
     *
     * Most recently opened wins, but only among books that are neither unopened nor
     * finished. Naming a book the reader closed for good would be specific and
     * wrong, which is worse here than being general — and a book they never opened
     * is not something they left off in the middle of.
     */
    private suspend fun bookInProgress(): BookInProgress? {
        val candidate = books.observeLibrary().first()
            .filter { it.lastOpenedAt != null && it.progress < 1.0 }
            .maxByOrNull { it.lastOpenedAt ?: 0L }
            ?: return null

        val position = books.progressOf(candidate.id)
        // The chapter index rather than the chapter: reading a whole chapter off
        // disk to recover its title would undo the reason chapters are stored
        // separately in the first place.
        val chapter = books.chapterIndex(candidate.id)
            .firstOrNull { it.index == position.chapterIndex }

        return BookInProgress(
            title = candidate.title,
            chapterLabel = when {
                // This book is read as pages, so the chapter slot of its stored
                // position holds a *page number* — PagePosition puts it there, and
                // both readers share one reading_progress row per book.
                //
                // Asked before the lookup below, and that order is the whole point.
                // `reflowFailed` covers two routes: a scan, which carries no
                // chapters at all, and a low-confidence reflow, which keeps a real
                // chapter list. On the second route a page number lands *inside*
                // that list and resolves to a genuine chapter with a genuine title —
                // so a range check alone would announce chapter two's real name to
                // someone sitting on page two.
                candidate.reflowFailed -> null

                // The stored place does not name a chapter of this book at all.
                chapter == null -> null
                // A chapter with no detected title still has an ordinal, and the
                // ordinal is true.
                !chapter.title.isNullOrBlank() -> chapter.title
                else -> "Chapter ${chapter.index + 1}"
            },
            percentRead = (candidate.progress * 100).roundToInt().coerceIn(0, 100),
        )
    }
}

/**
 * Supplies [ReminderWorker] its collaborators.
 *
 * Separate from `QuireWorkerFactory` rather than bolted onto it: the two share
 * nothing, and `DelegatingWorkerFactory` already exists to combine them. Adding
 * parameters to the import factory would have meant changing its tests for reasons
 * that have nothing to do with importing.
 */
class ReminderWorkerFactory(
    private val habits: HabitRepository,
    private val books: BookRepository,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        ReminderWorker::class.java.name ->
            ReminderWorker(appContext, workerParameters, habits, books, nowMs, zone)

        else -> null   // null lets WorkManager try the next factory
    }
}
