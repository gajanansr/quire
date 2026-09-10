package app.folio.android.ui.bookmarks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.folio.android.data.BookmarkWithBook
import app.folio.android.ui.FolioStrings
import app.folio.android.ui.common.EmptyState
import app.folio.android.ui.theme.Folio
import app.folio.android.ui.theme.FolioShapes

/**
 * Saved bookmarks across the whole library.
 *
 * Each row shows the passage the reader marked rather than a position, because a
 * chapter and offset mean nothing to a person scanning a list. The snippet was
 * captured when the bookmark was made, so it stays recognisable even if the book
 * is later reprocessed.
 */
@Composable
fun BookmarksScreen(
    bookmarks: List<BookmarkWithBook>,
    onOpen: (bookId: String, chapterIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Folio.colors

    if (bookmarks.isEmpty()) {
        EmptyState(
            title = FolioStrings.NO_BOOKMARKS,
            hint = FolioStrings.NO_BOOKMARKS_HINT,
            modifier = modifier.fillMaxSize().background(colors.bg),
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(colors.bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                FolioStrings.BOOKMARKS,
                color = colors.ink,
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(6.dp))
        }

        items(bookmarks, key = { it.bookmark.id }) { entry ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(FolioShapes.card)
                    .background(colors.bgAlt)
                    .clickable {
                        onOpen(entry.bookmark.bookId, entry.bookmark.chapterIndex)
                    }
                    .padding(16.dp),
            ) {
                Text(
                    entry.bookmark.snippet.ifBlank { "A saved place" },
                    color = colors.ink,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${entry.bookTitle} · Chapter ${entry.bookmark.chapterIndex + 1}",
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
