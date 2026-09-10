package app.folio.android.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.folio.android.data.BookRepository
import app.folio.android.ui.FolioStrings
import app.folio.android.ui.common.EmptyState
import app.folio.android.ui.library.LibraryScreen
import app.folio.android.ui.library.LibraryState
import app.folio.android.ui.theme.FolioTheme
import app.folio.android.ui.theme.FolioThemeName
import kotlinx.coroutines.flow.map
import java.util.Calendar

/**
 * The top-level screen switch.
 *
 * Deliberately a `when` over a small enum rather than a navigation library: there
 * are three destinations and no deep links yet, and a graph would be more machinery
 * than the problem has.
 */
@Composable
fun FolioRoot(
    repository: BookRepository,
    theme: FolioThemeName = FolioThemeName.LIGHT,
    onAddBook: () -> Unit,
) {
    var destination by remember { mutableStateOf(FolioDestination.LIBRARY) }

    val state by remember(repository) {
        repository.observeLibrary().map { LibraryState(books = it, loading = false) }
    }.collectAsState(initial = LibraryState())

    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }

    FolioTheme(theme) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            when (destination) {
                FolioDestination.LIBRARY -> LibraryScreen(
                    state = state,
                    hourOfDay = hour,
                    onOpenBook = { /* Reader arrives in Plan 4 */ },
                    onAddBook = onAddBook,
                    onOpenStreak = { /* Streak screen arrives in Plan 5 */ },
                    onOpenBookmarks = { destination = FolioDestination.BOOKMARKS },
                    onOpenSettings = { destination = FolioDestination.SETTINGS },
                )

                FolioDestination.BOOKMARKS -> EmptyState(
                    title = FolioStrings.NO_BOOKMARKS,
                    hint = FolioStrings.NO_BOOKMARKS_HINT,
                    modifier = Modifier.fillMaxSize(),
                )

                FolioDestination.SETTINGS -> EmptyState(
                    title = FolioStrings.NAV_SETTINGS,
                    hint = "Reading preferences arrive with the Reader.",
                    modifier = Modifier.fillMaxSize(),
                )
            }

            FolioPillNav(
                current = destination,
                onSelect = { destination = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 18.dp),
            )
        }
    }
}
