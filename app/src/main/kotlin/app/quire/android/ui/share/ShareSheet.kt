package app.quire.android.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalDensity
import app.quire.android.share.ShareIntents
import app.quire.android.share.Sharing
import app.quire.android.ui.QuireStrings
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.annotation.DrawableRes
import app.quire.android.ui.theme.Quire
import app.quire.android.ui.theme.QuireIcon
import app.quire.android.ui.theme.QuireIcons
import app.quire.android.ui.theme.QuireShapes
import app.quire.android.ui.theme.ReaderFont
import app.quire.android.ui.theme.SourceSerif
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.quire.core.habit.ReadingDay

/** What is being shared. */
sealed interface ShareCard {
    data class Quote(
        val bookId: String,
        val bookTitle: String,
        val author: String?,
        val text: String,
        val chapterLabel: String,
        /**
         * The face the reader was reading this passage in.
         *
         * A quote card is *their* passage, so it is set in the face they chose in the
         * typography sheet rather than in whatever the card was hardcoded to. It
         * travels on the card rather than as a parameter of the sheet because the
         * sheet is hosted in `QuireRoot`, which has no reader and no business knowing
         * about reading preferences — and because the only route that has an answer is
         * the Reader, which is also the only route that builds this.
         *
         * [ReaderFont.SERIF] for the routes with no reader at all: a book's
         * description from Book Details, and a saved bookmark.
         */
        val font: ReaderFont = ReaderFont.SERIF,
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
        if (days == 1) "1 day reading streak on Quire."
        else "$days day reading streak on Quire."
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
 * image, the words, the clipboard, the camera roll — is honest about what Quire can
 * do, and the system chooser is where the reader picks the app anyway.
 *
 * Nothing here runs without a tap. Quire declares no `INTERNET` permission; the
 * chooser is the only route off this device and the reader opens it themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    card: ShareCard,
    onDismiss: () -> Unit,
) {
    val colors = Quire.colors
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
        Sharing.start(context, ShareIntents.text(bodyOf(card, caption), QuireStrings.SHARE))
        onDismiss()
    }

