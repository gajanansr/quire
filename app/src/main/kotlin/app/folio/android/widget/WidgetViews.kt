package app.folio.android.widget

import android.content.Context
import android.widget.RemoteViews
import app.folio.android.R

/**
 * State into `RemoteViews`, and nothing else.
 *
 * There is no branching here on purpose. Every choice was made in `WidgetState.kt`,
 * where a test can see it; what is left is a transcription, and the only way it can
 * be wrong is by naming a view that does not exist — which is what `HabitWidgetTest`
 * inflates a real layout to catch.
 */

/**
 * The week strip's seven ids.
 *
 * Fixed ids rather than views built at runtime: a `RemoteViews` cannot inflate one
 * child per item without an adapter and a service, which is a great deal of
 * machinery for seven bars that never change in number.
 */
object HabitWidgetIds {
    val DAYS = intArrayOf(
        R.id.widget_day_0,
        R.id.widget_day_1,
        R.id.widget_day_2,
        R.id.widget_day_3,
        R.id.widget_day_4,
        R.id.widget_day_5,
        R.id.widget_day_6,
    )
}

fun habitViews(context: Context, state: HabitWidgetState): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_habit)
    val accent = context.getColor(R.color.widget_accent)
    val border = context.getColor(R.color.widget_border)

    views.setTextViewText(R.id.widget_habit_headline, state.headline)
    views.setTextViewText(R.id.widget_habit_detail, state.detail)
    views.setInt(
        R.id.widget_habit_flame, SET_COLOR_FILTER, if (state.lit) accent else border,
    )

    // setColorFilter and setImageAlpha are the two remotable methods an ImageView
    // has for this. Together they are what lets a four-minute day be drawn lighter
    // than a full one without a drawable per intensity.
    HabitWidgetIds.DAYS.zip(state.week).forEach { (id, bar) ->
        views.setInt(id, SET_COLOR_FILTER, if (bar.filled) accent else border)
        views.setInt(id, SET_IMAGE_ALPHA, bar.alpha)
    }
    views.setContentDescription(R.id.widget_habit_week, state.weekDescription)

    views.setOnClickPendingIntent(
        R.id.widget_habit_root,
        FolioWidgets.pendingOpen(
            context, FolioWidgets.REQUEST_HABIT, FolioWidgets.openIntent(context),
        ),
    )
    return views
}

/** Reflected by name at apply() time, so a typo here is a silent no-op. */
private const val SET_COLOR_FILTER = "setColorFilter"
private const val SET_IMAGE_ALPHA = "setImageAlpha"
