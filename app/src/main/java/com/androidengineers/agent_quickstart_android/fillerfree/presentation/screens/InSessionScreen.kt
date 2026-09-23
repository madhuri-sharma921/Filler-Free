package com.androidengineers.agent_quickstart_android.fillerfree.presentation.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidengineers.agent_quickstart_android.R
import com.androidengineers.agent_quickstart_android.fillerfree.camera.EyeContactAnalyzer
import com.androidengineers.agent_quickstart_android.fillerfree.camera.EyeContactCameraPreview
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.EmotionLabel
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.EyeContactState
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SessionStats
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SpeechEvent
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SpeechEventType
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SpeechTopic
import com.androidengineers.agent_quickstart_android.fillerfree.presentation.theme.FillerFreeColors
import com.androidengineers.agent_quickstart_android.fillerfree.presentation.theme.FillerFreeType
import com.androidengineers.agent_quickstart_android.model.AgentConversationState
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.random.Random

@Composable
fun InSessionScreen(
    modifier: Modifier = Modifier,
    userName: String,
    topic: SpeechTopic?,
    isConnecting: Boolean,
    isSessionActive: Boolean = false,
    liveTranscript: String,
    stats: SessionStats,
    recentEvents: List<SpeechEvent>,
    currentEmotion: EmotionLabel? = null,
    agentState: AgentConversationState = AgentConversationState.IDLE,
    eyeContactCoachingEnabled: Boolean = false,
    hasCameraPermission: Boolean = false,
    eyeContactState: EyeContactState = EyeContactState.DISABLED,
    eyeContactAnalyzer: EyeContactAnalyzer? = null,
    onEyeContactCoachingToggled: (Boolean) -> Unit = {},
    onEndSession: () -> Unit,
) {
    val lastEventIsInterruption = recentEvents.lastOrNull()?.type == SpeechEventType.AGENT_INTERRUPTION
    val cameraLive = eyeContactCoachingEnabled && hasCameraPermission && isSessionActive
    var selectedCoachRole by remember { mutableStateOf("Core Stylist") }

    val scrimFlash by animateColorAsState(
        targetValue = if (lastEventIsInterruption) FillerFreeColors.signalAmber.copy(alpha = 0.15f)
        else Color.Transparent,
        animationSpec = tween(300),
        label = "interruptionFlash",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(FillerFreeColors.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // HEADER
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = topic?.title ?: "Free talk",
                    style = FillerFreeType.interruptionLine,
                    color = FillerFreeColors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (isConnecting) {
                    Text(
                        text = "Syncing AI...",
                        style = FillerFreeType.counterLabel,
                        color = FillerFreeColors.signalAmber
                    )
                }
                MicIndicator(isPulsing = !lastEventIsInterruption)
            }

            // STATUS READOUTS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EmotionChip(emotion = currentEmotion, modifier = Modifier.weight(1f))
                EyeContactChip(state = eyeContactState, modifier = Modifier.weight(1f))
            }

            // COACH PERSONAS
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(FillerFreeColors.surface)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CoachPersonaItem(
                    name = "Core Stylist",
                    role = "CORE",
                    icon = R.drawable.ic_coach_core,
                    state = agentState,
                    isSelected = selectedCoachRole == "Core Stylist",
                    onClick = { selectedCoachRole = "Core Stylist" }
                )
                CoachPersonaItem(
                    name = "Energy Dynamicist",
                    role = "ENERGY",
                    icon = R.drawable.ic_coach_energy,
                    state = agentState,
                    isSelected = selectedCoachRole == "Energy Dynamicist",
                    onClick = { selectedCoachRole = "Energy Dynamicist" }
                )
                CoachPersonaItem(
                    name = "Presence Guardian",
                    role = "POSTURE",
                    icon = R.drawable.ic_coach_eyes,
                    state = agentState,
                    isSelected = selectedCoachRole == "Presence Guardian",
                    onClick = { selectedCoachRole = "Presence Guardian" }
                )
            }

            // TOGGLE (Fixed Visibility)
            EyeContactToggleRow(
                enabled = eyeContactCoachingEnabled,
                onToggled = onEyeContactCoachingToggled
            )

            // CENTERED USER CAMERA (Zoom-style)
            Box(
                modifier = Modifier
                    .size(width = 240.dp, height = 200.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .border(2.dp, FillerFreeColors.surfaceRaised, RoundedCornerShape(24.dp))
                    .background(FillerFreeColors.surfaceRaised),
                contentAlignment = Alignment.Center
            ) {
                SelfViewBubble(
                    cameraLive = cameraLive,
                    userName = userName,
                    eyeContactAnalyzer = eyeContactAnalyzer,
                    hasCameraPermission = hasCameraPermission,
                    modifier = Modifier.fillMaxSize()
                )
                
                FloatingReactionLayer(
                    emotion = currentEmotion,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // KEY METRICS GRID
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CounterChip(label = "FILLERS", value = stats.fillerCount, modifier = Modifier.weight(1f))
                CounterChip(label = "REPEATS", value = stats.repetitionCount, modifier = Modifier.weight(1f))
                CounterChip(label = "CUT-INS", value = stats.interruptionCount, accent = true, modifier = Modifier.weight(1f))
            }

            // LIVE SCROLLABLE TRANSCRIPT
            TranscriptCaption(text = liveTranscript)

            // FINISH ACTION
            Button(
                onClick = onEndSession,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FillerFreeColors.signalRed,
                    contentColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                Text(text = "Finish Session", style = FillerFreeType.interruptionLine)
            }
            
            Spacer(modifier = Modifier.height(20.dp))
        }

        Box(modifier = Modifier.fillMaxSize().background(scrimFlash))
    }
}

