package app.folio.android.ui.reader

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.folio.android.ui.theme.Folio
import app.folio.android.ui.theme.FolioPalettes
import app.folio.android.ui.theme.FolioShapes
import app.folio.android.ui.theme.FolioThemeName
import app.folio.android.ui.theme.ReaderFont
import app.folio.core.model.ChapterRef
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
    theme: FolioThemeName,
    onFontChange: (ReaderFont) -> Unit,
    onSizeChange: (Float) -> Unit,
    onJustifyChange: (Boolean) -> Unit,
    onThemeChange: (FolioThemeName) -> Unit,
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FolioThemeName.entries.forEach { name ->
                    ThemeSwatch(name = name, selected = name == theme) { onThemeChange(name) }
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
                            .clip(FolioShapes.button)
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
    Text(text, color = Folio.colors.muted, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun ChoiceTile(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = Folio.colors
    Box(
        modifier = modifier
            .clip(FolioShapes.button)
            .background(if (selected) colors.accentSoft else colors.bg)
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
    val colors = Folio.colors
    Box(
        modifier = Modifier
            .size(width = 66.dp, height = 46.dp)
            .clip(FolioShapes.button)
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

@Composable
private fun ThemeSwatch(name: FolioThemeName, selected: Boolean, onClick: () -> Unit) {
    val colors = Folio.colors
    val palette = FolioPalettes.of(name)
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(palette.bg)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) colors.accent else colors.border,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // A letter rather than a colour alone: Light and E-ink share a palette, so
        // swatch colour cannot distinguish them.
        Text(
            text = name.name.take(1),
            color = palette.ink,
            style = MaterialTheme.typography.labelSmall,
        )
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
fun BookmarkToast(onDone: () -> Unit) {
    val colors = Folio.colors

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
                .clip(FolioShapes.button)
                .background(colors.bgAlt)
                .padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Bookmark added", color = colors.ink, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Done",
                color = colors.accent,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(onClick = onDone),
            )
        }
    }
}
