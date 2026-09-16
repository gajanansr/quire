package app.quire.android.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.quire.android.share.TextHandoff
import app.quire.android.ui.QuireStrings
import app.quire.android.ui.theme.QuireIcon
import app.quire.android.ui.theme.QuireIcons
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.quire.android.ui.theme.label
import app.quire.android.ui.theme.Quire
import app.quire.android.ui.theme.QuirePalettes
import app.quire.android.ui.theme.QuireShapes
import app.quire.android.ui.theme.QuireThemeName
import app.quire.android.ui.theme.ReaderFont
import app.quire.core.model.ChapterRef
import kotlin.math.roundToInt

/**
 * Typography.
 *
 * Everything here changes the page immediately. The handoff treats these as live
 * controls rather than a settings form, and a reader adjusting type size is doing
 * it because the current size is uncomfortable right now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypographySheet(
    preferences: ReaderPreferences,
    theme: QuireThemeName,
    onFontChange: (ReaderFont) -> Unit,
    onSizeChange: (Float) -> Unit,
    onJustifyChange: (Boolean) -> Unit,
    onThemeChange: (QuireThemeName) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Quire.colors
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
                .padding(top = 22.dp, bottom = 26.dp),
        ) {
            SectionLabel("Font")
            Spacer(Modifier.height(10.dp))
            // 2x2, as the handoff lays it out.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ReaderFont.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { font ->
                            ChoiceTile(
                                label = font.label,
                                selected = font == preferences.font,
                                modifier = Modifier.weight(1f),
                                onClick = { onFontChange(font) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("Size")
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepperButton("A−", enabled = preferences.fontSizeSp > ReaderPreferences.MIN_SIZE) {
                    onSizeChange(-ReaderPreferences.STEP)
                }
                Text(
                    "${preferences.fontSizeSp.roundToInt()}px",
                    color = colors.muted,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                StepperButton("A+", enabled = preferences.fontSizeSp < ReaderPreferences.MAX_SIZE) {
                    onSizeChange(ReaderPreferences.STEP)
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("Alignment")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceTile("Left", !preferences.justify, Modifier.weight(1f)) { onJustifyChange(false) }
                ChoiceTile("Justify", preferences.justify, Modifier.weight(1f)) { onJustifyChange(true) }
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel("Theme")
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                QuireThemeName.entries.forEach { name ->
                    ThemePreview(
                        name = name,
                        selected = name == theme,
                        modifier = Modifier.weight(1f),
                        onClick = { onThemeChange(name) },
                    )
                }
            }
        }
    }
}

/**
 * The table of contents.
 *
 * Flat, as the handoff specifies — no nesting, even for books whose outline has
 * levels. A reader looking for a chapter wants a list they can scan, and the
 * hierarchy of a technical book's sub-sections gets in the way of that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentsSheet(
    chapters: List<ChapterRef>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Quire.colors
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
                .padding(top = 22.dp, bottom = 20.dp),
        ) {
            Text(
                "Contents",
                color = colors.ink,
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(14.dp))

            if (chapters.isEmpty()) {
                Text(
                    "This book has no chapters to jump between.",
                    color = colors.muted,
                    style = MaterialTheme.typography.bodyLarge,
                )
                return@Column
            }

            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(chapters, key = { it.index }) { ref ->
                    val current = ref.index == currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(QuireShapes.button)
                            .clickable { onSelect(ref.index) }
                            .padding(vertical = 13.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (current) colors.accent else Color.Transparent),
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            text = ref.title ?: "Chapter ${ref.index + 1}",
                            color = if (current) colors.accent else colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Quire.colors.muted, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun ChoiceTile(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = Quire.colors
    Box(
        modifier = modifier
            .clip(QuireShapes.button)
            .background(if (selected) colors.accentSoft else colors.bg)
            // Selection cannot rest on a tint alone. E-ink's accent is near-ink
            // with almost no chroma, so its accentSoft sits within a few percent of
            // the page — the fill all but disappears and every tile looks chosen.
            // A border reads at any chroma, and is the stronger affordance in the
            // colour themes too.
            .then(
                if (selected) Modifier.border(1.5.dp, colors.accent, QuireShapes.button)
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) colors.accent else colors.ink,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = Quire.colors
    Box(
        modifier = Modifier
            .size(width = 66.dp, height = 46.dp)
            .clip(QuireShapes.button)
            .background(colors.bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            // A disabled step is shown, not hidden: the bounds are part of the
            // control, and a button that vanishes at the limit is disorienting.
            color = if (enabled) colors.ink else colors.muted.copy(alpha = 0.4f),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

/**
 * One theme, drawn as the page it produces.
 *
 * The previous swatch was a circle of the theme's background with its initial in it,
 * which forced a reader to decode "L / P / D / E" and showed only one of the four
 * colours that actually change. This draws a miniature page instead: the real
 * background, three lines of the real ink colour, and the accent. What you see is
 * what the reader will look like.
 *
 * Every preview draws from [QuirePalettes], so a theme cannot look like one thing
 * here and another thing once chosen — the specimen and the page are the same
 * values.
 */
