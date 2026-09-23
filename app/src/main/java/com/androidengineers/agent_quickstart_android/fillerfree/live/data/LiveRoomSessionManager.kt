package com.androidengineers.agent_quickstart_android.fillerfree.live.data

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import com.androidengineers.agent_quickstart_android.fillerfree.live.domain.LiveRole
import com.androidengineers.agent_quickstart_android.fillerfree.live.domain.LiveRoomConnection
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.ClientRoleOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.VideoCanvas
import io.agora.rtc2.video.VideoEncoderConfiguration
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * RTC transport for one Interactive Live Streaming room -- audio AND
 * video: the speaker and mentor publish camera + mic, audience receives
 * both but publishes neither.
 *
 * Deliberately a SEPARATE RtcEngine instance/lifecycle from
 * [com.androidengineers.agent_quickstart_android.rtc.AgoraConversationSessionManager],
 * not a mode flag on it, because the two use fundamentally different
 * Agora channel profiles:
 *  - the solo coaching session: CHANNEL_PROFILE_COMMUNICATION, audio
 *    only, every participant publishes/subscribes equally, no role
 *    concept, no camera use (the front camera there is a *coaching
 *    signal input* for eye-contact detection, never published to
 *    anyone -- see fillerfree/camera/EyeContactAnalyzer).
 *  - an ILS room: CHANNEL_PROFILE_LIVE_BROADCASTING, where
 *    CLIENT_ROLE_BROADCASTER (speaker, mentor -- publishes camera + mic)
 *    vs CLIENT_ROLE_AUDIENCE (silent viewer -- publishes nothing) is a
 *    first-class distinction enforced by Agora itself: an audience
 *    member physically cannot publish audio or video even if the app
 *    tried to, which is exactly the guarantee a group session needs.
 *
 * The coach agent itself is unaffected by any of this: it keeps running
 * in the speaker's original coaching channel exactly as before. This
 * manager only handles the *second*, parallel channel that a mentor and
 * audience join to watch/co-host that same session (see
 * server/app/live/routes.py create_room docstring).
 */
class LiveRoomSessionManager(context: Context) {

    private val appContext = context.applicationContext
    private var rtcEngine: RtcEngine? = null
    private var joinDeferred: CompletableDeferred<Int>? = null

    private val _state = MutableStateFlow(LiveRoomConnectionState())
    val state: StateFlow<LiveRoomConnectionState> = _state.asStateFlow()

