package com.androidengineers.agent_quickstart_android.fillerfree.data

import com.androidengineers.agent_quickstart_android.config.QuickstartConfig
import com.google.gson.annotations.SerializedName
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Talks to server/app/mcp/routes.py -- the REST endpoints that mirror
 * this device's own analytics numbers into the server-side stores the
 * coach agent's MCP tools read from (see tool_server.py). Every call
 * here is best-effort and fire-and-forget from the app's perspective:
 * failures are swallowed by the caller (FillerFreeViewModel) because a
 * failed mirror write should never interrupt the coaching session
 * itself, only mean the agent's tools have slightly stale data.
 */
class CoachToolsBridgeApi(
    baseUrl: String = QuickstartConfig.backendBaseUrl,
) {
    private val service: CoachToolsBridgeService = Retrofit.Builder()
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
        .create(CoachToolsBridgeService::class.java)

    suspend fun pushLiveStats(
        channelName: String,
        topicTitle: String,
        fillerCount: Int,
        repetitionCount: Int,
        interruptionCount: Int,
        wordCount: Int,
        durationMs: Long,
        topOffender: String?,
    ) {
        service.pushLiveStats(
            PushLiveStatsRequest(
                channelName = channelName,
                topicTitle = topicTitle,
                fillerCount = fillerCount,
                repetitionCount = repetitionCount,
                interruptionCount = interruptionCount,
                wordCount = wordCount,
                durationMs = durationMs,
                topOffender = topOffender,
            )
        )
    }

    /** Returns null if the agent never called `queue_practice_topic` this session. */
    suspend fun popQueuedPractice(channelName: String): QueuedPracticeResult? {
        val body = service.popQueuedPractice(channelName)
        if (!body.isSuccessful) return null
        val payload = body.body() ?: return null
        if (!payload.found) return null
        return QueuedPracticeResult(
            topicId = payload.topicId ?: return null,
            topicTitle = payload.topicTitle ?: return null,
            reason = payload.reason,
        )
    }

    suspend fun reportSession(
        topicTitle: String,
        completedAtUnix: Long,
        fillerCount: Int,
        repetitionCount: Int,
        topOffender: String?,
    ) {
        service.reportSession(
            ReportSessionRequest(
                topicTitle = topicTitle,
                completedAtUnix = completedAtUnix,
                fillerCount = fillerCount,
                repetitionCount = repetitionCount,
                topOffender = topOffender,
            )
        )
    }

    private fun String.normalizeBaseUrl(): String {
        val configured = trim().ifBlank { "https://localhost" }
        return if (configured.endsWith('/')) configured else "$configured/"
    }

    private interface CoachToolsBridgeService {
        @POST("v1/coach-tools/live-stats")
        suspend fun pushLiveStats(@Body request: PushLiveStatsRequest): Response<Unit>

        @GET("v1/coach-tools/queued-practice/{channelName}")
        suspend fun popQueuedPractice(@Path("channelName") channelName: String): Response<QueuedPracticeResponse>

        @POST("v1/coach-tools/report-session")
        suspend fun reportSession(@Body request: ReportSessionRequest): Response<Unit>
    }

    private data class PushLiveStatsRequest(
        @SerializedName("channel_name") val channelName: String,
        @SerializedName("topic_title") val topicTitle: String,
        @SerializedName("filler_count") val fillerCount: Int,
        @SerializedName("repetition_count") val repetitionCount: Int,
        @SerializedName("interruption_count") val interruptionCount: Int,
        @SerializedName("word_count") val wordCount: Int,
        @SerializedName("duration_ms") val durationMs: Long,
        @SerializedName("top_offender") val topOffender: String?,
    )

    private data class QueuedPracticeResponse(
        val found: Boolean,
        @SerializedName("topic_id") val topicId: String? = null,
        @SerializedName("topic_title") val topicTitle: String? = null,
        val reason: String? = null,
    )

    private data class ReportSessionRequest(
        @SerializedName("topic_title") val topicTitle: String,
        @SerializedName("completed_at_unix") val completedAtUnix: Long,
        @SerializedName("filler_count") val fillerCount: Int,
        @SerializedName("repetition_count") val repetitionCount: Int,
        @SerializedName("top_offender") val topOffender: String?,
    )

    private companion object {
        const val NETWORK_TIMEOUT_SECONDS = 10L
    }
}

data class QueuedPracticeResult(
    val topicId: String,
    val topicTitle: String,
    val reason: String?,
)