@Composable
private fun CoachPersonaItem(
    name: String,
    role: String,
    icon: Int,
    state: AgentConversationState,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val activeColor = when (state) {
        AgentConversationState.SPEAKING -> FillerFreeColors.signalGreen
        AgentConversationState.THINKING -> FillerFreeColors.signalAmber
        else -> if (isSelected) FillerFreeColors.signalAmber else FillerFreeColors.textMuted
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(if (isSelected) activeColor.copy(alpha = 0.15f) else Color.Transparent)
                .border(if (isSelected) 2.dp else 0.dp, activeColor.copy(alpha = 0.4f), CircleShape)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = icon),
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                alpha = if (isSelected) 1f else 0.5f
            )
        }
        Text(
            text = role,
            style = FillerFreeType.counterLabel.copy(fontSize = 9.sp, letterSpacing = 1.sp),
            color = if (isSelected) activeColor else FillerFreeColors.textMuted,
            modifier = Modifier.padding(top = 4.dp),
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun SelfViewBubble(
    cameraLive: Boolean,
    userName: String,
    eyeContactAnalyzer: EyeContactAnalyzer?,
    hasCameraPermission: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (cameraLive && eyeContactAnalyzer != null) {
            EyeContactCameraPreview(
                modifier = Modifier.fillMaxSize(),
                enabled = true,
                hasCameraPermission = hasCameraPermission,
                analyzer = eyeContactAnalyzer,
            )
        } else {
            val initials = userName.split(" ")
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("") { it.take(1).uppercase(Locale.ROOT) }
                .ifBlank { "AI" }

            // ONLY INITIALS - Name removed as requested
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF6E56CF)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initials,
                    style = FillerFreeType.screenTitle.copy(fontSize = 32.sp, color = Color.White)
                )
            }
        }
    }
}

@Composable
private fun FloatingReactionLayer(
    emotion: EmotionLabel?,
    modifier: Modifier = Modifier,
) {
    val reactions = remember { mutableStateListOf<FloatingEmoji>() }
    var nextId by remember { mutableStateOf(0L) }

    LaunchedEffect(emotion) {
        if (emotion != null && emotion != EmotionLabel.UNKNOWN) {
            reactions.add(
                FloatingEmoji(
                    id = nextId++,
                    emojiRes = emotion.toEmojiRes(),
                    driftXDp = Random.nextFloat() * 60f - 30f,
                )
            )
            delay(500) 
        }
    }

    Box(modifier = modifier) {
        reactions.forEach { reaction ->
            key(reaction.id) {
                AnimatedEmoji(
                    emojiRes = reaction.emojiRes,
                    driftX = reaction.driftXDp.dp,
                    onFinished = { reactions.removeAll { it.id == reaction.id } },
                )
            }
        }
    }
}

private data class FloatingEmoji(val id: Long, val emojiRes: Int, val driftXDp: Float)

