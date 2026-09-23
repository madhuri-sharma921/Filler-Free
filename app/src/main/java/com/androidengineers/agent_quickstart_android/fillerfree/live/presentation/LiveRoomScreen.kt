package com.androidengineers.agent_quickstart_android.fillerfree.live.presentation

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.androidengineers.agent_quickstart_android.fillerfree.domain.model.SpeechTopic
import com.androidengineers.agent_quickstart_android.fillerfree.live.data.LiveRoomSessionManager
import com.androidengineers.agent_quickstart_android.fillerfree.live.domain.LiveParticipant
import com.androidengineers.agent_quickstart_android.fillerfree.live.domain.LiveRole
import com.androidengineers.agent_quickstart_android.fillerfree.live.domain.OpenLiveRoomSummary
import com.androidengineers.agent_quickstart_android.fillerfree.presentation.theme.FillerFreeColors
import com.androidengineers.agent_quickstart_android.fillerfree.presentation.theme.FillerFreeType

/**
 * Entry point for "Filler-Free: Live" -- host a group coaching session as
 * the speaker, or join an in-progress one as a mentor co-host or a
 * silent audience member. Reachable from the topic-select / summary
 * screens as a "Go Live" action; the core solo coaching flow
 * (FillerFreeScreen/FillerFreeViewModel) is untouched by this feature.
 *
 * Video: speaker and mentor publish camera + mic and see/hear each
 * other's video; audience receives video/audio but publishes neither
 * (enforced by Agora's CLIENT_ROLE_AUDIENCE, not just by this UI -- see
 * LiveRoomSessionManager). Audience members therefore never get a video
 * tile of their own to render, only speaker/mentor tiles.
 */
@Composable
fun LiveRoomScreen(
    userName: String,
    onExit: () -> Unit,
    viewModel: LiveRoomViewModel = run {
        // LiveRoomViewModel extends AndroidViewModel (it needs an
        // Application to construct its own LiveRoomSessionManager), and
        // also has a defaulted constructor param (injectedApi). The plain
        // viewModel() call with no factory only knows how to build a bare
        // ViewModel()/AndroidViewModel() with no extra args, so it
        // crashes with "Cannot create an instance of class
        // ...LiveRoomViewModel" -- this factory tells it exactly how, the
        // same way MainActivity already does for FillerFreeViewModel.
        val application = LocalContext.current.applicationContext as android.app.Application
        viewModel(
            factory = viewModelFactory {
                initializer { LiveRoomViewModel(application) }
            },
        )
    },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshOpenRooms() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FillerFreeColors.background)
            // FillerFreeScreen (the solo-coaching flow) uses a Scaffold,
            // which consumes system-bar insets for you automatically.
            // This screen is a plain Box with none of that, so without
            // this it draws its content straight under the status bar
            // and the nav bar / gesture area -- exactly the "Leave room"
            // button and header being cut off you're seeing.
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        when (state.screen) {
            LiveRoomUiState.Screen.BROWSE -> BrowseRoomsContent(
                state = state,
                onRefresh = viewModel::refreshOpenRooms,
                onHost = { topic -> viewModel.hostRoom(userName, topic) },
                onJoin = { room, role -> viewModel.joinRoom(room, userName, role) },
                onExit = onExit,
            )
            LiveRoomUiState.Screen.IN_ROOM -> InRoomContent(
                state = state,
                sessionManager = viewModel.sessionManager,
                onPromote = viewModel::promote,
                onToggleCamera = viewModel::toggleCamera,
                onSwitchCamera = viewModel::switchCamera,
                onLeave = viewModel::leaveRoom,
            )
        }
    }
}

@Composable
private fun ScreenHeader(onExit: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        IconButton(onClick = onExit) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = FillerFreeColors.textPrimary)
        }
        Spacer(Modifier.width(4.dp))
        Text(
            "Filler-Free: Live",
            style = FillerFreeType.screenTitle,
            color = FillerFreeColors.textPrimary,
        )
    }
}

@Composable
private fun SectionCard(
    borderColor: Color = FillerFreeColors.hairline,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(FillerFreeColors.surface)
            .padding(20.dp),
    ) {
        content()
    }
}

