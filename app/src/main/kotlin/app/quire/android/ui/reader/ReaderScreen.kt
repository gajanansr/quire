package app.quire.android.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import app.quire.core.reading.Selection
import app.quire.core.reading.TextAnchor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.DrawableRes
import app.quire.android.ui.QuireStrings
import app.quire.android.ui.theme.Quire
import app.quire.android.ui.theme.QuireIcon
import app.quire.android.ui.theme.QuireIcons
import app.quire.android.ui.theme.QuireShapes
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import app.quire.core.paginate.Measure
import app.quire.core.paginate.BlockStyles
import app.quire.core.paginate.ChapterOpening
import app.quire.core.paginate.Indentation
import app.quire.core.paginate.trailingSpacingPx
import app.quire.core.paginate.spacingAbovePx
import app.quire.core.model.ContentBlock
import kotlin.math.roundToInt

/**
 * The Reader.
 *
 * The page itself is the whole screen; chrome appears only on a centre tap and
 * disappears again. The handoff's phrase for this part of the loop is "disappear",
 * and every decision here follows from that — no persistent bars, no progress
 * overlay, and nothing from the habit system on top of the text.
 */
@Composable
fun ReaderScreen(
    state: ReaderState,
    onTap: () -> Unit,
    onNextPage: () -> Unit,
    onPreviousPage: () -> Unit,
    onBack: () -> Unit,
    onOpenContents: () -> Unit,
    onOpenTypography: () -> Unit,
    onBookmark: () -> Unit,
    onSharePage: () -> Unit,
    onFinish: () -> Unit,
    onSelectionStart: (TextAnchor) -> Unit = {},
    onSelectionExtend: (TextAnchor) -> Unit = {},
    onSelectionClear: () -> Unit = {},
    onHighlight: () -> Unit = {},
    onShareSelection: () -> Unit = {},
    onCopySelection: () -> Unit = {},
    /** Reports the size of the text column, which is what pagination must measure. */
    onContentSize: (IntSize) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = Quire.colors

    // Where every drawn word is, rebuilt for each page. Keyed on the page so a stale
    // entry can never resolve a touch to a character that has moved.
    val textMap = remember(state.chapterIndex, state.pageIndex) { PageTextMap() }
    val selecting = state.hasSelection

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.readerBg)
            // First in the chain, and the only gesture that survives a live
            // selection: a drag while selecting has to extend the passage, not turn
            // the page out from under it.
            .pointerInput(state.chapterIndex, state.pageIndex) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        textMap.anchorAt(offset)?.let(onSelectionStart)
                    },
                    onDrag = { change, _ ->
                        textMap.anchorAt(change.position)?.let(onSelectionExtend)
                    },
                )
            }
            .pointerInput(state.tapTogglesChrome, selecting) {
                detectTapGestures(
                    onTap = { offset ->
                        // A tap dismisses a selection rather than turning the page:
                        // tapping away is how every other Android app cancels one.
                        if (selecting) {
                            onSelectionClear()
                            return@detectTapGestures
                        }
                        // Thirds: the outer columns turn pages, the middle toggles
                        // chrome. Turning by tap matters more than it sounds — it is
                        // the gesture a thumb can make without shifting grip.
                        when {
                            offset.x < size.width * 0.25f -> onPreviousPage()
                            offset.x > size.width * 0.75f -> onNextPage()
                            else -> onTap()
                        }
                    },
                )
            }
            .pointerInput(selecting) {
                if (selecting) return@pointerInput
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragged < -SWIPE_THRESHOLD) onNextPage()
                        else if (dragged > SWIPE_THRESHOLD) onPreviousPage()
                        dragged = 0f
                    },
                ) { _, amount -> dragged += amount }
            },
    ) {
        PageContent(
            state = state,
            textMap = textMap,
            onContentSize = onContentSize,
            modifier = Modifier.fillMaxSize(),
        )

        if (selecting) {
            SelectionActions(
                onHighlight = onHighlight,
                onShare = onShareSelection,
                onCopy = onCopySelection,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        AnimatedVisibility(
            visible = state.chromeVisible && !selecting,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopBar(title = state.bookTitle, onBack = onBack)
        }

        AnimatedVisibility(
            visible = state.chromeVisible && !selecting,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomBar(
                state = state,
                onOpenContents = onOpenContents,
                onOpenTypography = onOpenTypography,
                onBookmark = onBookmark,
                onSharePage = onSharePage,
                onFinish = onFinish,
            )
        }
    }
}

private const val SWIPE_THRESHOLD = 80f

@Composable
private fun PageContent(
    state: ReaderState,
    textMap: PageTextMap,
    onContentSize: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Quire.colors
    val density = LocalDensity.current
    val chapter = state.chapter
    val page = state.currentPage

    // The padding lives here and the size is reported from inside it, so the box
    // pagination measures is by construction the box the text renders into. Keeping
    // them as two separate declarations invites them to drift apart, and the symptom
    // — a clipped last line — looks like lost text rather than a layout mismatch.
    // The column is capped and centred rather than filling the screen. Past about
    // 66 characters the eye loses its way back to the start of the next line; on a
    // phone this never binds, on a tablet or in landscape it is the difference
    // between a page and a spreadsheet. Capped here, where the size is reported, so
    // pagination measures the column the text is actually set in.
    BoxWithConstraints(
        modifier = modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 26.dp)
            .padding(top = 26.dp, bottom = 40.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
    val availablePx = with(density) { maxWidth.toPx() }
    val constraintsHeightPx = with(density) { maxHeight.toPx() }
    val columnPx = Measure.widthPx(
        availablePx,
        state.preferences.toSettings(with(density) { 1.sp.toPx() }),
    )

    Column(
        modifier = Modifier
            .width(with(density) { columnPx.toDp() })
            // Must fill the height, or onSizeChanged reports the height of whatever
            // is currently drawn rather than the page's. That reading feeds straight
            // back into pagination as the viewport, and the two collapse together:
            // less content gives a smaller page, which fits less content.
            .fillMaxHeight()
            .onSizeChanged(onContentSize),
    ) {
        // The chapter label only heads its first page; repeating it on every page
        // would be the running header the reflow pipeline works to remove. It is
        // also skipped when the chapter's own first heading already says it.
        if (state.showsChapterHeader) {
            // A chapter opens low on the page. That drop is what tells a reader at
            // a glance that one thing has ended and another has begun, and it is
            // the oldest signal in book design.
            Spacer(
                Modifier.height(
                    with(density) { ChapterOpening.sinkPx(constraintsHeightPx).toDp() },
                ),
            )
            Text(
                // The same expression the "does the chapter already say this?" check
                // compares against, so the two cannot drift into disagreeing.
                text = state.chapterLabel,
                color = colors.muted,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth(),
            )
            chapter?.title?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = it,
                    color = colors.ink,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(30.dp))
        }

        if (chapter == null || page == null) return@Column

        // Spacing above each block and none after the last, mirroring exactly what
        // the paginator budgeted. A fixed gap drawn after every block — including
        // the last — is space the page was never laid out for, and it pushes the
        // final line off the bottom.
        val settings = state.preferences.toSettings(with(density) { 1.sp.toPx() })
        page.slices.forEachIndexed { position, slice ->
            val block = chapter.blocks.getOrNull(slice.blockIndex) ?: return@forEachIndexed
            // The chapter's cached text, not block.plainText: this runs for every
            // visible block on every recomposition.
            val text = chapter.blockTexts.getOrNull(slice.blockIndex)
                ?: return@forEachIndexed

            val above = spacingAbovePx(block, settings, isFirstOnPage = position == 0)
            if (above > 0f) Spacer(Modifier.height(with(density) { above.toDp() }))

            if (slice.length == 0) {
                if (block is ContentBlock.PageBreak) Spacer(Modifier.height(8.dp))
                return@forEachIndexed
            }
            val portion = text.substring(
                slice.startChar.coerceIn(0, text.length),
                slice.endChar.coerceIn(0, text.length),
            )
            BlockText(
                block = block,
                text = portion,
                state = state,
                indented = Indentation.shouldIndent(
                    chapter.blocks, slice.blockIndex, slice.startChar,
                ),
                opensChapter = ChapterOpening.isChapterOpening(
                    chapter.blocks, slice.blockIndex, slice.startChar,
                ),
                // Marks are cut to this slice here, where both the span and the
                // slice are in hand. A highlight stated in chapter coordinates and
                // drawn against a page's own indices lands on the wrong words.
                marks = marksFor(state, slice.blockIndex, slice.startChar, slice.endChar),
                textMap = textMap,
                blockIndex = slice.blockIndex,
                sliceStart = slice.startChar,
            )

            val below = trailingSpacingPx(block, settings)
            if (below > 0f) Spacer(Modifier.height(with(density) { below.toDp() }))
        }
    }
    }
}

