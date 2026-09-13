package app.quire.android.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.quire.android.data.BookRepository
import app.quire.android.data.BookStore
import app.quire.android.data.QuireDatabase
import app.quire.android.data.HabitRepository
import app.quire.core.habit.DayMinutes
import app.quire.core.model.Book
import app.quire.core.model.BookMetadata
import app.quire.core.model.Chapter
import app.quire.core.model.ContentBlock
import app.quire.core.model.InlineSpan
import app.quire.core.model.ProcessingStatus
import app.quire.core.model.ReadingPosition
import app.quire.core.model.SourceFormat
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
import java.time.ZoneId

/**
 * A widget that is right only every half hour is a widget nobody trusts.
 *
 * The platform's minimum update period is thirty minutes, which is fine as a
 * backstop for the calendar turning over and useless for the thing that actually
 * changes these numbers: the reader reading. So every write that moves something a
 * widget shows says so, and this is the list of them.
 *
 * The notification is a constructor parameter rather than a context the repositories
 * hold, which is what keeps `BookRepository` and `HabitRepository` ignorant of
 * widgets entirely — and lets this count the calls.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetRefreshTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var db: QuireDatabase
    private lateinit var books: BookRepository
    private lateinit var habits: HabitRepository
    private var announced = 0

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), QuireDatabase::class.java,
        ).allowMainThreadQueries().build()
        books = BookRepository(db, BookStore(temp.root), onDataChanged = { announced++ })
        habits = HabitRepository(
            db, zone = { ZoneId.of("UTC") }, onDataChanged = { announced++ },
        )
    }

    @After
    fun tearDown() = db.close()

    private fun book(id: String) = Book(
        id = id, title = "The Weight of Silence", author = null, coverPath = null,
        metadata = BookMetadata(language = "en"), sourceFormat = SourceFormat.EPUB,
        status = ProcessingStatus.Ready,
        chapters = listOf(
            Chapter(0, "One", listOf(ContentBlock.Paragraph(listOf(InlineSpan("aaaa")))), 0, 4),
        ),
    )

    @Test
    fun `recorded minutes announce themselves`() = runBlocking {
        habits.record(listOf(DayMinutes(habits.todayEpochDay(), 12)))
        assertEquals(1, announced)
    }

    @Test
    fun `a session that credited nothing announces nothing`() = runBlocking {
        // The common case for someone who opened a book and closed it again. A
        // broadcast per non-event is battery spent to redraw the same card.
        habits.record(emptyList())
        assertEquals(0, announced)
    }

    @Test
    fun `finishing a book announces itself`() = runBlocking {
        habits.recordBookFinished()
        assertEquals(1, announced)
    }

    @Test
    fun `finishing a chapter announces itself`() = runBlocking {
        habits.recordChapterFinished()
        assertEquals(1, announced)
    }

    @Test
    fun `changing the daily goal announces itself`() = runBlocking {
        // The habit widget prints the goal — "10 minutes a day" — so a goal changed
        // in Settings and not reflected on the home screen is two answers to the
        // same question on one phone.
        habits.setDailyGoal(20)
        assertEquals(1, announced)
    }

    @Test
    fun `saving a book announces itself`() = runBlocking {
        books.save(book("a"))
        assertEquals(1, announced)
    }

    @Test
    fun `opening a book announces itself`() = runBlocking {
        books.save(book("a"))
        announced = 0
        books.markOpened("a")
        assertEquals(1, announced)
    }

    @Test
    fun `saved progress announces itself`() = runBlocking {
        books.save(book("a"))
        announced = 0
        books.saveProgress("a", ReadingPosition(0, 0, 2), 0.4)
        assertEquals(1, announced)
    }

    @Test
    fun `deleting a book announces itself once`() = runBlocking {
        books.save(book("a"))
        announced = 0
        books.delete("a")
        assertEquals(1, announced)
    }

    @Test
    fun `a refresh names its provider and the widgets to redraw`() {
        val intent = QuireWidgets.refreshIntent(app, QuireWidget.HABIT, intArrayOf(7, 9))
        assertEquals(AppWidgetManager.ACTION_APPWIDGET_UPDATE, intent.action)
        assertEquals(
            HabitWidgetProvider::class.java.name, intent.component?.className,
        )
        assertTrue(
            intArrayOf(7, 9).contentEquals(
                intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
            )
        )
    }

    @Test
    fun `nothing is broadcast when no widget is installed`() {
        // The common case: most readers will pin neither widget, and every write
        // would otherwise send two broadcasts into the void.
        shadowOf(app).clearBroadcastIntents()
        QuireWidgets.refresh(app)
        assertTrue(
            "a refresh was broadcast with no widget to receive it",
            shadowOf(app).broadcastIntents.isEmpty(),
        )
    }

    @Test
    fun `every widget has a provider to refresh`() {
        QuireWidget.entries.forEach { widget ->
            assertTrue(
                "${widget.name} names no provider",
                app.packageManager.getReceiverInfo(
                    android.content.ComponentName(app, widget.provider), 0,
                ).exported,
            )
        }
    }

    @Test
    fun `a changed theme announces itself`() = runBlocking {
        // The widgets are painted in the reader's theme, so changing it changes what
        // they show. Without an announcement the app turns dark and the widget beside
        // it stays on paper until the next write or the half-hour tick — which reads
        // as a bug rather than as a delay, on the one screen where both are visible
        // at once.
        habits.setTheme("NIGHT")
        assertEquals(1, announced)
    }
}
