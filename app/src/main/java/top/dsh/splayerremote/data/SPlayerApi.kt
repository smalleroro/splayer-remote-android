package top.dsh.splayerremote.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * SPlayer 外部 API 的抽象。
 *
 * 抽出接口不是为了「面向接口编程」这句口号，是为了**能测**：
 * 之前 SPlayerApi 是 final class，ViewModel 里直接 new，
 * 于是断连容忍、失败分层、指令转发这些逻辑一行都测不了。
 */
interface SPlayerApi {

    suspend fun info(): ServerInfo
    suspend fun status(): PlaybackStatus
    suspend fun queue(offset: Int, limit: Int): PlaybackQueue
    suspend fun nowPlaying(): NowPlaying
    suspend fun lyrics(): Lyrics

    suspend fun play(): OkResponse
    suspend fun pause(): OkResponse
    suspend fun stop(): OkResponse
    suspend fun next(): OkResponse
    suspend fun prev(): OkResponse
    suspend fun seek(positionMs: Long): OkResponse
    suspend fun setVolume(volume: Float): OkResponse

    /** 播放/暂停切换：查当前状态后决定发哪条指令 */
    suspend fun togglePlayPause(): OkResponse

    companion object {
        /** 默认地址：手机本机回环。连电脑时由用户改成内网 IP */
        const val DEFAULT_BASE_URL = "http://127.0.0.1:14558"

        /** 端口，供自动发现复用 */
        const val DEFAULT_PORT = 14558

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

/** HTTP 层拿到非 2xx */
class SPlayerHttpException(val code: Int, message: String) : IOException(message)

/**
 * 服务端回了 200，但业务上拒绝了。
 *
 * 与传输失败要分开处理：网络断了要改连接状态，
 * 而「这首歌唱不了」只是这一次操作失败，不该把界面刷成离线。
 */
class SPlayerBusinessException(message: String) : IOException(message)

/**
 * 真实实现：走 OkHttp 调 SPlayer 的 13 个公开端点。
 *
 * 只封装官方文档里写明的接口，不做推测性调用。
 */
class HttpSPlayerApi(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultClient,
) : SPlayerApi {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private fun url(path: String): String = baseUrl.trimEnd('/') + path

    private suspend fun <T> get(path: String, deserializer: DeserializationStrategy<T>): T =
        withContext(Dispatchers.IO) {
            execute(Request.Builder().url(url(path)).get().build(), deserializer)
        }

    private suspend fun post(
        path: String,
        body: JsonObject?,
        deserializer: DeserializationStrategy<OkResponse>,
    ): OkResponse = withContext(Dispatchers.IO) {
        val payload = (body ?: buildJsonObject { }).toString()
        val request = Request.Builder()
            .url(url(path))
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        val result = execute(request, deserializer)
        // 200 也可能带 {"ok":false}，之前完全没检查，指令静默失败还当成功
        if (!result.ok) {
            throw SPlayerBusinessException(result.error ?: "服务端拒绝了该操作")
        }
        result
    }

    private fun <T> execute(request: Request, deserializer: DeserializationStrategy<T>): T {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    json.parseToJsonElement(text).let { element ->
                        (element as? JsonObject)?.get("error")?.jsonPrimitive?.content
                    }
                }.getOrNull()
                throw SPlayerHttpException(response.code, message ?: "HTTP ${response.code}")
            }
            return json.decodeFromString(deserializer, text)
        }
    }

    // ---------------------------------------------------------------- 查询

    override suspend fun info(): ServerInfo = get("/api/info", ServerInfo.serializer())

    override suspend fun status(): PlaybackStatus = get("/api/status", PlaybackStatus.serializer())

    override suspend fun queue(offset: Int, limit: Int): PlaybackQueue =
        get("/api/queue?offset=$offset&limit=$limit", PlaybackQueue.serializer())

    override suspend fun nowPlaying(): NowPlaying = get("/api/now-playing", NowPlaying.serializer())

    override suspend fun lyrics(): Lyrics = get("/api/lyrics", Lyrics.serializer())

    // ---------------------------------------------------------------- 控制

    override suspend fun play(): OkResponse = post("/api/play", null, OkResponse.serializer())

    override suspend fun pause(): OkResponse = post("/api/pause", null, OkResponse.serializer())

    override suspend fun stop(): OkResponse = post("/api/stop", null, OkResponse.serializer())

    override suspend fun next(): OkResponse = post("/api/next", null, OkResponse.serializer())

    override suspend fun prev(): OkResponse = post("/api/prev", null, OkResponse.serializer())

    override suspend fun seek(positionMs: Long): OkResponse = post(
        "/api/seek",
        buildJsonObject { put("positionMs", JsonPrimitive(positionMs)) },
        OkResponse.serializer(),
    )

    override suspend fun setVolume(volume: Float): OkResponse = post(
        "/api/volume",
        buildJsonObject { put("volume", JsonPrimitive(volume.coerceIn(0f, 1f))) },
        OkResponse.serializer(),
    )

    override suspend fun togglePlayPause(): OkResponse {
        val current = status()
        return if (current.isPlaying) pause() else play()
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }
    }
}
