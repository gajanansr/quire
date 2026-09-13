package app.quire.android.widget

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.quire.android.MainActivity
import app.quire.android.ui.nav.HabitScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Where a tap lands.
 *
 * The only symptom of a wrong widget intent is a tap that does nothing, or one that
 * opens the app on the wrong screen — neither of which anything inside Quire can
 * notice. So which widget opens what is data on [QuireWidget], and the round trip
 * through the intent is asserted here rather than discovered on a home screen.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetIntentTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the habit widget opens the streak screen`() {
        assertEquals(HabitScreen.STREAK, QuireWidget.HABIT.opens)
        val intent = QuireWidgets.openIntent(app, QuireWidget.HABIT)
        assertEquals(HabitScreen.STREAK, QuireWidgets.habitScreenOf(intent))
    }

    @Test
    fun `the stats widget simply opens Quire`() {
        assertNull(QuireWidget.STATS.opens)
        assertNull(QuireWidgets.habitScreenOf(QuireWidgets.openIntent(app, QuireWidget.STATS)))
    }

    @Test
    fun `both widgets open Quire itself`() {
        QuireWidget.entries.forEach { widget ->
            assertEquals(
                MainActivity::class.java.name,
                QuireWidgets.openIntent(app, widget).component?.className,
            )
        }
    }

    @Test
    fun `the intent reuses the task Quire is already in`() {
        // NEW_TASK because a broadcast receiver has no task to launch into, and
        // SINGLE_TOP so the reader gets the Quire they left rather than a second
        // copy of it — and so onNewIntent, not onCreate, delivers the destination.
        val flags = QuireWidgets.openIntent(app, QuireWidget.HABIT).flags
        assertTrue(
            "the widget intent does not start its own task",
            flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
        assertTrue(
            "the widget intent would stack a second copy of Quire",
            flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0,
        )
    }

    @Test
    fun `a launcher intent names no screen`() {
        assertNull(QuireWidgets.habitScreenOf(Intent(Intent.ACTION_MAIN)))
        assertNull(QuireWidgets.habitScreenOf(null))
    }

    @Test
    fun `a name Quire does not recognise is ignored rather than thrown`() {
        // A widget pinned by an older version of Quire keeps the intent it was
        // created with. Reading an enum out of it with valueOf would crash the app
        // on a tap, months later, for the one reader who never removed it.
        val stale = Intent(app, MainActivity::class.java)
            .putExtra(QuireWidgets.EXTRA_HABIT_SCREEN, "TROPHIES")
        assertNull(QuireWidgets.habitScreenOf(stale))
    }

    @Test
    fun `the two widgets do not share a pending intent`() {
        // PendingIntent treats intents that differ only in extras as the same, so
        // without distinct request codes the second widget built would silently
        // inherit the first one's destination.
        assertNotEquals(QuireWidget.HABIT.requestCode, QuireWidget.STATS.requestCode)
    }
}
