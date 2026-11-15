package top.dsh.splayerremote

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.dsh.splayerremote.data.AppSettings
import top.dsh.splayerremote.data.HttpSPlayerApi
import top.dsh.splayerremote.data.LyricLine
import top.dsh.splayerremote.data.PlaybackStatus
import top.dsh.splayerremote.data.SPlayerApi
import top.dsh.splayerremote.data.SPlayerBusinessException
import top.dsh.splayerremote.data.Sample
import top.dsh.splayerremote.data.ServerDiscovery
import top.dsh.splayerremote.data.ServerInfo
import top.dsh.splayerremote.data.SettingsRepository
import top.dsh.splayerremote.data.Track
import top.dsh.splayerremote.domain.Clock
import top.dsh.splayerremote.domain.MonotonicClock
import top.dsh.splayerremote.domain.PollPolicy

/** 连接外观 */
enum class Connection { Unknown, Connecting, Connected, Failed }

/**
 * 队列的本地视图。
 *
 * 服务端可能有近千首，界面不可能一次全要。这里按 100 首一页按需取，
 * 用 Map 存"下标 → 曲目"，未取到的位置就是 null（界面显示占位）。
 */
data class QueueState(
    val total: Int = 0,
    val currentIndex: Int = -1,
    val items: Map<Int, Track> = emptyMap(),
    val loadingPages: Set<Int> = emptySet(),
) {
    operator fun get(index: Int): Track? = items[index]
}

/** 会话对外的完整快照 */
data class SessionState(
    val baseUrl: String = SPlayerApi.DEFAULT_BASE_URL,
    val connection: Connection = Connection.Unknown,
    val error: String? = null,
    val discovering: Boolean = false,
    val serverInfo: ServerInfo? = null,
    val track: Track? = null,
    /** 带采样时刻的状态，供本地插值使用 */
    val status: Sample<PlaybackStatus>? = null,
    val speed: Float = 1f,
    /** 本地音量（拖动时是用户拖到的值，不是服务端回读的旧值） */
    val volume: Float = 1f,
    val lyrics: List<LyricLine> = emptyList(),
    val lyricsTrackId: String? = null,
    val queue: QueueState = QueueState(),
    /** 一次性提示，消费后清空 */
    val message: String? = null,
)

/**
 * 会话层：**全应用唯一**的 API 客户端与轮询循环。
 *
 * 之前的问题是两个「会话」并存——ViewModel 一个、前台服务一个，
 * 各自轮询同一台服务器，造成重复请求、断连判定不一致、改地址不同步。
 * 现在只有这里发请求，其余角色订阅 [state]：
 *   - UI：把状态翻译成界面
 *   - 前台服务：把状态渲染成通知
 *
 * 生命周期用引用计数：Activity 与 Service 各自 acquire/release，
 * 双方都退出后才真正停轮询。
 */