@Composable
private fun ThemePreview(
    name: QuireThemeName,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = Quire.colors
    val palette = QuirePalettes.of(name)

    Column(
        modifier = modifier
            .clip(QuireShapes.chip)
            .clickable(onClick = onClick)
            .semantics { contentDescription = name.label() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(QuireShapes.chip)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) colors.accent else colors.border,
                    shape = QuireShapes.chip,
                )
                .padding(if (selected) 2.dp else 1.dp)
                .clip(QuireShapes.chip)
                .background(palette.bg)
                .padding(horizontal = 8.dp, vertical = 9.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                // A heading, two lines of body, and the accent — the four things
                // that differ between the palettes, in the proportions a page has.
                TextLine(palette.ink, 1f, 4.dp)
                TextLine(palette.muted, 0.85f, 3.dp)
                TextLine(palette.muted, 0.6f, 3.dp)
                TextLine(palette.accent, 0.3f, 3.dp)
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = name.label(),
            color = if (selected) colors.accent else colors.muted,
            maxLines = 1,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** One line of pretend text inside a [ThemePreview]. */
@Composable
private fun TextLine(color: Color, widthFraction: Float, height: Dp) {
    Box(
        Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(QuireShapes.chip)
            .background(color),
    )
}


/**
 * Everything that hands the chosen passage to another app.
 *
 * A list rather than three more buttons on the bar, because the bar is drawn over the
 * words the reader is trying to read and because these are the only actions that can
 * be missing: keeping them together is what lets the bar be the same three buttons on
 * every phone. [SelectionMenu] holds the rule; this draws it.
 *
 * **Quire does none of these itself.** It has no `INTERNET` permission — the manifest
 * removes it and `NoNetworkPermissionTest` fails the build the day one returns — so
 * the passage goes to an app the reader already has, and that is the whole feature.
 *
 * An action nothing on the phone answers is shown greyed rather than hidden, and a
 * tap on it replaces the footer with the reason. Hiding would be tidier and it would
 * also be silent: the reader never learns the action exists, and the sheet is a
 * different sheet on every phone, so nobody can be told where anything is. The
 * explanation goes *in the sheet* rather than in a toast because a toast over a modal
 * sheet is a stack, and because the sentence belongs next to the row that was tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionMoreSheet(
    available: Set<TextHandoff>,
    onShare: () -> Unit,
    onHandoff: (TextHandoff) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Quire.colors
    var explaining by remember { mutableStateOf<String?>(null) }

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
                .padding(top = 22.dp, bottom = 26.dp),
        ) {
            SelectionMenu.overflow(available).forEach { item ->
                HandoffRow(
                    icon = when (item.action) {
                        SelectionAction.TRANSLATE -> QuireIcons.Translate
                        SelectionAction.DICTIONARY -> QuireIcons.Dictionary
                        else -> QuireIcons.Share
                    },
                    label = SelectionMenu.label(item.action),
                    enabled = item.enabled,
                    onClick = {
                        val handoff = SelectionMenu.handoff(item.action)
                        when {
                            // Never nothing. A tap that is swallowed reads as a broken
                            // app, and this is the case a developer's own phone cannot
                            // show them.
                            !item.enabled -> explaining = SelectionMenu.unavailable(item.action)
                            handoff == null -> onShare()
                            else -> onHandoff(handoff)
                        }
                    },
                )
            }

            explaining?.let { said ->
                Spacer(Modifier.height(14.dp))
                Text(
                    said,
                    color = colors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun HandoffRow(
    @DrawableRes icon: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = Quire.colors
    // Still clickable when it is not available, deliberately. A row that refuses the
    // tap has nothing to say, and "nothing happened" is the failure this whole
    // arrangement exists to avoid.
    val tint = if (enabled) colors.ink else colors.muted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(QuireShapes.button)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuireIcon(icon, contentDescription = null, tint = tint, size = QuireIcons.Size.Large)
        Text(label, color = tint, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * "Bookmark added", with a Done action.
 *
 * Dismisses itself after a moment. The handoff shows a small confirmation rather
 * than a persistent marker, which keeps the reading page uncluttered — the page is
 * meant to disappear, and a badge that stays would undo that.
 */
@Composable
fun BookmarkToast(onDone: () -> Unit, message: String = "Bookmark added") {
    val colors = Quire.colors

    LaunchedEffect(Unit) {
        delay(2_200)
        onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 130.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            modifier = Modifier
                .clip(QuireShapes.button)
                .background(colors.bgAlt)
                .padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(message, color = colors.ink, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Done",
                color = colors.accent,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(onClick = onDone),
            )
        }
    }
}
