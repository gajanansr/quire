package app.quire.android.ui.common

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
import app.quire.android.ui.QuireStrings
import androidx.annotation.DrawableRes
import app.quire.android.ui.theme.Quire
import app.quire.android.ui.theme.QuireIcon
import app.quire.android.ui.theme.QuireIcons
import app.quire.android.ui.theme.QuireShapes
import app.quire.core.model.FailureReason

/** The designed empty state: a centred glyph, a line, an explainer, one action. */
@Composable
fun EmptyState(
    title: String,
    hint: String,
    actionLabel: String? = null,
    @DrawableRes actionIcon: Int? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = Quire.colors
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        QuireIcon(
            QuireIcons.Book,
            // Decorative: the headline underneath says what the state is.
            contentDescription = null,
            tint = colors.border,
            size = QuireIcons.Size.Hero,
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
            PrimaryButton(actionLabel, onAction, icon = actionIcon)
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
    val colors = Quire.colors
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(QuireShapes.chip)
                .background(colors.errorBg),
            contentAlignment = Alignment.Center,
        ) {
            Text("!", color = colors.errorText, style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(22.dp))
        Text(
            QuireStrings.ERROR_TITLE,
            color = colors.ink,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            QuireStrings.explain(reason),
            color = colors.muted,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(26.dp))
        PrimaryButton(QuireStrings.TRY_ANOTHER_FILE, onTryAnother)
        if (onReadOriginal != null) {
            Spacer(Modifier.height(10.dp))
            SecondaryButton(QuireStrings.READ_ORIGINAL_PDF, onReadOriginal)
        }
    }
}

@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
) {
    val colors = Quire.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(QuireShapes.button)
            .background(colors.buttonBg)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                QuireIcon(
                    icon,
                    // The label beside it says what the button does.
                    contentDescription = null,
                    tint = colors.buttonText,
                    size = QuireIcons.Size.Small,
                )
            }
            Text(label, color = colors.buttonText,
                style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Quire.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(QuireShapes.button)
            .border(1.dp, colors.border, QuireShapes.button)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = colors.ink, style = MaterialTheme.typography.labelLarge)
    }
}
