package app.folio.android.widget

import android.content.Context
import android.widget.RemoteViews

/**
 * The reading streak, on the home screen.
 *
 * The emotional one: it exists so that glancing at a phone is a reason to open a
 * book. Which is exactly why nothing on it is invented — a streak someone did not
 * earn stops meaning anything the first time they notice.
 */
class HabitWidgetProvider : FolioWidgetProvider() {

    override fun views(context: Context, snapshot: WidgetSnapshot): RemoteViews =
        habitViews(context, habitWidget(snapshot), widgetPalette(snapshot.theme))
}
