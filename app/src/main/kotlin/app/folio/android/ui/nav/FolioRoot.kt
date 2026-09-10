package app.folio.android.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.folio.android.data.BookRepository
import app.folio.android.ui.FolioStrings
import app.folio.android.ui.common.EmptyState
import app.folio.android.ui.common.ErrorState
import app.folio.android.ui.importing.AddBookSheet
import app.folio.android.ui.importing.ImportProgressScreen
import app.folio.android.ui.library.LibraryScreen
import app.folio.android.ui.library.LibraryState
import app.folio.android.ui.theme.FolioTheme
import app.folio.android.ui.theme.FolioThemeName
import app.folio.android.work.ImportProgress
import app.folio.core.model.FailureReason
import kotlinx.coroutines.flow.map
import java.util.Calendar

/**
 * The top-level screen switch.
 *
 * A `when` over a small enum rather than a navigation library: three destinations,
 * no deep links yet, and a graph would be more machinery than the problem has.
 */
@Composable
fun FolioRoot(
    repository: BookRepository,
    importProgress: ImportProgress?,
    theme: FolioThemeName = FolioThemeName.LIGHT,
    onChooseFile: () -> Unit,
    onDismissImport: () -> Unit,
) {
    var destination by remember { mutableStateOf(FolioDestination.LIBRARY) }
    var showAddSheet by remember { mutableStateOf(false) }

    val state by remember(repository) {
        repository.observeLibrary().map { LibraryState(books = it, loading = false) }
    }.collectAsState(initial = LibraryState())

    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }

    FolioTheme(theme) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {

            val failure = importProgress?.failureReason?.let(::failureReasonOf)

            when {
                // An import in flight owns the screen: the handoff shows it as a
                // full view, not a banner over the Library.
                failure != null -> ErrorState(
                    reason = failure,
                    onTryAnother = { onDismissImport(); showAddSheet = true },
                    modifier = Modifier.fillMaxSize(),
                )

                importProgress != null -> ImportProgressScreen(
                    stage = importProgress.stage,
                    pagesDone = importProgress.pagesDone,
                    pagesTotal = importProgress.pagesTotal,
                    onReadNow = onDismissImport,
                    modifier = Modifier.fillMaxSize(),
                )

                destination == FolioDestination.LIBRARY -> LibraryScreen(
                    state = state,
                    hourOfDay = hour,
                    onOpenBook = { /* Reader arrives in Plan 4 */ },
                    onAddBook = { showAddSheet = true },
                    onOpenStreak = { /* Streak screen arrives in Plan 5 */ },
                    onOpenBookmarks = { destination = FolioDestination.BOOKMARKS },
                    onOpenSettings = { destination = FolioDestination.SETTINGS },
                )

                destination == FolioDestination.BOOKMARKS -> EmptyState(
                    title = FolioStrings.NO_BOOKMARKS,
                    hint = FolioStrings.NO_BOOKMARKS_HINT,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> EmptyState(
                    title = FolioStrings.NAV_SETTINGS,
                    hint = "Reading preferences arrive with the Reader.",
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // The pill stays out of the way while a book is being prepared.
            if (importProgress == null) {
                FolioPillNav(
                    current = destination,
                    onSelect = { destination = it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 18.dp),
                )
            }

            if (showAddSheet) {
                AddBookSheet(
                    onChooseFile = { showAddSheet = false; onChooseFile() },
                    onDismiss = { showAddSheet = false },
                )
            }
        }
    }
}

/**
 * Turns the worker's failure string back into a reason.
 *
 * WorkManager output data is strings, so this crosses a stringly-typed boundary.
 * An unrecognised value maps to a generic failure rather than crashing — a new
 * reason added later should degrade to a vague message, not an exception.
 */
private fun failureReasonOf(name: String): FailureReason =
    FailureReason.entries.firstOrNull { it.name == name } ?: FailureReason.EXTRACTION_FAILED
