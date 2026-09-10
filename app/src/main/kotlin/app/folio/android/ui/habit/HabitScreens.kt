package app.folio.android.ui.habit

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
import app.folio.android.data.HabitRepository
import app.folio.android.data.HabitSummary
import app.folio.android.ui.common.PrimaryButton
import app.folio.android.ui.theme.Folio
import app.folio.android.ui.theme.FolioShapes
import app.folio.core.habit.Levels
import app.folio.core.habit.Milestone
import app.folio.core.habit.ReadingDay
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
    modifier: Modifier = Modifier,
) {
    val colors = Folio.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp)
            .padding(top = 40.dp, bottom = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
        Heatmap(days = summary.month(), goalMinutes = summary.goalMinutes, today = summary.today)

        Spacer(Modifier.height(28.dp))
        Text(
            // The handoff's gentle miss-a-day line. It matters that this is not a
            // warning: the product is meant to encourage returning, not to punish.
            "If you ever miss a day — the streak resets, but the reading doesn't.",
            color = colors.muted,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(30.dp))
        PrimaryButton("Continue", onContinue)
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(FolioShapes.button)
                .border(1.dp, colors.border, FolioShapes.button)
                .clickable(onClick = onShare)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Share Streak", color = colors.ink, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/**
 * The sentence under the number.
 *
 * Written from what actually happened. A reader on day one is not told they have
 * read every day this week.
 */
private fun streakSentence(summary: HabitSummary): String = when {
    summary.currentStreak == 0 && summary.longestStreak == 0 ->
        "Read today to begin a streak."
    summary.currentStreak == 0 ->
        "Your longest run was ${summary.longestStreak} days. Today can start the next."
    summary.currentStreak >= 7 -> "You've read every day this week."
    summary.currentStreak == 1 -> "You showed up today."
    else -> "You've read ${summary.currentStreak} days running."
}

@Composable
private fun Heatmap(days: List<ReadingDay>, goalMinutes: Int, today: Long) {
    val colors = Folio.colors
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
                            .clip(FolioShapes.chip)
                            .background(
                                if (ratio <= 0f) colors.border.copy(alpha = 0.45f)
                                else colors.accent.copy(alpha = 0.25f + 0.75f * ratio)
                            )
                            .then(
                                if (day.epochDay == today)
                                    Modifier.border(2.dp, colors.accent, FolioShapes.chip)
                                else Modifier
                            ),
                    )
                }
            }
        }
    }
}

/** Milestones: achieved above, locked below, no counts. */
@Composable
fun MilestonesScreen(summary: HabitSummary, modifier: Modifier = Modifier) {
    val colors = Folio.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 26.dp, bottom = 120.dp),
    ) {
        Text("Milestones", color = colors.ink, style = MaterialTheme.typography.headlineMedium)
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
    val colors = Folio.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(FolioShapes.card)
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
                        Text("✓", color = colors.buttonText,
                            style = MaterialTheme.typography.labelSmall)
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
fun LevelScreen(summary: HabitSummary, modifier: Modifier = Modifier) {
    val colors = Folio.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 26.dp, bottom = 120.dp),
    ) {
        Text(
            "Level ${summary.level.index} · ${summary.level.name}",
            color = colors.ink,
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(14.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(FolioShapes.chip)
                .background(colors.border),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(summary.levelProgress.toFloat().coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(FolioShapes.chip)
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
                .clip(FolioShapes.card)
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
                            .clip(FolioShapes.pill)
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
    val colors = Folio.colors
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

        HabitRepository.GOAL_OPTIONS.forEach { minutes ->
            val active = minutes == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(FolioShapes.button)
                    .background(if (active) colors.accentSoft else colors.bgAlt)
                    .then(
                        if (active) Modifier.border(1.5.dp, colors.accent, FolioShapes.button)
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
                if (minutes == HabitRepository.RECOMMENDED_GOAL) {
                    Text(
                        "Recommended",
                        color = colors.muted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
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
    val colors = Folio.colors
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
