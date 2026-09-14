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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import app.quire.android.notify.Reminders
import app.quire.android.ui.QuireStrings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.quire.android.data.AppSettingsEntity
import app.quire.core.habit.Goals
import app.quire.android.share.QuireLinks
import app.quire.android.share.QuireRelease
import app.quire.android.share.Author
import app.quire.android.ui.theme.Quire
import app.quire.android.ui.theme.QuireColors
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
    onShowAuthor: () -> Unit,
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
            GoalRow(selected = settings.dailyGoalMinutes, onSelect = onGoalChange)
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

        Spacer(Modifier.height(28.dp))
        // One line, and it is the link. The Support group that used to sit above it
        // pointed at a payment page; a whole titled section asking a reader for money
        // was also the loudest thing in Settings, which is not what this app is for.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(QuireShapes.button)
                .clickable(onClick = onShowAuthor)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Made with love by ${Author.NAME}.",
                color = colors.accent,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
            )
        }
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
 * The daily goal: the four presets, and a stepper for every other number.
 *
 * Tapping this row used to cycle 5 → 10 → 20 → 30 → 5, which meant a reader who
 * wanted fifteen minutes had no way to say so and a reader who wanted five had to
 * tap past three wrong answers to get back to it. The chips are now direct and the
 * stepper covers everything in between.
 *
 * Minus and plus rather than a slider: a slider is Material's, would need painting,
 * and cannot be aimed at a particular minute with a thumb on a phone. The step is
 * [Goals.step] — fine where a minute matters, coarse where it does not.
 */
