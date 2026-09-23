package com.androidengineers.agent_quickstart_android.fillerfree.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SpeechTopic
import com.androidengineers.agent_quickstart_android.fillerfree.presentation.theme.FillerFreeColors
import com.androidengineers.agent_quickstart_android.fillerfree.presentation.theme.FillerFreeType

/**
 * RE-DESIGNED: Attractive Topic Selection + Name Entry.
 * Fixes:
 * 1. CLEAN NAME FIELD: Simple label, no extra text.
 * 2. START GUARD: Button disabled until name is entered.
 */
@Composable
fun TopicSelectScreen(
    modifier: Modifier = Modifier,
    userName: String,
    onNameChanged: (String) -> Unit,
    topics: List<SpeechTopic>,
    selectedTopic: SpeechTopic?,
    errorMessage: String?,
    onTopicSelected: (SpeechTopic) -> Unit,
    onStart: () -> Unit,
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
        ) {
            Text(
                text = "Filler-Free",
                style = FillerFreeType.screenTitle,
                color = FillerFreeColors.textPrimary,
                modifier = Modifier.padding(top = 16.dp)
            )
            
            // CLEAN NAME INPUT
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedTextField(
                value = userName,
                onValueChange = onNameChanged,
                label = { Text("Enter Your Name", color = FillerFreeColors.textSecondary) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = FillerFreeType.body,
                colors = TextFieldDefaults.colors(
                    focusedTextColor = FillerFreeColors.textPrimary,
                    unfocusedTextColor = FillerFreeColors.textPrimary,
                    focusedContainerColor = FillerFreeColors.surface,
                    unfocusedContainerColor = FillerFreeColors.surface,
                    focusedLabelColor = FillerFreeColors.signalAmber,
                    unfocusedLabelColor = FillerFreeColors.textSecondary,
                    cursorColor = FillerFreeColors.signalAmber,
                    focusedIndicatorColor = FillerFreeColors.signalAmber,
                    unfocusedIndicatorColor = FillerFreeColors.surfaceRaised
                ),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "Select Coaching Focus",
                style = FillerFreeType.interruptionLine,
                color = FillerFreeColors.textPrimary
            )

            Spacer(modifier = Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                topics.forEach { topic ->
                    val isSelected = topic == selectedTopic
                    TopicCard(
                        topic = topic,
                        isSelected = isSelected,
                        onClick = { onTopicSelected(topic) }
                    )
                }
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = FillerFreeType.counterLabel,
                    color = FillerFreeColors.signalRed,
                    modifier = Modifier.padding(top = 24.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(40.dp))

            // START GUARD: Enabled only if name and topic are selected
            Button(
                onClick = onStart,
                enabled = selectedTopic != null && userName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FillerFreeColors.signalAmber,
                    contentColor = Color(0xFF1C1A17),
                    disabledContainerColor = FillerFreeColors.surfaceRaised,
                    disabledContentColor = FillerFreeColors.textMuted
                ),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                Text(text = "Enter Coaching Studio", style = FillerFreeType.interruptionLine)
            }
            
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TopicCard(
    topic: SpeechTopic,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isSelected) FillerFreeColors.surfaceRaised else FillerFreeColors.surface)
            .border(
                width = 2.dp,
                color = if (isSelected) FillerFreeColors.signalAmber else Color.Transparent,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(20.dp)
    ) {
        Column {
            Text(
                text = topic.title,
                style = FillerFreeType.interruptionLine,
                color = if (isSelected) FillerFreeColors.signalAmber else FillerFreeColors.textPrimary
            )
            Text(
                text = topic.description,
                style = FillerFreeType.body.copy(fontSize = 14.sp),
                color = FillerFreeColors.textSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
