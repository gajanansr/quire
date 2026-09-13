package app.quire.android.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.quire.android.data.HabitSummary
import app.quire.android.data.LibraryBook
import app.quire.android.ui.QuireStrings
import app.quire.android.ui.common.EmptyState
import app.quire.android.ui.theme.Quire
import app.quire.android.ui.theme.QuireIcon
import app.quire.android.ui.theme.QuireIcons
import app.quire.android.ui.theme.QuireShapes
import kotlin.math.roundToInt

/**
 * The Library.
 *
 * Greeting, habit card, a Continue Reading card when there is something to continue,
 * and the three-column grid whose last cell is the add tile. Every value shown comes
 * from persisted data — there is no placeholder book anywhere in this file.
 */
@Composable
fun LibraryScreen(
    state: LibraryState,
    habits: HabitSummary,
    hourOfDay: Int,
    onOpenBook: (String) -> Unit,
    onAddBook: () -> Unit,
    onOpenStreak: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Quire.colors

    if (state.isEmpty) {
        // The populated branch gets its gutters from the grid's contentPadding;
        // the empty branch has no grid, so it needs them here or the greeting is
        // clipped against the screen edge.
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(colors.bg)
                .padding(horizontal = 20.dp),
        ) {
            LibraryHeader(hourOfDay, state, habits)
            EmptyState(
                title = QuireStrings.LIBRARY_EMPTY,
                hint = QuireStrings.LIBRARY_EMPTY_HINT,
                actionLabel = QuireStrings.ADD_BOOK,
                actionIcon = QuireIcons.Add,
                onAction = onAddBook,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier.fillMaxSize().background(colors.bg),
        // The floating pill sits ~88dp tall including its inset; the last row's
        // title needs to clear it, not sit behind it.
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 150.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                LibraryHeader(hourOfDay, state, habits)
                HabitCard(habits = habits, onClick = onOpenStreak)
                state.continueReading?.let { book ->
                    Spacer(Modifier.height(20.dp))
                    ContinueReadingCard(book, onClick = { onOpenBook(book.id) })
                }
                Spacer(Modifier.height(22.dp))
                Text(
                    text = QuireStrings.YOUR_BOOKS,
                    color = colors.ink,
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(2.dp))
            }
        }

        items(state.books, key = { it.id }) { book ->
            BookTile(book = book, onClick = { onOpenBook(book.id) })
        }

        item { AddBookTile(onClick = onAddBook) }
    }
}

@Composable
private fun LibraryHeader(
    hourOfDay: Int,
    state: LibraryState,
    habits: HabitSummary,
) {
    val colors = Quire.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = QuireStrings.greeting(hourOfDay),
                color = colors.ink,
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = subtitleFor(state, habits),
                color = colors.muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * The header's second line — the handoff's "7 day reading streak · 12 min today",
 * built from real data.
 *
 * Each half appears only when it is true. A reader on their first day is told how
 * many books they have, not handed an invented streak.
 */
private fun subtitleFor(state: LibraryState, habits: HabitSummary): String {
    val parts = buildList {
        if (habits.currentStreak > 0) {
            add(
                if (habits.currentStreak == 1) "1 day reading streak"
                else "${habits.currentStreak} day reading streak"
            )
        }
        if (habits.minutesToday > 0) add("${habits.minutesToday} min today")
    }
    if (parts.isNotEmpty()) return parts.joinToString(" · ")

    return when (state.books.size) {
        0 -> ""
        1 -> "1 book in your library"
        else -> "${state.books.size} books in your library"
    }
}

@Composable
private fun HabitCard(habits: HabitSummary, onClick: () -> Unit) {
    val colors = Quire.colors
    val week = habits.week()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(QuireShapes.card)
            .background(colors.bgAlt)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The real seven days, empty ones included. Drawing only the days that
        // were read would make every reader look perfect.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            week.forEach { day ->
                val ratio = if (habits.goalMinutes <= 0) 0f
                else (day.minutes.toFloat() / habits.goalMinutes).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .size(width = 7.dp, height = 20.dp)
                        .clip(QuireShapes.chip)
                        .background(
                            if (ratio <= 0f) colors.border
                            else colors.accent.copy(alpha = 0.3f + 0.7f * ratio)
                        ),
                )
            }
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                habitTitle(habits),
                color = colors.ink,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                habitSubtitle(habits),
                color = colors.muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        QuireIcon(
            QuireIcons.Forward,
            contentDescription = null,
            tint = colors.muted,
            size = QuireIcons.Size.Small,
        )
    }
}

private fun habitTitle(habits: HabitSummary): String = when {
    habits.currentStreak >= 7 -> QuireStrings.HABIT_STREAK
    habits.currentStreak > 0 -> "${habits.currentStreak}-day streak"
    else -> "Start a reading habit"
}

private fun habitSubtitle(habits: HabitSummary): String = when {
    habits.goalMet -> "Today's goal is done."
    habits.minutesToday > 0 ->
        "${habits.minutesToday} of ${habits.goalMinutes} minutes today"
    habits.currentStreak >= 7 -> QuireStrings.HABIT_SUBTITLE
    else -> "${habits.goalMinutes} minutes a day"
}

@Composable
private fun ContinueReadingCard(book: LibraryBook, onClick: () -> Unit) {
    val colors = Quire.colors
    Column {
        Text(
            QuireStrings.CONTINUE_READING,
            color = colors.ink,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(QuireShapes.card)
                .background(colors.bgAlt)
                .clickable(onClick = onClick)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(
                bookId = book.id,
                title = book.title,
                coverPath = book.coverPath,
                modifier = Modifier.size(width = 54.dp, height = 78.dp),
            )
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                book.author?.let {
                    Text(it, color = colors.muted, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(10.dp))
                ProgressBar(book.progress)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Continue — ${(book.progress * 100).roundToInt()}%",
                    color = colors.accent,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun ProgressBar(progress: Double) {
    val colors = Quire.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(QuireShapes.chip)
            .background(colors.border),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0.0, 1.0).toFloat())
                .height(4.dp)
                .clip(QuireShapes.chip)
                .background(colors.accent),
        )
    }
}

@Composable
private fun BookTile(book: LibraryBook, onClick: () -> Unit) {
    val colors = Quire.colors
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        BookCover(
            bookId = book.id,
            title = book.title,
            coverPath = book.coverPath,
            modifier = Modifier.fillMaxWidth().aspectRatio(0.68f),
        )
        Spacer(Modifier.height(7.dp))
        Text(
            book.title,
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        book.author?.let {
            Text(
                it,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun AddBookTile(onClick: () -> Unit) {
    val colors = Quire.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.68f)
            .clip(QuireShapes.chip)
            .border(1.dp, colors.border, QuireShapes.chip)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        QuireIcon(
            QuireIcons.Add,
            // The tile is labelled by the button beside it and by the empty state.
            contentDescription = QuireStrings.ADD_BOOK,
            tint = colors.muted,
            size = QuireIcons.Size.Large,
        )
    }
}
