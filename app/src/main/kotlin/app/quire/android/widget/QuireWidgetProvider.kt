package app.quire.android.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import app.quire.android.QuireApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What both widgets do, which is the same thing with a different layout.
 *
 * `onUpdate` arrives on the main thread of a broadcast receiver, and the data it
 * needs is in Room — so the work moves to [Dispatchers.IO] behind `goAsync()`. Two
 * rules make that safe, and both have a failure that only shows up in the field:
 *
 *  - The `PendingResult` is finished in a `finally`. A receiver that never finishes
 *    one holds the broadcast open until the system kills it, which reads as an ANR
 *    and a widget that did not update.
 *  - The load is wrapped. A widget is not worth crashing the reader's launcher over
 *    (`AppWidgetHost` runs in the launcher's process, but an uncaught exception here
 *    kills Quire's), and leaving the last good content on screen is better than a
 *    blank card: the previous `RemoteViews` stays until something replaces it.
 */
abstract class QuireWidgetProvider : AppWidgetProvider() {

    /**
     * The one thing each widget does differently.
     *
     * `internal` rather than `protected` so a test in this module can call it. The
     * broadcast around it cannot be reached from a JVM test at all, so this is the
     * last seam where "the habit provider draws the habit widget" can be asserted —
     * and swapping two providers' bodies would otherwise pass every other test and
     * put the wrong widget on both home screens.
     */
    internal abstract fun views(context: Context, snapshot: WidgetSnapshot): RemoteViews

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        if (appWidgetIds.isEmpty()) return
        val application = context.applicationContext as? QuireApp ?: return

        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                val graph = application.graph
                val snapshot = WidgetData.load(graph.repository, graph.habits)
                val views = views(appContext, snapshot)
                appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
            } catch (_: Exception) {
                // Deliberately silent, and deliberately not a placeholder: the widget
                // keeps whatever it last drew, which was true when it was drawn.
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /**
         * One scope for every provider instance, because there is one instance per
         * broadcast. A scope created in `onUpdate` would be collected with the
         * receiver while its coroutine was still reading the database.
         */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
