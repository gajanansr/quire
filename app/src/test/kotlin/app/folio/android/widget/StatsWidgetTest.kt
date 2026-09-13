package app.folio.android.widget

import android.app.Application
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.folio.android.R
import app.folio.android.data.HabitSummary
import app.folio.android.ui.theme.FolioThemeName
import app.folio.core.habit.ReadingDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The stats widget: its declaration, its sizes, and what it actually draws.
 *
 * The three states this widget has are the three ways it can be wrong on a home
 * screen — a stocked library shown the empty invitation, an empty one shown three
 * zeroes, or a book row left visible with nothing in it. Each has a test.
 */
@RunWith(RobolectricTestRunner::class)
class StatsWidgetTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun render(
        state: StatsWidgetState,
        theme: FolioThemeName = FolioThemeName.PAPER,
    ): View = statsViews(app, state, widgetPalette(theme)).apply(app, FrameLayout(app))

    private fun render(snapshot: WidgetSnapshot): View = render(statsWidget(snapshot))

    /** What `ImageView.setColorFilter(int)` builds: SRC_ATOP over the white shape. */
    private fun tint(color: Int) = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)

    private fun filterOn(root: View, id: Int) =
        root.findViewById<ImageView>(id).colorFilter

    /** How far the progress fill's clip drawable has been opened, 0..10000. */
    private fun fillLevel(root: View): Int =
        root.findViewById<ImageView>(R.id.widget_stats_progress_fill).drawable.level

    private fun text(root: View, id: Int): String =
        root.findViewById<TextView>(id).text.toString()

    private fun visible(root: View, id: Int): Boolean =
        root.findViewById<View>(id).visibility == View.VISIBLE

    private fun habits(days: List<ReadingDay>) =
        HabitSummary(days = days, today = 20_000L)

    @Test
    fun `the provider is declared, exported and wired to its info xml`() {
        AppWidgetInfo.assertDeclaredCorrectly(
            StatsWidgetProvider::class.java, R.xml.widget_stats_info,
        )
    }

    @Test
    fun `the stats widget is four cells by two`() {
        assertEquals(4, AppWidgetInfo.int(R.xml.widget_stats_info, "targetCellWidth"))
        assertEquals(2, AppWidgetInfo.int(R.xml.widget_stats_info, "targetCellHeight"))
    }

    @Test
    fun `the stats widget is never woken by the clock`() {
        // Nothing here changes while Folio is closed: a book is not finished by the
        // passage of time. A periodic update would spend battery redrawing numbers
        // that cannot have moved. FolioWidgets.refresh is its only refresh.
        assertEquals(0, AppWidgetInfo.int(R.xml.widget_stats_info, "updatePeriodMillis"))
    }

    @Test
    fun `the preview layout the picker shows is a real layout`() {
        val preview = AppWidgetInfo.resource(R.xml.widget_stats_info, "previewLayout")
        assertEquals(R.layout.widget_stats_preview, preview)
        assertNotNull(
            android.view.LayoutInflater.from(app).inflate(preview, FrameLayout(app), false)
        )
    }

    @Test
    fun `the three tiles carry their values and labels`() {
        val root = render(
            WidgetSnapshot(
                habits = habits(listOf(ReadingDay(20_000L, 200, 10))),
                booksFinished = 4,
                chaptersFinished = 37,
                libraryCount = 6,
            )
        )
        assertEquals(3, StatsWidgetIds.VALUES.size)
        assertEquals("4", text(root, StatsWidgetIds.VALUES[0]))
        assertEquals("Books", text(root, StatsWidgetIds.LABELS[0]))
        assertEquals("37", text(root, StatsWidgetIds.VALUES[1]))
        assertEquals("Chapters", text(root, StatsWidgetIds.LABELS[1]))
        assertEquals("3h 20m", text(root, StatsWidgetIds.VALUES[2]))
        assertEquals("Read", text(root, StatsWidgetIds.LABELS[2]))
    }

    @Test
    fun `the book being read is drawn with a bar that matches the number beside it`() {
        val root = render(
            WidgetSnapshot(
                libraryCount = 2,
                currentBook = CurrentBook("b1", "The Weight of Silence", 0.42),
            )
        )
        assertTrue(visible(root, R.id.widget_stats_current))
        assertEquals("The Weight of Silence", text(root, R.id.widget_stats_title))
        assertEquals("42% through", text(root, R.id.widget_stats_percent))

        // The bar is not a ProgressBar: its colours could not be themed before API
        // 31. It is a clip drawable opened to the percentage, and a clip drawable
        // left at its default level draws nothing at all — so a missed setImageLevel
        // would show an empty track and read as a book nobody has started.
        assertEquals(4_200, fillLevel(root))
    }

    @Test
    fun `an untouched book and a finished one are both drawn honestly`() {
        val at = { progress: Double ->
            fillLevel(render(WidgetSnapshot(
                libraryCount = 1,
                currentBook = CurrentBook("b1", "Nightjar", progress),
            )))
        }
        assertEquals(0, at(0.004))
        assertEquals(10_000, at(0.999))
    }

    @Test
    fun `the widget is drawn in the theme the reader chose`() {
        val sepia = widgetPalette(FolioThemeName.SEPIA)
        val root = render(
            statsWidget(WidgetSnapshot(
                libraryCount = 2,
                booksFinished = 4,
                currentBook = CurrentBook("b1", "The Weight of Silence", 0.42),
            )),
            FolioThemeName.SEPIA,
        )
        assertEquals(tint(sepia.surface), filterOn(root, R.id.widget_stats_surface))
        assertEquals(tint(sepia.edge), filterOn(root, R.id.widget_stats_hairline))
        assertEquals(tint(sepia.mark), filterOn(root, R.id.widget_stats_mark))
        assertEquals(
            tint(sepia.edge), filterOn(root, R.id.widget_stats_progress_track),
        )
        assertEquals(
            tint(sepia.accent), filterOn(root, R.id.widget_stats_progress_fill),
        )
        assertEquals(
            sepia.ink,
            root.findViewById<TextView>(StatsWidgetIds.VALUES[0]).currentTextColor,
        )
        assertEquals(
            sepia.muted,
            root.findViewById<TextView>(StatsWidgetIds.LABELS[0]).currentTextColor,
        )
    }

    @Test
    fun `two themes do not draw the same widget`() {
        val snapshot = WidgetSnapshot(libraryCount = 2, booksFinished = 1)
        val sepia = render(statsWidget(snapshot), FolioThemeName.SEPIA)
        val black = render(statsWidget(snapshot), FolioThemeName.BLACK)
        assertNotEquals(
            "the card is the same colour in Sepia and Black",
            filterOn(sepia, R.id.widget_stats_surface),
            filterOn(black, R.id.widget_stats_surface),
        )
    }

    @Test
    fun `the mark stays on the card when there is nothing else on it`() {
        // The empty invitation switches off the tiles, the book row and the prompt.
        // The mark is a child of the root rather than of any of them precisely so
        // that the one state with nothing on the card still says whose card it is.
        val root = render(WidgetSnapshot())
        assertTrue(visible(root, R.id.widget_stats_empty))
        assertTrue(visible(root, R.id.widget_stats_mark))
        assertEquals(
            tint(widgetPalette(FolioThemeName.PAPER).mark),
            filterOn(root, R.id.widget_stats_mark),
        )
    }

    @Test
    fun `an empty library is invited, and shown no zeroes at all`() {
        val root = render(WidgetSnapshot())
        assertTrue(visible(root, R.id.widget_stats_empty))
        assertEquals("Your library is empty.", text(root, R.id.widget_stats_empty_title))
        assertEquals("Add a book to get started.", text(root, R.id.widget_stats_empty_detail))
        assertTrue("three zeroes are showing", !visible(root, R.id.widget_stats_tiles))
        assertTrue(!visible(root, R.id.widget_stats_current))
        assertTrue(!visible(root, R.id.widget_stats_prompt))
    }

    @Test
    fun `a stocked library sees its real zeroes and not the invitation`() {
        val root = render(WidgetSnapshot(libraryCount = 3))
        assertTrue(visible(root, R.id.widget_stats_tiles))
        assertTrue(!visible(root, R.id.widget_stats_empty))
        assertEquals("0", text(root, StatsWidgetIds.VALUES[0]))
    }

    @Test
    fun `nothing open leaves a prompt rather than an empty book row`() {
        // The failure this catches: a book row left visible with a blank title and a
        // progress bar sitting at zero, which reads as a bug rather than as a state.
        val root = render(WidgetSnapshot(libraryCount = 3))
        assertTrue(visible(root, R.id.widget_stats_prompt))
        assertEquals("3 books waiting", text(root, R.id.widget_stats_prompt))
        assertTrue(!visible(root, R.id.widget_stats_current))
    }

    @Test
    fun `the stats provider draws the stats widget`() {
        val root = StatsWidgetProvider()
            .views(app, WidgetSnapshot(libraryCount = 2, chaptersFinished = 5))
            .apply(app, FrameLayout(app))
        assertEquals("5", text(root, StatsWidgetIds.VALUES[1]))
        assertEquals("Chapters", text(root, StatsWidgetIds.LABELS[1]))
    }

    @Test
    fun `the whole widget opens Folio when tapped`() {
        val root = render(WidgetSnapshot(libraryCount = 1))
        assertTrue(
            "the stats widget has no click target",
            root.findViewById<View>(R.id.widget_stats_root).hasOnClickListeners(),
        )
    }
}
