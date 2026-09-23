package com.androidengineers.agent_quickstart_android.fillerfree.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.MetricTrend
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.ProgressSummary
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.TrendDirection
import com.androidengineers.agent_quickstart_android.fillerfree.presentation.theme.FillerFreeColors
import com.androidengineers.agent_quickstart_android.fillerfree.presentation.theme.FillerFreeType

/**
 * Cross-session progress read — this is what turns individual sessions
 * into a coaching relationship: streaks, trend direction on the metrics
 * that matter, and the one habit worth targeting next. Reachable from the
 * session-end summary screen.
 */
@Composable
fun ProgressScreen(
    modifier: Modifier = Modifier,
    summary: ProgressSummary?,
    onBack: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FillerFreeColors.background)
            .padding(24.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Your progress",
                style = FillerFreeType.screenTitle,
                color = FillerFreeColors.textPrimary,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (summary == null || summary.totalSessions == 0) {
                    EmptyProgressState()
                } else {
                    HeadlineCard(headline = summary.headline)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatBlock(
                            label = "SESSIONS",
                            value = summary.totalSessions.toString(),
                            modifier = Modifier.weight(1f),
                        )
                        StatBlock(
                            label = "STREAK",
                            value = if (summary.currentStreakDays > 0) "${summary.currentStreakDays}d" else "—",
                            modifier = Modifier.weight(1f),
                            accent = summary.currentStreakDays >= 2,
                        )
                    }

                    TrendCard(trend = summary.issuesPerMinuteTrend)
                    TrendCard(trend = summary.interruptionsPerMinuteTrend)

                    summary.recurringHabit?.let { habit ->
                        RecurringHabitCard(habit = habit)
                    }
                }
            }

            Button(
                onClick = onBack,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FillerFreeColors.surfaceRaised,
                    contentColor = FillerFreeColors.textPrimary,
                ),
                contentPadding = PaddingValues(vertical = 16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                Text(text = "Back", style = FillerFreeType.interruptionLine)
            }
        }
    }
}

@Composable
private fun EmptyProgressState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(FillerFreeColors.surface)
            .padding(20.dp),
    ) {
        Text(
            text = "Finish your first session to start tracking progress here.",
            style = FillerFreeType.body,
            color = FillerFreeColors.textMuted,
        )
    }
}

@Composable
private fun HeadlineCard(headline: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(FillerFreeColors.surfaceRaised)
            .padding(20.dp),
    ) {
        Column {
            Text(
                text = "COACH'S READ",
                style = FillerFreeType.counterLabel,
                color = FillerFreeColors.signalAmber,
            )
            Text(
                text = headline,
                style = FillerFreeType.body,
                color = FillerFreeColors.textPrimary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun StatBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(FillerFreeColors.surface)
            .padding(vertical = 14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = FillerFreeType.counterNumber,
                color = if (accent) FillerFreeColors.signalGreen else FillerFreeColors.textPrimary,
            )
            Text(
                text = label,
                style = FillerFreeType.counterLabel,
                color = FillerFreeColors.textMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun TrendCard(trend: MetricTrend) {
    val (dotColor, directionLabel) = when (trend.direction) {
        TrendDirection.IMPROVING -> FillerFreeColors.signalGreen to "Improving"
        TrendDirection.WORSENING -> FillerFreeColors.signalAmber to "Worth watching"
        TrendDirection.STEADY -> FillerFreeColors.textSecondary to "Steady"
        TrendDirection.NOT_ENOUGH_DATA -> FillerFreeColors.textMuted to "Not enough data"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(FillerFreeColors.surface)
            .padding(16.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = trend.label.uppercase(),
                    style = FillerFreeType.counterLabel,
                    color = FillerFreeColors.textMuted,
                )
                Text(
                    text = directionLabel,
                    style = FillerFreeType.counterLabel,
                    color = dotColor,
                )
            }
            Text(
                text = "%.1f".format(trend.latestValue),
                style = FillerFreeType.counterNumber,
                color = FillerFreeColors.textPrimary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = trend.changeDescription,
                style = FillerFreeType.counterLabel,
                color = FillerFreeColors.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun RecurringHabitCard(habit: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(FillerFreeColors.surface)
            .padding(16.dp),
    ) {
        Column {
            Text(
                text = "RECURRING HABIT",
                style = FillerFreeType.counterLabel,
                color = FillerFreeColors.signalRed,
            )
            Text(
                text = "\"$habit\" — showing up across your recent sessions.",
                style = FillerFreeType.body,
                color = FillerFreeColors.textPrimary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}