package com.androidengineers.agent_quickstart_android.data

import com.androidengineers.agent_quickstart_android.config.QuickstartConfig
import com.androidengineers.agent_quickstart_android.model.AgentInviteResult
import com.androidengineers.agent_quickstart_android.model.AgoraTokenBundle
import com.androidengineers.agent_quickstart_android.model.BackendHealthResult
import com.androidengineers.agent_quickstart_android.model.RenewalTokens
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

class ConversationAgoraApi(
    baseUrl: String = QuickstartConfig.backendBaseUrl,
) {
    private val service: ConversationBackendService = Retrofit.Builder()
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
        .create(ConversationBackendService::class.java)

    suspend fun checkHealth(): BackendHealthResult {
        val body = service.health().requireBody()
        return BackendHealthResult(status = body.status, version = body.version)
    }

    suspend fun requestSessionBootstrap(): AgoraTokenBundle {
        val body = service.bootstrap(
            request = BootstrapRequest(),
        ).requireBody()
        return AgoraTokenBundle(
            appId = body.appId.requireValue("app_id"),
            agentRtcUid = body.agentRtcUid,
            rtcToken = body.rtcToken.requireValue("rtc_token"),
            rtmToken = body.rtmToken.requireValue("rtm_token"),
            uid = body.requesterRtcUid.toString(),
            channel = body.channelName.requireValue("channel_name"),
            rtmUserId = body.requesterRtmUserId.requireValue("requester_rtm_user_id"),
        )
    }

    suspend fun renewTokens(
        channel: String,
        rtcUid: Int,
        rtmUserId: String,
    ): RenewalTokens {
        val body = service.refresh(
            request = RefreshRequest(
                channelName = channel,
                requesterRtcUid = rtcUid,
                requesterRtmUserId = rtmUserId,
            ),
        ).requireBody()
        return RenewalTokens(
            rtcToken = body.rtcToken.requireValue("rtc_token"),
            rtmToken = body.rtmToken.requireValue("rtm_token"),
        )
    }

    suspend fun inviteAgent(
        channelName: String,
        requesterRtcUid: String,
        systemPrompt: String? = null,
        role: String = "delivery",
    ): AgentInviteResult {
        val body = service.join(
            request = JoinRequest(
                channelName = channelName,
                requesterRtcUid = requesterRtcUid.toIntOrNull()
                    ?: throw IOException("The requester RTC UID must be numeric."),
                systemPrompt = systemPrompt,
                role = role,
            ),
        ).requireBody()
        return AgentInviteResult(
            agentId = body.agentId.requireValue("agent_id"),
            createTimestampSeconds = body.createdAtUnix,
            state = body.status,
            role = body.role ?: role,
            rtcUid = body.rtcUid ?: 0,
        )
    }

    /**
     * Hands the "floor" to a different coach persona in the same channel.
     * See server routes.py switch_role: since the ConvoAI SDK has no native
     * mute, this leaves the currently active role's agent and joins/resumes
     * the requested role's agent, so only one coach is ever actually
     * speaking at a time.
     */
    suspend fun switchCoachRole(
        channelName: String,
        requesterRtcUid: String,
        role: String,
        systemPrompt: String? = null,
    ): AgentInviteResult {
        val body = service.switchRole(
            request = SwitchRoleRequest(
                channelName = channelName,
                requesterRtcUid = requesterRtcUid.toIntOrNull()
                    ?: throw IOException("The requester RTC UID must be numeric."),
                role = role,
                systemPrompt = systemPrompt,
            ),
        ).requireBody()
        return AgentInviteResult(
            agentId = body.agentId.requireValue("agent_id"),
            createTimestampSeconds = body.createdAtUnix,
            state = body.status,
            role = body.role ?: role,
            rtcUid = body.rtcUid ?: 0,
        )
    }

    suspend fun stopConversation(agentId: String, channelName: String) {
        service.leave(
            request = AgentActionRequest(agentId = agentId, channelName = channelName),
        ).requireSuccess()
    }

    suspend fun interruptAgent(agentId: String, channelName: String) {
        service.interrupt(
            request = AgentActionRequest(agentId = agentId, channelName = channelName),
        ).requireSuccess()
    }

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
        return IOException(
            detail.ifBlank { "Quickstart server request failed with status ${code()}." }
        )
    }

    private fun String?.requireValue(key: String): String {
        return this?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IOException("Missing '$key' in quickstart server response.")
    }

    private fun String.normalizeBaseUrl(): String {
        val configured = trim().ifBlank { "https://localhost" }
        return if (configured.endsWith('/')) configured else "$configured/"
    }

    private interface ConversationBackendService {
        @GET("health")
        suspend fun health(): Response<HealthResponse>

        @POST("v1/conversation/bootstrap")
        suspend fun bootstrap(
            @Body request: BootstrapRequest,
        ): Response<BootstrapResponse>

        @POST("v1/conversation/join")
        suspend fun join(
            @Body request: JoinRequest,
        ): Response<JoinResponse>

        @POST("v1/conversation/switch-role")
        suspend fun switchRole(
            @Body request: SwitchRoleRequest,
        ): Response<JoinResponse>

        @POST("v1/conversation/interrupt")
        suspend fun interrupt(
            @Body request: AgentActionRequest,
        ): Response<ActionResponse>

        @POST("v1/conversation/leave")
        suspend fun leave(
            @Body request: AgentActionRequest,
        ): Response<ActionResponse>

        @POST("v1/conversation/refresh")
        suspend fun refresh(
            @Body request: RefreshRequest,
        ): Response<RefreshResponse>
    }

    private class BootstrapRequest

    private data class JoinRequest(
        @SerializedName("channel_name") val channelName: String,
        @SerializedName("requester_rtc_uid") val requesterRtcUid: Int,
        @SerializedName("system_prompt") val systemPrompt: String? = null,
        @SerializedName("role") val role: String = "delivery",
    )

    private data class SwitchRoleRequest(
        @SerializedName("channel_name") val channelName: String,
        @SerializedName("requester_rtc_uid") val requesterRtcUid: Int,
        @SerializedName("role") val role: String,
        @SerializedName("system_prompt") val systemPrompt: String? = null,
    )

    private data class AgentActionRequest(
        @SerializedName("agent_id") val agentId: String,
        @SerializedName("channel_name") val channelName: String,
    )

    private data class RefreshRequest(
        @SerializedName("channel_name") val channelName: String,
        @SerializedName("requester_rtc_uid") val requesterRtcUid: Int,
        @SerializedName("requester_rtm_user_id") val requesterRtmUserId: String,
    )

    private data class HealthResponse(val status: String, val version: String)

    private data class BootstrapResponse(
        @SerializedName("app_id") val appId: String? = null,
        @SerializedName("agent_rtc_uid") val agentRtcUid: Int,
        @SerializedName("channel_name") val channelName: String? = null,
        @SerializedName("rtc_token") val rtcToken: String? = null,
        @SerializedName("rtm_token") val rtmToken: String? = null,
        @SerializedName("requester_rtc_uid") val requesterRtcUid: Int,
        @SerializedName("requester_rtm_user_id") val requesterRtmUserId: String? = null,
    )

    private data class JoinResponse(
        @SerializedName("agent_id") val agentId: String? = null,
        @SerializedName("created_at_unix") val createdAtUnix: Long? = null,
        val status: String? = null,
        val role: String? = null,
        @SerializedName("rtc_uid") val rtcUid: Int? = null,
    )

    private data class RefreshResponse(
        @SerializedName("rtc_token") val rtcToken: String? = null,
        @SerializedName("rtm_token") val rtmToken: String? = null,
    )

    private data class ActionResponse(val success: Boolean, val message: String)

    private companion object {
        const val NETWORK_TIMEOUT_SECONDS = 15L
    }
}