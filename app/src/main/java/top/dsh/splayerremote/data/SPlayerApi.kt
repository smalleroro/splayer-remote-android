package top.dsh.splayerremote.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * SPlayer 外部 API 的客户端。
 *
 * 只封装官方文档里公开的 13 个端点，不做任何推测性调用。
 * 基础地址由用户在设置里填（默认 http://127.0.0.1:14558），
 * 局域网场景填电脑的内网 IP。
 */
class SPlayerApi(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultClient,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private fun url(path: String): String = baseUrl.trimEnd('/') + path

    private suspend fun <T> get(path: String, deserializer: kotlinx.serialization.DeserializationStrategy<T>): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url(path)).get().build()
            execute(request, deserializer)
        }

    private suspend fun <T> post(
        path: String,
        body: JsonObject?,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        val payload = (body ?: buildJsonObject { }).toString()
        val request = Request.Builder()
            .url(url(path))
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        execute(request, deserializer)
    }

    private fun <T> execute(request: Request, deserializer: kotlinx.serialization.DeserializationStrategy<T>): T {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // 官方约定：400 为参数非法，响应体里带 error 字段
                val message = runCatching {
                    json.parseToJsonElement(text).let { element ->
                        (element as? JsonObject)?.get("error")?.jsonPrimitive?.content
                    }
                }.getOrNull()
                throw SPlayerException(response.code, message ?: "HTTP ${response.code}")
            }
            return json.decodeFromString(deserializer, text)
        }
    }

    // ---------------------------------------------------------------- 查询

    suspend fun info(): ServerInfo = get("/api/info", ServerInfo.serializer())

    suspend fun status(): PlaybackStatus {
        // 记录发起时刻，供界面补偿网络往返
        val requestAt = android.os.SystemClock.elapsedRealtime()
        val result = get("/api/status", PlaybackStatus.serializer())
        result.fetchedAt = requestAt
        return result
    }

    suspend fun queue(offset: Int = 0, limit: Int = 50): PlaybackQueue =
        get("/api/queue?offset=$offset&limit=$limit", PlaybackQueue.serializer())

    suspend fun nowPlaying(): NowPlaying = get("/api/now-playing", NowPlaying.serializer())

    /** 当前曲目的完整解析歌词；无歌词时 lyric 为空数组 */
    suspend fun lyrics(): Lyrics = get("/api/lyrics", Lyrics.serializer())

    // ---------------------------------------------------------------- 控制

    suspend fun play(): OkResponse = post("/api/play", null, OkResponse.serializer())

    suspend fun pause(): OkResponse = post("/api/pause", null, OkResponse.serializer())

    suspend fun stop(): OkResponse = post("/api/stop", null, OkResponse.serializer())

    suspend fun next(): OkResponse = post("/api/next", null, OkResponse.serializer())

    suspend fun prev(): OkResponse = post("/api/prev", null, OkResponse.serializer())

    suspend fun seek(positionMs: Long): OkResponse =
        post(
            "/api/seek",
            buildJsonObject { put("positionMs", kotlinx.serialization.json.JsonPrimitive(positionMs)) },
            OkResponse.serializer(),
        )

    suspend fun setVolume(volume: Float): OkResponse =
        post(
            "/api/volume",
            buildJsonObject { put("volume", kotlinx.serialization.json.JsonPrimitive(volume.coerceIn(0f, 1f))) },
            OkResponse.serializer(),
        )

    /** 播放/暂停切换：查当前状态后决定发哪条指令 */
    suspend fun togglePlayPause(): OkResponse {
        val current = status()
        return if (current.isPlaying) pause() else play()
    }

    companion object {
        /** 默认地址：手机本机回环。连电脑时由用户改成内网 IP */
        const val DEFAULT_BASE_URL = "http://127.0.0.1:14558"

        /** SharedPreferences 文件名与键名，App 与前台服务共用同一份 */
        const val PREFS_NAME = "splayer_remote"
        const val KEY_BASE_URL = "base_url"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }

        /** 多播 DNS 之外最简单的方式：让用户填 IP，这里只负责规范化前缀 */
        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return DEFAULT_BASE_URL
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }
            return withScheme.trimEnd('/')
        }
    }
}

/** 与 SPlayer 通信时的错误，区分 HTTP 状态码便于界面提示 */
class SPlayerException(val code: Int, message: String) : IOException(message)