@Composable
private fun BrowseRoomsContent(
    state: LiveRoomUiState,
    onRefresh: () -> Unit,
    onHost: (String) -> Unit,
    onJoin: (OpenLiveRoomSummary, LiveRole) -> Unit,
    onExit: () -> Unit,
) {
    var selectedTopic by remember { mutableStateOf(SpeechTopic.ALL.first()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        ScreenHeader(onExit)
        Spacer(Modifier.height(8.dp))

        SectionCard {
            Column {
                Text(
                    "HOST A GROUP SESSION",
                    style = FillerFreeType.counterLabel,
                    color = FillerFreeColors.signalAmber,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(12.dp))
                Text("Topic", style = FillerFreeType.counterLabel, color = FillerFreeColors.textMuted)
                Text(
                    selectedTopic.title,
                    style = FillerFreeType.body,
                    color = FillerFreeColors.textPrimary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                Text(
                    "Camera and mic will be on -- your face is visible to everyone who joins.",
                    style = FillerFreeType.counterLabel,
                    color = FillerFreeColors.textMuted,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Button(
                    onClick = { onHost(selectedTopic.title) },
                    enabled = !state.isConnecting,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = FillerFreeColors.signalAmber,
                        contentColor = Color(0xFF1C1A17),
                    ),
                    contentPadding = PaddingValues(vertical = 14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.isConnecting) "Starting..." else "Go live as speaker",
                        style = FillerFreeType.interruptionLine,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "JOIN A LIVE SESSION",
                style = FillerFreeType.counterLabel,
                color = FillerFreeColors.textSecondary,
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = onRefresh) {
                Text("Refresh", color = FillerFreeColors.signalAmber, style = FillerFreeType.counterLabel)
            }
        }
        Spacer(Modifier.height(12.dp))

        if (state.openRooms.isEmpty()) {
            SectionCard {
                Text(
                    "No open rooms right now. Ask someone to host one, or start your own above.",
                    style = FillerFreeType.body,
                    color = FillerFreeColors.textMuted,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.openRooms.forEach { room ->
                    OpenRoomCard(room = room, enabled = !state.isConnecting, onJoin = onJoin)
                }
            }
        }

        state.errorMessage?.let { message ->
            Spacer(Modifier.height(20.dp))
            SectionCard(borderColor = FillerFreeColors.signalRed) {
                Text(message, color = FillerFreeColors.signalRed, style = FillerFreeType.body)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun OpenRoomCard(
    room: OpenLiveRoomSummary,
    enabled: Boolean,
    onJoin: (OpenLiveRoomSummary, LiveRole) -> Unit,
) {
    SectionCard {
        Column {
            Text(room.topicTitle, style = FillerFreeType.interruptionLine, color = FillerFreeColors.textPrimary)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(8.dp).clip(CircleShape).background(FillerFreeColors.signalGreen),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${room.audienceCount} watching",
                    style = FillerFreeType.counterLabel,
                    color = FillerFreeColors.textMuted,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onJoin(room, LiveRole.MENTOR) },
                    enabled = enabled,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = FillerFreeColors.signalAmber),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Co-host", style = FillerFreeType.counterLabel)
                }
                OutlinedButton(
                    onClick = { onJoin(room, LiveRole.AUDIENCE) },
                    enabled = enabled,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = FillerFreeColors.textSecondary),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Watch", style = FillerFreeType.counterLabel)
                }
            }
        }
    }
}

/** Wraps a raw Agora-created SurfaceView for Compose, tearing it down cleanly on dispose. */
@Composable
private fun AgoraVideoSurface(
    modifier: Modifier = Modifier,
    createSurface: () -> SurfaceView,
) {
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { _ ->
            createSurface().also { surfaceView = it }
        },
    )

    DisposableEffect(Unit) {
        onDispose { surfaceView = null }
    }
}

@Composable
private fun VideoTile(
    modifier: Modifier = Modifier,
    label: String,
    isSpeaker: Boolean,
    hasVideo: Boolean,
    onBindSurface: (SurfaceView) -> Unit,
    createSurface: () -> SurfaceView,
) {
    Box(
        modifier = modifier
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(14.dp))
            .background(FillerFreeColors.surfaceRaised),
    ) {
        if (hasVideo) {
            AgoraVideoSurface(
                modifier = Modifier.fillMaxSize(),
                createSurface = {
                    createSurface().also(onBindSurface)
                },
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "\uD83D\uDC64",
                    style = FillerFreeType.screenTitle,
                    color = FillerFreeColors.textMuted,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x99000000))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            if (isSpeaker) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(FillerFreeColors.signalAmber))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = FillerFreeType.counterLabel, color = Color.White)
        }
    }
}