@Composable
private fun BlockText(
    block: ContentBlock,
    text: String,
    state: ReaderState,
    indented: Boolean,
    opensChapter: Boolean,
    marks: List<Pair<IntRange, Color>>,
    textMap: PageTextMap,
    blockIndex: Int,
    sliceStart: Int,
) {
    val colors = Quire.colors
    val density = LocalDensity.current
    val prefs = state.preferences

    // Asked for, not restated. Every place this was described separately from the
    // paginator, the two drifted and the page lost its last line.
    val settings = prefs.toSettings(with(density) { 1.sp.toPx() })
    val blockStyle = BlockStyles.of(block, settings)
        .copy(openingInitial = opensChapter)
        .let {
            if (indented) {
                it.copy(firstLineIndentPx = BlockStyles.firstLineIndentPx(settings))
            } else {
                it
            }
        }
    val style = readerTextStyle(blockStyle, prefs.font.family(), density)

    // Compose's own layout of this block, kept so a touch can be resolved to a
    // character. Nothing else can answer that question, and re-deriving it from font
    // metrics would be a second description of a line that already exists.
    var layout by remember(text, style) { mutableStateOf<TextLayoutResult?>(null) }
    var topLeft by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun register() {
        val result = layout ?: return
        textMap.put(
            PageTextMap.Entry(
                blockIndex = blockIndex,
                sliceStart = sliceStart,
                topLeft = topLeft,
                size = size,
                layout = result,
            ),
        )
    }

    Text(
        // The same annotated text the paginator measured, initial and all. The marks
        // are backgrounds, which change no metric and so cannot move a line break —
        // MeasureMatchesRenderTest is what keeps that true.
        text = readerText(text, blockStyle, marks),
        color = if (block is ContentBlock.BlockQuote) colors.muted else colors.ink,
        style = style,
        onTextLayout = { layout = it; register() },
        modifier = Modifier
            .padding(start = with(density) { blockStyle.indentPx.toDp() })
            .onGloballyPositioned { coordinates ->
                topLeft = coordinates.positionInRoot()
                size = coordinates.size
                register()
            },
    )
}

