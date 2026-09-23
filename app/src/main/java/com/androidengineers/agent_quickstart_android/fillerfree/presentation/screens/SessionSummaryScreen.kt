package com.androidengineers.agent_quickstart_android.fillerfree.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SessionSummary
import com.androidengineers.agent_quickstart_android.fillerfree.presentation.QueuedPracticeSuggestion
import com.androidengineers.agent_quickstart_android.fillerfree.presentation.theme.FillerFreeColors
import com.androidengineers.agent_quickstart_android.fillerfree.presentation.theme.FillerFreeType

/**
 * RE-DESIGNED: High-quality, attractive Session Summary.
 * Uses a warm brownish theme with rich cards and clear stats.
 */
@Composable
fun SessionSummaryScreen(
    modifier: Modifier = Modifier,
    summary: SessionSummary?,
    onStartNewSession: () -> Unit,
    onViewProgress: () -> Unit,
    // Present only when the coach agent called queue_practice_topic mid-
    // session (see CoachAgentPromptBuilder + FillerFreeViewModel.endSession).
    // Absent for the large majority of sessions -- that's the normal case.
    suggestedPractice: QueuedPracticeSuggestion? = null,
    onPracticeSuggested: (QueuedPracticeSuggestion) -> Unit = { onStartNewSession() },
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FillerFreeColors.background)
            .padding(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Session Complete",
                style = FillerFreeType.screenTitle,
                color = FillerFreeColors.textPrimary,
                modifier = Modifier.padding(top = 12.dp)
            )

            Text(
                text = "Here's how your explanation went.",
                style = FillerFreeType.body,
                color = FillerFreeColors.textSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 32.dp)
            )

            if (summary != null) {
                // Scoreboard Card
                SummaryCard {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "TOTAL ISSUES",
                            style = FillerFreeType.counterLabel,
                            color = FillerFreeColors.textMuted
                        )
                        Text(
                            text = summary.stats.totalIssues.toString(),
                            style = FillerFreeType.counterNumber.copy(fontSize = 48.sp),
                            color = if (summary.stats.totalIssues == 0) FillerFreeColors.signalGreen else FillerFreeColors.signalAmber,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatMiniBlock(label = "Fillers", value = summary.stats.fillerCount.toString())
                            StatMiniBlock(label = "Repeats", value = summary.stats.repetitionCount.toString())
                            StatMiniBlock(label = "Cut-ins", value = summary.stats.interruptionCount.toString())
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Coach's Verdict Card
                SummaryCard(borderColor = FillerFreeColors.signalAmber.copy(alpha = 0.3f)) {
                    Column {
                        Text(
                            text = "COACH'S VERDICT",
                            style = FillerFreeType.counterLabel,
                            color = FillerFreeColors.signalAmber,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = summary.closingTip,
                            style = FillerFreeType.body.copy(lineHeight = 24.sp),
                            color = FillerFreeColors.textPrimary,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        
                        summary.topOffender?.let { offender ->
                            Box(
                                modifier = Modifier
                                    .padding(top = 16.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(FillerFreeColors.signalRed.copy(alpha = 0.1f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "Watch out for: \"$offender\"",
                                    style = FillerFreeType.counterLabel,
                                    color = FillerFreeColors.signalRed
                                )
                            }
                        }
                    }
                }
            }

            if (suggestedPractice != null) {
                Spacer(modifier = Modifier.height(20.dp))
                SummaryCard(borderColor = FillerFreeColors.signalGreen.copy(alpha = 0.3f)) {
                    Column {
                        Text(
                            text = "COACH SUGGESTS",
                            style = FillerFreeType.counterLabel,
                            color = FillerFreeColors.signalGreen,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Try again: ${suggestedPractice.topicTitle}",
                            style = FillerFreeType.body.copy(lineHeight = 24.sp),
                            color = FillerFreeColors.textPrimary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                        )
                        suggestedPractice.reason?.let { reason ->
                            Text(
                                text = reason,
                                style = FillerFreeType.counterLabel,
                                color = FillerFreeColors.textMuted,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(40.dp))

            // Action Buttons
            if (suggestedPractice != null) {
                Button(
                    onClick = { onPracticeSuggested(suggestedPractice) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FillerFreeColors.signalGreen,
                        contentColor = Color(0xFF1C1A17)
                    ),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    Text(text = "Try again: ${suggestedPractice.topicTitle}", style = FillerFreeType.interruptionLine)
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Button(
                onClick = onViewProgress,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FillerFreeColors.surfaceRaised,
                    contentColor = FillerFreeColors.textPrimary
                ),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                Text(text = "View Long-term Progress", style = FillerFreeType.interruptionLine)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onStartNewSession,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FillerFreeColors.signalAmber,
                    contentColor = Color(0xFF1C1A17) // Back to black for contrast
                ),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                Text(text = "Practice Again", style = FillerFreeType.interruptionLine)
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SummaryCard(
    borderColor: Color = FillerFreeColors.hairline,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(FillerFreeColors.surface)
            .padding(20.dp)
    ) {
        content()
    }
}

@Composable
private fun StatMiniBlock(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = FillerFreeType.interruptionLine, color = FillerFreeColors.textPrimary)
        Text(text = label.uppercase(), style = FillerFreeType.counterLabel.copy(fontSize = 9.sp), color = FillerFreeColors.textMuted)
    }
}
