package app.folio.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.folio.android.data.AppSettingsEntity
import app.folio.android.data.HabitRepository
import app.folio.android.share.FolioLinks
import app.folio.android.share.FolioRelease
import app.folio.android.share.SupportLink
import app.folio.android.ui.theme.Folio
import app.folio.android.ui.theme.FolioIcon
import app.folio.android.ui.theme.FolioIcons
import app.folio.android.ui.theme.FolioShapes
import app.folio.android.ui.theme.FolioThemeName
import app.folio.android.ui.theme.label

/**
 * Settings.
 *
 * Grouped cards on a soft canvas, as the handoff draws them. Deliberately short:
 * the design offers a handful of real choices rather than a preferences screen,
 * and every row here changes something a reader can see.
 */
@Composable
fun SettingsScreen(
    settings: AppSettingsEntity,
    theme: FolioThemeName,
    bookCount: Int,
    onCycleTheme: () -> Unit,
    onGoalChange: (Int) -> Unit,
    onOpenLicences: () -> Unit,
    onShowSupport: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Folio.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgAlt)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 26.dp, bottom = 120.dp),
    ) {
        Text("Settings", color = colors.ink, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))

        GroupLabel("Reading")
        Group {
            ValueRow(
                label = "Daily goal",
                value = "${settings.dailyGoalMinutes} min",
                onClick = {
                    val options = HabitRepository.GOAL_OPTIONS
                    val next = options[(options.indexOf(settings.dailyGoalMinutes) + 1)
                        .mod(options.size)]
                    onGoalChange(next)
                },
            )
            Divider()
            ValueRow(
                label = "Default theme",
                value = theme.label(),
                onClick = onCycleTheme,
            )
        }

        Spacer(Modifier.height(22.dp))
        GroupLabel("Library")
        Group {
            ValueRow(
                label = "Imported books",
                value = "$bookCount",
                onClick = null,
            )
            Divider()
            ValueRow(
                label = "Storage",
                // Folio's copy lives in app storage and goes when the app does.
                value = "On this device",
                onClick = null,
            )
        }

        Spacer(Modifier.height(22.dp))
        GroupLabel("About")
        Group {
            ValueRow(label = "Folio", value = FolioRelease.VERSION_NAME, onClick = null)
            Divider()
            ValueRow(label = "Fonts", value = "SIL OFL 1.1", onClick = onOpenLicences)
            Divider()
            // Lucide is ISC licensed, which requires the notice to travel with the
            // icons. It ships in res/raw/lucide_license.txt.
            ValueRow(label = "Icons", value = "Lucide · ISC", onClick = onOpenLicences)
            Divider()
            ValueRow(
                label = "Your books",
                // Worth stating plainly rather than burying in a privacy policy.
                value = "Never leave this device",
                onClick = null,
            )
            Divider()
            // The privacy policy. Play requires one at a public URL, and a reader who
            // wants to read it should not have to find the store listing to do it.
            //
            // Until that URL exists the row is the short answer instead of a link:
            // tapping through to a browser that shows a 404 would make Folio look
            // like it is hiding the very thing this row is for.
            if (FolioLinks.isSet(FolioLinks.PRIVACY_POLICY)) {
                ValueRow(
                    label = "Privacy policy",
                    value = FolioLinks.displayHost(FolioLinks.PRIVACY_POLICY),
                    onClick = { onOpenLink(FolioLinks.PRIVACY_POLICY) },
                )
            } else {
                ValueRow(
                    label = "Privacy",
                    value = "Nothing is collected",
                    onClick = null,
                )
            }
            if (FolioLinks.isSet(FolioLinks.WEBSITE)) {
                Divider()
                ValueRow(
                    label = "Website",
                    value = FolioLinks.displayHost(FolioLinks.WEBSITE),
                    onClick = { onOpenLink(FolioLinks.WEBSITE) },
                )
            }
            if (FolioLinks.isSet(FolioLinks.SOURCE)) {
                Divider()
                ValueRow(
                    label = "Source",
                    value = FolioLinks.displayHost(FolioLinks.SOURCE),
                    onClick = { onOpenLink(FolioLinks.SOURCE) },
                )
            }
            if (FolioLinks.isSet(FolioLinks.CONTACT_EMAIL)) {
                Divider()
                ValueRow(
                    label = "Contact",
                    value = FolioLinks.displayHost(FolioLinks.CONTACT_EMAIL),
                    onClick = {
                        onOpenLink(
                            FolioLinks.mailto(FolioLinks.CONTACT_EMAIL, "Folio on Android"),
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        GroupLabel("Support")
        Group {
            ValueRow(
                label = "Show your support",
                // Opened in the reader's browser. Folio holds no payment details and
                // declares no INTERNET permission — handing the URL to the system is
                // the whole of what happens here.
                value = "razorpay.me",
                onClick = onShowSupport,
            )
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "Made with love by ${SupportLink.AUTHOR}.",
            color = colors.muted,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(),
        color = Folio.colors.muted,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(FolioShapes.card)
            .background(Folio.colors.bg),
    ) { content() }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(start = 16.dp)
            .background(Folio.colors.border),
    )
}

@Composable
private fun ValueRow(label: String, value: String, onClick: (() -> Unit)?) {
    val colors = Folio.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = colors.ink, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = colors.muted, style = MaterialTheme.typography.bodyLarge)
            // A chevron only where tapping does something, so the row's
            // affordance matches its behaviour.
            if (onClick != null) {
                Spacer(Modifier.padding(horizontal = 4.dp))
                FolioIcon(
                    FolioIcons.Forward,
                    contentDescription = null,
                    tint = colors.muted,
                    size = FolioIcons.Size.Small,
                )
            }
        }
    }
}
