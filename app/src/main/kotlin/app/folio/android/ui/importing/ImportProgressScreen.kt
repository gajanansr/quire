package app.folio.android.ui.importing

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.folio.android.ui.FolioStrings
import app.folio.android.ui.common.PrimaryButton
import app.folio.android.ui.theme.Folio
import app.folio.android.ui.theme.FolioShapes
import app.folio.android.work.ImportProgressStore

/** The stages the user is shown, in the order the handoff shows them. */
enum class ImportStep(val label: String) {
    EXTRACTING(FolioStrings.EXTRACTING_TEXT),
    DETECTING(FolioStrings.DETECTING_CHAPTERS),
    ;

    companion object {
        /**
         * Which designed steps are complete at a given pipeline stage.
         *
         * The pipeline has nine states; the design shows two rows. Mapping rather
         * than exposing the pipeline keeps the promise that no machinery reaches
         * the user.
         */
        fun completedAt(stage: String): Set<ImportStep> = when (stage) {
            ImportProgressStore.STAGE_IMPORTING,
            ImportProgressStore.STAGE_DETECTING_FORMAT,
            ImportProgressStore.STAGE_EXTRACTING,
            ImportProgressStore.STAGE_OCR,
            -> emptySet()

            ImportProgressStore.STAGE_DETECTING_STRUCTURE -> setOf(EXTRACTING)

            ImportProgressStore.STAGE_NORMALIZING,
            ImportProgressStore.STAGE_READY,
            -> setOf(EXTRACTING, DETECTING)

            else -> emptySet()
        }
    }
}

/**
 * "Preparing your book…" through to "Your book is ready."
 *
 * Deliberately vague about what is happening. Whether a book took the text path or
 * spent two minutes in OCR is Folio's problem, not the reader's, and the brief is
 * explicit that no unnecessary technical terminology should surface.
 */
@Composable
fun ImportProgressScreen(
    stage: String,
    pagesDone: Int,
    pagesTotal: Int,
    onReadNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Folio.colors
    val ready = stage == ImportProgressStore.STAGE_READY
    val completed = ImportStep.completedAt(stage)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (ready) FolioStrings.BOOK_READY else FolioStrings.PREPARING,
            color = colors.ink,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(28.dp))

        ImportStep.entries.forEach { step ->
            StepRow(label = step.label, done = step in completed)
            Spacer(Modifier.height(12.dp))
        }

        // A long OCR pass is the one case where silence would feel like a hang, so
        // it gets a page count — still without naming the technique.
        if (pagesTotal > 0 && !ready) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Page $pagesDone of $pagesTotal",
                color = colors.muted,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(10.dp))
            val fraction by animateFloatAsState(
                targetValue = pagesDone.toFloat() / pagesTotal.coerceAtLeast(1),
                label = "importProgress",
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(FolioShapes.chip)
                    .background(colors.border),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .height(4.dp)
                        .clip(FolioShapes.chip)
                        .background(colors.accent),
                )
            }
        }

        if (ready) {
            Spacer(Modifier.height(28.dp))
            PrimaryButton(FolioStrings.READ_NOW, onReadNow)
        }
    }
}

@Composable
private fun StepRow(label: String, done: Boolean) {
    val colors = Folio.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .then(
                    if (done) Modifier.background(colors.accent)
                    else Modifier.border(1.5.dp, colors.border, CircleShape)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Text(
                    "✓",
                    color = colors.buttonText,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Text(
            text = label,
            color = if (done) colors.ink else colors.muted,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
