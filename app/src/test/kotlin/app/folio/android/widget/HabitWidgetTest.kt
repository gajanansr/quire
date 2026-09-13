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
import app.folio.android.ui.theme.FolioThemeName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The habit widget: its declaration, its sizes, and what it actually draws.
 *
 * `WidgetStateTest` settles what it says. This settles that the saying reaches a
 * view — a `RemoteViews` that names an id the layout does not have fails silently on
 * a home screen, leaving a widget with one line missing and no error anywhere.
 */
@RunWith(RobolectricTestRunner::class)
class HabitWidgetTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    /** `apply` returns the inflated tree; the parent it is given supplies layout
     *  params only, and never receives the view. */
    private fun render(
        state: HabitWidgetState,
        theme: FolioThemeName = FolioThemeName.PAPER,
    ): View = habitViews(app, state, widgetPalette(theme)).apply(app, FrameLayout(app))

    private fun filterOn(root: View, id: Int) =
        root.findViewById<ImageView>(id).colorFilter

    private fun text(root: View, id: Int): String =
        root.findViewById<TextView>(id).text.toString()

    private val week = List(7) { DayBar(filled = it % 2 == 0, alpha = 200) }

    private fun state(headline: String, detail: String = "", lit: Boolean = true) =
        HabitWidgetState(headline, detail, week, "Read 4 of the last 7 days", lit)

    @Test
    fun `the provider is declared, exported and wired to its info xml`() {
        AppWidgetInfo.assertDeclaredCorrectly(
            HabitWidgetProvider::class.java, R.xml.widget_habit_info,
        )
    }

    @Test
    fun `the habit widget is four cells by two`() {
        assertEquals(4, AppWidgetInfo.int(R.xml.widget_habit_info, "targetCellWidth"))
        assertEquals(2, AppWidgetInfo.int(R.xml.widget_habit_info, "targetCellHeight"))
    }

    @Test
    fun `the habit widget is refreshed by the clock as well as by the app`() {
        // The only widget whose content changes with nothing happening: at midnight
        // "minutes today" resets and a streak can break. Half an hour is the
        // platform's floor and the right backstop; FolioWidgets.refresh is what
        // keeps it current the rest of the time.
        assertEquals(
            1_800_000,
            AppWidgetInfo.int(R.xml.widget_habit_info, "updatePeriodMillis"),
        )
    }

    @Test
    fun `the preview layout the picker shows is a real layout`() {
        val preview = AppWidgetInfo.resource(R.xml.widget_habit_info, "previewLayout")
        assertEquals(R.layout.widget_habit_preview, preview)
        assertNotNull(android.view.LayoutInflater.from(app).inflate(preview, FrameLayout(app), false))
    }

    @Test
    fun `the widget draws the headline and detail it was given`() {
        val root = render(state("7 days", "12 of 20 minutes today"))
        assertEquals("7 days", text(root, R.id.widget_habit_headline))
        assertEquals("12 of 20 minutes today", text(root, R.id.widget_habit_detail))
    }

    @Test
    fun `the week strip is announced rather than read out as seven images`() {
        val root = render(state("7 days"))
        assertEquals(
            "Read 4 of the last 7 days",
            root.findViewById<View>(R.id.widget_habit_week)
                .contentDescription.toString(),
        )
    }

    @Test
    fun `a fresh install is invited rather than congratulated`() {
        val root = render(habitWidget(WidgetSnapshot()))
        assertEquals("Start a reading habit", text(root, R.id.widget_habit_headline))
        assertEquals("10 minutes a day", text(root, R.id.widget_habit_detail))
    }

    @Test
    fun `the week is seven bars and every one of them is drawn`() {
        // Seven fixed ids rather than views built at runtime: a RemoteViews cannot
        // inflate a child per item without an adapter, so the ids are the contract.
        assertEquals(7, HabitWidgetIds.DAYS.size)
        val root = render(state("1 day"))
        HabitWidgetIds.DAYS.forEach { id ->
            assertNotNull("day bar $id is missing from the layout", root.findViewById<ImageView>(id))
        }
    }

    @Test
    fun `a day that was read is accent, a day that was not is the border colour`() {
        // The one thing a reversed condition would get exactly backwards, and the
        // one thing nobody would notice from a screenshot of a week of full days.
        val root = render(
            HabitWidgetState(
                "1 day", "", listOf(DayBar(true, 200)) + List(6) { DayBar(false, 255) },
                "Read 1 of the last 7 days", lit = true,
            )
        )
        val read = root.findViewById<ImageView>(HabitWidgetIds.DAYS[0])
        val unread = root.findViewById<ImageView>(HabitWidgetIds.DAYS[1])

        val paper = widgetPalette(FolioThemeName.PAPER)
        assertEquals(tint(paper.accent), read.colorFilter)
        assertEquals(tint(paper.edge), unread.colorFilter)
        assertEquals(200, read.imageAlpha)
    }

    @Test
    fun `the flame is lit in the accent colour only when there is a streak`() {
        val flame = { lit: Boolean ->
            render(state("1 day", lit = lit))
                .findViewById<ImageView>(R.id.widget_habit_flame).colorFilter
        }
        val paper = widgetPalette(FolioThemeName.PAPER)
        assertEquals(tint(paper.accent), flame(true))
        // Muted rather than the border colour the unread bars take: one faint icon
        // reads as a drawing that failed to load, where seven faint bars read as a
        // week with nothing in it.
        assertEquals(tint(paper.muted), flame(false))
    }

    /** What `ImageView.setColorFilter(int)` builds: SRC_ATOP over the white shape. */
    private fun tint(color: Int) = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)

    @Test
    fun `the widget is drawn in the theme the reader chose`() {
        // The failure this exists for is quiet and total: a reader on Sepia, E-ink
        // or Black gets a widget in somebody else's palette, and nothing inside the
        // app looks wrong. Sepia is checked rather than Night because Night is what
        // a dark launcher would have produced anyway, so it would pass even if the
        // theme never arrived.
        val sepia = widgetPalette(FolioThemeName.SEPIA)
        val root = render(state("7 days", "12 of 20 minutes today"), FolioThemeName.SEPIA)

        assertEquals(tint(sepia.surface), filterOn(root, R.id.widget_habit_surface))
        assertEquals(tint(sepia.edge), filterOn(root, R.id.widget_habit_hairline))
        assertEquals(
            sepia.ink,
            root.findViewById<TextView>(R.id.widget_habit_headline).currentTextColor,
        )
        assertEquals(
            sepia.muted,
            root.findViewById<TextView>(R.id.widget_habit_detail).currentTextColor,
        )
        assertEquals(
            tint(sepia.accent),
            filterOn(root, HabitWidgetIds.DAYS[0]),
        )
    }

    @Test
    fun `two themes do not draw the same widget`() {
        // Everything above would still pass if the palette were resolved once and
        // the theme ignored.
        val sepia = render(state("7 days"), FolioThemeName.SEPIA)
        val night = render(state("7 days"), FolioThemeName.NIGHT)
        assertNotEquals(
            "the card is the same colour in Sepia and Night",
            filterOn(sepia, R.id.widget_habit_surface),
            filterOn(night, R.id.widget_habit_surface),
        )
        assertNotEquals(
            "the headline is the same colour in Sepia and Night",
            sepia.findViewById<TextView>(R.id.widget_habit_headline).currentTextColor,
            night.findViewById<TextView>(R.id.widget_habit_headline).currentTextColor,
        )
    }

    @Test
    fun `the mark is on the widget, quietly, in every theme`() {
        // Muted rather than accent: the mark says whose widget this is and must not
        // compete with the streak. It is tinted rather than drawn in a fixed colour
        // because a two-tone launcher mark is mud on Black and invisible on E-ink.
        FolioThemeName.entries.forEach { theme ->
            val palette = widgetPalette(theme)
            val root = render(state("7 days"), theme)
            val mark = root.findViewById<ImageView>(R.id.widget_habit_mark)
            assertNotNull("$theme: the widget carries no mark", mark)
            assertEquals("$theme: the mark is not muted", tint(palette.mark), mark.colorFilter)
        }
    }

    @Test
    fun `the habit provider draws the habit widget`() {
        // Nothing else says so. Every other test here calls the renderer directly,
        // so swapping the two providers' bodies would pass all of them and put the
        // stats widget on both home screens.
        val root = HabitWidgetProvider()
            .views(app, WidgetSnapshot())
            .apply(app, FrameLayout(app))
        assertEquals("Start a reading habit", text(root, R.id.widget_habit_headline))
    }

    @Test
    fun `the whole widget opens Folio when tapped`() {
        // One click target on the root, not a button: a widget that only responds in
        // the few pixels around its text feels broken.
        val root = render(state("1 day"))
        assertTrue(
            "the habit widget has no click target",
            root.findViewById<View>(R.id.widget_habit_root).hasOnClickListeners(),
        )
    }
}