class SessionRepository(
    context: Context,
    private val apiFactory: (String) -> SPlayerApi = { HttpSPlayerApi(it) },
    private val discovery: suspend () -> String? = { ServerDiscovery.discover() },
    private val clock: Clock = MonotonicClock,
) {

    val settings = SettingsRepository(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(
        SessionState(baseUrl = settings.current.baseUrl)
    )
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** 当前客户端；换地址时整体替换，轮询每轮重新读它 */
    @Volatile
    private var api: SPlayerApi = apiFactory(settings.current.baseUrl)

    private var pollJob: Job? = null
    private var settingsJob: Job? = null
    private var volumeJob: Job? = null

    /** Activity 与 Service 的占用计数 */
    private var acquireCount = 0

    private var foreground = true

    private var consecutiveFailures = 0
    private var lastDiscoveryAt = 0L
    private var lastError: String? = null

    private var queueMetaAt = 0L

    /**
     * 歌词状态。
     *
     * 关键点：只有**真的拿到非空歌词**才记 [lyricsLoadedFor]。
     * 之前的写法在发起请求前就把曲目记为「已取过」，于是换歌那一刻
     * `/api/lyrics` 还没准备好、返回空数组，这首歌词就被永久判成"没有"，
     * 界面上「看歌词」按钮直接消失——这正是切换歌曲后按钮不见的原因。
     */
    private var lyricsLoadedFor: String? = null

    /** 最近一次轮询到的曲目 id，用来检测换歌 */
    private var lyricsTrackId: String? = null

    /** 当前曲目已尝试拉取歌词的次数，避免无歌词的歌被无限重试 */
    private var lyricsAttempts = 0

    /** 下次允许重试歌词的时刻 */
    private var lyricsNextAttemptAt = 0L

    /** 用户刚拖过音量，短时间内不接受服务端回读覆盖 */
    private var volumeLockUntil = 0L

    init {
        observeSettings()
        if (settings.current.autoConnect) {
            _state.update { it.copy(connection = Connection.Connecting) }
        }
    }

    // ---------------------------------------------------------------- 生命周期

    /** Activity / Service 进入活跃状态 */
    fun acquire() {
        acquireCount++
        if (pollJob == null) startPolling()
    }

    /** 释放；计数归零才真正停 */
    fun release() {
        acquireCount = (acquireCount - 1).coerceAtLeast(0)
        if (acquireCount == 0) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    /** 前台状态影响轮询节奏 */
    fun setForeground(value: Boolean) {
        foreground = value
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                // 每轮重新读 api：自动发现或用户改地址后，下一轮就用新客户端。
                // 之前把这个引用在循环外捕获，导致发现成功后仍连旧地址。
                val client = api
                val ok = refreshOnce(client)
                val base = settings.current.pollIntervalMs

                if (ok) {
                    consecutiveFailures = 0
                    val playing = _state.value.status?.value?.isPlaying == true
                    delay(PollPolicy.pollDelay(base, playing, foreground))
                } else {
                    consecutiveFailures++
                    if (!PollPolicy.isDisconnected(consecutiveFailures)) {
                        // 容忍期：保持"连接中"，不弹错误
                        _state.update {
                            it.copy(connection = Connection.Connecting, error = null)
                        }
                        delay(PollPolicy.retryDelay(base))
                    } else {
                        _state.update {
                            it.copy(connection = Connection.Failed, error = lastError)
                        }
                        val now = clock.now()
                        if (PollPolicy.shouldDiscover(
                                autoDiscoverEnabled = settings.current.autoDiscover,
                                consecutiveFailures = consecutiveFailures,
                                lastDiscoveryAt = lastDiscoveryAt,
                                now = now,
                            )
                        ) {
                            lastDiscoveryAt = now
                            runDiscovery(switchToFound = true)
                        }
                        delay(PollPolicy.OFFLINE_RETRY_MS)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 设置联动

    private fun observeSettings() {
        settingsJob?.cancel()
        settingsJob = scope.launch {
            settings.settings.collect { next ->
                val previousBase = _state.value.baseUrl
                if (next.baseUrl != previousBase) {
                    switchBaseUrl(next.baseUrl)
                }
                // 轮询间隔每轮现读，不需要重启循环
            }
        }
    }

    private fun switchBaseUrl(url: String) {
        api = apiFactory(url)
        consecutiveFailures = 0
        lyricsLoadedFor = null
        lyricsTrackId = null
        lyricsAttempts = 0
        lyricsNextAttemptAt = 0L
        queueMetaAt = 0L
        _state.update {
            it.copy(
                baseUrl = url,
                connection = Connection.Connecting,
                error = null,
                serverInfo = null,
                track = null,
                status = null,
                lyrics = emptyList(),
                lyricsTrackId = null,
                queue = QueueState(),
            )
        }
    }

    /** 用户主动改地址（设置页） */
    fun setBaseUrl(raw: String) {
        val normalized = SPlayerApi.normalizeBaseUrl(raw)
        settings.setBaseUrl(normalized)
    }

    /** 手动重连 */
    fun reconnect() {
        consecutiveFailures = 0
        lastDiscoveryAt = 0L
        switchBaseUrl(settings.current.baseUrl)
        if (pollJob == null) startPolling()
    }

    /** 手动触发一次局域网发现 */
    fun discover() {
        scope.launch { runDiscovery(switchToFound = true) }
    }

    private suspend fun runDiscovery(switchToFound: Boolean) {
        if (_state.value.discovering) return
        _state.update { it.copy(discovering = true) }
        val found = try {
            discovery()
        } catch (e: CancellationException) {
            _state.update { it.copy(discovering = false) }
            throw e
        } catch (e: Exception) {
            null
        }
        if (found == null) {
            _state.update {
                it.copy(
                    discovering = false,
                    message = "没有在当前网段找到 SPlayer",
                )
            }
            return
        }
        _state.update { it.copy(discovering = false) }
        if (switchToFound && found != settings.current.baseUrl) {
            // 写回设置会触发 observeSettings → switchBaseUrl，下一轮轮询即生效
            settings.setBaseUrl(found)
        } else {
            switchBaseUrl(found)
        }
    }

    // ---------------------------------------------------------------- 轮询

    private suspend fun refreshOnce(client: SPlayerApi): Boolean {
        return try {
            val info = client.info()
            val now = client.nowPlaying()
            val status = client.status()
            val fetchedAt = clock.now()

            val track = now.track
            val trackId = track?.id

            // 换歌：先清掉上一首的歌词，否则会短暂显示别人的词
            if (trackId != lyricsTrackId) {
                lyricsTrackId = trackId
                lyricsLoadedFor = null
                lyricsAttempts = 0
                lyricsNextAttemptAt = 0L
                _state.update { it.copy(lyrics = emptyList(), lyricsTrackId = trackId) }
            }

            // 歌词：拿到非空结果才算完成；失败或空结果按间隔重试，
            // 因为换歌瞬间 SPlayer 往往还没把歌词准备好
            val lyrics = when {
                trackId == null -> emptyList()
                lyricsLoadedFor == trackId -> _state.value.lyrics
                lyricsAttempts >= MAX_LYRICS_ATTEMPTS -> _state.value.lyrics
                fetchedAt < lyricsNextAttemptAt -> _state.value.lyrics
                else -> {
                    lyricsAttempts++
                    lyricsNextAttemptAt = fetchedAt + LYRICS_RETRY_MS
                    val fetched = runCatching { client.lyrics().lyric }.getOrNull()
                    if (!fetched.isNullOrEmpty()) {
                        lyricsLoadedFor = trackId
                        fetched
                    } else {
                        emptyList()
                    }
                }
            }

            // 队列元信息降频：只取 1 条就能拿到 total 与 currentIndex，很便宜
            if (fetchedAt - queueMetaAt > QUEUE_META_REFRESH_MS) {
                queueMetaAt = fetchedAt
                runCatching { client.queue(offset = 0, limit = 1) }.getOrNull()?.let { meta ->
                    _state.update {
                        it.copy(
                            queue = it.queue.copy(
                                total = meta.total,
                                currentIndex = meta.currentIndex,
                            )
                        )
                    }
                    ensureQueuePageFor(meta.currentIndex)
                }
            }

            _state.update { state ->
                val volume = if (clock.now() < volumeLockUntil) {
                    state.volume
                } else {
                    status.volume
                }
                state.copy(
                    connection = Connection.Connected,
                    error = null,
                    serverInfo = info,
                    track = track,
                    status = Sample(status, fetchedAt),
                    speed = now.speed.takeIf { it > 0f } ?: 1f,
                    volume = volume,
                    lyrics = lyrics,
                    lyricsTrackId = trackId,
                )
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastError = describe(e)
            false
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is top.dsh.splayerremote.data.SPlayerHttpException ->
            "服务端返回 ${e.code}：${e.message}"
        else -> e.message ?: "无法连接"
    }

    // ---------------------------------------------------------------- 队列分页

    /** 界面滚到某处时按需加载对应页 */
    fun ensureQueueLoaded(index: Int) {
        ensureQueuePageFor(index)
    }

    private fun ensureQueuePageFor(index: Int) {
        if (index < 0) return
        val page = index / QUEUE_PAGE_SIZE
        val queue = _state.value.queue
        if (page in queue.loadingPages) return
        if (queue.items.containsKey(index)) return

        _state.update { it.copy(queue = it.queue.copy(loadingPages = it.queue.loadingPages + page)) }
        scope.launch {
            try {
                val resp = api.queue(offset = page * QUEUE_PAGE_SIZE, limit = QUEUE_PAGE_SIZE)
                _state.update { state ->
                    val merged = state.queue.items.toMutableMap()
                    resp.tracks.forEachIndexed { i, t -> merged[resp.offset + i] = t }
                    state.copy(
                        queue = state.queue.copy(
                            total = resp.total,
                            currentIndex = resp.currentIndex,
                            items = merged,
                            loadingPages = state.queue.loadingPages - page,
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(queue = it.queue.copy(loadingPages = it.queue.loadingPages - page))
                }
            }
        }
    }

    // ---------------------------------------------------------------- 指令

    fun togglePlayPause() = command { it.togglePlayPause() }

    fun next() = command { it.next() }

    fun prev() = command { it.prev() }

    fun stopPlayback() = command { it.stop() }

    fun seekTo(positionMs: Long) = command { it.seek(positionMs) }

    /**
     * 音量：先本地生效、合并 150ms 内的连续拖动，再发一次请求。
     *
     * 之前每拖动一个像素就发一次 POST，一次拖动上百个请求，
     * 而轮询又把服务端旧值覆盖回来，滑块会往回跳。
     */
    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        volumeLockUntil = clock.now() + VOLUME_LOCK_MS
        _state.update { it.copy(volume = clamped) }

        volumeJob?.cancel()
        volumeJob = scope.launch {
            delay(VOLUME_DEBOUNCE_MS)
            runCatching { api.setVolume(clamped) }
        }
    }

    /**
     * 指令失败的分层：
     *  - 业务拒绝（ok:false）：只提示，不动连接状态；
     *  - 传输失败：不立刻标离线，交给轮询的状态机判定——
     *    否则一次切歌超时就会把界面闪成"未连接"。
     */
    private fun command(block: suspend (SPlayerApi) -> Unit) {
        scope.launch {
            try {
                block(api)
                // 指令之后立即回读一次，界面更跟手
                refreshOnce(api)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SPlayerBusinessException) {
                _state.update { it.copy(message = e.message) }
            } catch (e: Exception) {
                lastError = describe(e)
            }
        }
    }

    /** 消费一次性提示 */
    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    companion object {
        /** 队列分页大小；与服务端 limit 上限一致（1..100） */
        const val QUEUE_PAGE_SIZE = 100

        /** 队列元信息（total / currentIndex）的刷新间隔 */
        private const val QUEUE_META_REFRESH_MS = 2_000L

        /** 歌词重试间隔：换歌瞬间服务端常常还没准备好 */
        private const val LYRICS_RETRY_MS = 700L

        /** 单曲最多重试次数，约 14 秒后放弃 */
        private const val MAX_LYRICS_ATTEMPTS = 20

        /** 音量合并窗口 */
        private const val VOLUME_DEBOUNCE_MS = 150L

        /** 本地音量优先期，避免轮询回读把滑块拽回去 */
        private const val VOLUME_LOCK_MS = 800L
    }
}

/** 便于 Activity / Service 拿到应用级会话 */
val Context.session: SessionRepository
    get() = (applicationContext as RemoteApp).session
