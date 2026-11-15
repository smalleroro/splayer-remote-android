package top.dsh.splayerremote.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.dsh.splayerremote.data.LyricLine
import top.dsh.splayerremote.data.AppSettings
import top.dsh.splayerremote.data.ServerDiscovery
import top.dsh.splayerremote.data.SettingsRepository
import top.dsh.splayerremote.data.PlaybackQueue
import top.dsh.splayerremote.data.PlaybackStatus
import top.dsh.splayerremote.data.SPlayerApi
import top.dsh.splayerremote.data.SPlayerException
import top.dsh.splayerremote.data.ServerInfo
import top.dsh.splayerremote.data.Track

/** 连接状态，界面顶部据此显示指示灯 */
enum class ConnectionState { Unknown, Connecting, Connected, Failed }

data class RemoteUiState(
    val baseUrl: String = "http://127.0.0.1:14558",
    val connection: ConnectionState = ConnectionState.Unknown,
    val errorMessage: String? = null,
    val serverInfo: ServerInfo? = null,
    val status: PlaybackStatus? = null,
    val currentTrack: Track? = null,
    val queue: PlaybackQueue? = null,
    val volume: Float = 1f,
    /** 歌词行；无歌词时为空列表 */
    val lyrics: List<LyricLine> = emptyList(),
    /** 当前高亮行在 lyrics 里的下标，-1 表示还没到第一行 */
    val currentLyricIndex: Int = -1,
    /** 歌词属于哪首曲目，用来判断换歌后要不要重新拉 */
    val lyricsTrackId: String? = null,
    /** 歌词面板是否展开 */
    val showLyrics: Boolean = false,
    /** 用户正在拖动进度条时为 true，此时忽略轮询回来的 position */
    val isSeeking: Boolean = false,
    val seekPreviewMs: Long = 0L,
    /** 播放速度倍率，本地插值时按它推进 */
    val speed: Float = 1f,
    /** 是否正在扫描局域网 */
    val discovering: Boolean = false,
)

/**
 * 遥控端的状态中枢。
 *
 * 只做三件事：轮询 /api/now-playing 与 /api/status、转发控制指令、维护界面状态。
 * 轮询间隔 1 秒——官方文档说明 now-playing 是"适合频繁轮询的轻量快照"，
 * 再密意义不大，再疏进度条会跳。
 */
