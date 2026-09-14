package app.quire.android.ui.habit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.quire.android.data.HabitSummary
import app.quire.android.notify.ReadingQuotes
import app.quire.android.ui.common.PrimaryButton
import androidx.annotation.DrawableRes
import app.quire.android.ui.theme.Quire
import app.quire.android.ui.theme.QuireIcon
import app.quire.android.ui.theme.QuireIcons
import app.quire.android.ui.theme.QuireShapes
import app.quire.core.habit.Goals
import app.quire.core.habit.Levels
import app.quire.core.habit.Milestone
import app.quire.core.habit.ReadingDay
import kotlin.math.roundToInt

/**
 * Reading streak.
 *
 * The heatmap is four weeks of real history. A day with no reading is drawn as an
 * empty cell rather than omitted, because the gaps are the honest part — a strip
 * showing only the good days would be a different, flattering chart.
 */
@Composable
fun StreakScreen(
    summary: HabitSummary,
    onContinue: () -> Unit,
    onShare: () -> Unit,
    onOpenMilestones: () -> Unit = {},
    onOpenLevel: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = Quire.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp)
            .padding(top = 40.dp, bottom = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        QuireIcon(
            QuireIcons.Streak,
            contentDescription = null,
            // A lit flame only once there is a streak to show. Drawing it in the
            // accent colour on day zero would congratulate someone for nothing.
            tint = if (summary.currentStreak > 0) colors.accent else colors.border,
            size = QuireIcons.Size.Hero,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (summary.currentStreak == 1) "1 day" else "${summary.currentStreak} days",
            color = colors.ink,
            style = MaterialTheme.typography.displayLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = streakSentence(summary),
            color = colors.muted,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )

        Spacer(Modifier.height(32.dp))
        Heatmap(
            days = summary.month(),
            goalMinutes = summary.goalMinutes,
            today = summary.today,
            restDays = summary.restDays.toSet(),
        )

        Spacer(Modifier.height(16.dp))
        Text(
            // What actually happened, in words, beside the picture of it. A run that
            // carried a rest day and said nothing about it would be a lie told by
            // omission — the one thing forgiveness here is not allowed to be.
            restSentence(summary),
            color = colors.muted,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(16.dp))
        Text(
            // The rule, stated plainly where the reader can check it against the
            // squares above. Replaces the handoff's "the streak resets, but the
            // reading doesn't", which stopped being true the day rest days shipped.
            "A run keeps going through one quiet day a week. " +
                "Only the days you read are counted.",
            color = colors.muted,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )

        if (summary.daysRead > 0) {
            Spacer(Modifier.height(16.dp))
            Text(
                // The number that never resets. A reader whose run has just ended
                // still has this one, and it is the honest total rather than the
                // fragile one — the reading survives whatever the chain does.
                if (summary.daysRead == 1) "One day of reading, all told."
                else "${summary.daysRead} days of reading, all told.",
                color = colors.ink,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Spacer(Modifier.height(26.dp))
        // The day's quotation, keyed on the same local day the reminder uses, so the
        // sentence here and the one in tonight's notification are the same sentence.
        // Every line is public domain and verified against a primary text; see
        // ReadingQuotes.
        val quote = ReadingQuotes.forDay(summary.today)
        Text(
            "“${quote.text}”",
            color = colors.muted,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            quote.author,
            color = colors.muted,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
        )

        Spacer(Modifier.height(26.dp))
        // Milestones and Level hang off the streak screen rather than the tab bar:
        // they are things you look at occasionally, not destinations.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SecondaryLink(
                "Milestones", onOpenMilestones, Modifier.weight(1f),
                icon = QuireIcons.Milestone,
            )
            SecondaryLink(
                "Level ${summary.level.index}", onOpenLevel, Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(24.dp))
        PrimaryButton("Continue", onContinue)
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(QuireShapes.button)
                .border(1.dp, colors.border, QuireShapes.button)
                .clickable(onClick = onShare)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuireIcon(
                    QuireIcons.Share,
                    contentDescription = null,
                    tint = colors.ink,
                    size = QuireIcons.Size.Small,
                )
                Text("Share Streak", color = colors.ink,
                    style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/**
 * The sentence under the number.
 *
 * Written from what actually happened. A reader on day one is not told they have
 * read every day this week — and since a run may now span a rest day, no line here
 * claims the days were consecutive either. "You've read every day this week" was
 * exactly that claim, and it is false for any run that carried one.
 */
private fun streakSentence(summary: HabitSummary): String = when {
    summary.currentStreak == 0 && summary.longestStreak == 0 ->
        "Read today to begin a run."
    summary.currentStreak == 0 ->
        "Your longest run was ${summary.longestStreak} days. Today can start the next."
    summary.currentStreak == 1 -> "You showed up today."
    else -> "${summary.currentStreak} days of reading in this run."
}

/**
 * What the run had to carry, named rather than hidden.
 *
 * A rest day is a day with no reading, and saying so is the whole difference between
 * forgiveness and a repaired number. It is stated as a fact and never as a cost:
 * nothing was spent, nothing is owed, and the figure above never counted it.
 */
private fun restSentence(summary: HabitSummary): String = when {
    summary.currentStreak == 0 -> "Every day you read is drawn above, gaps included."
    summary.restDays.isEmpty() -> "Every day of this run is a day you read."
    summary.restDays.size == 1 -> "One quiet day along the way, carried — not counted."
    else -> "${summary.restDays.size} quiet days along the way, carried — not counted."
}

/**
 * Four weeks of real history.
 *
 * The record of truth, and the reason forgiveness here cannot quietly become a lie:
 * a rest day is drawn as the empty square it is, outlined so the reader can see
 * *which* empty day the run carried. Filling it in would be the streak-freeze
 * mistake — a day nobody read, painted as a day they did.
 */
@Composable
private fun Heatmap(
    days: List<ReadingDay>,
    goalMinutes: Int,
    today: Long,
    restDays: Set<Long>,
) {
    val colors = Quire.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        days.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { day ->
                    // Intensity by how much of the goal was met, so a heavy day
                    // reads darker than a bare one — the handoff's colour-mix.
                    val ratio = if (goalMinutes <= 0) 0f
                    else (day.minutes.toFloat() / goalMinutes).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(QuireShapes.chip)
                            .background(
                                if (ratio <= 0f) colors.border.copy(alpha = 0.45f)
                                else colors.accent.copy(alpha = 0.25f + 0.75f * ratio)
                            )
                            .then(
                                when {
                                    day.epochDay == today ->
                                        Modifier.border(2.dp, colors.accent, QuireShapes.chip)
                                    // A muted outline on an empty square: the run
                                    // reached this day and nobody read. Deliberately
                                    // quieter than today's accent ring — it is a
                                    // note, not an alarm.
                                    day.epochDay in restDays ->
                                        Modifier.border(1.5.dp, colors.muted, QuireShapes.chip)
                                    else -> Modifier
                                }
                            ),
                    )
                }
            }
        }
    }
}

