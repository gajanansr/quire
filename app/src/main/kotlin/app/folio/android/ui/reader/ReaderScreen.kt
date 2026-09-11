package app.folio.android.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.DrawableRes
import app.folio.android.ui.FolioStrings
import app.folio.android.ui.theme.Folio
import app.folio.android.ui.theme.FolioIcon
import app.folio.android.ui.theme.FolioIcons
import app.folio.android.ui.theme.FolioShapes
import app.folio.core.model.ContentBlock
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
    onFinish: () -> Unit,
    /** Reports the size of the text column, which is what pagination must measure. */
    onContentSize: (IntSize) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = Folio.colors

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.readerBg)
            .pointerInput(state.tapTogglesChrome) {
                detectTapGestures(
                    onTap = { offset ->
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
            .pointerInput(Unit) {
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
            onContentSize = onContentSize,
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(
            visible = state.chromeVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopBar(title = state.bookTitle, onBack = onBack)
        }

        AnimatedVisibility(
            visible = state.chromeVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            BottomBar(
                state = state,
                onOpenContents = onOpenContents,
                onOpenTypography = onOpenTypography,
                onBookmark = onBookmark,
                onFinish = onFinish,
            )
        }
    }
}

private const val SWIPE_THRESHOLD = 80f

@Composable
private fun PageContent(
    state: ReaderState,
    onContentSize: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Folio.colors
    val chapter = state.chapter
    val page = state.currentPage

    // The padding lives here and the size is reported from inside it, so the box
    // pagination measures is by construction the box the text renders into. Keeping
    // them as two separate declarations invites them to drift apart, and the symptom
    // — a clipped last line — looks like lost text rather than a layout mismatch.
    Column(
        modifier = modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 26.dp)
            .padding(top = 26.dp, bottom = 40.dp)
            .onSizeChanged(onContentSize),
    ) {
        // The chapter label only heads its first page; repeating it on every page
        // would be the running header the reflow pipeline works to remove. It is
        // also skipped when the chapter's own first heading already says it.
        if (state.showsChapterHeader) {
            Text(
                text = "Chapter ${state.chapterIndex + 1}",
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

        page.slices.forEach { slice ->
            val block = chapter.blocks.getOrNull(slice.blockIndex) ?: return@forEach
            // The chapter's cached text, not block.plainText: this runs for every
            // visible block on every recomposition.
            val text = chapter.blockTexts.getOrNull(slice.blockIndex) ?: return@forEach
            if (slice.length == 0) {
                if (block is ContentBlock.PageBreak) Spacer(Modifier.height(8.dp))
                return@forEach
            }
            val portion = text.substring(
                slice.startChar.coerceIn(0, text.length),
                slice.endChar.coerceIn(0, text.length),
            )
            BlockText(block = block, text = portion, state = state)
            Spacer(Modifier.height(if (block is ContentBlock.Heading) 12.dp else 14.dp))
        }
    }
}

@Composable
private fun BlockText(block: ContentBlock, text: String, state: ReaderState) {
    val colors = Folio.colors
    val prefs = state.preferences
    val align = if (prefs.justify) TextAlign.Justify else TextAlign.Start

    when (block) {
        is ContentBlock.Heading -> Text(
            text = text,
            color = colors.ink,
            fontFamily = prefs.font.family(),
            fontSize = (prefs.fontSizeSp * 1.4f).sp,
            lineHeight = (prefs.fontSizeSp * 1.4f * ReaderPreferences.LINE_HEIGHT).sp,
            style = MaterialTheme.typography.headlineMedium,
        )

        is ContentBlock.BlockQuote -> Text(
            text = text,
            color = colors.muted,
            fontFamily = prefs.font.family(),
            fontSize = prefs.fontSizeSp.sp,
            lineHeight = (prefs.fontSizeSp * ReaderPreferences.LINE_HEIGHT).sp,
            modifier = Modifier.padding(start = 14.dp),
        )

        else -> Text(
            text = text,
            color = colors.ink,
            fontFamily = prefs.font.family(),
            fontSize = prefs.fontSizeSp.sp,
            lineHeight = (prefs.fontSizeSp * ReaderPreferences.LINE_HEIGHT).sp,
            textAlign = align,
        )
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    val colors = Folio.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.readerBg.copy(alpha = 0.96f))
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolioIcon(
            FolioIcons.Back,
            contentDescription = FolioStrings.BACK,
            tint = colors.ink,
            size = FolioIcons.Size.Large,
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
    onFinish: () -> Unit,
) {
    val colors = Folio.colors
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
                .clip(FolioShapes.chip)
                .background(colors.border),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(state.progress.toFloat().coerceIn(0f, 1f))
                    .height(3.dp)
                    .clip(FolioShapes.chip)
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
            ChromeAction(FolioIcons.Bookmark, FolioStrings.BOOKMARK, onBookmark)
            ChromeAction(FolioIcons.Contents, FolioStrings.CONTENTS, onOpenContents)
            ChromeAction(FolioIcons.Typography, FolioStrings.TYPOGRAPHY, onOpenTypography)
            ChromeAction(FolioIcons.Done, FolioStrings.FINISH, onFinish)
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
    val colors = Folio.colors
    FolioIcon(
        icon = icon,
        contentDescription = label,
        tint = colors.ink,
        size = FolioIcons.Size.Large,
        modifier = Modifier
            .clip(FolioShapes.button)
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 14.dp, vertical = 13.dp),
    )
}
