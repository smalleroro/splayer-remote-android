package top.dsh.splayerremote.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 主题模式 */
enum class ThemeMode { System, Dark, Light }

/** 歌词字号 */
enum class LyricFontSize { Small, Medium, Large }

/** 封面尺寸 */
enum class CoverSize { Small, Medium, Large }

/**
 * 进度条外观。
 *
 * 用自绘代替 Material Slider：Slider 的滑块形状与轨道粗细写死在组件里，
 * 想「让用户自己挑」只能自己画。
 */
enum class ProgressStyle {
    /** 流动波浪，形如一个「～」，播放时向前涌 */
    Wavy,

    /** M3 新版：粗轨 + 缝隙 + 竖直头部 */
    Expressive,

    /** 经典圆点滑块 */
    Classic,

    /** 细线，尾部小圆点 */
    Thin,

    /** 分段格子 */
    Segment,

    /** 音频波形柱 */
    Waveform,
}

/**
 * 应用设置快照。
 *
 * 全部是不可变值，界面直接读这个对象；改设置时整体替换，
 * Compose 靠引用比较就能感知变化。
 */
data class AppSettings(
    // 连接
    val baseUrl: String = SPlayerApi.DEFAULT_BASE_URL,
    /** 轮询间隔毫秒；越小越跟手，越费电 */
    val pollIntervalMs: Long = 1_000L,
    /** 启动时自动尝试连接 */
    val autoConnect: Boolean = true,
    /** 连不上时自动扫描局域网 */
    val autoDiscover: Boolean = true,

    // 外观
    val themeMode: ThemeMode = ThemeMode.System,
    val coverSize: CoverSize = CoverSize.Medium,
    /** 进度条样式；默认流动波浪 */
    val progressStyle: ProgressStyle = ProgressStyle.Wavy,

    // 歌词
    val showTranslation: Boolean = true,
    val lyricFontSize: LyricFontSize = LyricFontSize.Medium,
    val autoScrollLyrics: Boolean = true,

    // 通知
    /** 关掉后不启动前台服务，只在应用前台时轮询 */
    val persistentNotification: Boolean = true,
    /** 通知里是否显示专辑封面 */
    val notificationCover: Boolean = true,

    // 行为
    /** 退出应用时是否一并停掉前台服务 */
    val stopServiceOnExit: Boolean = false,
)

/**
 * 可观察的设置仓库。
 *
 * 之前的问题：前台服务只在 onCreate 读一次 SharedPreferences，
 * 于是改完地址通知栏还连着旧机器；三个开关更是没有任何消费方。
 * 现在统一从这里读，改任何一个值都会推给订阅者（UI 与服务）。
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    private fun load(): AppSettings = AppSettings(
        baseUrl = SPlayerApi.normalizeBaseUrl(
            prefs.getString(KEY_BASE_URL, SPlayerApi.DEFAULT_BASE_URL) ?: SPlayerApi.DEFAULT_BASE_URL
        ),
        pollIntervalMs = prefs.getLong(KEY_POLL_INTERVAL, 1_000L),
        autoConnect = prefs.getBoolean(KEY_AUTO_CONNECT, true),
        autoDiscover = prefs.getBoolean(KEY_AUTO_DISCOVER, true),
        themeMode = prefs.getString(KEY_THEME, ThemeMode.System.name)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
        coverSize = prefs.getString(KEY_COVER_SIZE, CoverSize.Medium.name)
            ?.let { runCatching { CoverSize.valueOf(it) }.getOrNull() } ?: CoverSize.Medium,
        progressStyle = prefs.getString(KEY_PROGRESS_STYLE, ProgressStyle.Wavy.name)
            ?.let { runCatching { ProgressStyle.valueOf(it) }.getOrNull() } ?: ProgressStyle.Wavy,
        showTranslation = prefs.getBoolean(KEY_SHOW_TRANSLATION, true),
        lyricFontSize = prefs.getString(KEY_LYRIC_FONT, LyricFontSize.Medium.name)
            ?.let { runCatching { LyricFontSize.valueOf(it) }.getOrNull() } ?: LyricFontSize.Medium,
        autoScrollLyrics = prefs.getBoolean(KEY_AUTO_SCROLL, true),
        persistentNotification = prefs.getBoolean(KEY_PERSISTENT_NOTIFICATION, true),
        notificationCover = prefs.getBoolean(KEY_NOTIFICATION_COVER, true),
        stopServiceOnExit = prefs.getBoolean(KEY_STOP_ON_EXIT, false),
    )

    /** 批量更新：传一个修改函数，写盘并广播 */
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString(KEY_BASE_URL, next.baseUrl)
            .putLong(KEY_POLL_INTERVAL, next.pollIntervalMs)
            .putBoolean(KEY_AUTO_CONNECT, next.autoConnect)
            .putBoolean(KEY_AUTO_DISCOVER, next.autoDiscover)
            .putString(KEY_THEME, next.themeMode.name)
            .putString(KEY_COVER_SIZE, next.coverSize.name)
            .putString(KEY_PROGRESS_STYLE, next.progressStyle.name)
            .putBoolean(KEY_SHOW_TRANSLATION, next.showTranslation)
            .putString(KEY_LYRIC_FONT, next.lyricFontSize.name)
            .putBoolean(KEY_AUTO_SCROLL, next.autoScrollLyrics)
            .putBoolean(KEY_PERSISTENT_NOTIFICATION, next.persistentNotification)
            .putBoolean(KEY_NOTIFICATION_COVER, next.notificationCover)
            .putBoolean(KEY_STOP_ON_EXIT, next.stopServiceOnExit)
            .apply()

        _settings.value = next
    }

    /** 只改服务器地址，顺带规范化——所有写入路径都走这里 */
    fun setBaseUrl(raw: String) {
        val normalized = SPlayerApi.normalizeBaseUrl(raw)
        update { it.copy(baseUrl = normalized) }
    }

    companion object {
        const val PREFS_NAME = "splayer_remote"

        // base_url 这个键沿用旧版本的名字，避免升级后用户配置丢失
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_POLL_INTERVAL = "poll_interval_ms"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_AUTO_DISCOVER = "auto_discover"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_COVER_SIZE = "cover_size"
        private const val KEY_PROGRESS_STYLE = "progress_style"
        private const val KEY_SHOW_TRANSLATION = "show_translation"
        private const val KEY_LYRIC_FONT = "lyric_font_size"
        private const val KEY_AUTO_SCROLL = "auto_scroll_lyrics"
        private const val KEY_PERSISTENT_NOTIFICATION = "persistent_notification"
        private const val KEY_NOTIFICATION_COVER = "notification_cover"
        private const val KEY_STOP_ON_EXIT = "stop_service_on_exit"
    }
}
