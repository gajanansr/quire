package app.folio.android.widget

import app.folio.android.data.BookRepository
import app.folio.android.data.HabitRepository
import kotlinx.coroutines.flow.first

/**
 * The one read a widget does.
 *
 * Both widgets take the same snapshot, because both are drawn from the same two
 * repositories and a widget update is a broadcast with about ten seconds to live —
 * two passes over the database would be two chances to be interrupted halfway and
 * leave one widget a version behind the other.
 *
 * The repositories arrive as parameters rather than being pulled out of
 * [app.folio.android.FolioApp]'s graph, so a test can hand this an in-memory
 * database. The provider is the only place that knows about the graph.
 */
object WidgetData {

    suspend fun load(books: BookRepository, habits: HabitRepository): WidgetSnapshot {
        val summary = habits.observeSummary().first()
        val settings = habits.settings()
        val library = books.observeLibrary().first()

        return WidgetSnapshot(
            habits = summary,
            booksFinished = settings.booksFinished,
            chaptersFinished = settings.chaptersFinished,
            libraryCount = library.size,
            // The Library's Continue Reading rule, unchanged: the most recently
            // opened book that was actually begun and is not yet finished. The query
            // already returns the library in that order. Anything looser would have
            // the widget invite someone to continue a book they never opened.
            currentBook = library.firstOrNull {
                it.lastOpenedAt != null && it.progress > 0.0 && it.progress < 1.0
            }?.let { CurrentBook(it.id, it.title, it.progress) },
        )
    }
}