@Composable
private fun AnimatedEmoji(emojiRes: Int, driftX: Dp, onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, animationSpec = tween(1500, easing = LinearEasing))
        onFinished()
    }
    
    val riseDp = (-240).dp * progress.value
    val fadeAlpha = if (progress.value < 0.6f) 1f else (1f - (progress.value - 0.6f) / 0.4f).coerceIn(0f, 1f)
    val driftOffset = driftX * progress.value
    val scale = 0.8f + (progress.value * 0.4f)

    Image(
        painter = painterResource(id = emojiRes),
        contentDescription = null,
        modifier = Modifier
            .offset(x = driftOffset, y = riseDp)
            .size(40.dp)
            .scale(scale)
            .alpha(fadeAlpha),
    )
}

private fun EmotionLabel.toEmojiRes(): Int = when (this) {
    EmotionLabel.CONFIDENT -> R.drawable.emoji_confident
    EmotionLabel.EXCITED -> R.drawable.emoji_excited
    EmotionLabel.NERVOUS -> R.drawable.emoji_nervous
    EmotionLabel.FRUSTRATED -> R.drawable.emoji_frustrated
    EmotionLabel.FLAT -> R.drawable.emoji_flat
    else -> R.drawable.emoji_unknown
}

@Composable
private fun EyeContactToggleRow(enabled: Boolean, onToggled: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(FillerFreeColors.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Eyes & Presence AI", style = FillerFreeType.body, color = Color.White)
        Switch(
            checked = enabled,
            onCheckedChange = onToggled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = FillerFreeColors.signalGreen,
                checkedThumbColor = Color.White,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = FillerFreeColors.surfaceRaised,
                uncheckedBorderColor = FillerFreeColors.hairline
            )
        )
    }
}

@Composable
private fun TranscriptCaption(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp, max = 120.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(FillerFreeColors.surface)
            .padding(14.dp),
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = text.ifBlank { "AI Coaches are active. Start explaining..." },
                style = FillerFreeType.body,
                color = if (text.isBlank()) FillerFreeColors.textMuted else FillerFreeColors.textPrimary,
            )
        }
    }
}

@Composable
private fun CounterChip(label: String, value: Int, modifier: Modifier = Modifier, accent: Boolean = false) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(FillerFreeColors.surface)
            .padding(vertical = 12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value.toString(),
                style = FillerFreeType.counterNumber.copy(fontSize = 24.sp),
                color = if (accent && value > 0) FillerFreeColors.signalAmber else Color.White,
            )
            Text(text = label, style = FillerFreeType.counterLabel, color = FillerFreeColors.textMuted)
        }
    }
}

@Composable
private fun EmotionChip(emotion: EmotionLabel?, modifier: Modifier = Modifier) {
    val (emojiRes, label, color) = when (emotion) {
        EmotionLabel.CONFIDENT -> Triple(R.drawable.emoji_confident, "Confident", FillerFreeColors.signalGreen)
        EmotionLabel.EXCITED -> Triple(R.drawable.emoji_excited, "Energy Up", FillerFreeColors.signalGreen)
        EmotionLabel.NERVOUS -> Triple(R.drawable.emoji_nervous, "Pacing High", FillerFreeColors.signalAmber)
        EmotionLabel.FRUSTRATED -> Triple(R.drawable.emoji_frustrated, "Hesitation", FillerFreeColors.signalRed)
        EmotionLabel.FLAT -> Triple(R.drawable.emoji_flat, "Steady", FillerFreeColors.textSecondary)
        else -> Triple(R.drawable.emoji_unknown, "Syncing Tone", FillerFreeColors.textMuted)
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(FillerFreeColors.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Image(painter = painterResource(id = emojiRes), contentDescription = null, modifier = Modifier.size(16.dp))
        Text(text = label, style = FillerFreeType.counterLabel, color = color, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun EyeContactChip(state: EyeContactState, modifier: Modifier = Modifier) {
    val (dotColor, label) = when (state) {
        EyeContactState.GOOD -> FillerFreeColors.signalGreen to "Eyes Up"
        EyeContactState.LOOKING_AWAY -> FillerFreeColors.signalAmber to "Look Here"
        EyeContactState.NO_FACE -> FillerFreeColors.signalAmber to "No Face"
        else -> FillerFreeColors.textMuted to "Video Off"
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(FillerFreeColors.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Text(text = label, style = FillerFreeType.counterLabel, color = dotColor, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun MicIndicator(isPulsing: Boolean) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (isPulsing) FillerFreeColors.signalGreen.copy(alpha = 0.2f) else FillerFreeColors.signalAmber.copy(alpha = 0.4f))
            .padding(8.dp)
    ) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(if (isPulsing) FillerFreeColors.signalGreen else FillerFreeColors.signalAmber))
    }
}
