package app.folio.android.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.folio.android.data.BookRepository
import app.folio.android.data.BookStore
import app.folio.android.data.FolioDatabase
import app.folio.android.data.HabitRepository
import app.folio.core.habit.DayMinutes
import app.folio.core.model.Book
import app.folio.core.model.BookMetadata
import app.folio.core.model.Chapter
import app.folio.core.model.ContentBlock
import app.folio.core.model.InlineSpan
import app.folio.core.model.ProcessingStatus
import app.folio.core.model.ReadingPosition
import app.folio.core.model.SourceFormat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.ZoneId

/**
 * The job that puts the rule and the words together.
 *
 * The worker is thin on purpose — gather, decide, post, reschedule — so what is
 * worth testing here is the wiring between those four, not the rule again. In
 * particular: that the day is only marked as reminded when something was actually
 * delivered, and that switching reminders off leaves nothing behind that could wake
 * up later.
 */
@RunWith(RobolectricTestRunner::class)
class ReminderWorkerTest {

    @get:Rule val temp = TemporaryFolder()

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager
        get() = app.getSystemService(NotificationManager::class.java)

    private val zone = ZoneId.of("UTC")
    private val date = LocalDate.of(2026, 9, 13)
    private val today = date.toEpochDay()

    /** Five past eight in the evening: inside the default reminder's window. */
    private var nowMs = date.atTime(20, 5).atZone(zone).toInstant().toEpochMilli()

