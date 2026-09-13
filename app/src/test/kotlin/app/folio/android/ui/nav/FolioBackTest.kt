package app.folio.android.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Back, from every place a reader can stand.
 *
 * Worth a test of its own because the failure is invisible until it happens to you:
 * Back used to close the app from the middle of a chapter, and no screen looks wrong
 * while that is true. The rule is only checkable by naming every level and saying
 * where each one lands.
 */
class FolioBackTest {

    private fun popped(snapshot: NavSnapshot): NavSnapshot {
        val action = back(snapshot)
        assertTrue("expected to pop, got $action", action is BackAction.Pop)
        return (action as BackAction.Pop).next
    }

    @Test
    fun `the library asks before closing the app`() {
        assertEquals(BackAction.ConfirmExit, back(NavSnapshot()))
    }

    @Test
    fun `a tab returns to the library rather than closing`() {
        listOf(FolioDestination.BOOKMARKS, FolioDestination.SETTINGS).forEach { tab ->
            val next = popped(NavSnapshot(destination = tab))
            assertEquals(FolioDestination.LIBRARY, next.destination)
        }
    }

    @Test
    fun `leaving the reader returns to the book it was opened from`() {
        // The detail that makes Back feel right. A reader opens a book from its
        // details page; Back should be the way back to that page, not past it.
        val next = popped(NavSnapshot(readingBookId = "b1", openBookId = "b1"))
        assertEquals(null, next.readingBookId)
        assertEquals("b1", next.openBookId)
    }

    @Test
    fun `a book opened from bookmarks returns to the bookmarks list`() {
        // Opened from the Bookmarks tab, there is no details page underneath — so
        // Back lands on the list the reader came from, one more press from home.
        val next = popped(
            NavSnapshot(readingBookId = "b1", destination = FolioDestination.BOOKMARKS)
        )
        assertEquals(null, next.readingBookId)
        assertEquals(FolioDestination.BOOKMARKS, next.destination)
        assertEquals(BackAction.Pop(NavSnapshot()), back(next))
    }

    @Test
    fun `the original pdf viewer closes before the reader does`() {
        val next = popped(NavSnapshot(readingOriginal = true, readingBookId = "b1"))
        assertEquals(false, next.readingOriginal)
        assertEquals("b1", next.readingBookId)
    }

    @Test
    fun `book details returns to the library`() {
        assertEquals(NavSnapshot(), popped(NavSnapshot(openBookId = "b1")))
    }

    @Test
    fun `milestones and level return to the streak they were opened from`() {
        listOf(HabitScreen.MILESTONES, HabitScreen.LEVEL).forEach { screen ->
            assertEquals(
                HabitScreen.STREAK,
                popped(NavSnapshot(habitScreen = screen)).habitScreen,
            )
        }
    }

    @Test
    fun `the streak screen closes to whatever is underneath it`() {
        assertEquals(null, popped(NavSnapshot(habitScreen = HabitScreen.STREAK)).habitScreen)
    }

    @Test
    fun `back always terminates at the library`() {
        // Every level pops exactly one thing, so the deepest state must unwind to a
        // bare Library in a bounded number of presses. A rule that popped nothing —
        // or popped two things — would show up here rather than as a stuck screen.
        var snapshot = NavSnapshot(
            readingOriginal = true,
            readingBookId = "b1",
            openBookId = "b1",
            habitScreen = HabitScreen.MILESTONES,
            destination = FolioDestination.BOOKMARKS,
        )
        var presses = 0
        while (back(snapshot) is BackAction.Pop) {
            snapshot = popped(snapshot)
            presses++
            assertTrue("back is not unwinding", presses < 20)
        }
        assertEquals(NavSnapshot(), snapshot)
        assertEquals(BackAction.ConfirmExit, back(snapshot))
    }
}
