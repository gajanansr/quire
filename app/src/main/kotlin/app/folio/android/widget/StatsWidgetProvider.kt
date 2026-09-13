package app.folio.android.widget

import android.content.Context
import android.widget.RemoteViews

/**
 * What reading has added up to, on the home screen.
 *
 * The quiet counterpart to the streak: books and chapters finished and hours spent
 * are the slow numbers, and the book currently open is the one that gets someone
 * back into it. All four come from storage, and a library with nothing in it is told
 * so rather than shown three zeroes.
 */
class StatsWidgetProvider : FolioWidgetProvider() {

    override fun views(context: Context, snapshot: WidgetSnapshot): RemoteViews =
        statsViews(context, statsWidget(snapshot))
}
