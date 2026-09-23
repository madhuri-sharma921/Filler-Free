package com.androidengineers.agent_quickstart_android.fillerfree.live.presentation

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.androidengineers.agent_quickstart_android.fillerfree.live.data.LiveRoomApi
import com.androidengineers.agent_quickstart_android.fillerfree.live.data.LiveRoomSessionManager
import com.androidengineers.agent_quickstart_android.fillerfree.live.domain.LiveRole
import com.androidengineers.agent_quickstart_android.fillerfree.live.domain.LiveRoomState
import com.androidengineers.agent_quickstart_android.fillerfree.live.domain.OpenLiveRoomSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.abs

data class LiveRoomUiState(
    val screen: Screen = Screen.BROWSE,
    val myRole: LiveRole = LiveRole.AUDIENCE,
    val roomState: LiveRoomState? = null,
    val openRooms: List<OpenLiveRoomSummary> = emptyList(),
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val isCameraEnabled: Boolean = true,
    val localRtcUid: Int = 0,
    // UIDs currently publishing a video track -- see
    // LiveRoomSessionManager.LiveRoomConnectionState.remoteVideoUids.
    // The screen uses this to decide which participants get a real video
    // tile rendered vs. an audio-only placeholder (e.g. an audience
    // member who hasn't been promoted yet has no video to show).
    val remoteVideoUids: Set<Int> = emptySet(),
    val errorMessage: String? = null,
) {
    enum class Screen { BROWSE, IN_ROOM }
}

/**
 * Drives the "solo practice -> coached group session" flow: hosting a
 * room as the speaker, or joining one as a mentor/audience member.
 *
 * This owns its own [LiveRoomSessionManager] (its own RTC engine
 * instance -- see that class's docstring for why it can't share the
 * coaching session's engine) and is deliberately independent of
 * [com.androidengineers.agent_quickstart_android.fillerfree.presentation.FillerFreeViewModel]:
 * the speaker's coaching session (transcript analytics, the coach
 * agent, Room history) keeps running exactly as it does today, entirely
 * unaware that a room exists. This ViewModel only manages the second,
 * parallel audience/mentor channel layered on top of it.
 */
