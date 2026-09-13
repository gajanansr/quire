package app.folio.android.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import app.folio.android.R

/**
 * State and palette into `RemoteViews`, and nothing else.
 *
 * There is no branching here on purpose. Every choice about *what* a widget says was
 * made in `WidgetState.kt` and every choice about *what colour* in `WidgetPalette.kt`,
 * both where a test can see them; what is left is a transcription, and the only ways
 * it can be wrong are by naming a view that does not exist or a method the platform
 * will not call — which is what `HabitWidgetTest` inflates a real layout to catch.
 */

/**
 * The methods this file reaches through [RemoteViews.setInt].
 *
 * `setInt` does not call anything directly: it records a method *name*, and the
 * launcher looks that name up by reflection when it applies the views, refusing
 * anything not annotated `@RemotableViewMethod`. A typo, or a method that turns out
 * not to be remotable, is therefore a silent no-op on somebody's home screen — no
 * exception, no log, just a widget that is the wrong colour or a progress bar stuck
 * at nothing.
 *
 * Named here, in one place, so `RemotableCallTest` can assert the platform really
 * does expose every one of them — which is also the proof they work on API 26, since
 * the annotation is the whole of the platform's own check and all three predate it.
 */
internal object RemotableCalls {
    /** `ImageView.setColorFilter(int)` — SRC_ATOP, so a white shape becomes the colour. */
    const val COLOR_FILTER = "setColorFilter"

    /** `ImageView.setImageAlpha(int)` — 0..255, how a short reading day is drawn faint. */
    const val IMAGE_ALPHA = "setImageAlpha"

    /** `ImageView.setImageLevel(int)` — 0..10000, what clips the progress fill. */
    const val IMAGE_LEVEL = "setImageLevel"

    /** All three, on the one class they are called against. */
    val onImageView = listOf(COLOR_FILTER, IMAGE_ALPHA, IMAGE_LEVEL)
}

/**
 * Tints a shape held in an `ImageView`.
 *
 * The whole of Folio's widget theming, and the one technique that works from API 26:
 * `RemoteViews.setColorStateList` is API 31, and `setInt(id, "setBackgroundColor", …)`
 * — the other thing available — paints square corners, which is no use for a card, a
 * chip or a progress track. `ImageView.setColorFilter(int)` blends SRC_ATOP: it keeps
 * the drawable's alpha, so the rounded corners stay rounded, and replaces the colour,
 * so a shape drawn in white comes out exactly the colour asked for.
 */
private fun RemoteViews.paint(viewId: Int, color: Int) =
    setInt(viewId, RemotableCalls.COLOR_FILTER, color)

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

fun habitViews(
    context: Context,
    state: HabitWidgetState,
    palette: WidgetPalette,
): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_habit)

    // The card first: surface, then the hairline over it. Two views because a colour
    // filter paints one colour and a card with an edge is two.
    views.paint(R.id.widget_habit_surface, palette.surface)
    views.paint(R.id.widget_habit_hairline, palette.edge)
    views.paint(R.id.widget_habit_mark, palette.mark)

    views.setTextViewText(R.id.widget_habit_headline, state.headline)
    views.setTextColor(R.id.widget_habit_headline, palette.ink)
    views.setTextViewText(R.id.widget_habit_detail, state.detail)
    views.setTextColor(R.id.widget_habit_detail, palette.muted)
    // Unlit is muted, not the border colour the unread day bars use. Both say "not
    // yet", but a field of seven faint bars reads as a week with nothing in it while
    // one faint icon reads as a drawing that failed to load — and on Night the border
    // colour is four steps from the card it sits on.
    views.paint(
        R.id.widget_habit_flame, if (state.lit) palette.accent else palette.muted,
    )

    // setColorFilter and setImageAlpha are the two remotable methods an ImageView
    // has for this. Together they are what lets a four-minute day be drawn lighter
    // than a full one without a drawable per intensity.
    HabitWidgetIds.DAYS.zip(state.week).forEach { (id, bar) ->
        views.paint(id, if (bar.filled) palette.accent else palette.edge)
        views.setInt(id, RemotableCalls.IMAGE_ALPHA, bar.alpha)
    }
    views.setContentDescription(R.id.widget_habit_week, state.weekDescription)

    views.setOnClickPendingIntent(
        R.id.widget_habit_root,
        FolioWidgets.pendingOpen(context, FolioWidget.HABIT),
    )
    return views
}

