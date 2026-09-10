package app.folio.android.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.folio.android.data.BookRepository
import app.folio.android.data.LibraryBook
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryState(
    val books: List<LibraryBook> = emptyList(),
    val loading: Boolean = true,
) {
    val isEmpty: Boolean get() = !loading && books.isEmpty()

    /**
     * The book the Continue Reading card offers: the most recently opened one that
     * has actually been started and is not yet finished. A book at 0% belongs in the
     * grid, not in a card inviting the user to continue something they never began.
     */
    val continueReading: LibraryBook?
        get() = books.firstOrNull { it.lastOpenedAt != null && it.progress > 0.0 && it.progress < 1.0 }
}

class LibraryViewModel(private val repository: BookRepository) : ViewModel() {

    val state: StateFlow<LibraryState> =
        repository.observeLibrary()
            .map { LibraryState(books = it, loading = false) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = LibraryState(),
            )

    fun delete(bookId: String) {
        viewModelScope.launch { repository.delete(bookId) }
    }

    fun markOpened(bookId: String) {
        viewModelScope.launch { repository.markOpened(bookId) }
    }
}
