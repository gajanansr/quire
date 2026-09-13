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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import app.folio.android.share.ShareIntents
import app.folio.android.share.Sharing
import app.folio.android.ui.FolioStrings
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.annotation.DrawableRes
import app.folio.android.ui.theme.Folio
import app.folio.android.ui.theme.FolioIcon
import app.folio.android.ui.theme.FolioIcons
import app.folio.android.ui.theme.FolioShapes
import app.folio.android.ui.theme.SourceSerif
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * A card as plain text.
 *
 * The same thing the picture says, for the destinations that take words. Written
 * once so the image and the text can never disagree about what was shared.
 */
fun ShareCard.asText(): String = when (this) {
    is ShareCard.Quote -> buildString {
        append('\u201C').append(text.trim()).append('\u201D')
        append("\n\n\u2014 ").append(bookTitle)
        author?.takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
        if (chapterLabel.isNotBlank()) append(" (").append(chapterLabel).append(')')
    }

    is ShareCard.Streak ->
        if (days == 1) "1 day reading streak on Folio."
        else "$days day reading streak on Folio."
}

/**
 * The share sheet.
 *
 * A 9:16 preview in story proportions, a caption row, and destinations.
 *
 * The destinations are the four things that actually happen, not four apps. The
 * handoff drew glyphs standing in for named services, and they stayed generic on
 * purpose — a recognisable mark without the SDK and the brand guidelines is a
 * trademark problem. But generic glyphs that also did nothing were worse than either
 * option: the sheet closed and nothing was shared. Naming the real actions — an
 * image, the words, the clipboard, the camera roll — is honest about what Folio can
 * do, and the system chooser is where the reader picks the app anyway.
 *
 * Nothing here runs without a tap. Folio declares no `INTERNET` permission; the
 * chooser is the only route off this device and the reader opens it themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    card: ShareCard,
    onDismiss: () -> Unit,
) {
    val colors = Folio.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var caption by remember { mutableStateOf("") }
    var style by remember { mutableStateOf(ShareCardStyle.COVER) }
    val palette = style.palette(bookIdOf(card))

    // The card is captured from the composable already on screen rather than drawn
    // a second time into a bitmap. Two descriptions of the same card would drift,
    // and the one nobody looks at would be the one that shipped.
    val cardLayer = rememberGraphicsLayer()

    fun shareText() {
        Sharing.start(context, ShareIntents.text(bodyOf(card, caption), FolioStrings.SHARE))
        onDismiss()
    }

    fun copyText() {
        Sharing.copy(context, FolioStrings.SHARE, bodyOf(card, caption))
        onDismiss()
    }

    fun withCard(use: (android.graphics.Bitmap) -> Unit) {
        scope.launch {
            use(cardLayer.toImageBitmap().asAndroidBitmap())
            onDismiss()
        }
    }

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
                    .clip(FolioShapes.card)
                    .drawWithContent {
                        // Recorded at twice the size it is shown at, then drawn back
                        // down by half. The preview is unchanged; the exported PNG is
                        // 2x. It matters because the card is 9:16 and about 600px wide
                        // on screen — post that to a feed and the platform recompresses
                        // it again, which turns a serif quote to mush. Scaling the
                        // recording re-rasterises the type at the larger size rather
                        // than enlarging pixels, so the words stay sharp.
                        val exported = IntSize(
                            (size.width * EXPORT_SCALE).toInt(),
                            (size.height * EXPORT_SCALE).toInt(),
                        )
                        cardLayer.record(size = exported) {
                            scale(EXPORT_SCALE, pivot = Offset.Zero) {
                                this@drawWithContent.drawContent()
                            }
                        }
                        scale(1f / EXPORT_SCALE, pivot = Offset.Zero) { drawLayer(cardLayer) }
                    },
            ) {
                when (card) {
                    is ShareCard.Quote -> QuoteCard(card, palette)
                    is ShareCard.Streak -> StreakCard(card, palette)
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShareCardStyle.entries.forEach { option ->
                    Swatch(
                        style = option,
                        bookId = bookIdOf(card),
                        selected = option == style,
                        onClick = { style = option },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            // A real field, not the placeholder the handoff drew. A caption box that
            // cannot be typed in is worse than none: it promises an edit and then
            // sends the passage without it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(FolioShapes.button)
                    .background(colors.bg)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                BasicTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { field ->
                        if (caption.isEmpty()) {
                            Text(
                                FolioStrings.ADD_A_CAPTION,
                                color = colors.muted,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        field()
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Destination(FolioIcons.Story, FolioStrings.SHARE_IMAGE) {
                    withCard { bitmap ->
                        Sharing.start(
                            context,
                            ShareIntents.image(
                                Sharing.cacheCard(context, bitmap),
                                caption.trim(),
                                FolioStrings.SHARE,
                            ),
                        )
                    }
                }
                Destination(FolioIcons.Message, FolioStrings.SHARE_TEXT, ::shareText)
                Destination(FolioIcons.Copy, FolioStrings.COPY, ::copyText)
                Destination(FolioIcons.Save, FolioStrings.SAVE) {
                    withCard { bitmap ->
                        // Below API 29 writing to the picture library needs a
                        // permission Folio does not ask for, so the reader gets the
                        // chooser instead — which reaches the gallery anyway, by way
                        // of them picking it.
                        val saved = Sharing.saveToPictures(context, bitmap, cardFileName(card))
                        if (saved == null) {
                            Sharing.start(
                                context,
                                ShareIntents.image(
                                    Sharing.cacheCard(context, bitmap),
                                    caption.trim(),
                                    FolioStrings.SAVE,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The card as words: the reader's caption, the passage, and the same footer the
 * picture carries. A passage shared as text and the same passage shared as an image
 * should not read differently.
 */
