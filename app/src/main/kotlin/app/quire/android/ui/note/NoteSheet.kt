package app.quire.android.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.quire.android.ui.QuireStrings
import app.quire.android.ui.theme.Quire
import app.quire.android.ui.theme.QuireShapes

/**
 * Where a reader writes, reads and changes their own words about a passage.
 *
 * One sheet for all three, and for both the routes in: a passage chosen in the Reader
 * and a mark opened from the Bookmarks list. Two sheets would be two places to get
 * the saving rules wrong, and those rules are the whole of not losing what somebody
 * wrote.
 *
 * The passage sits above the field, in muted, because a note needs something to be
 * about — and because the Bookmarks route arrives with no page on screen to look at.
 *
 * Every decision here is [NoteEdit]'s rather than this file's: whether Save does
 * anything, whether saving writes or clears, and whether Delete exists at all. There
 * is no Compose test dependency in this project, so a branch written inside a
 * composable is a branch nothing checks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteSheet(
    draft: NoteDraft,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Quire.colors
    val canSave = NoteEdit.canSave(draft)

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
                // The keyboard covers the bottom of a sheet, and the bottom of this
                // one is where Save is. Without this the reader types a paragraph and
                // has nowhere to put it.
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 22.dp, bottom = 26.dp),
        ) {
            Text(
                QuireStrings.NOTE_TITLE,
                color = colors.muted,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(10.dp))

            // The passage, quoted. Three lines at most: it is here to say what the
            // note is about, and a sheet that shows a whole paragraph of the book has
            // no room left for the reader's own words.
            Text(
                draft.snippet.ifBlank { QuireStrings.NOTE_HINT },
                color = colors.muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .clip(QuireShapes.button)
                    .background(colors.bg)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                BasicTextField(
                    value = draft.text,
                    onValueChange = onTextChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { field ->
                        if (draft.text.isEmpty()) {
                            Text(
                                QuireStrings.NOTE_HINT,
                                color = colors.muted,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        field()
                    },
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Only ever offered on a note that exists. A Delete on a blank sheet
                // is a control that cannot do anything, which is the same fault as a
                // dead action on the selection bar.
                if (NoteEdit.canDelete(draft)) {
                    Text(
                        QuireStrings.NOTE_DELETE,
                        color = colors.muted,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(QuireShapes.pill)
                            .clickable(onClick = onDelete)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    QuireStrings.NOTE_CANCEL,
                    color = colors.muted,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(QuireShapes.pill)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                // Live exactly when saving would do something. A Save that works on an
                // unchanged sheet teaches the reader it means nothing; one that is
                // dead on a changed sheet loses what they typed.
                Text(
                    QuireStrings.NOTE_SAVE,
                    color = if (canSave) colors.accent else colors.muted,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(QuireShapes.pill)
                        .clickable(enabled = canSave, onClick = onSave)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}
