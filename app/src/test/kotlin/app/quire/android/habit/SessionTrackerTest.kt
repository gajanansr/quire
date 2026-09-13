package app.quire.android.habit

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.quire.android.data.QuireDatabase
import app.quire.android.data.HabitRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId

/**
 * The habit loop closing: reading produces recorded minutes.
 *
 * Without this the whole habit system has no input — every streak and goal would
 * be computed from a table nothing ever writes to.
 */
@RunWith(RobolectricTestRunner::class)
class SessionTrackerTest {

    private lateinit var db: QuireDatabase
    private lateinit var habits: HabitRepository
    private var clock = 0L

    private val minute = 60_000L
    private val utc = ZoneId.of("UTC")

    /** 10:00 on an arbitrary day. */
    private val tenAm = 20_000L * 24 * 60 * minute + 10 * 60 * minute

    @Before
    fun setUp() {
        clock = tenAm
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), QuireDatabase::class.java,
        ).allowMainThreadQueries().build()
        // Both sides share one clock, or they disagree about what day it is.
        habits = HabitRepository(db, zone = { utc }, nowMs = { clock })
    }

    @After
    fun tearDown() = db.close()

    private fun tracker() = SessionTracker(habits, zone = { utc }, nowMs = { clock })

    @Test
    fun `reading records minutes against the day`() = runBlocking {
        val t = tracker()
        t.record()
        repeat(20) { clock += minute; t.record() }

        val credits = t.flush()
        assertTrue("nothing was recorded", credits.isNotEmpty())
        assertEquals(20, credits.single().minutes)
    }

    @Test
    fun `recorded minutes reach the habit summary`() = runBlocking {
        // The end-to-end assertion the app was missing: read, and the Library's
        // streak data actually changes.
        habits.setDailyGoal(10)
        val t = tracker()
        t.record()
        repeat(12) { clock += minute; t.record() }
        t.flush()

        val summary = habits.observeSummary().first()
        assertEquals(12, summary.minutesToday)
        assertTrue("goal should be met", summary.goalMet)
        assertEquals(1, summary.currentStreak)
    }

    @Test
    fun `a glance records nothing`() = runBlocking {
        val t = tracker()
        t.record()
        clock += 4_000
        assertTrue(t.flush().isEmpty())
        assertEquals(0, habits.observeSummary().first().minutesToday)
    }

    @Test
    fun `flushing twice does not double-count`() = runBlocking {
        val t = tracker()
        t.record()
        repeat(10) { clock += minute; t.record() }
        t.flush()
        t.flush()
        assertEquals(10, habits.observeSummary().first().minutesToday)
    }

    @Test
    fun `a second session in the same day adds to the first`() = runBlocking {
        val t = tracker()
        t.record(); repeat(6) { clock += minute; t.record() }
        t.flush()

        clock += 4 * 60 * minute
        t.record(); repeat(9) { clock += minute; t.record() }
        t.flush()

        assertEquals(15, habits.observeSummary().first().minutesToday)
    }

    @Test
    fun `an idle phone does not accumulate reading`() = runBlocking {
        // Five minutes of reading, then the phone sits on the page for two hours.
        val t = tracker()
        t.record()
        repeat(5) { clock += minute; t.record() }
        clock += 120 * minute

        t.flush()
        val recorded = habits.observeSummary().first().minutesToday
        assertTrue("credited $recorded minutes for five minutes of reading", recorded <= 8)
    }

    @Test
    fun `the tracker reports whether a session is in progress`() {
        val t = tracker()
        assertFalse(t.isActive)
        t.record()
        assertTrue(t.isActive)
        runBlocking { t.flush() }
        assertFalse(t.isActive)
    }
}