    private val eventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            joinDeferred?.complete(Constants.ERR_OK)
        }

        override fun onError(errorCode: Int) {
            Log.w(TAG, "live_room_rtc_error code=$errorCode")
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            _state.update { it.copy(remoteUidsHeard = it.remoteUidsHeard + uid) }
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            _state.update {
                it.copy(
                    remoteUidsHeard = it.remoteUidsHeard - uid,
                    remoteVideoUids = it.remoteVideoUids - uid,
                )
            }
        }

        // Fires when a remote user starts or stops publishing video (e.g. a
        // mentor gets promoted and turns their camera on, or an audience
        // member is demoted and their video track disappears). This is how
        // the UI knows *which* remote UIDs actually have a video feed to
        // render, versus ones that are only publishing audio.
        override fun onRemoteVideoStateChanged(
            uid: Int,
            state: Int,
            reason: Int,
            elapsed: Int,
        ) {
            val isPublishing = state == Constants.REMOTE_VIDEO_STATE_STARTING ||
                    state == Constants.REMOTE_VIDEO_STATE_DECODING
            _state.update { current ->
                current.copy(
                    remoteVideoUids = if (isPublishing) {
                        current.remoteVideoUids + uid
                    } else {
                        current.remoteVideoUids - uid
                    },
                )
            }
        }

        override fun onClientRoleChanged(
            oldRole: Int,
            newRole: Int,
            newRoleOptions: ClientRoleOptions,
        ) {
            _state.update { it.copy(isBroadcasting = newRole == Constants.CLIENT_ROLE_BROADCASTER) }
        }
    }

    suspend fun join(connection: LiveRoomConnection) {
        leave()
        ensureEngine(connection.appId)
        val engine = rtcEngine ?: throw IllegalStateException("RTC engine failed to initialize for live room.")

        val isBroadcaster = connection.role != LiveRole.AUDIENCE
        val deferred = CompletableDeferred<Int>()
        joinDeferred = deferred

        if (isBroadcaster) {
            // Local preview must start before/around join so the speaker or
            // mentor sees their own camera immediately, not just once a
            // remote peer acknowledges the stream.
            engine.startPreview()
        }

        val result = withContext(Dispatchers.Main.immediate) {
            engine.joinChannel(
                connection.rtcToken,
                connection.channelName,
                connection.rtcUid,
                ChannelMediaOptions().apply {
                    channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                    clientRoleType = if (isBroadcaster) {
                        Constants.CLIENT_ROLE_BROADCASTER
                    } else {
                        Constants.CLIENT_ROLE_AUDIENCE
                    }
                    publishMicrophoneTrack = isBroadcaster
                    publishCameraTrack = isBroadcaster
                    autoSubscribeAudio = true
                    autoSubscribeVideo = true
                }
            )
        }

        if (result != Constants.ERR_OK) {
            joinDeferred = null
            throw IOException("Live room RTC join failed (${RtcEngine.getErrorDescription(result)}).")
        }

        try {
            withTimeout(JOIN_TIMEOUT_MS) { deferred.await() }
        } catch (error: TimeoutCancellationException) {
            joinDeferred = null
            throw IOException("Live room RTC join timed out.", error)
        } catch (error: CancellationException) {
            joinDeferred = null
            throw error
        }

        _state.value = LiveRoomConnectionState(
            roomId = connection.roomId,
            channelName = connection.channelName,
            role = connection.role,
            isBroadcasting = isBroadcaster,
            isConnected = true,
        )

        // Explicit, not just implied by autoSubscribeAudio in the join
        // options above: makes sure remote audio is neither muted nor
        // played at zero volume for this device, regardless of role.
        // Costs nothing for a broadcaster and directly targets the
        // "audience can't hear the speaker" symptom for an audience
        // member.
        engine.muteAllRemoteAudioStreams(false)
        engine.adjustPlaybackSignalVolume(100)
    }

    /**
     * Renders this device's own camera preview into [surfaceView]. Call
     * once the view is available (e.g. from an AndroidView factory in
     * Compose) and only when this device is a broadcaster -- audience
     * members never have a camera preview to show.
     */
    fun bindLocalPreview(surfaceView: SurfaceView) {
        rtcEngine?.setupLocalVideo(VideoCanvas(surfaceView, VideoCanvas.RENDER_MODE_HIDDEN, 0))
    }

    /**
     * Renders a remote participant's camera feed into [surfaceView]. Safe
     * to call for any UID currently in [LiveRoomConnectionState.remoteVideoUids];
     * calling it for a UID that isn't actually publishing video yet just
     * means the surface stays blank until they start.
     */
    fun bindRemoteVideo(uid: Int, surfaceView: SurfaceView) {
        rtcEngine?.setupRemoteVideo(VideoCanvas(surfaceView, VideoCanvas.RENDER_MODE_HIDDEN, uid))
    }

    /** Creates a raw Agora render surface -- use this instead of `SurfaceView(context)`
     * directly so the view is wired for Agora's renderer from the start.
     * This is a static Agora helper that only needs a Context, not a
     * live engine instance, so it works even if called a moment before
     * the engine finishes initializing.
     */
    fun createRendererView(): SurfaceView = RtcEngine.CreateRendererView(appContext)

    /** Speaker-facing: hand this device the mentor's broadcaster slot or drop it. */
    fun setLocalRole(role: LiveRole) {
        val engine = rtcEngine ?: return
        val isBroadcaster = role != LiveRole.AUDIENCE
        engine.setClientRole(
            if (isBroadcaster) Constants.CLIENT_ROLE_BROADCASTER else Constants.CLIENT_ROLE_AUDIENCE
        )
        if (isBroadcaster) {
            engine.startPreview()
            engine.muteLocalVideoStream(false)
            engine.muteLocalAudioStream(false)
        } else {
            engine.stopPreview()
            engine.muteLocalVideoStream(true)
            engine.muteLocalAudioStream(true)
        }
        _state.update { it.copy(role = role, isBroadcasting = isBroadcaster) }
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        rtcEngine?.muteLocalAudioStream(!enabled)
    }

    fun setCameraEnabled(enabled: Boolean) {
        rtcEngine?.muteLocalVideoStream(!enabled)
        _state.update { it.copy(isCameraEnabled = enabled) }
    }

    fun switchCamera() {
        rtcEngine?.switchCamera()
    }

    fun leave() {
        joinDeferred?.cancel()
        joinDeferred = null
        rtcEngine?.let { engine ->
            runCatching { engine.stopPreview() }
            runCatching { engine.leaveChannel() }
        }
        rtcEngine = null
        runCatching { RtcEngine.destroy() }
        _state.value = LiveRoomConnectionState()
    }

    private suspend fun ensureEngine(appId: String) = withContext(Dispatchers.Main.immediate) {
        if (rtcEngine != null) return@withContext
        val config = RtcEngineConfig().apply {
            mContext = appContext
            mAppId = appId
            mEventHandler = eventHandler
            mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
        }
        rtcEngine = RtcEngine.create(config)?.apply {
            enableAudio()
            enableVideo()
            setDefaultAudioRoutetoSpeakerphone(true)
            // Modest, phone-call-friendly defaults -- this is a coaching
            // room, not a broadcast studio, so bandwidth/battery matter
            // more than resolution.
            setVideoEncoderConfiguration(
                VideoEncoderConfiguration(
                    VideoEncoderConfiguration.VD_640x360,
                    VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
                    VideoEncoderConfiguration.STANDARD_BITRATE,
                    VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_PORTRAIT,
                )
            )
        } ?: throw IllegalStateException("Live room RTC engine failed to initialize.")
    }

    private companion object {
        const val TAG = "LiveRoomSessionManager"
        const val JOIN_TIMEOUT_MS = 15_000L
    }
}

data class LiveRoomConnectionState(
    val roomId: String? = null,
    val channelName: String? = null,
    val role: LiveRole = LiveRole.AUDIENCE,
    val isBroadcasting: Boolean = false,
    val isConnected: Boolean = false,
    val isCameraEnabled: Boolean = true,
    val remoteUidsHeard: Set<Int> = emptySet(),
    // Subset of remoteUidsHeard that are actually publishing a video
    // track right now -- what the UI should actually try to render.
    val remoteVideoUids: Set<Int> = emptySet(),
)