class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    /** 设置仓库：UI 和前台服务共用同一份 */
    val settingsRepo = SettingsRepository(app)

    private val _ui = MutableStateFlow(
        RemoteUiState(baseUrl = settingsRepo.settings.value.baseUrl)
    )
    val ui: StateFlow<RemoteUiState> = _ui.asStateFlow()

    /**
     * 当前使用的 API 客户端。
     *
     * 写 `@Volatile` 是因为它会被两个协程上下文读到：轮询协程与指令协程。
     * 换服务器时整体替换实例，读方永远拿到一个完整、可用的对象。
     */
    @Volatile
    private var api: SPlayerApi = SPlayerApi(settingsRepo.settings.value.baseUrl)

    private var pollJob: Job? = null

    /**
     * 最近一次拉取失败的原始原因。
     *
     * 不直接写进界面状态：单次失败会在容忍期内被吞掉，
     * 只有连续失败到阈值才把它作为断开原因展示出来。
     */
    @Volatile
    private var lastError: String? = null

    /** 扫描进行中标记，避免并发触发多次扫描 */
    @Volatile
    private var discovering: Boolean = false

    init {
        if (settingsRepo.settings.value.autoConnect) {
            connect()
        }
    }

    // ---------------------------------------------------------------- 连接

    fun updateBaseUrl(raw: String) {
        val normalized = SPlayerApi.normalizeBaseUrl(raw)
        settingsRepo.update { it.copy(baseUrl = normalized) }
        // 先换实例再重连：connect() 内部会取消旧轮询，
        // 旧协程即便刚读到旧实例，也因为取消而不会再回写状态
        api = SPlayerApi(normalized)
        _ui.update { it.copy(baseUrl = normalized, connection = ConnectionState.Connecting, errorMessage = null) }
        connect()
    }

    /** 更换任意设置；返回时界面会立刻用新值重绘 */
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val before = settingsRepo.settings.value
        settingsRepo.update(transform)
        val after = settingsRepo.settings.value

        // 服务器地址变了要重建客户端并重连
        if (before.baseUrl != after.baseUrl) {
            api = SPlayerApi(after.baseUrl)
            _ui.update { it.copy(baseUrl = after.baseUrl) }
            connect()
            return
        }

        // 轮询间隔变了要重启轮询才生效
        if (before.pollIntervalMs != after.pollIntervalMs) {
            connect()
        }
    }

    fun connect() {
        pollJob?.cancel()
        _ui.update { it.copy(connection = ConnectionState.Connecting, errorMessage = null) }
        val client = api
        // 读一次设置里的间隔：用户调到 2 秒就真按 2 秒跑
        val interval = settingsRepo.settings.value.pollIntervalMs
        pollJob = viewModelScope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                val ok = refreshOnce(client)
                if (ok) {
                    consecutiveFailures = 0
                    delay(interval)
                } else {
                    consecutiveFailures++

                    if (consecutiveFailures < FAILURE_THRESHOLD_TO_DISCONNECT) {
                        // 容忍期：WiFi 抖动、服务端一次 GC 都会让请求超时，
                        // 立刻标"未连接"会让界面来回闪。
                        // 这段时间保持"连接中"外观，不弹错误信息。
                        _ui.update {
                            it.copy(
                                connection = ConnectionState.Connecting,
                                errorMessage = null,
                            )
                        }
                        delay(shortRetryInterval(interval))
                    } else {
                        // 连续失败达到阈值，认定真的断了
                        _ui.update {
                            it.copy(
                                connection = ConnectionState.Failed,
                                errorMessage = lastError,
                            )
                        }
                        delay(RETRY_INTERVAL_MS)
                        maybeAutoDiscover(interval)
                    }
                }
            }
        }
    }

    /**
     * 容忍期内的短重试间隔。
     *
     * 取轮询间隔的一半并夹在 300~1000ms 之间：
     * 间隔设得很短的用户希望快速恢复，设得长的也不该等太久。
     */
    private fun shortRetryInterval(pollInterval: Long): Long =
        (pollInterval / 2).coerceIn(300L, 1_000L)

    /**
     * 连不上且开了「自动扫描」时，去局域网里找一次。
     *
     * 找到就换地址重连；找不到就继续按原节奏重试，
     * 不在这里反复扫描——254 个地址扫一遍不便宜。
     */
    private suspend fun maybeAutoDiscover(interval: Long) {
        if (!settingsRepo.settings.value.autoDiscover) return
        if (discovering) return

        discovering = true
        _ui.update { it.copy(discovering = true) }
        val found = runCatching { ServerDiscovery.discover() }.getOrNull()
        discovering = false
        _ui.update { it.copy(discovering = false) }

        if (found != null && found != settingsRepo.settings.value.baseUrl) {
            settingsRepo.update { it.copy(baseUrl = found) }
            api = SPlayerApi(found)
            _ui.update { it.copy(baseUrl = found) }
        }
    }

    /** 手动触发一次发现 */
    fun discoverServer() {
        if (discovering) return
        viewModelScope.launch {
            discovering = true
            _ui.update { it.copy(discovering = true) }
            val found = runCatching { ServerDiscovery.discover() }.getOrNull()
            discovering = false
            if (found != null) {
                settingsRepo.update { it.copy(baseUrl = found) }
                api = SPlayerApi(found)
                _ui.update { it.copy(baseUrl = found, discovering = false) }
                connect()
            } else {
                _ui.update {
                    it.copy(
                        discovering = false,
                        errorMessage = "没有在当前网段找到 SPlayer",
                    )
                }
            }
        }
    }

    /**
     * 拉一次完整状态；返回是否成功。
     *
     * [client] 由调用方传入而不是直接读字段：换服务器时旧协程可能仍在飞行中，
     * 用自己那份实例能保证这次请求全程对着同一台机器。
     */
    private suspend fun refreshOnce(client: SPlayerApi): Boolean {
        return try {
            // info / now-playing / status 是核心三段，任一失败即视为断开
            val info = client.info()
            val now = client.nowPlaying()
            val status = client.status()
            // 队列是附属信息：拿不到时保留上一次的结果，
            // 而不是把界面刷成"队列为空"——那会让人误以为队列被清空了
            val queue = runCatching { client.queue(0, 100) }.getOrNull()

            // 歌词只在换歌时重新拉，不用每秒都请求。
            // 用 trackId 判断是不是同一首——标题可能重复，id 不会。
            val trackId = now.track?.id
            val trackChanged = trackId != _ui.value.lyricsTrackId
            val lyrics: List<LyricLine> = if (trackChanged) {
                runCatching { client.lyrics().lyric }.getOrDefault(emptyList())
            } else {
                _ui.value.lyrics
            }

            val position = status.position
            val nextIndex = if (lyrics.isEmpty()) -1 else findLyricIndex(lyrics, position)

            _ui.update { state ->
                state.copy(
                    connection = ConnectionState.Connected,
                    errorMessage = null,
                    serverInfo = info,
                    status = status,
                    currentTrack = now.track,
                    queue = queue ?: state.queue,
                    volume = status.volume,
                    lyrics = lyrics,
                    lyricsTrackId = if (trackChanged) trackId else state.lyricsTrackId,
                    currentLyricIndex = nextIndex,
                    seekPreviewMs = if (state.isSeeking) state.seekPreviewMs else position,
                )
            }
            true
        } catch (e: SPlayerException) {
            lastError = "服务端返回 ${e.code}：${e.message}"
            false
        } catch (e: Exception) {
            lastError = e.message ?: "无法连接"
            false
        }
    }

    /**
     * 二分查找当前时间落在哪一行歌词里。
     *
     * 歌词按 startTime 升序排列，行数一般几十到几百，
     * 线上扫描也够快，但二分更稳，切成高频轮询也不会成为瓶颈。
     */
    private fun findLyricIndex(lyrics: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lyrics.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lyrics[mid].startTime <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    fun toggleLyrics() {
        _ui.update { it.copy(showLyrics = !it.showLyrics) }
    }

    private fun markFailed(message: String) {
        _ui.update { it.copy(connection = ConnectionState.Failed, errorMessage = message) }
    }

    // ---------------------------------------------------------------- 控制

    fun togglePlayPause() = command { it.togglePlayPause() }

    fun next() = command { it.next() }

    fun prev() = command { it.prev() }

    fun stop() = command { it.stop() }

    fun setVolume(volume: Float) {
        _ui.update { it.copy(volume = volume) }
        viewModelScope.launch {
            runCatching { api.setVolume(volume) }
        }
    }

    /** 拖动中：只更新本地预览，不发请求 */
    fun onSeekStart() {
        _ui.update { it.copy(isSeeking = true, seekPreviewMs = it.status?.position ?: 0L) }
    }

    fun onSeekChange(positionMs: Long) {
        _ui.update { it.copy(seekPreviewMs = positionMs) }
    }

    /** 松手：发 seek 请求并立即刷新一次 */
    fun onSeekCommit() {
        val target = _ui.value.seekPreviewMs
        _ui.update { it.copy(isSeeking = false) }
        viewModelScope.launch {
            val client = api
            runCatching { client.seek(target) }
            refreshOnce(client)
        }
    }

    private fun command(block: suspend (SPlayerApi) -> Any) {
        viewModelScope.launch {
            val client = api
            try {
                block(client)
                // 指令发出后立刻回读一次，让界面跟上真实状态
                refreshOnce(client)
            } catch (e: Exception) {
                markFailed(e.message ?: "指令发送失败")
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    companion object {
        /**
         * 默认地址：本机回环。手机上连不到电脑时用户需要在设置里改成电脑内网 IP。
         *
         * 之所以不用内网 IP 写死：换网段就失效，而且每台电脑不一样。
         * 更稳妥的做法是启动时并发探测网关所在网段的常见主机，
         * 但那会拖慢冷启动，这里保持简单——由用户填一次，之后记在 SharedPreferences。
         */
        private const val RETRY_INTERVAL_MS = 3_000L

        /**
         * 连续失败多少次才认定真的断开。
         *
         * 2 次意味着：单次超时被容忍，连续两次才报警。
         * 轮询间隔 500ms 时大约 1 秒就能确认断开，间隔 5 秒时约 10 秒——
         * 对"看一眼手机发现没连上"这个场景都够用。
         */
        private const val FAILURE_THRESHOLD_TO_DISCONNECT = 2
    }
}
