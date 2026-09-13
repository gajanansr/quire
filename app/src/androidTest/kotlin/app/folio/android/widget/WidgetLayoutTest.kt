package app.folio.android.widget

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.folio.android.R
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widgets fit the smallest box a launcher will give them.
 *
 * The one thing JVM tests cannot reach: `WidgetState` decides *what* the widget says
 * and is covered thoroughly, but whether those words fit is a question for a real
 * inflater with real fonts. At the declared minimum — 250×110dp for both — the stats
 * widget has three tiles, a book title and a progress bar to place in about 80dp of
 * usable height, and a widget that overflows is not clipped tidily: the launcher just
 * cuts it, on the one phone whose grid is tightest.
 *
 * Runs on a device because RemoteViews inflation is a framework path, and the point
 * is the framework's own measurement rather than a model of it.
 */
class WidgetLayoutTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun dp(value: Int): Int =
        (value * app.resources.displayMetrics.density).toInt()

    /** Inflates a widget layout and measures it in a box of exactly this size. */
    private fun measured(layout: Int, widthDp: Int, heightDp: Int): View {
        var root: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val views = RemoteViews(app.packageName, layout)
            val parent = android.widget.FrameLayout(app)
            val view = views.apply(app, parent)
            val widthSpec = View.MeasureSpec.makeMeasureSpec(dp(widthDp), View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(dp(heightDp), View.MeasureSpec.EXACTLY)
            view.measure(widthSpec, heightSpec)
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            root = view
        }
        return root!!
    }

    /** The deepest bottom edge any visible descendant reaches. */
    private fun contentBottom(view: View): Int {
        if (view !is ViewGroup) return view.bottom
        var deepest = 0
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child.visibility == View.GONE) continue
            deepest = maxOf(deepest, child.top + contentBottom(child))
        }
        return maxOf(deepest, 0)
    }

    private fun assertFits(name: String, layout: Int, widthDp: Int, heightDp: Int) {
        val view = measured(layout, widthDp, heightDp)
        val bottom = contentBottom(view)
        assertTrue(
            "$name at ${widthDp}x${heightDp}dp: content reaches ${bottom}px of " +
                "${view.measuredHeight}px — the launcher will cut it",
            bottom <= view.measuredHeight,
        )
    }

    @Test
    fun theStatsWidgetFitsItsDeclaredMinimum() {
        assertFits("stats", R.layout.widget_stats, widthDp = 250, heightDp = 110)
    }

    @Test
    fun theHabitWidgetFitsItsDeclaredMinimum() {
        assertFits("habit", R.layout.widget_habit, widthDp = 250, heightDp = 110)
    }

    @Test
    fun bothFitTheNarrowestResizeTheyAllow() {
        // minResizeWidth/Height is 180x110dp: a reader can drag them this small.
        assertFits("stats", R.layout.widget_stats, widthDp = 180, heightDp = 110)
        assertFits("habit", R.layout.widget_habit, widthDp = 180, heightDp = 110)
    }

    @Test
    fun bothFitAFullFourByTwoCell() {
        assertFits("stats", R.layout.widget_stats, widthDp = 330, heightDp = 160)
        assertFits("habit", R.layout.widget_habit, widthDp = 330, heightDp = 160)
    }
}
