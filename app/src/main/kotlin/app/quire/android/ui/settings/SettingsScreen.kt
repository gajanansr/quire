package app.quire.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import app.quire.android.notify.Reminders
import app.quire.android.ui.QuireStrings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.quire.android.data.AppSettingsEntity
import app.quire.android.data.HabitRepository
import app.quire.android.share.QuireLinks
import app.quire.android.share.QuireRelease
import app.quire.android.share.SupportLink
import app.quire.android.ui.theme.Quire
import app.quire.android.ui.theme.QuireIcon
import app.quire.android.ui.theme.QuireIcons
import app.quire.android.ui.theme.QuireShapes
import app.quire.android.ui.theme.QuireThemeName
import app.quire.android.ui.theme.label

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
    theme: QuireThemeName,
    bookCount: Int,
    onCycleTheme: () -> Unit,
    onGoalChange: (Int) -> Unit,
    onOpenLicences: () -> Unit,
    onShowSupport: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Whether Android will actually deliver anything Quire posts. */
    canPostNotifications: Boolean = true,
    use24HourClock: Boolean = false,
    onRemindersChange: (Boolean) -> Unit = {},
    onReminderTimeChange: (Int) -> Unit = {},
    onReminderKindsChange: (Boolean, Boolean) -> Unit = { _, _ -> },
    onOpenNotificationSettings: () -> Unit = {},
) {
    val colors = Quire.colors

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
        GroupLabel("Reminders")
        Group {
            ToggleRow(
                label = QuireStrings.REMINDERS,
                // The line under the switch is the only place a reader can find out
                // that Android is refusing to deliver. Without it the switch says
                // "on" and nothing arrives, which is unanswerable from inside the
                // app.
                status = QuireStrings.reminderStatus(
                    settings.remindersEnabled, canPostNotifications,
                ),
                checked = settings.remindersEnabled,
                onCheckedChange = onRemindersChange,
            )

            // Everything below only exists while reminders do. Controls for
            // something switched off are clutter the reader has to read past to
            // find the switch that turns it on.
            if (settings.remindersEnabled) {
                Divider()
                if (!canPostNotifications) {
                    // The way out, since Quire cannot undo this itself. The row
                    // above has already said what is wrong.
                    ValueRow(
                        label = "Open notification settings",
                        value = "",
                        onClick = onOpenNotificationSettings,
                    )
                } else {
                    TimeRow(
                        selected = settings.reminderMinuteOfDay,
                        use24Hour = use24HourClock,
                        onSelect = onReminderTimeChange,
                    )
                    Divider()
                    ToggleRow(
                        label = QuireStrings.REMINDER_DAILY,
                        checked = settings.dailyReminderEnabled,
                        onCheckedChange = {
                            onReminderKindsChange(it, settings.streakReminderEnabled)
                        },
                    )
                    Divider()
                    ToggleRow(
                        label = QuireStrings.REMINDER_STREAK,
                        checked = settings.streakReminderEnabled,
                        onCheckedChange = {
                            onReminderKindsChange(settings.dailyReminderEnabled, it)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            // The promise, where the switch is, rather than only in the invitation
            // the reader saw once weeks ago.
            QuireStrings.REMINDER_CHANNEL_EXPLAINER,
            color = colors.muted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

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
                // Quire's copy lives in app storage and goes when the app does.
                value = "On this device",
                onClick = null,
            )
        }

        Spacer(Modifier.height(22.dp))
        GroupLabel("About")
        Group {
            ValueRow(label = "Quire", value = QuireRelease.VERSION_NAME, onClick = null)
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
            // tapping through to a browser that shows a 404 would make Quire look
            // like it is hiding the very thing this row is for.
            if (QuireLinks.isSet(QuireLinks.PRIVACY_POLICY)) {
                ValueRow(
                    label = "Privacy policy",
                    value = QuireLinks.displayHost(QuireLinks.PRIVACY_POLICY),
                    onClick = { onOpenLink(QuireLinks.PRIVACY_POLICY) },
                )
            } else {
                ValueRow(
                    label = "Privacy",
                    value = "Nothing is collected",
                    onClick = null,
                )
            }
            if (QuireLinks.isSet(QuireLinks.WEBSITE)) {
                Divider()
                ValueRow(
                    label = "Website",
                    value = QuireLinks.displayHost(QuireLinks.WEBSITE),
                    onClick = { onOpenLink(QuireLinks.WEBSITE) },
                )
            }
            if (QuireLinks.isSet(QuireLinks.SOURCE)) {
                Divider()
                ValueRow(
                    label = "Source",
                    value = QuireLinks.displayHost(QuireLinks.SOURCE),
                    onClick = { onOpenLink(QuireLinks.SOURCE) },
                )
            }
            if (QuireLinks.isSet(QuireLinks.CONTACT_EMAIL)) {
                Divider()
                ValueRow(
                    label = "Contact",
                    value = QuireLinks.displayHost(QuireLinks.CONTACT_EMAIL),
                    onClick = {
                        onOpenLink(
                            QuireLinks.mailto(QuireLinks.CONTACT_EMAIL, "Quire on Android"),
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
                // Opened in the reader's browser. Quire holds no payment details and
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
        color = Quire.colors.muted,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(QuireShapes.card)
            .background(Quire.colors.bg),
    ) { content() }
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(start = 16.dp)
            .background(Quire.colors.border),
    )
}

/**
 * A switch, drawn from the Quire palette.
 *
 * Material's own `Switch` is the obvious choice and is wrong here for the reason the
 * exit dialog was: it arrives wearing the platform's colours, and it would be the
 * one control in Settings that ignores the theme the reader picked. This is a track
 * and a knob, which is all a switch is.
 */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    status: String? = null,
) {
    val colors = Quire.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row, not just the switch: a 50dp target beats a 20dp one,
            // and the label is what the reader is actually aiming at.
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.ink, style = MaterialTheme.typography.bodyLarge)
            if (status != null) {
                Text(status, color = colors.muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        Box(
            Modifier
                .size(width = 44.dp, height = 26.dp)
                .clip(CircleShape)
                .background(if (checked) colors.accent else colors.border),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(colors.bg),
            )
        }
    }
}

/**
 * The eight times on offer, as chips.
 *
 * Wrapped rather than scrolled: eight things the reader can see at once beats eight
 * they have to go looking for, and a horizontal scroller inside a vertical one is a
 * gesture conflict for no gain.
 */
@Composable
private fun TimeRow(selected: Int, use24Hour: Boolean, onSelect: (Int) -> Unit) {
    val colors = Quire.colors
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            QuireStrings.REMINDER_TIME,
            color = colors.ink,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Reminders.TIME_OPTIONS.forEach { minute ->
                val active = minute == selected
                Box(
                    Modifier
                        .clip(QuireShapes.chip)
                        .background(if (active) colors.accentSoft else colors.bgAlt)
                        .then(
                            if (active) Modifier.border(1.5.dp, colors.accent, QuireShapes.chip)
                            else Modifier
                        )
                        .clickable { onSelect(minute) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Text(
                        Reminders.formatTime(minute, use24Hour),
                        color = if (active) colors.accent else colors.muted,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String, onClick: (() -> Unit)?) {
    val colors = Quire.colors
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