/** The three stat tiles, paired value and label. */
object StatsWidgetIds {
    val VALUES = intArrayOf(
        R.id.widget_stat_value_0, R.id.widget_stat_value_1, R.id.widget_stat_value_2,
    )
    val LABELS = intArrayOf(
        R.id.widget_stat_label_0, R.id.widget_stat_label_1, R.id.widget_stat_label_2,
    )
}

/**
 * What a whole progress bar is, as a `ClipDrawable` level.
 *
 * `ProgressBar` has no remotable method for its colours before API 31, so the bar is
 * two tinted `ImageView`s instead and the fill is clipped by its drawable level. The
 * level scale is the platform's, not ours.
 */
private const val FULL_LEVEL = 10_000

fun statsViews(
    context: Context,
    state: StatsWidgetState,
    palette: WidgetPalette,
): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_stats)

    views.paint(R.id.widget_stats_surface, palette.surface)
    views.paint(R.id.widget_stats_hairline, palette.edge)
    views.paint(R.id.widget_stats_mark, palette.mark)

    // A RemoteViews cannot add or remove a child, so every state is in the layout
    // and exactly one combination of them is left visible. The alternative — a blank
    // book row with a progress bar at zero — reads as a bug rather than as a state.
    views.show(R.id.widget_stats_empty, state.empty != null)
    views.show(R.id.widget_stats_tiles, state.empty == null)
    views.show(R.id.widget_stats_current, state.current != null)
    views.show(R.id.widget_stats_prompt, state.prompt != null)

    state.empty?.let { empty ->
        views.setTextViewText(R.id.widget_stats_empty_title, empty.title)
        views.setTextColor(R.id.widget_stats_empty_title, palette.ink)
        views.setTextViewText(R.id.widget_stats_empty_detail, empty.detail)
        views.setTextColor(R.id.widget_stats_empty_detail, palette.muted)
    }

    StatsWidgetIds.VALUES.zip(state.tiles).forEach { (id, tile) ->
        views.setTextViewText(id, tile.value)
        views.setTextColor(id, palette.ink)
    }
    StatsWidgetIds.LABELS.zip(state.tiles).forEach { (id, tile) ->
        views.setTextViewText(id, tile.label)
        views.setTextColor(id, palette.muted)
    }

    views.paint(R.id.widget_stats_progress_track, palette.edge)
    views.paint(R.id.widget_stats_progress_fill, palette.accent)

    state.current?.let { current ->
        views.setTextViewText(R.id.widget_stats_title, current.title)
        views.setTextColor(R.id.widget_stats_title, palette.ink)
        views.setTextViewText(R.id.widget_stats_percent, current.detail)
        views.setTextColor(R.id.widget_stats_percent, palette.muted)
        // The fill is a <clip>, and a clip drawable at its default level draws
        // nothing at all — so this line is the difference between a progress bar and
        // an empty track, and StatsWidgetTest asserts the level rather than trusting
        // that the call arrived.
        views.setInt(
            R.id.widget_stats_progress_fill,
            RemotableCalls.IMAGE_LEVEL,
            current.percent * FULL_LEVEL / 100,
        )
        // The bar is the same fact as the text beside it, so it is not announced
        // twice; the title and the percentage carry it.
        views.setContentDescription(
            R.id.widget_stats_current, "${current.title}, ${current.detail}",
        )
    }
    state.prompt?.let {
        views.setTextViewText(R.id.widget_stats_prompt, it)
        views.setTextColor(R.id.widget_stats_prompt, palette.muted)
    }

    views.setOnClickPendingIntent(
        R.id.widget_stats_root,
        FolioWidgets.pendingOpen(context, FolioWidget.STATS),
    )
    return views
}

private fun RemoteViews.show(viewId: Int, visible: Boolean) =
    setViewVisibility(viewId, if (visible) View.VISIBLE else View.GONE)