/**
 * The marks to paint behind one slice of one block: saved highlights first, the live
 * selection over them.
 *
 * Saved highlights and a selection are the same shape — both are [TextSpan]s in
 * chapter coordinates — so both go through the same arithmetic and neither can drift
 * from the other.
 */
@Composable
private fun marksFor(
    state: ReaderState,
    blockIndex: Int,
    sliceStart: Int,
    sliceEnd: Int,
): List<Pair<IntRange, Color>> {
    val colors = Quire.colors
    val saved = state.highlights.mapNotNull { span ->
        Selection.portionOf(span, blockIndex, sliceStart, sliceEnd)?.let { it to colors.highlight }
    }
    val live = state.selection
        ?.let { Selection.portionOf(it, blockIndex, sliceStart, sliceEnd) }
        ?.let { it to colors.accentSoft }
    return if (live == null) saved else saved + live
}

/**
 * What a reader can do with the passage they have chosen.
 *
 * Sits where the bottom chrome would, and replaces it: both at once would cover the
 * page, and the reader is looking at the words, not at the controls.
 */
@Composable
private fun SelectionActions(
    onHighlight: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Quire.colors
    Row(
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 22.dp)
            .clip(QuireShapes.pill)
            .background(colors.bgAlt)
            .border(1.dp, colors.border, QuireShapes.pill)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionAction(QuireIcons.Highlight, QuireStrings.HIGHLIGHT, onHighlight)
        SelectionAction(QuireIcons.Share, QuireStrings.SHARE, onShare)
        SelectionAction(QuireIcons.Copy, QuireStrings.COPY, onCopy)
    }
}