/** Milestones: achieved above, locked below, no counts. */
@Composable
fun MilestonesScreen(
    summary: HabitSummary,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = Quire.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 26.dp, bottom = 120.dp),
    ) {
        BackRow("Milestones", onBack)
        Spacer(Modifier.height(18.dp))

        if (summary.achieved.isNotEmpty()) {
            MilestoneGroup(summary.achieved, achieved = true)
            Spacer(Modifier.height(18.dp))
        }
        if (summary.locked.isNotEmpty()) {
            MilestoneGroup(summary.locked, achieved = false)
        }
    }
}

@Composable
private fun MilestoneGroup(milestones: List<Milestone>, achieved: Boolean) {
    val colors = Quire.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(QuireShapes.card)
            .background(colors.bgAlt),
    ) {
        milestones.forEach { milestone ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .then(
                            if (achieved) Modifier.background(colors.accent)
                            else Modifier.border(1.5.dp, colors.border, CircleShape)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (achieved) {
                        QuireIcon(
                            QuireIcons.Done,
                            // The milestone's title is the next thing read out.
                            contentDescription = null,
                            tint = colors.buttonText,
                            size = 14.dp,
                        )
                    }
                }
                Spacer(Modifier.size(14.dp))
                Column {
                    Text(
                        milestone.title,
                        color = if (achieved) colors.ink else colors.muted,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        milestone.detail,
                        color = colors.muted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** Level and XP. */
@Composable
fun LevelScreen(
    summary: HabitSummary,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = Quire.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 26.dp, bottom = 120.dp),
    ) {
        BackRow("Level ${summary.level.index} · ${summary.level.name}", onBack)
        Spacer(Modifier.height(14.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(QuireShapes.chip)
                .background(colors.border),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(summary.levelProgress.toFloat().coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(QuireShapes.chip)
                    .background(colors.accent),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = summary.nextLevel?.let {
                "${summary.xp} XP · ${it.minXp - summary.xp} to ${it.name}"
            } ?: "${summary.xp} XP · every level reached",
            color = colors.muted,
            style = MaterialTheme.typography.labelSmall,
        )

        Spacer(Modifier.height(24.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(QuireShapes.card)
                .background(colors.bgAlt),
        ) {
            Levels.all.forEach { level ->
                val current = level.index == summary.level.index
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .clip(QuireShapes.pill)
                            .background(if (current) colors.accent else Color.Transparent)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "${level.index}",
                            color = if (current) colors.buttonText else colors.muted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Text(
                        level.name,
                        color = if (current) colors.ink else colors.muted,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${level.minXp} XP",
                        color = colors.muted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/** "How much would you like to read today?" — the handoff's four options. */
@Composable
fun GoalScreen(
    selected: Int,
    onSelect: (Int) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Quire.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(horizontal = 26.dp)
            .padding(top = 60.dp, bottom = 30.dp),
    ) {
        Text(
            "How much would you like to read today?",
            color = colors.ink,
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(28.dp))

        // The presets keep their full-width rows: on first run this is the reader's
        // first decision and four clear answers beat a control they have to work out.
        // A number of their own is one row further down, where it does not compete.
        Goals.PRESETS.forEach { minutes ->
            val active = minutes == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(QuireShapes.button)
                    .background(if (active) colors.accentSoft else colors.bgAlt)
                    .then(
                        if (active) Modifier.border(1.5.dp, colors.accent, QuireShapes.button)
                        else Modifier
                    )
                    .clickable { onSelect(minutes) }
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$minutes min",
                    color = if (active) colors.accent else colors.ink,
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.weight(1f))
                if (minutes == Goals.RECOMMENDED) {
                    Text(
                        "Recommended",
                        color = colors.muted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        // The row that exists so "one of these four" is not the whole answer. It
        // shows the current number whatever it is, so a reader on seventeen minutes
        // sees seventeen rather than nothing selected.
        val custom = selected !in Goals.PRESETS
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(QuireShapes.button)
                .background(if (custom) colors.accentSoft else colors.bgAlt)
                .then(
                    if (custom) Modifier.border(1.5.dp, colors.accent, QuireShapes.button)
                    else Modifier
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GoalStep(
                "−",
                enabled = selected > Goals.MIN,
                onClick = { onSelect(Goals.decrease(selected)) },
            )
            Spacer(Modifier.weight(1f))
            Text(
                "$selected min",
                color = if (custom) colors.accent else colors.ink,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.weight(1f))
            GoalStep(
                "+",
                enabled = selected < Goals.MAX,
                onClick = { onSelect(Goals.increase(selected)) },
            )
        }

        Spacer(Modifier.weight(1f))
        PrimaryButton("Continue", onContinue)
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** "10 minutes ✓ / You showed up today." */
@Composable
fun GoalCompleteScreen(
    goalMinutes: Int,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Quire.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "$goalMinutes minutes ✓",
            color = colors.ink,
            style = MaterialTheme.typography.displayLarge,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "You showed up today.",
            color = colors.muted,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(34.dp))
        PrimaryButton("Continue", onContinue)
    }
}

/** Percentage of the daily goal met, for the Library card. */
fun HabitSummary.goalPercent(): Int =
    if (goalMinutes <= 0) 0
    else ((minutesToday.toDouble() / goalMinutes) * 100).roundToInt().coerceIn(0, 100)

/**
 * The book completion screen.
 *
 * Deliberately quiet. The handoff gives finishing a book a checkmark and a short
 * sentence rather than fanfare — the reward for reading is having read.
 */
@Composable
fun BookCompleteScreen(
    title: String,
    pages: Int,
    minutes: Int,
    onBackToLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Quire.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            QuireIcon(
                QuireIcons.Done,
                contentDescription = null,
                tint = colors.buttonText,
                size = 30.dp,
            )
        }
        Spacer(Modifier.height(22.dp))
        Text(
            "BOOK COMPLETE",
            color = colors.muted,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            color = colors.ink,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "$pages pages · $minutes minutes",
            color = colors.muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "You finished it.",
            color = colors.ink,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(30.dp))
        PrimaryButton("Back to Library", onBackToLibrary)
    }
}

/**
 * The opening screen: a headline and one action.
 *
 * No form, no carousel — the handoff is emphatic that the first thing a reader
 * sees is a sentence and a way in.
 */
@Composable
fun OnboardingScreen(onGetStarted: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Quire.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .padding(horizontal = 30.dp)
            .padding(top = 90.dp, bottom = 40.dp),
    ) {
        Text(
            "A quiet place to read.",
            color = colors.ink,
            style = MaterialTheme.typography.displayLarge,
        )
        Spacer(Modifier.weight(1f))
        PrimaryButton("Get Started", onGetStarted)
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** A minus or a plus on the goal picker, dimmed rather than removed at a limit. */
@Composable
private fun GoalStep(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = Quire.colors
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(colors.bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) colors.ink else colors.border,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun BackRow(title: String, onBack: () -> Unit) {
    val colors = Quire.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "‹",
            color = colors.ink,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.clickable(onClick = onBack),
        )
        Spacer(Modifier.size(12.dp))
        Text(title, color = colors.ink, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun SecondaryLink(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
) {
    val colors = Quire.colors
    Box(
        modifier = modifier
            .clip(QuireShapes.button)
            .border(1.dp, colors.border, QuireShapes.button)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                QuireIcon(
                    icon,
                    contentDescription = null,
                    tint = colors.ink,
                    size = QuireIcons.Size.Small,
                )
            }
            Text(label, color = colors.ink, style = MaterialTheme.typography.labelLarge)
        }
    }
}
