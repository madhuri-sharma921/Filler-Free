package com.androidengineers.agent_quickstart_android.fillerfree.live.data

import com.androidengineers.agent_quickstart_android.config.QuickstartConfig
import com.androidengineers.agent_quickstart_android.fillerfree.live.domain.LiveParticipant
import com.androidengineers.agent_quickstart_android.fillerfree.live.domain.LiveRole
import com.androidengineers.agent_quickstart_android.fillerfree.live.domain.LiveRoomConnection
import com.androidengineers.agent_quickstart_android.fillerfree.live.domain.LiveRoomState
import com.androidengineers.agent_quickstart_android.fillerfree.live.domain.OpenLiveRoomSummary
import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Talks to server/app/live/routes.py. Kept as a separate small client
 * (rather than folded into ConversationAgoraApi) because ILS rooms are a
 * genuinely separate transport concern -- a LIVE_BROADCASTING channel
 * with role-based tokens, not the 1:1 coaching channel -- see
 * LiveRoomSessionManager for why that also means a separate RTC engine
 * instance on the client side.
 */
class LiveRoomApi(
    baseUrl: String = QuickstartConfig.backendBaseUrl,
) {
    private val service: LiveRoomBackendService = Retrofit.Builder()
        .baseUrl(baseUrl.normalizeBaseUrl())
        .client(
            OkHttpClient.Builder()
                .connectTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(LiveRoomBackendService::class.java)

    suspend fun createRoom(
        speakerRtcUid: Int,
        speakerDisplayName: String,
        topicTitle: String,
    ): LiveRoomConnection {
        val body = service.createRoom(
            CreateRoomRequest(
                speakerRtcUid = speakerRtcUid,
                speakerDisplayName = speakerDisplayName,
                topicTitle = topicTitle,
            )
        ).requireBody()
        return LiveRoomConnection(
            roomId = body.roomId.requireValue("room_id"),
            channelName = body.channelName.requireValue("channel_name"),
            appId = body.appId.requireValue("app_id"),
            rtcToken = body.rtcToken.requireValue("rtc_token"),
            rtcUid = body.rtcUid,
            role = LiveRole.fromWire(body.role),
        )
    }

    suspend fun joinRoom(
        roomId: String,
        rtcUid: Int,
        displayName: String,
        requestedRole: LiveRole,
    ): LiveRoomConnection {
        val body = service.joinRoom(
            JoinRoomRequest(
                roomId = roomId,
                rtcUid = rtcUid,
                displayName = displayName,
                requestedRole = if (requestedRole == LiveRole.MENTOR) "mentor" else "audience",
            )
        ).requireBody()
        return LiveRoomConnection(
            roomId = body.roomId.requireValue("room_id"),
            channelName = body.channelName.requireValue("channel_name"),
            appId = body.appId.requireValue("app_id"),
            rtcToken = body.rtcToken.requireValue("rtc_token"),
            rtcUid = body.rtcUid,
            role = LiveRole.fromWire(body.role),
        )
    }

    suspend fun leaveRoom(roomId: String, rtcUid: Int) {
        service.leaveRoom(LeaveRoomRequest(roomId = roomId, rtcUid = rtcUid)).requireSuccess()
    }

    suspend fun promoteParticipant(
        roomId: String,
        requesterRtcUid: Int,
        targetRtcUid: Int,
        newRole: LiveRole,
    ): LiveRoomState {
        val body = service.promoteParticipant(
            PromoteRequest(
                roomId = roomId,
                requesterRtcUid = requesterRtcUid,
                targetRtcUid = targetRtcUid,
                newRole = if (newRole == LiveRole.MENTOR) "mentor" else "audience",
            )
        ).requireBody()
        return body.toDomain()
    }

    suspend fun roomState(roomId: String): LiveRoomState {
        return service.roomState(roomId).requireBody().toDomain()
    }

    suspend fun listOpenRooms(): List<OpenLiveRoomSummary> {
        val body = service.listOpenRooms().requireBody()
        return body.rooms.map { room ->
            OpenLiveRoomSummary(
                roomId = room.roomId,
                topicTitle = room.topicTitle,
                speakerRtcUid = room.speakerRtcUid,
                audienceCount = room.participants.count { it.role == "audience" },
            )
        }
    }

    private fun RoomStateResponse.toDomain(): LiveRoomState = LiveRoomState(
        roomId = roomId,
        channelName = channelName,
        topicTitle = topicTitle,
        speakerRtcUid = speakerRtcUid,
        participants = participants.map {
            LiveParticipant(rtcUid = it.rtcUid, displayName = it.displayName, role = LiveRole.fromWire(it.role))
        },
        coachAgentActive = coachAgentActive,
    )

    private fun <T> Response<T>.requireBody(): T {
        if (!isSuccessful) throw toIOException()
        return body() ?: throw IOException("The quickstart server returned an empty response.")
    }

    private fun Response<*>.requireSuccess() {
        if (!isSuccessful) throw toIOException()
    }

    private fun Response<*>.toIOException(): IOException {
        val payload = errorBody()?.string().orEmpty()
        val detail = runCatching { JSONObject(payload).optString("detail") }.getOrNull().orEmpty()
        return IOException(detail.ifBlank { "Live room request failed with status ${code()}." })
    }

    private fun String?.requireValue(key: String): String {
        return this?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IOException("Missing '$key' in live room response.")
    }

    private fun String.normalizeBaseUrl(): String {
        val configured = trim().ifBlank { "https://localhost" }
        return if (configured.endsWith('/')) configured else "$configured/"
    }

    private interface LiveRoomBackendService {
        @POST("v1/live/rooms")
        suspend fun createRoom(@Body request: CreateRoomRequest): Response<RoomConnectionResponse>

        @POST("v1/live/rooms/join")
        suspend fun joinRoom(@Body request: JoinRoomRequest): Response<RoomConnectionResponse>

        @POST("v1/live/rooms/leave")
        suspend fun leaveRoom(@Body request: LeaveRoomRequest): Response<Unit>

        @POST("v1/live/rooms/promote")
        suspend fun promoteParticipant(@Body request: PromoteRequest): Response<RoomStateResponse>

        @GET("v1/live/rooms/{roomId}")
        suspend fun roomState(@Path("roomId") roomId: String): Response<RoomStateResponse>

        @GET("v1/live/rooms")
        suspend fun listOpenRooms(): Response<ListRoomsResponse>
    }

    private data class CreateRoomRequest(
        @SerializedName("speaker_rtc_uid") val speakerRtcUid: Int,
        @SerializedName("speaker_display_name") val speakerDisplayName: String,
        @SerializedName("topic_title") val topicTitle: String,
    )

    private data class JoinRoomRequest(
        @SerializedName("room_id") val roomId: String,
        @SerializedName("rtc_uid") val rtcUid: Int,
        @SerializedName("display_name") val displayName: String,
        @SerializedName("requested_role") val requestedRole: String,
    )

    private data class LeaveRoomRequest(
        @SerializedName("room_id") val roomId: String,
        @SerializedName("rtc_uid") val rtcUid: Int,
    )

    private data class PromoteRequest(
        @SerializedName("room_id") val roomId: String,
        @SerializedName("requester_rtc_uid") val requesterRtcUid: Int,
        @SerializedName("target_rtc_uid") val targetRtcUid: Int,
        @SerializedName("new_role") val newRole: String,
    )

    private data class RoomConnectionResponse(
        @SerializedName("room_id") val roomId: String? = null,
        @SerializedName("channel_name") val channelName: String? = null,
        @SerializedName("app_id") val appId: String? = null,
        @SerializedName("rtc_token") val rtcToken: String? = null,
        @SerializedName("rtc_uid") val rtcUid: Int = 0,
        val role: String = "audience",
    )

    private data class ParticipantResponse(
        @SerializedName("rtc_uid") val rtcUid: Int,
        @SerializedName("display_name") val displayName: String,
        val role: String,
    )

    private data class RoomStateResponse(
        @SerializedName("room_id") val roomId: String,
        @SerializedName("channel_name") val channelName: String,
        @SerializedName("topic_title") val topicTitle: String,
        @SerializedName("speaker_rtc_uid") val speakerRtcUid: Int,
        val participants: List<ParticipantResponse>,
        @SerializedName("coach_agent_active") val coachAgentActive: Boolean,
    )

    private data class ListRoomsResponse(val rooms: List<RoomStateResponse>)

    private companion object {
        const val NETWORK_TIMEOUT_SECONDS = 15L
    }
}