    fun copyText() {
        Sharing.copy(context, QuireStrings.SHARE, bodyOf(card, caption))
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
        shape = QuireShapes.sheet,
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
                    .clip(QuireShapes.card)
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
                    .clip(QuireShapes.button)
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
                                QuireStrings.ADD_A_CAPTION,
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
                Destination(QuireIcons.Story, QuireStrings.SHARE_IMAGE) {
                    withCard { bitmap ->
                        Sharing.start(
                            context,
                            ShareIntents.image(
                                Sharing.cacheCard(context, bitmap),
                                caption.trim(),
                                QuireStrings.SHARE,
                            ),
                        )
                    }
                }
                Destination(QuireIcons.Message, QuireStrings.SHARE_TEXT, ::shareText)
                Destination(QuireIcons.Copy, QuireStrings.COPY, ::copyText)
                Destination(QuireIcons.Save, QuireStrings.SAVE) {
                    withCard { bitmap ->
                        // Below API 29 writing to the picture library needs a
                        // permission Quire does not ask for, so the reader gets the
                        // chooser instead — which reaches the gallery anyway, by way
                        // of them picking it.
                        val saved = Sharing.saveToPictures(context, bitmap, cardFileName(card))
                        if (saved == null) {
                            Sharing.start(
                                context,
                                ShareIntents.image(
                                    Sharing.cacheCard(context, bitmap),
                                    caption.trim(),
                                    QuireStrings.SAVE,
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
    val footer = "${QuireStrings.APP_NAME} \u00B7 ${QuireStrings.SHARE_FOOTER}"
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
        .joinToString("").trim('-').take(40).ifBlank { "quire" }
    return "quire-$safe-${System.currentTimeMillis()}.png"
}

@Composable
private fun Destination(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    val colors = Quire.colors
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
            QuireIcon(
                icon,
                contentDescription = null,
                tint = colors.ink,
                size = QuireIcons.Size.Large,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = colors.muted, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * One card's measurements, in the units Compose draws in.
 *
 * The type sizes come back pinned against the reader's system font scale —
 * `Dp.toSp()` divides by it, so what is rendered is the number of pixels the card's
 * proportions asked for. A share card is a picture with fixed proportions and no way
 * to re-flow once it is a PNG; if an accessibility font scale grew the passage but not
 * the card, the quote would run off the bottom of an export nobody can fix.
 */
private data class CardFrame(
    val metrics: CardMetrics.Frame,
    val margin: Dp,
    val gap: Dp,
    val label: TextUnit,
    val wordmark: TextUnit,
    val display: TextUnit,
)

@Composable
private fun cardFrameOf(widthDp: Float): CardFrame {
    val metrics = CardMetrics.of(widthDp)
    val density = LocalDensity.current
    return with(density) {
        CardFrame(
            metrics = metrics,
            margin = metrics.marginDp.dp,
            gap = metrics.gapDp.dp,
            label = metrics.labelSp.dp.toSp(),
            wordmark = metrics.wordmarkSp.dp.toSp(),
            display = metrics.displaySp.dp.toSp(),
        )
    }
}

@Composable
private fun QuoteCard(card: ShareCard.Quote, palette: CardPalette) {
    // The card asks how wide it actually is rather than assuming the sheet's preview
    // width. Everything below is a fraction of that, which is what makes the preview
    // and the exported PNG the same design at two scales — see [CardMetrics].
    BoxWithConstraints(Modifier.fillMaxSize().background(palette.brush)) {
        val frame = cardFrameOf(maxWidth.value)
        val density = LocalDensity.current
        Column(
            Modifier.fillMaxSize().padding(frame.margin),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    card.bookTitle,
                    color = palette.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = frame.label,
                    lineHeight = frame.label * LABEL_LEADING,
                    style = MaterialTheme.typography.labelSmall,
                )
                card.author?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        color = palette.muted,
                        fontSize = frame.label,
                        lineHeight = frame.label * LABEL_LEADING,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            // Sized to the passage rather than cut to a fixed 180 characters. The
            // old version closed the quotation mark after cutting, so a truncated
            // passage looked complete — a reader who chose three paragraphs shared
            // one sentence and had no way to tell.
            val fit = QuoteFit.of(card.text, frame.metrics.widthDp)
            // Centred in its own field rather than hung under the title. A six-word
            // passage and a six-hundred-character one get the same field; the short
            // one sits in the middle of it with air either side, the long one fills
            // it. Left to SpaceBetween alone the short passage clings to the title
            // with all the emptiness below it, which reads as a layout that gave up.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = frame.gap),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "“${fit.text}”",
                    color = palette.ink,
                    // The reader's own face, not the card's. A quote card is their
                    // passage; setting it in a face they did not choose makes it a
                    // picture of Quire's opinion instead. The wordmark below stays
                    // Source Serif — that is Quire's mark, and a brand line that
                    // changes typeface with a preference is not a brand line.
                    fontFamily = card.font.family(),
                    fontStyle = if (QuoteFit.isItalic(card.font)) {
                        FontStyle.Italic
                    } else {
                        FontStyle.Normal
                    },
                    fontSize = with(density) { fit.fontSizeSp.dp.toSp() },
                    lineHeight = with(density) {
                        (fit.fontSizeSp * QuoteFit.LINE_HEIGHT).dp.toSp()
                    },
                    maxLines = fit.maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Column {
                card.chapterLabel.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        color = palette.muted,
                        fontSize = frame.label,
                        lineHeight = frame.label * LABEL_LEADING,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(frame.gap))
                }
                Wordmark(palette, frame)
            }
        }
    }
}

@Composable
private fun StreakCard(card: ShareCard.Streak, palette: CardPalette) {
    BoxWithConstraints(Modifier.fillMaxSize().background(palette.brush)) {
        val frame = cardFrameOf(maxWidth.value)
        Column(
            Modifier.fillMaxSize().padding(frame.margin),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Reading Streak",
                color = palette.muted,
                fontSize = frame.label,
                lineHeight = frame.label * LABEL_LEADING,
                style = MaterialTheme.typography.labelSmall,
            )

            // The same proportional frame as the quote card. Two cards that share a
            // shape and not a set of measurements drift apart one edit at a time.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "${card.days}",
                    color = palette.ink,
                    fontFamily = SourceSerif,
                    fontSize = frame.display,
                    lineHeight = frame.display * 1.15f,
                    style = MaterialTheme.typography.displayLarge,
                )
                Text(
                    if (card.days == 1) "day" else "days",
                    color = palette.muted,
                    fontSize = frame.label,
                    lineHeight = frame.label * LABEL_LEADING,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(frame.gap * 2))
                Row(horizontalArrangement = Arrangement.spacedBy(frame.gap / 2)) {
                    card.week.forEach { day ->
                        val met = card.goalMinutes > 0 && day.minutes >= card.goalMinutes
                        Box(
                            Modifier
                                .size(width = frame.gap, height = frame.gap * 2.5f)
                                .clip(QuireShapes.chip)
                                .background(
                                    if (met) palette.ink
                                    else palette.ink.copy(alpha = 0.22f)
                                ),
                        )
                    }
                }
            }

            Wordmark(palette, frame)
        }
    }
}

/** Leading for the card's small labels, as a multiple of their size. */
private const val LABEL_LEADING = 1.3f

/**
 * The card's footer: what this is, and how to get it.
 *
 * The tagline used to sit here, which is lovely and tells a stranger nothing they can
 * act on. A shared card is the only piece of Quire most people will ever see, so the
 * second line is an invitation instead. It lives in [QuireStrings.SHARE_FOOTER]
 * beside the text share's own footer, so a passage posted as words and the same
 * passage posted as a picture say the same thing — and so it becomes a store link the
 * day there is one to point at, in a single edit.
 */
@Composable
private fun Wordmark(palette: CardPalette, frame: CardFrame) {
    Column {
        Text(
            QuireStrings.APP_NAME,
            color = palette.ink,
            fontFamily = SourceSerif,
            fontSize = frame.wordmark,
            lineHeight = frame.wordmark * 1.25f,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            QuireStrings.SHARE_FOOTER,
            color = palette.muted,
            fontSize = frame.label,
            lineHeight = frame.label * LABEL_LEADING,
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
    val colors = Quire.colors
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