@Composable
private fun SelectionAction(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
) {
    val colors = Quire.colors
    Row(
        modifier = Modifier
            .clip(QuireShapes.pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuireIcon(icon, contentDescription = null, tint = colors.ink)
        Text(label, color = colors.ink, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    val colors = Quire.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.readerBg.copy(alpha = 0.96f))
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuireIcon(
            QuireIcons.Back,
            contentDescription = QuireStrings.BACK,
            tint = colors.ink,
            size = QuireIcons.Size.Large,
            modifier = Modifier
                .pointerInput(Unit) { detectTapGestures { onBack() } },
        )
        Spacer(Modifier.padding(horizontal = 10.dp))
        Text(
            title,
            color = colors.muted,
            maxLines = 1,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BottomBar(
    state: ReaderState,
    onOpenContents: () -> Unit,
    onOpenTypography: () -> Unit,
    onBookmark: () -> Unit,
    onSharePage: () -> Unit,
    onFinish: () -> Unit,
) {
    val colors = Quire.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.readerBg.copy(alpha = 0.96f))
            .padding(horizontal = 20.dp)
            .padding(top = 14.dp)
            .navigationBarsPadding()
            .padding(bottom = 14.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(QuireShapes.chip)
                .background(colors.border),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(state.progress.toFloat().coerceIn(0f, 1f))
                    .height(3.dp)
                    .clip(QuireShapes.chip)
                    .background(colors.accent),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${(state.progress * 100).roundToInt()}% · page ${state.pageIndex + 1} of ${state.pageCount}",
            color = colors.muted,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ChromeAction(QuireIcons.Bookmark, QuireStrings.BOOKMARK, onBookmark)
            ChromeAction(QuireIcons.Share, QuireStrings.SHARE, onSharePage)
            ChromeAction(QuireIcons.Contents, QuireStrings.CONTENTS, onOpenContents)
            ChromeAction(QuireIcons.Typography, QuireStrings.TYPOGRAPHY, onOpenTypography)
            ChromeAction(QuireIcons.Done, QuireStrings.FINISH, onFinish)
        }
    }
}

/**
 * One action in the reader's bottom bar.
 *
 * Icon only, which is what the handoff draws: the bar sits under the page the whole
 * time the controls are visible, and four words compete with the prose for attention
 * in a way four quiet glyphs do not. The name survives as the content description,
 * so nothing is lost to a screen reader.
 *
 * The padding is generous on purpose — an 18dp icon is well under the 48dp minimum
 * touch target, and the tappable area, not the drawing, is what has to be big.
 */
@Composable
private fun ChromeAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    val colors = Quire.colors
    QuireIcon(
        icon = icon,
        contentDescription = label,
        tint = colors.ink,
        size = QuireIcons.Size.Large,
        modifier = Modifier
            .clip(QuireShapes.button)
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 14.dp, vertical = 13.dp),
    )
}
