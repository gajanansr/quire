package app.quire.android.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import app.quire.core.reading.Selection
import app.quire.core.reading.SelectionEdge
import app.quire.core.reading.TextAnchor
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
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
    /** A tap while a passage is chosen: the anchor decides whether it survives. */
    onSelectionTap: (TextAnchor?) -> Unit = {},
    onHandleGrab: (SelectionEdge) -> Unit = {},
    onHandleMove: (TextAnchor) -> Unit = {},
    onHandleRelease: () -> Unit = {},
    onHighlight: () -> Unit = {},
    onShareSelection: () -> Unit = {},
    onCopySelection: () -> Unit = {},
    /** Reports the size of the text column, which is what pagination must measure. */
    onContentSize: (IntSize) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = Quire.colors
    val density = LocalDensity.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    // Where every drawn word is, rebuilt for each page. Keyed on the page *itself*,
    // not on its number: a typography change re-pages and lands the reader on the
    // same index more often than not — always on a chapter's first page. Keyed on the
    // index alone the map was reused, and blocks that had fallen off the page kept
    // their old rows. A press low on the page could then resolve into a block that
    // was no longer on screen, light up, and save a highlight against text the reader
    // never touched.
    val page = state.currentPage
    val textMap = remember(state.chapterIndex, state.pageIndex, page) { PageTextMap() }
    val selecting = state.hasSelection
    val handleRadiusPx = with(density) { HANDLE_RADIUS.toPx() }

    // Where the two ends of the live passage are on screen. Recomputed when the
    // selection moves *or* when the page finishes laying itself out — without the
    // revision key the first frame of a selection would draw no handles at all.
    val carets = remember(state.selection, textMap.revision) {
        state.selection?.let {
            SelectionCarets(
                start = textMap.caretAt(it.start, SelectionEdge.START),
                end = textMap.caretAt(it.end, SelectionEdge.END),
            )
        }
    }

    // Read inside the gesture handlers rather than captured, so a pointer input that
    // is not being torn down still sees the current answer.
    val liveCarets by rememberUpdatedState(carets)
    val liveSelecting by rememberUpdatedState(selecting)
    // Set for as long as a handle is held. The long-press detector stands down while
    // it is: a handle drag is not a new selection, and starting one would throw away
    // the passage the reader is in the middle of adjusting.
    val holding = remember { mutableStateOf<SelectionEdge?>(null) }
    // The two halves of telling a long press apart from a drag, one for each order
    // the two can happen in.
    //
    // [sweeping]: the press got there first, by being held past its timeout. The drag
    // loop stands down — a sweep that starts on the right edge and runs down the page
    // is a selection, and without this it would extend the passage *and* dim the
    // screen at once.
    //
    // [dragging]: the drag got there first, by moving past touch slop. The long press
    // stands down. It has to be told: `awaitLongPressOrCancellation` has **no slop
    // check** — disassembling foundation 1.12.1 shows it watching only consumption,
    // out-of-bounds and pointer-up — so its timer runs out under a moving finger like
    // any other. A slow page-turn swipe held past 500ms would otherwise pop a
    // selection under the thumb mid-swipe and swallow the page turn.
    val sweeping = remember { mutableStateOf(false) }
    val dragging = remember { mutableStateOf(false) }

    // Session brightness, and the level bar that shows it. Deliberately *not* stored,
    // because a brightness chosen in a dark room is the wrong one to restore in
    // daylight and the reader cannot see the gesture that would fix it — see
    // ScreenBrightness.
    //
    // Plain `remember`, and `rememberSaveable` would be actively wrong. A rotation
    // destroys this activity, `QuireRoot` holds the open book in a plain `remember`,
    // so the reader comes back to the Library and this composable never runs to
    // consume the saved value. An unconsumed entry is re-saved on every save after
    // that, and the *next* book they open would inherit a level set in another room
    // hours earlier: exactly the failure this design exists to avoid.
    var brightness by remember { mutableFloatStateOf(ScreenBrightness.FOLLOW_SYSTEM) }
    var brightnessShown by remember { mutableStateOf(false) }
    var brightnessHeld by remember { mutableStateOf(false) }
    var pageSize by remember { mutableStateOf(IntSize.Zero) }

    ReaderBrightness(brightness)

    LaunchedEffect(brightnessHeld, brightness) {
        if (brightnessShown && !brightnessHeld) {
            delay(BRIGHTNESS_LINGER_MS)
            brightnessShown = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.readerBg)
            .onSizeChanged { pageSize = it }
            // This Box is the surface that catches every touch, so its own top-left
            // is the origin everything about the page has to be stated against. The
            // Reader does not start at the root — the whole app sits inside a
            // statusBarsPadding — and the blocks register where they are in the
            // *root*. Left unreconciled, a long press selected a word a status bar
            // higher than the finger. See PageTextMap.origin.
            .onGloballyPositioned { textMap.origin = it.positionInRoot() }
            // Outermost, so it sees a touch last — and last is where it has to be,
            // because it is the one detector that cannot judge for itself. Its timer
            // is wall-clock: it will fire under a finger that has been sweeping across
            // the page for half a second. The drag loop below consumes once it has
            // classified, which cancels this, and [dragging] says the same thing a
            // second way in case an ordering surprise gets past the consumption.
            .pointerInput(state.chapterIndex, state.pageIndex, page) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        if (holding.value != null || dragging.value) {
                            return@detectDragGesturesAfterLongPress
                        }
                        sweeping.value = true
                        textMap.anchorAt(offset)?.let {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelectionStart(it)
                        }
                    },
                    // Gated on `sweeping` rather than on the guards above: when the
                    // press was refused there is no passage this drag may touch, and
                    // extending on it would grow whatever selection happened to be
                    // left over from before.
                    onDrag = { change, _ ->
                        if (!sweeping.value || holding.value != null) {
                            return@detectDragGesturesAfterLongPress
                        }
                        textMap.anchorAt(change.position)?.let(onSelectionExtend)
                    },
                    onDragEnd = { sweeping.value = false },
                    onDragCancel = { sweeping.value = false },
                )
            }
            // Tap, page turn and brightness in one loop, because they are one
            // decision. Three detectors each guessing on their own is how a reader
            // ends up two pages further on when they meant to dim the screen.
            .pointerInput(state.chapterIndex, state.pageIndex, page) {
                val slop = viewConfiguration.touchSlop
                val longPress = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    var dx = 0f
                    var dy = 0f
                    var lastY = down.position.y
                    // Null until the finger has travelled far enough to mean
                    // something. Decided once and then held: re-deciding every frame
                    // makes a diagonal drag flicker between turning and dimming.
                    // DragIntent.NONE is a decision too — "this drag means nothing" —
                    // and is not the same as not having decided yet.
                    var intent: DragIntent? = null
                    var lifted = down

                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            // A handle took this gesture. It is innermost and
                            // consumes, so this is how the page hears about it.
                            if (change.isConsumed) return@awaitEachGesture
                            lifted = change
                            if (!change.pressed) break

                            dx += change.positionChange().x
                            dy += change.positionChange().y

                            if (intent == null && max(abs(dx), abs(dy)) >= slop) {
                                // The long press got there first, which it can only
                                // have done by being held past its timeout. This
                                // finger is choosing words, not turning a page.
                                if (sweeping.value) return@awaitEachGesture
                                intent = ReaderGestures.intentOf(
                                    down.position.x, size.width.toFloat(), dx, dy, slop,
                                )
                                dragging.value = true
                                if (intent == DragIntent.BRIGHTNESS) {
                                    // Seeded from the system on the very first drag,
                                    // so the screen moves from where it already is
                                    // instead of from an invented starting point.
                                    if (brightness == ScreenBrightness.FOLLOW_SYSTEM) {
                                        brightness = context.systemBrightnessSeed()
                                    }
                                    brightnessHeld = true
                                    brightnessShown = true
                                    lastY = change.position.y
                                }
                            }

                            if (intent == DragIntent.BRIGHTNESS) {
                                brightness = ScreenBrightness.dragged(
                                    current = brightness,
                                    dragPx = change.position.y - lastY,
                                    trackPx = size.height.toFloat(),
                                )
                                lastY = change.position.y
                            }

                            // Once this gesture is a drag it is not also a long press,
                            // and consuming is the only way to say so — the long-press
                            // detector has no slop check and would otherwise fire
                            // under a finger that has been swiping for half a second.
                            // Every classification consumes, DragIntent.NONE included:
                            // a drag that means nothing still is not a long press.
                            if (intent != null) change.consume()
                        }
                    } finally {
                        // Not the last statements of the block: a repagination while
                        // the finger is down changes this pointer input's keys, which
                        // resets it by throwing through here. Left unhandled that
                        // pinned the level bar on screen for the rest of the session.
                        dragging.value = false
                        if (intent == DragIntent.BRIGHTNESS) brightnessHeld = false
                    }

                    when {
                        // A page turn while a passage is chosen would take the page
                        // out from under it.
                        intent == DragIntent.PAGE_TURN && !liveSelecting ->
                            when (ReaderGestures.turnFor(dx, SWIPE_THRESHOLD)) {
                                PageTurn.NEXT -> onNextPage()
                                PageTurn.PREVIOUS -> onPreviousPage()
                                PageTurn.NONE -> Unit
                            }

                        // A tap: no drag decided, and the finger was not held long
                        // enough for the long press to have taken it.
                        intent == null &&
                            lifted.uptimeMillis - down.uptimeMillis < longPress -> {
                            if (liveSelecting) {
                                onSelectionTap(textMap.anchorAt(down.position))
                            } else {
                                when (
                                    ReaderGestures.tapZone(
                                        down.position.x, size.width.toFloat(),
                                    )
                                ) {
                                    TapZone.PREVIOUS -> onPreviousPage()
                                    TapZone.NEXT -> onNextPage()
                                    TapZone.CHROME -> onTap()
                                }
                            }
                        }

                        else -> Unit
                    }
                }
            }
            // Innermost, so a press near a handle is seen here first and consumed
            // before anything above can read it as a tap or a page turn. This is the
            // whole of the disambiguation between grabbing a handle and everything
            // else — it is not a question of thresholds.
            .pointerInput(state.chapterIndex, state.pageIndex, page) {
                awaitEachGesture {
                    // Unconsumed only: the action bar is a child and so is deeper
                    // still, and it consumes its own press. Where it overlaps a
                    // handle's grab area, the button the reader can see wins.
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val found = liveCarets ?: return@awaitEachGesture
                    val edge = SelectionHandles.grabbed(
                        down.position, found.start, found.end, handleRadiusPx,
                    ) ?: return@awaitEachGesture

                    down.consume()
                    holding.value = edge
                    onHandleGrab(edge)

                    // The finger keeps the grip it took. Resolving the raw touch
                    // point would put the caret wherever the thumb is — a line below
                    // the text it is adjusting, since the handle hangs beneath it.
                    val caret = if (edge == SelectionEdge.START) found.start else found.end
                    val grip = down.position - Offset(
                        caret?.x ?: down.position.x,
                        caret?.let { (it.top + it.bottom) / 2f } ?: down.position.y,
                    )

                    var last: TextAnchor? = null
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            change.consume()
                            if (!change.pressed) break

                            val at = textMap.anchorAt(change.position - grip) ?: continue
                            if (at == last) continue
                            last = at
                            // The only "which character am I on" signal there is
                            // without a magnifier, and the one Android itself gives.
                            // A tick per character is what makes a handle feel
                            // attached to the text rather than to the finger.
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onHandleMove(at)
                        }
                    } finally {
                        // Must be a finally. This pointer input is reset when its keys
                        // change — a repagination while a handle is held does it — and
                        // that throws straight through the loop. Leaving `holding` set
                        // would disable long-press selection for the rest of the
                        // session, silently and with nothing to point at.
                        holding.value = null
                        onHandleRelease()
                    }
                }
            },
    ) {
        PageContent(
            state = state,
            textMap = textMap,
            onContentSize = onContentSize,
            modifier = Modifier.fillMaxSize(),
        )

        if (selecting && carets != null) {
            SelectionHandleOverlay(carets, handleRadiusPx, colors.accent)
        }

        if (selecting) {
            // Above the passage when the passage is low on the page. A bar pinned to
            // the bottom sits on the words it is offering to copy, which is the one
            // place it must never be.
            val top = SelectionActionBar.prefersTop(
                // A passage whose far end is not on this page runs off the foot of
                // it, so it reaches as low as a passage can reach.
                selectionBottomPx = when {
                    carets?.end != null -> carets.end.bottom
                    carets?.start != null -> pageSize.height.toFloat()
                    else -> 0f
                },
                viewportHeightPx = pageSize.height.toFloat(),
            )
            SelectionActions(
                onHighlight = onHighlight,
                onShare = onShareSelection,
                onCopy = onCopySelection,
                atTop = top,
                modifier = Modifier.align(
                    if (top) Alignment.TopCenter else Alignment.BottomCenter,
                ),
            )
        }

        AnimatedVisibility(
            visible = brightnessShown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            BrightnessLevel(level = brightness)
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

/**
 * The drawn radius of a selection handle.
 *
 * The *touch* target is much larger — see [SelectionHandles] — because the drawing is
 * the affordance and the grabbable area is what has to fit a thumb. A control drawn
 * and hit-tested at the same 10dp is one nobody can hit, and a reader's conclusion is
 * that the handles do not work rather than that they missed.
 */
private val HANDLE_RADIUS = 10.dp

/** How long the brightness bar stays after the finger lifts. */
private const val BRIGHTNESS_LINGER_MS = 800L

/** Where the two ends of the live passage are, on the page currently drawn. */
data class SelectionCarets(val start: CaretRect?, val end: CaretRect?)

/**
 * The two grabbable ends of the chosen passage.
 *
 * Drawn over the text rather than in it, so nothing here changes a metric and nothing
 * here can move a line break — which is what keeps `MeasureMatchesRenderTest` true
 * while the reader has a selection open.
 *
 * A null caret means that end is on a block this page does not draw. It is simply not
 * drawn: a passage can run off the foot of a page, and a handle parked in the margin
 * would be a lie about where the passage stops.
 */
@Composable
private fun SelectionHandleOverlay(
    carets: SelectionCarets,
    radiusPx: Float,
    color: Color,
) {
    Canvas(Modifier.fillMaxSize()) {
        listOfNotNull(
            carets.start?.let { SelectionEdge.START to it },
            carets.end?.let { SelectionEdge.END to it },
        ).forEach { (edge, caret) ->
            // The caret bar itself. Without it the teardrop hangs under the line with
            // nothing saying which character it is actually holding.
            drawRect(
                color = color,
                topLeft = Offset(caret.x - CARET_WIDTH_PX / 2f, caret.top),
                size = Size(CARET_WIDTH_PX, caret.height),
            )

            val centre = SelectionHandles.centreOf(caret, edge, radiusPx)
            drawCircle(color = color, radius = radiusPx, center = centre)
            // The square corner that points back at the caret, which is what turns a
            // circle into the teardrop every Android reader recognises — and which
            // says, without a label, which end of the passage this handle is.
            drawRect(
                color = color,
                topLeft = Offset(
                    x = if (edge == SelectionEdge.START) caret.x - radiusPx else caret.x,
                    y = caret.bottom,
                ),
                size = Size(radiusPx, radiusPx),
            )
        }
    }
}

private const val CARET_WIDTH_PX = 4f

/**
 * How bright the screen is, while the reader is changing it.
 *
 * A bar and nothing else: no number, no dialog, nothing that outlives the gesture. It
 * is drawn against the accent rather than the page so it stays legible at the floor —
 * feedback that disappears as the screen dims is feedback exactly when it is needed.
 */
@Composable
private fun BrightnessLevel(level: Float) {
    val colors = Quire.colors
    val shown = level.coerceIn(ScreenBrightness.FLOOR, ScreenBrightness.CEILING)
    Box(
        modifier = Modifier
            // Inboard of the strip the thumb is on, or the thumb covers the only
            // feedback the gesture has.
            .padding(end = 34.dp)
            .height(160.dp)
            .width(5.dp)
            .clip(QuireShapes.chip)
            .background(colors.border),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier
                .fillMaxHeight(shown)
                .width(5.dp)
                .clip(QuireShapes.chip)
                .background(colors.accent),
        )
    }
}

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
        // Found once for the page, not once per block drawn on it. The search stops at
        // the chapter's first paragraph, so it is usually a step or two — but a chapter
        // with no paragraph at all (a plates section, a reflow that produced only
        // headings) walks every block, and asking per slice did that on every frame.
        val openingIndex = ChapterOpening.openingIndex(chapter.blocks)
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
                opensChapter = ChapterOpening.opensChapter(
                    openingIndex, slice.blockIndex, slice.startChar,
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
    atTop: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = Quire.colors
    Row(
        modifier = modifier
            .then(
                if (atTop) Modifier.statusBarsPadding().padding(top = 22.dp)
                else Modifier.navigationBarsPadding().padding(bottom = 22.dp),
            )
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
            // Pages counted in the book, not in the pages currently laid out.
            //
            // This read "page 12 of 719", where the denominator was the whole chapter
            // laid out at the reader's type size. A chapter is now laid out a window
            // at a time, so that number would be the size of the window — "page 5 of
            // 20", resetting as the reader went. The unit that survives is the printed
            // page: `ReadingEstimates` counts characters, which is the same measure
            // Book Details states a book's length in, and for this 565,896-character
            // novel it says 314 against the PDF's real 315.
            //
            // "about", because that is what it is. An estimate presented as exact is
            // worse than a different unit honestly labelled — and this one has the
            // compensation of not changing when the reader changes the type size,
            // which the old number did on every tap.
            text = state.readingLine,
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
