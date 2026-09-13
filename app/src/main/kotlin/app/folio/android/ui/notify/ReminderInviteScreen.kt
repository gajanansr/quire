package app.folio.android.ui.notify

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.folio.android.ui.FolioStrings
import app.folio.android.ui.common.PrimaryButton
import app.folio.android.ui.theme.Folio

/**
 * The one time Folio asks about notifications.
 *
 * Shown after a reading session that recorded real minutes, which is the first
 * moment there is any evidence the reader wants to come back — not on first launch,
 * where the question arrives before there is anything for it to be about and gets
 * the refusal it deserves.
 *
 * Both answers are final. "No thanks" sets the same flag as yes, so the offer is
 * made once either way; anything else would make declining a thing the reader has
 * to keep doing.
 */
@Composable
fun ReminderInviteScreen(
    time: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Folio.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(horizontal = 30.dp)
            .padding(top = 90.dp, bottom = 40.dp),
    ) {
        Text(
            FolioStrings.REMINDER_INVITE_TITLE,
            color = colors.ink,
            style = MaterialTheme.typography.displayLarge,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            FolioStrings.REMINDER_INVITE_BODY,
            color = colors.muted,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            // The offer names the time it would arrive. "We'll remind you" without
            // saying when is a blank cheque, and the reader is being asked to sign
            // it before they can see what it costs.
            "$time, every evening you haven't read.",
            color = colors.accent,
            style = MaterialTheme.typography.titleLarge,
        )

        Spacer(Modifier.weight(1f))
        PrimaryButton(FolioStrings.REMINDER_INVITE_YES, onAccept)
        Spacer(Modifier.height(6.dp))
        // Plain text rather than a second button: declining should be effortless,
        // but it should not compete with the offer for attention either way.
        Box(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onDecline)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                FolioStrings.REMINDER_INVITE_NO,
                color = colors.muted,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}
