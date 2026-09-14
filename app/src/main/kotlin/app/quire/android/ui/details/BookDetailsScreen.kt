package app.quire.android.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.quire.android.ui.QuireStrings
import app.quire.android.ui.common.PrimaryButton
import app.quire.android.ui.common.SecondaryButton
import app.quire.android.ui.library.CoverGradient
import androidx.annotation.DrawableRes
import app.quire.android.ui.theme.Quire
import app.quire.android.ui.theme.QuireIcon
import app.quire.android.ui.theme.QuireIcons
import app.quire.android.ui.theme.QuireShapes
import app.quire.core.reading.ReadingEstimates

/**
 * Book Details.
 *
 * Every figure on this screen comes from the book's own persisted state. Where a
 * value is genuinely unknown — no description, no chapters, nothing read yet — the
 * screen says so rather than showing a plausible number.
 */
@Composable
fun BookDetailsScreen(
    state: BookDetailsState,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onReadOriginal: () -> Unit,
    onOpenContents: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = Quire.colors
    // A book with no publisher's description has no synopsis to show, so the tab is
    // not offered and Details opens instead. A tab whose only content is "there is
    // nothing here" is worse than one fewer tab — and Quire will not write a
    // synopsis of its own, because an invented one would read exactly like a real
    // one and there would be no way to tell.
    val tabs = remember(state.synopsis) {
        DetailsTab.entries.filterNot { it == DetailsTab.SYNOPSIS && state.synopsis == null }
    }
    var tab by remember(tabs) { mutableStateOf(tabs.first()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        CoverHeader(state = state, onBack = onBack, onShare = onShare)

        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(20.dp))
            Text(
                state.title,
                color = colors.ink,
                style = MaterialTheme.typography.headlineLarge,
            )
            state.author?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = colors.muted, style = MaterialTheme.typography.bodyLarge)
            }

            if (state.subjects.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.subjects.take(3).forEach { GenreChip(it) }
                }
            }

            Spacer(Modifier.height(22.dp))
            StatStrip(state)

            state.paceSentence?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, color = colors.muted, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(24.dp))
            TabRow(tabs = tabs, selected = tab, onSelect = { tab = it })
            Spacer(Modifier.height(16.dp))
            TabBody(state = state, tab = tab)

            Spacer(Modifier.height(28.dp))
            if (state.reflowFailed) {
                // Said before the reader opens it, not discovered afterwards. A scan
                // has no text to reflow, and pretending otherwise is what produced
                // books with invented chapters and missing sentences.
                ScannedNotice()
                Spacer(Modifier.height(14.dp))
                SecondaryButton(QuireStrings.READ_SCANNED_PAGES, onReadOriginal)
            } else {
                PrimaryButton(
                    if (state.started) QuireStrings.CONTINUE_READING else "Start Reading",
                    onContinue,
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("Contents", onOpenContents, Modifier.weight(1f))
                SecondaryButton(QuireStrings.BOOKMARKS, onOpenBookmarks, Modifier.weight(1f))
            }
            Spacer(Modifier.height(26.dp))
            // Quiet, and last. Removing a book is rare, permanent, and the one action
            // on this screen a reader must not hit by accident — so it is a line of
            // text at the bottom rather than a fourth button competing with the three
            // things they came here to do.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(QuireShapes.button)
                    .clickable(onClick = onRemove)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    QuireStrings.REMOVE_BOOK,
                    color = colors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(20.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun CoverHeader(
    state: BookDetailsState,
    onBack: () -> Unit,
    onShare: () -> Unit,
) {
    val colors = Quire.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(CoverGradient.brush(state.id)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIcon(
                onClick = onBack,
                icon = QuireIcons.Back,
                description = QuireStrings.BACK,
            )
            Spacer(Modifier.weight(1f))
            GlassIcon(
                onClick = onShare,
                icon = QuireIcons.Share,
                description = QuireStrings.SHARE,
            )
        }
        Text(
            text = state.title,
            color = Color.White.copy(alpha = 0.94f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 40.dp),
        )
    }
}

@Composable
private fun GlassIcon(
    onClick: () -> Unit,
    @DrawableRes icon: Int,
    description: String,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.25f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        QuireIcon(icon, contentDescription = description, tint = Color.White)
    }
}

@Composable
private fun GenreChip(label: String) {
    val colors = Quire.colors
    Box(
        Modifier
            .clip(QuireShapes.chip)
            .background(colors.bgAlt)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(label, color = colors.muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StatStrip(state: BookDetailsState) {
    val colors = Quire.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(QuireShapes.card)
            .background(colors.bgAlt)
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Stat("${state.percentComplete}%", "complete")
        Stat(state.chapterLabel, "chapter")
        Stat(state.pagesLabel, "pages")
        Stat(state.timeLeftLabel.removeSuffix(" left"), "left")
    }
}

@Composable
private fun Stat(value: String, label: String) {
    val colors = Quire.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = colors.ink, style = MaterialTheme.typography.titleLarge)
        Text(label, color = colors.muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TabRow(
    tabs: List<DetailsTab>,
    selected: DetailsTab,
    onSelect: (DetailsTab) -> Unit,
) {
    val colors = Quire.colors
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        tabs.forEach { entry ->
            val active = entry == selected
            Column(
                modifier = Modifier.clickable { onSelect(entry) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    entry.label,
                    color = if (active) colors.ink else colors.muted,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .size(width = 26.dp, height = 2.dp)
                        .background(if (active) colors.accent else Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun TabBody(state: BookDetailsState, tab: DetailsTab) {
    val colors = Quire.colors
    val body = when (tab) {
        // Only reachable when there is one; the tab is hidden otherwise.
        DetailsTab.SYNOPSIS -> state.synopsis.orEmpty()

        DetailsTab.DETAILS -> buildString {
            appendLine("Format · ${state.format}")
            appendLine("Chapters · ${state.chapterCount}")
            append("Length · about ${ReadingEstimates.pageCount(state.totalChars)} pages")
        }

        DetailsTab.AUTHOR -> state.author
            ?: "No author is listed for this book."
    }

    Text(body, color = colors.muted, style = MaterialTheme.typography.bodyLarge)
}

/**
 * Tells the reader what kind of book this is, before they open it.
 *
 * Framed as a fact about the file rather than an error: nothing has gone wrong, and
 * they have not lost anything they ever had. What they would lose is the typography
 * and chapters Quire cannot honestly provide for a picture of a page.
 */
@Composable
private fun ScannedNotice() {
    val colors = Quire.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(QuireShapes.card)
            .background(colors.bgAlt)
            .padding(16.dp),
    ) {
        Text(
            QuireStrings.SCANNED_TITLE,
            color = colors.ink,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            QuireStrings.SCANNED_EXPLAINER,
            color = colors.muted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
