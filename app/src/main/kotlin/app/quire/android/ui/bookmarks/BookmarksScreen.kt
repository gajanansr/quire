package app.quire.android.ui.bookmarks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.quire.android.data.BookmarkEntity
import app.quire.android.data.BookmarkWithBook
import app.quire.android.ui.QuireStrings
import app.quire.android.ui.common.EmptyState
import app.quire.android.ui.theme.Quire
import app.quire.android.ui.theme.QuireHighlights
import app.quire.android.ui.theme.QuireThemeName
import app.quire.android.ui.theme.highlightColourNamed
import app.quire.android.ui.theme.QuireIcon
import app.quire.android.ui.theme.QuireIcons
import app.quire.android.ui.theme.QuireShapes
import androidx.annotation.DrawableRes
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment

/**
 * Saved passages across the whole library.
 *
 * Each row shows the words the reader marked rather than a position, because a
 * chapter and offset mean nothing to a person scanning a list. The snippet was
 * captured when the mark was made, so it stays recognisable even if the book is
 * later reprocessed.
 *
 * A highlight and a bookmark share a row and a table: a bookmark is a highlight of
 * no width. Only the label differs, and only so the reader can tell at a glance
 * which ones are their own choice of words.
 */
@Composable
fun BookmarksScreen(
    bookmarks: List<BookmarkWithBook>,
    onOpen: (bookId: String, chapterIndex: Int) -> Unit,
    onShare: (BookmarkWithBook) -> Unit,
    onRemove: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Quire.colors
    val theme = Quire.theme

    if (bookmarks.isEmpty()) {
        EmptyState(
            title = QuireStrings.NO_BOOKMARKS,
            hint = QuireStrings.NO_BOOKMARKS_HINT,
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
                QuireStrings.BOOKMARKS,
                color = colors.ink,
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(6.dp))
        }

        items(bookmarks, key = { it.bookmark.id }) { entry ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(QuireShapes.card)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    bookmarkSwatch(entry.bookmark, theme)?.let { swatch ->
                        Box(
                            modifier = Modifier
                                .size(13.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .border(1.dp, colors.border, CircleShape),
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(
                        "${entry.bookTitle} · Chapter ${entry.bookmark.chapterIndex + 1}",
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    RowAction(QuireIcons.Share, QuireStrings.SHARE) { onShare(entry) }
                    Spacer(Modifier.size(4.dp))
                    RowAction(QuireIcons.Remove, QuireStrings.REMOVE) {
                        onRemove(entry.bookmark.id)
                    }
                }
            }
        }
    }
}

/**
 * The colour to show beside one saved mark, or null when there is none to show.
 *
 * Pure, and the reason it is not written inline: this is the claim that the list and
 * the page agree about what "Doubt" looks like, and a claim like that is worth an
 * assertion rather than a reviewer's glance. Resolved through the same
 * [QuireHighlights.over] the reader's page uses, so the two cannot be changed apart.
 *
 * Null for a plain bookmark. A bookmark marks a place and has no words to colour;
 * a swatch there would invent a category the reader never chose.
 */
fun bookmarkSwatch(bookmark: BookmarkEntity, theme: QuireThemeName): Color? =
    if (!bookmark.isHighlight) null
    else QuireHighlights.over(theme, highlightColourNamed(bookmark.highlightColour))

@Composable
private fun RowAction(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    val colors = Quire.colors
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        QuireIcon(
            icon,
            contentDescription = description,
            tint = colors.muted,
            size = QuireIcons.Size.Small,
        )
    }
}