@Composable
private fun InRoomContent(
    state: LiveRoomUiState,
    sessionManager: LiveRoomSessionManager,
    onPromote: (Int, LiveRole) -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onLeave: () -> Unit,
) {
    val room = state.roomState
    val isBroadcaster = state.myRole != LiveRole.AUDIENCE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(room?.topicTitle ?: "Live session", style = FillerFreeType.screenTitle, color = FillerFreeColors.textPrimary)
        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(FillerFreeColors.surfaceRaised)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            if (!state.isConnected) {
                CircularProgressIndicator(
                    color = FillerFreeColors.signalAmber,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("Connecting...", style = FillerFreeType.counterLabel, color = FillerFreeColors.textSecondary)
            } else {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(FillerFreeColors.signalAmber))
                Spacer(Modifier.width(8.dp))
                Text(
                    "You're ${roleLabel(state.myRole)}",
                    style = FillerFreeType.counterLabel,
                    color = FillerFreeColors.signalAmber,
                )
            }
        }

        if (state.isConnected && room != null) {
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (room.coachAgentActive) {
                            FillerFreeColors.signalGreen.copy(alpha = 0.15f)
                        } else {
                            FillerFreeColors.surfaceRaised
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Box(
                    modifier = Modifier.size(8.dp).clip(CircleShape).background(
                        if (room.coachAgentActive) FillerFreeColors.signalGreen else FillerFreeColors.textMuted
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (room.coachAgentActive) {
                        "Coach is live \u2014 correcting fillers for everyone to hear"
                    } else {
                        "Coach unavailable for this session"
                    },
                    style = FillerFreeType.counterLabel,
                    color = if (room.coachAgentActive) FillerFreeColors.signalGreen else FillerFreeColors.textMuted,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Video grid: speaker + mentor only -- audience never publishes
        // video, so there's nothing of theirs to render here regardless
        // of remoteVideoUids. No remember() here: room comes straight
        // from already-observed StateFlow state, so memoizing the filter
        // just risks it going stale by one recomposition behind the real
        // participant list (e.g. showing an empty grid right after the
        // speaker/mentor actually joined).
        val videoParticipants = room?.participants?.filter {
            it.role == LiveRole.SPEAKER || it.role == LiveRole.MENTOR
        } ?: emptyList()

        if (videoParticipants.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (videoParticipants.size > 1) 2 else 1),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .height(if (videoParticipants.size > 1) 340.dp else 220.dp),
            ) {
                items(videoParticipants, key = { it.rtcUid }) { participant ->
                    val isLocal = participant.rtcUid == state.localRtcUid
                    val hasVideo = isLocal.let { local ->
                        if (local) isBroadcaster && state.isCameraEnabled else participant.rtcUid in state.remoteVideoUids
                    }
                    VideoTile(
                        label = if (isLocal) "You" else participant.displayName,
                        isSpeaker = participant.role == LiveRole.SPEAKER,
                        hasVideo = hasVideo,
                        onBindSurface = { surface ->
                            if (isLocal) {
                                sessionManager.
                                bindLocalPreview(surface)
                            } else {
                                sessionManager.bindRemoteVideo(participant.rtcUid, surface)
                            }
                        },
                        createSurface = { sessionManager.createRendererView() },
                    )
                }
            }

            if (isBroadcaster) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onToggleCamera,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = FillerFreeColors.textPrimary),
                    ) {
                        Text(if (state.isCameraEnabled) "Camera on" else "Camera off", style = FillerFreeType.counterLabel)
                    }
                    OutlinedButton(
                        onClick = onSwitchCamera,
                        enabled = state.isCameraEnabled,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = FillerFreeColors.textPrimary),
                    ) {
                        Text("Flip camera", style = FillerFreeType.counterLabel)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }

        Text("PARTICIPANTS", style = FillerFreeType.counterLabel, color = FillerFreeColors.textSecondary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                room?.participants?.forEachIndexed { index, participant ->
                    if (index > 0) {
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(FillerFreeColors.hairline))
                    }
                    ParticipantRow(
                        participant = participant,
                        canPromote = state.myRole == LiveRole.SPEAKER && participant.role != LiveRole.SPEAKER,
                        onPromote = onPromote,
                    )
                }
                if (room?.participants.isNullOrEmpty()) {
                    Text("Just you so far.", style = FillerFreeType.body, color = FillerFreeColors.textMuted)
                }
            }
        }

        state.errorMessage?.let { message ->
            Spacer(Modifier.height(16.dp))
            SectionCard(borderColor = FillerFreeColors.signalRed) {
                Text(message, color = FillerFreeColors.signalRed, style = FillerFreeType.body)
            }
        }

        Spacer(Modifier.weight(1f))
        Button(
            onClick = onLeave,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = FillerFreeColors.signalRed, contentColor = FillerFreeColors.textPrimary),
            contentPadding = PaddingValues(vertical = 14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Leave room", style = FillerFreeType.interruptionLine)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ParticipantRow(
    participant: LiveParticipant,
    canPromote: Boolean,
    onPromote: (Int, LiveRole) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(participant.displayName, style = FillerFreeType.body, color = FillerFreeColors.textPrimary, fontWeight = FontWeight.Medium)
            Text(
                roleLabel(participant.role).replaceFirstChar { it.uppercase() },
                style = FillerFreeType.counterLabel,
                color = if (participant.role == LiveRole.SPEAKER) FillerFreeColors.signalAmber else FillerFreeColors.textMuted,
            )
        }
        if (canPromote) {
            val nextRole = if (participant.role == LiveRole.MENTOR) LiveRole.AUDIENCE else LiveRole.MENTOR
            TextButton(onClick = { onPromote(participant.rtcUid, nextRole) }) {
                Text(
                    if (nextRole == LiveRole.MENTOR) "Make mentor" else "To audience",
                    style = FillerFreeType.counterLabel,
                    color = FillerFreeColors.signalAmber,
                )
            }
        }
    }
}

private fun roleLabel(role: LiveRole): String = when (role) {
    LiveRole.SPEAKER -> "the speaker"
    LiveRole.MENTOR -> "co-hosting mentor"
    LiveRole.AUDIENCE -> "watching"
}