    private lateinit var db: FolioDatabase
    private lateinit var books: BookRepository
    private lateinit var habits: HabitRepository

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        db = Room.inMemoryDatabaseBuilder(app, FolioDatabase::class.java)
            .allowMainThreadQueries().build()
        books = BookRepository(db, BookStore(temp.root)) { nowMs }
        habits = HabitRepository(db, zone = { zone }, nowMs = { nowMs })
    }

    @After
    fun tearDown() = db.close()

    private fun worker() = TestListenableWorkerBuilder<ReminderWorker>(app)
        .setWorkerFactory(ReminderWorkerFactory(habits, books, { nowMs }, { zone }))
        .build()

    private fun run() = runBlocking { worker().doWork() }

    private fun shade(): List<Notification> = shadowOf(manager).allNotifications

    private fun titleOf(n: Notification) =
        n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

    private fun bodyOf(n: Notification) =
        n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

    private fun scheduled(): List<WorkInfo> =
        WorkManager.getInstance(app)
            .getWorkInfosForUniqueWork(ReminderScheduler.WORK_NAME).get()

    private fun book(id: String, title: String) = Book(
        id = id, title = title, author = "Ursula K. Le Guin", coverPath = null,
        metadata = BookMetadata(language = "en"), sourceFormat = SourceFormat.EPUB,
        status = ProcessingStatus.Ready,
        chapters = listOf(
            Chapter(0, "The Year 1491", listOf(ContentBlock.Paragraph(listOf(InlineSpan("aaaa")))), 0, 4),
            Chapter(1, "The Place Inside the Blizzard", listOf(ContentBlock.Paragraph(listOf(InlineSpan("bbbb")))), 4, 4),
        ),
    )

    // ---------------------------------------------------------- the main rule

    @Test
    fun `a day the reader has already read is left alone`() = runBlocking {
        habits.setRemindersEnabled(true)
        habits.record(listOf(DayMinutes(today, 12)))

        run()

        assertTrue("Folio interrupted a day that was already read: ${shade()}", shade().isEmpty())
        assertEquals(
            "a day with no reminder was marked as reminded",
            -1L,
            habits.settings().lastReminderDay,
        )
    }

    @Test
    fun `a clean day at the chosen time gets one reminder`() = runBlocking {
        habits.setRemindersEnabled(true)

        run()

        assertEquals("expected exactly one reminder, got ${shade()}", 1, shade().size)
        assertEquals(today, habits.settings().lastReminderDay)
    }

    @Test
    fun `running again the same day says nothing more`() = runBlocking {
        habits.setRemindersEnabled(true)
        run()
        // Clear the shade so a second post would be visible. Sharing one id means
        // counting notifications cannot tell a replacement from a no-op.
        manager.cancelAll()

        run()

        assertTrue("Folio reminded twice in one day: ${shade()}", shade().isEmpty())
    }

    @Test
    fun `a reminder outside its window does not burn the day`() = runBlocking {
        // The phone dozed through the evening and woke at two in the morning. Saying
        // nothing is right; recording that today was reminded would then silence the
        // evening the reader was actually awake for.
        habits.setRemindersEnabled(true)
        nowMs = date.plusDays(1).atTime(2, 0).atZone(zone).toInstant().toEpochMilli()

        run()

        assertTrue(shade().isEmpty())
        assertEquals(-1L, habits.settings().lastReminderDay)
    }

    @Test
    fun `nothing is delivered when the phone refuses notifications`() = runBlocking {
        habits.setRemindersEnabled(true)
        shadowOf(manager).setNotificationsEnabled(false)

        run()

        assertTrue(shade().isEmpty())
        assertEquals(
            "a day was marked reminded by a notification that could not be delivered",
            -1L,
            habits.settings().lastReminderDay,
        )
    }

    // ------------------------------------------------------------ scheduling

    @Test
    fun `a delivery schedules the next one`() = runBlocking {
        habits.setRemindersEnabled(true)

        run()

        assertEquals("tomorrow's reminder was not scheduled", 1, scheduled().size)
        assertEquals(WorkInfo.State.ENQUEUED, scheduled().single().state)
    }

    @Test
    fun `switching reminders off leaves nothing behind to wake up later`() = runBlocking {
        // Off has to be permanent, not "off until the next run reschedules itself".
        // A job that keeps re-enqueueing after the reader said no is a reminder that
        // comes back, which is the bug this clause exists to prevent.
        habits.setRemindersEnabled(true)
        run()
        assertEquals(1, scheduled().size)

        habits.setRemindersEnabled(false)
        manager.cancelAll()
        run()

        assertTrue("Folio spoke after being switched off: ${shade()}", shade().isEmpty())
        assertTrue(
            "a reminder is still pending after being switched off: ${scheduled().map { it.state }}",
            scheduled().none {
                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
            },
        )
    }

    // ------------------------------------------------------ the reader's book

    @Test
    fun `the reminder names the book the reader actually has open`() = runBlocking {
        books.save(book("b1", "The Left Hand of Darkness"))
        books.markOpened("b1")
        books.saveProgress("b1", ReadingPosition(1, 0, 0), progress = 0.42)
        habits.setRemindersEnabled(true)

        run()

        val text = shade().single().let { "${titleOf(it)} ${bodyOf(it)}" }
        assertTrue("the reminder did not name the reader's book: $text", "The Left Hand of Darkness" in text)
    }

    @Test
    fun `the chapter named is the one the reader is in`() = runBlocking {
        books.save(book("b1", "The Left Hand of Darkness"))
        books.markOpened("b1")
        books.saveProgress("b1", ReadingPosition(1, 0, 0), progress = 0.42)
        habits.setRemindersEnabled(true)

        // Walked day by day so every variant of the copy is exercised against real
        // stored data, not only the one today happens to select.
        val named = (0..5).map { offset ->
            nowMs = date.plusDays(offset.toLong()).atTime(20, 5)
                .atZone(zone).toInstant().toEpochMilli()
            habits.recordReminderSent(-1L)
            manager.cancelAll()
            run()
            shade().single().let { "${titleOf(it)} ${bodyOf(it)}" }
        }
        assertTrue(
            "no variant ever named the chapter the reader is in: $named",
            named.any { "The Place Inside the Blizzard" in it },
        )
        assertTrue(
            "a variant named a chapter the reader is not in: $named",
            named.none { "The Year 1491" in it },
        )
    }

    @Test
    fun `a book the reader finished is not the one they are in the middle of`() = runBlocking {
        books.save(book("done", "A Wizard of Earthsea"))
        books.save(book("open", "The Tombs of Atuan"))
        books.markOpened("open")
        books.saveProgress("open", ReadingPosition(0, 0, 0), progress = 0.3)
        // Finished, and opened most recently — so "most recent" alone would pick it.
        nowMs += 60_000
        books.markOpened("done")
        books.saveProgress("done", ReadingPosition(1, 0, 0), progress = 1.0)
        habits.setRemindersEnabled(true)

        run()

        val text = shade().single().let { "${titleOf(it)} ${bodyOf(it)}" }
        assertTrue("the reminder named a finished book: $text", "The Tombs of Atuan" in text)
    }

    @Test
    fun `a reader with nothing open is still reminded, without a book being invented`() = runBlocking {
        habits.setRemindersEnabled(true)

        run()

        val text = shade().single().let { "${titleOf(it)} ${bodyOf(it)}" }
        assertTrue("nothing was said at all", text.isNotBlank())
        assertTrue("a book appeared out of nowhere: $text", "null" !in text)
    }

    @Test
    fun `a long streak changes the register`() = runBlocking {
        habits.setRemindersEnabled(true)
        // Four consecutive days met, the most recent being yesterday: a live streak
        // with today still unread, which is exactly when the streak words apply.
        (1..4).forEach { back ->
            habits.record(listOf(DayMinutes(today - back, 30)))
        }

        run()

        val text = shade().single().let { "${titleOf(it)} ${bodyOf(it)}" }
        assertTrue("the streak was not named: $text", "4 days" in text)
    }
}
