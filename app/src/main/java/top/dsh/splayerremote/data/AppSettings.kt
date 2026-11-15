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
 * 应用设置快照。
 *
 * 全部是不可变值，界面直接读这个对象；改设置时整体替换，
 * Compose 靠引用比较就能感知变化。
 */
data class AppSettings(
    // 连接
    val baseUrl: String = SPlayerApi.DEFAULT_BASE_URL,
    /** 轮询间隔毫秒；越小越跟手，越费电 */
    val pollIntervalMs: Long = 500L,
    /** 启动时自动尝试连接 */
    val autoConnect: Boolean = true,
    /** 找不到配置的服务器时自动扫描局域网 */
    val autoDiscover: Boolean = false,

    // 外观
    val themeMode: ThemeMode = ThemeMode.System,
    val coverSize: CoverSize = CoverSize.Medium,

    // 歌词
    val showTranslation: Boolean = true,
    val lyricFontSize: LyricFontSize = LyricFontSize.Medium,
    val autoScrollLyrics: Boolean = true,

    // 通知
    val persistentNotification: Boolean = true,
    val notificationCover: Boolean = true,

    // 行为
    val stopServiceOnExit: Boolean = false,
)

/**
 * 设置的读写与广播。
 *
 * 用 SharedPreferences 存盘（键名与前台服务共用），
 * 再用 StateFlow 把变更推给 Compose 和 Service。
 * 设置改动要同时影响 UI 与常驻通知，所以集中在这里管理。
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(SPlayerApi.PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings = AppSettings(
        baseUrl = SPlayerApi.normalizeBaseUrl(
            prefs.getString(KEY_BASE_URL, SPlayerApi.DEFAULT_BASE_URL) ?: SPlayerApi.DEFAULT_BASE_URL
        ),
        pollIntervalMs = prefs.getLong(KEY_POLL_INTERVAL, 500L),
        autoConnect = prefs.getBoolean(KEY_AUTO_CONNECT, true),
        autoDiscover = prefs.getBoolean(KEY_AUTO_DISCOVER, false),
        themeMode = prefs.getString(KEY_THEME, ThemeMode.System.name)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System,
        coverSize = prefs.getString(KEY_COVER_SIZE, CoverSize.Medium.name)
            ?.let { runCatching { CoverSize.valueOf(it) }.getOrNull() } ?: CoverSize.Medium,
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
        val editor = prefs.edit()
        editor.putString(KEY_BASE_URL, next.baseUrl)
        editor.putLong(KEY_POLL_INTERVAL, next.pollIntervalMs)
        editor.putBoolean(KEY_AUTO_CONNECT, next.autoConnect)
        editor.putBoolean(KEY_AUTO_DISCOVER, next.autoDiscover)
        editor.putString(KEY_THEME, next.themeMode.name)
        editor.putString(KEY_COVER_SIZE, next.coverSize.name)
        editor.putBoolean(KEY_SHOW_TRANSLATION, next.showTranslation)
        editor.putString(KEY_LYRIC_FONT, next.lyricFontSize.name)
        editor.putBoolean(KEY_AUTO_SCROLL, next.autoScrollLyrics)
        editor.putBoolean(KEY_PERSISTENT_NOTIFICATION, next.persistentNotification)
        editor.putBoolean(KEY_NOTIFICATION_COVER, next.notificationCover)
        editor.putBoolean(KEY_STOP_ON_EXIT, next.stopServiceOnExit)
        editor.apply()

        _settings.value = next
    }

    companion object {
        // base_url 这个键沿用旧版本的名字，避免升级后用户配置丢失
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_POLL_INTERVAL = "poll_interval_ms"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_AUTO_DISCOVER = "auto_discover"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_COVER_SIZE = "cover_size"
        private const val KEY_SHOW_TRANSLATION = "show_translation"
        private const val KEY_LYRIC_FONT = "lyric_font_size"
        private const val KEY_AUTO_SCROLL = "auto_scroll_lyrics"
        private const val KEY_PERSISTENT_NOTIFICATION = "persistent_notification"
        private const val KEY_NOTIFICATION_COVER = "notification_cover"
        private const val KEY_STOP_ON_EXIT = "stop_service_on_exit"
    }
}
