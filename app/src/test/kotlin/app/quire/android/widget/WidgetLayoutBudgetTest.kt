package app.quire.android.widget

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import app.quire.android.R
import app.quire.android.ui.theme.QuireThemeName
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The widgets fit every box a launcher will give them — measured, not reasoned about.
 *
 * `WidgetLayoutTest` asks this question on a device and is the authority, because a
 * real inflater with real fonts is what a home screen has. It cannot be run from
 * here. So this asks the same question where it *can* be asked, and the answer is
 * worth more than it looks: both layouts give every TextView a fixed height and let
 * it autosize, so the height of a widget is a sum of constants and does not depend on
 * the font at all. What Robolectric measures is therefore what a phone measures, and
 * the one thing that does vary with the font — which text size autosizing settles on
 * — cannot push anything off the card.
 *
 * Two measures, because they answer different questions:
 *
 *  - [deviceMetric] is `WidgetLayoutTest`'s own `contentBottom`, copied exactly,
 *    warts and all: it adds a child's top to a figure that already includes it, so
 *    it over-reports, and a leaf sitting deep in a shallow tree is charged nearly
 *    twice its depth. Copied rather than corrected because the point is to know
 *    what the device test will say before it is run.
 *  - [depth] is the true deepest bottom, which is what a launcher actually cuts.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetLayoutBudgetTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    /** The card's own padding, which the content must stay inside on every edge. */
    private val paddingDp = 12

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun measured(view: View, context: Context, widthDp: Int, heightDp: Int): View {
        val w = View.MeasureSpec.makeMeasureSpec(context.dp(widthDp), View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(context.dp(heightDp), View.MeasureSpec.EXACTLY)
        view.measure(w, h)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    /** `WidgetLayoutTest.contentBottom`, copied verbatim so it reports what it reports. */
    private fun deviceMetric(view: View): Int {
        if (view !is ViewGroup) return view.bottom
        var deepest = 0
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child.visibility == View.GONE) continue
            deepest = maxOf(deepest, child.top + deviceMetric(child))
        }
        return maxOf(deepest, 0)
    }

    /** The deepest bottom edge anything under [view] actually reaches. */
    private fun depth(view: View, offset: Int): Int {
        if (view !is ViewGroup) return offset + view.height
        var deepest = offset
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child.visibility == View.GONE) continue
            deepest = maxOf(deepest, depth(child, offset + child.top))
        }
        return deepest
    }

    /**
     * The content column — the one LinearLayout among the root's children.
     *
     * The card and its hairline are full-bleed ImageViews and reach the bottom edge
     * by design, so measuring the root would report the box height every time and
     * prove nothing.
     */
    private fun content(root: View): View =
        (0 until (root as ViewGroup).childCount)
            .map { (root as ViewGroup).getChildAt(it) }
            .first { it is LinearLayout }

    /** Every box a reader can put these in: declared minimum, narrowest, full cell, largest. */
    private val sizes = listOf(250 to 110, 180 to 110, 330 to 160, 360 to 180)

    private fun paper() = widgetPalette(QuireThemeName.PAPER)

    /** Every state either widget can be in, named the way a reader would find it. */
    private fun states(context: Context): List<Pair<String, RemoteViews>> = listOf(
        "streak: a fresh install" to
            habitViews(context, habitWidget(WidgetSnapshot()), paper()),
        "streak: a week of reading" to habitViews(
            context,
            HabitWidgetState(
                "14 days", "20 of 20 minutes today",
                List(7) { DayBar(filled = true, alpha = 255) },
                "Read 7 of the last 7 days", lit = true,
            ),
            paper(),
        ),
        "stats: an empty library" to
            statsViews(context, statsWidget(WidgetSnapshot()), paper()),
        "stats: books waiting" to statsViews(
            context, statsWidget(WidgetSnapshot(libraryCount = 3)), paper(),
        ),
        "stats: a book open" to statsViews(
            context,
            statsWidget(
                WidgetSnapshot(
                    booksFinished = 4,
                    chaptersFinished = 137,
                    libraryCount = 3,
                    currentBook = CurrentBook("b", "The Weight of Silence", 0.42),
                )
            ),
            paper(),
        ),
    )

    private fun assertFits(name: String, root: View, context: Context, w: Int, h: Int) {
        measured(root, context, w, h)
        val box = context.dp(h)
        val density = context.resources.displayMetrics.density
        fun dp(px: Int) = "%.1fdp".format(px / density)

        assertTrue(
            "$name at ${w}x${h}dp: WidgetLayoutTest would measure ${dp(deviceMetric(root))} " +
                "of ${h}dp and fail on a device",
            deviceMetric(root) <= box,
        )

        val body = content(root)
        val reached = depth(body, body.top)
        assertTrue(
            "$name at ${w}x${h}dp: the content reaches ${dp(reached)} of ${h}dp, past the " +
                "card's ${paddingDp}dp bottom padding — the launcher will cut it",
            reached <= box - context.dp(paddingDp),
        )
    }

    @Test
    fun `both layouts fit every box, in every state`() {
        sizes.forEach { (w, h) ->
            states(app).forEach { (name, views) ->
                assertFits(name, views.apply(app, FrameLayout(app)), app, w, h)
            }
        }
    }

    @Test
    fun `the layouts the device test inflates fit the sizes it uses`() {
        // The same three boxes WidgetLayoutTest measures, inflated the same way —
        // straight from the layout, with the visibilities the XML declares rather
        // than the ones a provider sets. That is the run this branch cannot make.
        listOf(250 to 110, 180 to 110, 330 to 160).forEach { (w, h) ->
            listOf("habit" to R.layout.widget_habit, "stats" to R.layout.widget_stats)
                .forEach { (name, layout) ->
                    assertFits(
                        name, RemoteViews(app.packageName, layout).apply(app, FrameLayout(app)),
                        app, w, h,
                    )
                }
        }
    }

    @Test
    fun `a reader with large system text still gets a whole widget`() {
        // The failure this exists for: someone at 1.3x font scale whose streak
        // pushes the week strip off the bottom of the card. It cannot happen here
        // because every TextView has a fixed height and autosizes inside it — the
        // text gets smaller, the card does not get taller — and this is what says so.
        val large = Configuration(app.resources.configuration).apply { fontScale = 1.3f }
        val context = app.createConfigurationContext(large)
        sizes.forEach { (w, h) ->
            states(context).forEach { (name, views) ->
                assertFits(
                    "$name at 1.3x text",
                    views.apply(context, FrameLayout(context)), context, w, h,
                )
            }
        }
    }

    @Test
    fun `the week strip grows with the widget`() {
        // The other half of "compact": the streak widget is not a small card in a
        // big box. Everything above the strip is fixed, so every pixel a reader adds
        // by resizing goes to the data.
        fun strip(heightDp: Int): Int {
            val root = habitViews(app, habitWidget(WidgetSnapshot()), paper())
                .apply(app, FrameLayout(app))
            measured(root, app, 250, heightDp)
            return root.findViewById<View>(R.id.widget_habit_week).height
        }

        val minimum = strip(110)
        val fullCell = strip(160)
        assertTrue("the week strip has no height at the declared minimum", minimum > 0)
        assertTrue(
            "the week strip is ${minimum}px at 110dp and ${fullCell}px at 160dp — it is " +
                "not using the room a resize gives it",
            fullCell > minimum + app.dp(40),
        )
    }
}
