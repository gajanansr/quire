package app.folio.android.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.folio.android.ui.library.CoverGradient
import androidx.annotation.DrawableRes
import app.folio.android.ui.theme.Folio
import app.folio.android.ui.theme.FolioIcon
import app.folio.android.ui.theme.FolioIcons
import app.folio.android.ui.theme.FolioShapes
import app.folio.android.ui.theme.SourceSerif
import app.folio.core.habit.ReadingDay

/** What is being shared. */
sealed interface ShareCard {
    data class Quote(
        val bookId: String,
        val bookTitle: String,
        val author: String?,
        val text: String,
        val chapterLabel: String,
    ) : ShareCard

    data class Streak(
        val days: Int,
        val week: List<ReadingDay>,
        val goalMinutes: Int,
    ) : ShareCard
}

/**
 * The share sheet.
 *
 * A 9:16 preview in story proportions, a caption row, and destinations.
 *
 * The destination glyphs are deliberately generic. The handoff says no real brand
 * logos were used and none should be added without the actual SDKs and brand
 * guidelines — using a recognisable mark without permission is a trademark problem,
 * not a design shortcut. "Save Image" is the one destination that genuinely works
 * today; the others hand off to the system share sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    card: ShareCard,
    onShare: () -> Unit,
    onSaveImage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Folio.colors

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.bgAlt,
        shape = FolioShapes.sheet,
        dragHandle = null,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 18.dp, bottom = 22.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Share", color = colors.ink,
                    style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Cancel",
                    color = colors.accent,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }

            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.62f)
                    .align(Alignment.CenterHorizontally)
                    .aspectRatio(9f / 16f)
                    .clip(FolioShapes.card),
            ) {
                when (card) {
                    is ShareCard.Quote -> QuoteCard(card)
                    is ShareCard.Streak -> StreakCard(card)
                }
            }

            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(FolioShapes.button)
                    .background(colors.bg)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                Text(
                    "Add a caption…",
                    color = colors.muted,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Destination(FolioIcons.Story, "Stories", onShare)
                Destination(FolioIcons.Message, "Message", onShare)
                Destination(FolioIcons.More, "More", onShare)
                Destination(FolioIcons.Save, "Save", onSaveImage)
            }
        }
    }
}

@Composable
private fun Destination(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    val colors = Folio.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        // Generic glyphs, never brand marks. See the class comment: a recognisable
        // logo without the SDK and the brand guidelines is a trademark problem, so
        // "Stories" gets a picture and "Message" an arrow rather than anyone's mark.
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .border(1.dp, colors.border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            FolioIcon(
                icon,
                contentDescription = null,
                tint = colors.ink,
                size = FolioIcons.Size.Large,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = colors.muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun QuoteCard(card: ShareCard.Quote) {
    Box(
        Modifier.fillMaxSize().background(CoverGradient.of(card.bookId)),
    ) {
        Column(
            Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    card.bookTitle,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
                card.author?.let {
                    Text(
                        it,
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Text(
                text = "“${card.text.take(180)}”",
                color = Color.White,
                fontFamily = SourceSerif,
                fontStyle = FontStyle.Italic,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )

            Column {
                Text(
                    card.chapterLabel,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(8.dp))
                Wordmark()
            }
        }
    }
}

@Composable
private fun StreakCard(card: ShareCard.Streak) {
    Box(Modifier.fillMaxSize().background(Folio.colors.ink)) {
        Column(
            Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Reading Streak",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${card.days}",
                    color = Color.White,
                    fontFamily = SourceSerif,
                    style = MaterialTheme.typography.displayLarge,
                )
                Text(
                    if (card.days == 1) "day" else "days",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    card.week.forEach { day ->
                        val met = card.goalMinutes > 0 && day.minutes >= card.goalMinutes
                        Box(
                            Modifier
                                .size(width = 8.dp, height = 20.dp)
                                .clip(FolioShapes.chip)
                                .background(
                                    if (met) Color.White
                                    else Color.White.copy(alpha = 0.22f)
                                ),
                        )
                    }
                }
            }

            Wordmark()
        }
    }
}

@Composable
private fun Wordmark() {
    Column {
        Text(
            "Folio",
            color = Color.White,
            fontFamily = SourceSerif,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "A quiet place to read.",
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