class LiveRoomViewModel(
    application: Application,
    injectedApi: LiveRoomApi? = null,
) : AndroidViewModel(application) {

    // Constructed lazily, not as a default parameter value: a default
    // parameter runs during the ViewModel's own constructor, so if
    // LiveRoomApi() (Retrofit/OkHttp setup) ever throws, ViewModelProvider
    // swallows the real exception and reports a generic "Cannot create an
    // instance of class LiveRoomViewModel" with no underlying cause visible
    // in Logcat. Lazy construction means that failure happens later, on
    // first real use, where it's both avoidable (see refreshOpenRooms) and,
    // if it still throws, visible with its actual stack trace.
    private val api: LiveRoomApi by lazy { injectedApi ?: LiveRoomApi() }

    // Exposed (read-only) rather than kept private: the Compose screen
    // needs a direct handle on this to bind local/remote video surfaces
    // (bindLocalPreview/bindRemoteVideo/createRendererView) -- that's a
    // view-layer concern (SurfaceView lifecycle tied to AndroidView) that
    // doesn't belong translated through ViewModel state.
    val sessionManager: LiveRoomSessionManager by lazy { LiveRoomSessionManager(application) }

    private val _uiState = MutableStateFlow(LiveRoomUiState())
    val uiState: StateFlow<LiveRoomUiState> = _uiState.asStateFlow()

    private val localRtcUid = stableRtcUidFor(application)

    // Room state (who's in the room, their roles, whether the coach agent
    // is active) has no push channel -- the backend is plain REST, so the
    // only way a joiner sees someone else arrive/leave/get promoted is by
    // asking again. Without this, refreshRoomState's single post-join call
    // is a one-time snapshot: it freezes at whatever the room looked like
    // the instant you joined and never updates again, which is exactly
    // why a speaker who joined moments apart from an audience member could
    // go missing from that audience member's screen forever.
    private var roomPollingJob: Job? = null

    init {
        _uiState.update { it.copy(localRtcUid = localRtcUid) }
        // Mirrors the RTC layer's own state (who's actually publishing
        // video right now, whether our camera is on) into the UI state,
        // so LiveRoomScreen only ever reads from uiState and never needs
        // to touch sessionManager.state directly.
        sessionManager.state
            .onEach { rtcState ->
                _uiState.update {
                    it.copy(
                        remoteVideoUids = rtcState.remoteVideoUids,
                        isCameraEnabled = rtcState.isCameraEnabled,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    fun refreshOpenRooms() {
        viewModelScope.launch {
            runCatching { api.listOpenRooms() }
                .onSuccess { rooms -> _uiState.update { it.copy(openRooms = rooms) } }
                .onFailure { error ->
                    _uiState.update { it.copy(errorMessage = error.message ?: "Could not load live rooms.") }
                }
        }
    }

    /** Speaker: promote the current solo session into a group room. */
    fun hostRoom(displayName: String, topicTitle: String) {
        _uiState.update { it.copy(isConnecting = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching {
                val connection = api.createRoom(
                    speakerRtcUid = localRtcUid,
                    speakerDisplayName = displayName.ifBlank { "Speaker" },
                    topicTitle = topicTitle,
                )
                sessionManager.join(connection)
                connection
            }.onSuccess { connection ->
                refreshRoomState(connection.roomId)
                _uiState.update {
                    it.copy(
                        screen = LiveRoomUiState.Screen.IN_ROOM,
                        myRole = LiveRole.SPEAKER,
                        isConnecting = false,
                        isConnected = true,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isConnecting = false, errorMessage = error.message ?: "Could not start the live room.")
                }
            }
        }
    }

    /** Mentor or audience: join an existing room. */
    fun joinRoom(room: OpenLiveRoomSummary, displayName: String, asRole: LiveRole) {
        _uiState.update { it.copy(isConnecting = true, errorMessage = null) }
        viewModelScope.launch {
            runCatching {
                val connection = api.joinRoom(
                    roomId = room.roomId,
                    rtcUid = localRtcUid,
                    displayName = displayName.ifBlank { if (asRole == LiveRole.MENTOR) "Mentor" else "Viewer" },
                    requestedRole = asRole,
                )
                sessionManager.join(connection)
                connection
            }.onSuccess { connection ->
                refreshRoomState(connection.roomId)
                _uiState.update {
                    it.copy(
                        screen = LiveRoomUiState.Screen.IN_ROOM,
                        myRole = connection.role,
                        isConnecting = false,
                        isConnected = true,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(isConnecting = false, errorMessage = error.message ?: "Could not join the live room.")
                }
            }
        }
    }

    /** Speaker-only: hand the mentor co-host slot to an audience member. */
    fun promote(targetRtcUid: Int, newRole: LiveRole) {
        val roomId = _uiState.value.roomState?.roomId ?: return
        viewModelScope.launch {
            runCatching { api.promoteParticipant(roomId, localRtcUid, targetRtcUid, newRole) }
                .onSuccess { state -> _uiState.update { it.copy(roomState = state) } }
                .onFailure { error ->
                    _uiState.update { it.copy(errorMessage = error.message ?: "Could not update that participant.") }
                }
        }
    }

    /** Toggle this device's own camera on/off (mentor/speaker only -- no-op for audience). */
    fun toggleCamera() {
        sessionManager.setCameraEnabled(!_uiState.value.isCameraEnabled)
    }

    fun switchCamera() {
        sessionManager.switchCamera()
    }

    fun leaveRoom() {
        val roomId = _uiState.value.roomState?.roomId
        stopRoomPolling()
        sessionManager.leave()
        viewModelScope.launch {
            if (roomId != null) {
                runCatching { api.leaveRoom(roomId, localRtcUid) }
            }
            _uiState.value = LiveRoomUiState(localRtcUid = localRtcUid)
            refreshOpenRooms()
        }
    }

    private fun refreshRoomState(roomId: String) {
        roomPollingJob?.cancel()
        roomPollingJob = viewModelScope.launch {
            while (true) {
                runCatching { api.roomState(roomId) }
                    .onSuccess { state -> _uiState.update { it.copy(roomState = state) } }
                // A failed poll (brief network hiccup, tunnel blip) is
                // not surfaced as an error -- the room keeps showing
                // its last known-good state and just tries again next
                // tick, rather than flashing an error banner on every
                // missed beat.
                delay(ROOM_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopRoomPolling() {
        roomPollingJob?.cancel()
        roomPollingJob = null
    }

    override fun onCleared() {
        stopRoomPolling()
        sessionManager.leave()
        super.onCleared()
    }
}

private const val ROOM_POLL_INTERVAL_MS = 2_500L

/**
 * A stable-per-device-install RTC UID, derived from ANDROID_ID rather
 * than freshly randomized on every LiveRoomViewModel construction.
 *
 * The previous version used Random.nextInt() as a property initializer,
 * which re-rolls every time this ViewModel is (re)created -- and it CAN
 * be recreated mid-session on a real device: a low-memory process kill
 * and restore, or any path that ends up calling the viewModel() factory
 * again, hands the same physical device a brand new random UID. The
 * server keys participants by rtc_uid (see live_room_store.py
 * add_participant), so a UID change mid-session doesn't update your
 * existing entry -- it silently adds a second one, which is exactly the
 * duplicate "Watching" row this fixes.
 *
 * ANDROID_ID is stable for the lifetime of the app install (survives
 * process death and reboots; only changes on a factory reset or
 * uninstall/reinstall on API 26+), which is exactly the stability this
 * needs. Values are folded into the Agora RTC uid range (1 to
 * 2^31 - 1, see server/app/live/schemas.py's rtc_uid field validators)
 * with abs() + a floor, since ANDROID_ID's hash can be negative or zero.
 */
private fun stableRtcUidFor(application: Application): Int {
    val androidId = Settings.Secure.getString(application.contentResolver, Settings.Secure.ANDROID_ID)
        ?: return 100_000
    val hashed = abs(androidId.hashCode())
    return (hashed % 799_999) + 100_000
}

private inline fun MutableStateFlow<LiveRoomUiState>.update(
    block: (LiveRoomUiState) -> LiveRoomUiState,
) {
    value = block(value)
}