@Composable
private fun GoalRow(selected: Int, onSelect: (Int) -> Unit) {
    val colors = Quire.colors
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                QuireStrings.DAILY_GOAL,
                color = colors.ink,
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepButton(
                    label = "−",
                    description = QuireStrings.GOAL_LESS,
                    enabled = selected > Goals.MIN,
                    onClick = { onSelect(Goals.decrease(selected)) },
                )
                Text(
                    "$selected min",
                    color = colors.accent,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        // Wide enough that the row does not jump between "5 min"
                        // and "120 min" as the reader steps through it.
                        .widthIn(min = 72.dp)
                        .padding(horizontal = 4.dp),
                    textAlign = TextAlign.Center,
                )
                StepButton(
                    label = "+",
                    description = QuireStrings.GOAL_MORE,
                    enabled = selected < Goals.MAX,
                    onClick = { onSelect(Goals.increase(selected)) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Goals.PRESETS.forEach { minutes ->
                val active = minutes == selected
                Box(
                    Modifier
                        .clip(QuireShapes.chip)
                        .background(if (active) colors.accentSoft else colors.bgAlt)
                        .then(
                            if (active) Modifier.border(1.5.dp, colors.accent, QuireShapes.chip)
                            else Modifier
                        )
                        .clickable { onSelect(minutes) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Text(
                        "$minutes min",
                        color = if (active) colors.accent else colors.muted,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

/** A minus or a plus, drawn from the palette and dimmed rather than hidden at a limit. */
@Composable
private fun StepButton(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = Quire.colors
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.bgAlt)
            // Dimmed and inert rather than removed: a control that disappears at the
            // end of a range moves everything beside it, and the reader loses the
            // button they were aiming at.
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) colors.ink else colors.border,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

/**
 * The reminder time: eight one-tap chips, and a clock for every other minute.
 *
 * The chips were the only way to set this and are now a shortcut — they cover a
 * commute, a lunch break, an evening and a bedtime. "Choose a time" opens a real
 * dial, because the reader who wants 21:40 was previously told they could not
 * have it.
 */
@Composable
private fun TimeRow(selected: Int, use24Hour: Boolean, onSelect: (Int) -> Unit) {
    val colors = Quire.colors
    var picking by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                QuireStrings.REMINDER_TIME,
                color = colors.ink,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                Reminders.formatTime(selected, use24Hour),
                color = colors.accent,
                style = MaterialTheme.typography.titleMedium,
            )
        }
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
            // Last in the same wrap, so it reads as one more way to answer the same
            // question rather than as a separate setting.
            Box(
                Modifier
                    .clip(QuireShapes.chip)
                    .border(1.dp, colors.border, QuireShapes.chip)
                    .clickable { picking = true }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(
                    QuireStrings.REMINDER_TIME_CHOOSE,
                    color = colors.ink,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }

    if (picking) {
        QuireTimePicker(
            minuteOfDay = selected,
            use24Hour = use24Hour,
            onDismiss = { picking = false },
            onConfirm = { picking = false; onSelect(it) },
        )
    }
}

/**
 * The fourteen colours Material's clock is painted with.
 *
 * A plain data class rather than Material's own `TimePickerColors` because that one
 * can only be built inside a composition, and the rule it carries — that not one of
 * these fourteen comes from Material's default scheme — is only checkable as an
 * assertion. `ClockColorsTest` holds it against all five palettes.
 */
data class QuireClockColors(
    val clockDial: Color,
    val selector: Color,
    val container: Color,
    val periodSelectorBorder: Color,
    val clockDialSelectedContent: Color,
    val clockDialUnselectedContent: Color,
    val periodSelectorSelectedContainer: Color,
    val periodSelectorUnselectedContainer: Color,
    val periodSelectorSelectedContent: Color,
    val periodSelectorUnselectedContent: Color,
    val timeSelectorSelectedContainer: Color,
    val timeSelectorUnselectedContainer: Color,
    val timeSelectorSelectedContent: Color,
    val timeSelectorUnselectedContent: Color,
) {
    /** Every colour, for a test that wants to walk them without naming each one. */
    fun all(): List<Color> = listOf(
        clockDial, selector, container, periodSelectorBorder,
        clockDialSelectedContent, clockDialUnselectedContent,
        periodSelectorSelectedContainer, periodSelectorUnselectedContainer,
        periodSelectorSelectedContent, periodSelectorUnselectedContent,
        timeSelectorSelectedContainer, timeSelectorUnselectedContainer,
        timeSelectorSelectedContent, timeSelectorUnselectedContent,
    )
}

/**
 * Quire's palette, mapped onto the clock.
 *
 * Every value is a token from [QuireColors]. Left to itself Material paints this
 * control from its own scheme — the exit dialog arrived in lavender for exactly that
 * reason — and a clock in Material purple on the E-ink page would be the one surface
 * in Quire that ignores the theme the reader picked.
 *
 * The selected number sits on the accent and the unselected on the dial, which is
 * what the contrast test measures: on E-ink and Sepia these pairs are the ones that
 * get close, and a dial nobody can read is not a picker.
 */
fun quireClockColors(colors: QuireColors) = QuireClockColors(
    clockDial = colors.bgAlt,
    selector = colors.accent,
    container = colors.bg,
    periodSelectorBorder = colors.border,
    clockDialSelectedContent = colors.buttonText,
    clockDialUnselectedContent = colors.ink,
    periodSelectorSelectedContainer = colors.accentSoft,
    periodSelectorUnselectedContainer = colors.bg,
    periodSelectorSelectedContent = colors.accent,
    periodSelectorUnselectedContent = colors.muted,
    timeSelectorSelectedContainer = colors.accentSoft,
    timeSelectorUnselectedContainer = colors.bgAlt,
    timeSelectorSelectedContent = colors.accent,
    timeSelectorUnselectedContent = colors.ink,
)

/**
 * A clock face for picking the reminder time.
 *
 * Material3's own `TimePickerDialog` was the obvious choice and is not used: it
 * draws its own title, its own mode toggle and its own buttons from `MaterialTheme`,
 * so the frame around the dial would arrive in the platform's colours even with the
 * dial itself corrected. A plain `Dialog` with Quire's own surface and Quire's own
 * two words is less code and is the app's.
 *
 * [use24Hour] comes from the system setting rather than a locale guess, so a phone
 * that shows 20:00 is handed a twenty-four hour dial with no am/pm toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuireTimePicker(
    minuteOfDay: Int,
    use24Hour: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val colors = Quire.colors
    val state = rememberTimePickerState(
        initialHour = Reminders.hourOf(minuteOfDay),
        initialMinute = Reminders.minuteOf(minuteOfDay),
        is24Hour = use24Hour,
    )
    val clock = quireClockColors(colors)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(QuireShapes.card)
                .background(colors.bgAlt)
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                QuireStrings.REMINDER_TIME,
                color = colors.ink,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(18.dp))
            TimePicker(
                state = state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = clock.clockDial,
                    selectorColor = clock.selector,
                    containerColor = clock.container,
                    periodSelectorBorderColor = clock.periodSelectorBorder,
                    clockDialSelectedContentColor = clock.clockDialSelectedContent,
                    clockDialUnselectedContentColor = clock.clockDialUnselectedContent,
                    periodSelectorSelectedContainerColor = clock.periodSelectorSelectedContainer,
                    periodSelectorUnselectedContainerColor = clock.periodSelectorUnselectedContainer,
                    periodSelectorSelectedContentColor = clock.periodSelectorSelectedContent,
                    periodSelectorUnselectedContentColor = clock.periodSelectorUnselectedContent,
                    timeSelectorSelectedContainerColor = clock.timeSelectorSelectedContainer,
                    timeSelectorUnselectedContainerColor = clock.timeSelectorUnselectedContainer,
                    timeSelectorSelectedContentColor = clock.timeSelectorSelectedContent,
                    timeSelectorUnselectedContentColor = clock.timeSelectorUnselectedContent,
                ),
            )
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    QuireStrings.CANCEL,
                    color = colors.muted,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(QuireShapes.chip)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    QuireStrings.SET,
                    color = colors.accent,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(QuireShapes.chip)
                        .clickable {
                            onConfirm(Reminders.minuteOfDay(state.hour, state.minute))
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
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
