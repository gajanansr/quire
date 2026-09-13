package app.quire.android.ui.importing

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.quire.android.ui.QuireStrings
import app.quire.android.ui.theme.Quire
import app.quire.android.ui.theme.QuireIcon
import app.quire.android.ui.theme.QuireIcons
import app.quire.android.ui.theme.QuireShapes

/**
 * The Add Book sheet.
 *
 * One row, as the handoff specifies. Resisting the urge to add a second source is
 * the point: the design is deliberately a single obvious action.
 */
// ModalBottomSheet is still marked experimental in Material3. Opting in explicitly
// here, at the one place it is used, keeps the surface area of that risk visible.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddBookSheet(
    onChooseFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Quire.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgAlt,
        shape = QuireShapes.sheet,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 22.dp, bottom = 28.dp),
        ) {
            Text(
                QuireStrings.ADD_BOOK_TITLE,
                color = colors.ink,
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(QuireShapes.button)
                    .background(colors.bg)
                    .clickable(onClick = onChooseFile)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(QuireShapes.chip)
                        .background(colors.accentSoft),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        QuireStrings.CHOOSE_FROM_FILES,
                        color = colors.ink,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        QuireStrings.SUPPORTED_FORMATS,
                        color = colors.muted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                QuireIcon(
                    QuireIcons.Forward,
                    contentDescription = null,
                    tint = colors.muted,
                    size = QuireIcons.Size.Small,
                )
            }
        }
    }
}
