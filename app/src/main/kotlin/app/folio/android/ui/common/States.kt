package app.folio.android.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.folio.android.ui.FolioStrings
import app.folio.android.ui.theme.Folio
import app.folio.android.ui.theme.FolioShapes
import app.folio.core.model.FailureReason

/** The designed empty state: a centred glyph, a line, an explainer, one action. */
@Composable
fun EmptyState(
    title: String,
    hint: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = Folio.colors
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(width = 44.dp, height = 58.dp)
                .border(1.5.dp, colors.border, FolioShapes.chip)
        )
        Spacer(Modifier.height(22.dp))
        Text(
            title,
            color = colors.ink,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            hint,
            color = colors.muted,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(26.dp))
            PrimaryButton(actionLabel, onAction)
        }
    }
}

/**
 * The designed error state.
 *
 * One sentence the user can understand, an explainer, and a way forward. When a
 * partial result exists — a PDF whose reflow was not confident — the fallback to the
 * original file is offered rather than rejecting the book outright, per the brief.
 */
@Composable
fun ErrorState(
    reason: FailureReason,
    onTryAnother: () -> Unit,
    onReadOriginal: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = Folio.colors
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(FolioShapes.chip)
                .background(colors.errorBg),
            contentAlignment = Alignment.Center,
        ) {
            Text("!", color = colors.errorText, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(22.dp))
        Text(
            FolioStrings.ERROR_TITLE,
            color = colors.ink,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            FolioStrings.explain(reason),
            color = colors.muted,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(26.dp))
        PrimaryButton(FolioStrings.TRY_ANOTHER_FILE, onTryAnother)
        if (onReadOriginal != null) {
            Spacer(Modifier.height(10.dp))
            SecondaryButton(FolioStrings.READ_ORIGINAL_PDF, onReadOriginal)
        }
    }
}

@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Folio.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(FolioShapes.button)
            .background(colors.buttonBg)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = colors.buttonText, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Folio.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(FolioShapes.button)
            .border(1.dp, colors.border, FolioShapes.button)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = colors.ink, style = MaterialTheme.typography.labelLarge)
    }
}