private fun bodyOf(card: ShareCard, caption: String): String {
    val note = caption.trim()
    val footer = "${FolioStrings.APP_NAME} \u00B7 ${FolioStrings.SHARE_FOOTER}"
    return listOf(note, card.asText(), footer)
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
}

/** A filename a reader will recognise in their gallery months later. */
private fun cardFileName(card: ShareCard): String {
    val stem = when (card) {
        is ShareCard.Quote -> card.bookTitle
        is ShareCard.Streak -> "reading-streak"
    }
    val safe = stem.lowercase().map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("").trim('-').take(40).ifBlank { "folio" }
    return "folio-$safe-${System.currentTimeMillis()}.png"
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
private fun QuoteCard(card: ShareCard.Quote, palette: CardPalette) {
    Box(Modifier.fillMaxSize().background(palette.brush)) {
        Column(
            Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    card.bookTitle,
                    color = palette.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
                card.author?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        color = palette.muted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Text(
                text = "“${card.text.take(180)}”",
                color = palette.ink,
                fontFamily = SourceSerif,
                fontStyle = FontStyle.Italic,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )

            Column {
                card.chapterLabel.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = palette.muted, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(8.dp))
                }
                Wordmark(palette)
            }
        }
    }
}

@Composable
private fun StreakCard(card: ShareCard.Streak, palette: CardPalette) {
    Box(Modifier.fillMaxSize().background(palette.brush)) {
        Column(
            Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Reading Streak",
                color = palette.muted,
                style = MaterialTheme.typography.labelSmall,
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${card.days}",
                    color = palette.ink,
                    fontFamily = SourceSerif,
                    style = MaterialTheme.typography.displayLarge,
                )
                Text(
                    if (card.days == 1) "day" else "days",
                    color = palette.muted,
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
                                    if (met) palette.ink
                                    else palette.ink.copy(alpha = 0.22f)
                                ),
                        )
                    }
                }
            }

            Wordmark(palette)
        }
    }
}

/**
 * The card's footer: what this is, and how to get it.
 *
 * The tagline used to sit here, which is lovely and tells a stranger nothing they can
 * act on. A shared card is the only piece of Folio most people will ever see, so the
 * second line is an invitation instead. It lives in [FolioStrings.SHARE_FOOTER]
 * beside the text share's own footer, so a passage posted as words and the same
 * passage posted as a picture say the same thing — and so it becomes a store link the
 * day there is one to point at, in a single edit.
 */
@Composable
private fun Wordmark(palette: CardPalette) {
    Column {
        Text(
            FolioStrings.APP_NAME,
            color = palette.ink,
            fontFamily = SourceSerif,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            FolioStrings.SHARE_FOOTER,
            color = palette.muted,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * How much larger the shared picture is than the preview.
 *
 * Two, not more: the card is captured from a live composable, and every step up
 * costs memory on the way to a PNG for a picture nobody zooms into.
 */
private const val EXPORT_SCALE = 2f

/** A card belongs to a book, or to no book at all — a streak has no cover. */
private fun bookIdOf(card: ShareCard): String? = when (card) {
    is ShareCard.Quote -> card.bookId
    is ShareCard.Streak -> null
}

/**
 * One style to pick from.
 *
 * Drawn as the palette itself rather than labelled: the swatch is the answer to the
 * question being asked, and six words would crowd a sheet that is mostly picture. A
 * ring marks the chosen one, and the name travels in the semantics so a screen reader
 * hears "Sepia" rather than "button".
 */
@Composable
private fun Swatch(
    style: ShareCardStyle,
    bookId: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = Folio.colors
    val palette = style.palette(bookId)
    Box(
        modifier = Modifier
            .padding(horizontal = 5.dp)
            .size(38.dp)
            .clip(CircleShape)
            .selectable(selected = selected, onClick = onClick)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) colors.ink else colors.border,
                shape = CircleShape,
            )
            .padding(if (selected) 4.dp else 3.dp)
            .clip(CircleShape)
            .background(palette.brush)
            .semantics { contentDescription = style.label },
    )
}
