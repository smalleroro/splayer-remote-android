package top.dsh.splayerremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.dsh.splayerremote.data.PlaybackStatus
import top.dsh.splayerremote.data.SPlayerApi
import top.dsh.splayerremote.data.Track
import java.net.URL

/**
 * 常驻通知遥控服务。
 *
 * 目的：退出 App 后仍然能锁屏/下拉通知栏控制播放，不用每次切回应用。
 *
 * 结构：
 *   - 前台服务保活，每秒轮询 /api/now-playing 与 /api/status；
 *   - 通知里显示歌名、歌手、播放状态，附带上一首/暂停/下一首三个按钮；
 *   - 点按通知本体回到 App。
 *
 * 为什么不常驻 WebSocket：服务端 ws 需要额外开启，而且断线重连逻辑更复杂；
 * 这里每秒一次 HTTP 已经够用，且与 App 内轮询保持同一套行为。
 */
class RemoteService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null

    private lateinit var api: SPlayerApi

    /** 上次通知的内容指纹，避免每秒重建通知导致闪烁 */
    private var lastSignature: String? = null

    /** 封面缓存，避免每秒下载同一张图 */
    private var coverCacheKey: String? = null
    private var coverBitmap: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        api = SPlayerApi(readBaseUrl())
        startForeground(NOTIFICATION_ID, buildNotification(null, null))
        startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> command { it.togglePlayPause() }
            ACTION_NEXT -> command { it.next() }
            ACTION_PREV -> command { it.prev() }
            ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 轮询

    private fun startPolling() {
        pollJob = scope.launch {
            while (isActive) {
                val ok = refreshOnce()
                delay(if (ok) POLL_INTERVAL_MS else RETRY_INTERVAL_MS)
            }
        }
    }

    private suspend fun refreshOnce(): Boolean {
        return try {
            val now = api.nowPlaying()
            val status = api.status()
            val track = now.track

            // 只在内容变化时重建通知
            val signature = buildString {
                append(track?.id ?: "none")
                append('|').append(status.state)
                append('|').append(track?.title ?: "")
                append('|').append(status.volume)
            }
            if (signature != lastSignature) {
                lastSignature = signature
                val cover = loadCover(track?.cover ?: track?.album?.cover)
                val notification = buildNotification(track, status, cover)
                withContext(Dispatchers.Main) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification)
                }
            }
            true
        } catch (e: Exception) {
            // 连不上时把状态写进通知，让用户知道不是按钮坏了
            val signature = "error:${e.javaClass.simpleName}"
            if (signature != lastSignature) {
                lastSignature = signature
                val notification = buildErrorNotification(e.message ?: "无法连接")
                withContext(Dispatchers.Main) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification)
                }
            }
            false
        }
    }

    private fun command(block: suspend (SPlayerApi) -> Unit) {
        scope.launch {
            try {
                block(api)
                // 指令发出后立刻回读，通知更新更跟手
                refreshOnce()
            } catch (_: Exception) {
                // 失败时下一轮轮询会把错误写进通知
            }
        }
    }

    /** 封面按 URL 缓存，同一首歌不重复下载 */
    private suspend fun loadCover(url: String?): Bitmap? {
        if (url == null) return null
        if (url == coverCacheKey) return coverBitmap
        return try {
            val bitmap = withContext(Dispatchers.IO) {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            }
            coverCacheKey = url
            coverBitmap = bitmap
            bitmap
        } catch (e: Exception) {
            coverCacheKey = url
            coverBitmap = null
            null
        }
    }

    // ---------------------------------------------------------------- 通知

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "遥控器",
            // 播放控制类通知用 LOW：常驻但不吵，不弹横幅
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "SPlayer 遥控器的常驻播放控制"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(
        track: Track?,
        status: PlaybackStatus?,
        cover: Bitmap? = null,
    ): Notification {
        val isPlaying = status?.state == "playing"

        val title = track?.title?.takeIf { it.isNotBlank() } ?: "SPlayer 遥控器"
        val artist = track?.artists?.joinToString(" / ") { it.name }.orEmpty()
        val subtitle = if (artist.isNotBlank()) artist else "未连接或没有播放内容"

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (cover != null) {
            builder.setLargeIcon(cover)
        }

        // 进度条：有总时长才显示
        val duration = status?.duration ?: 0L
        if (duration > 0L) {
            builder.setProgress(
                1000,
                (((status?.position ?: 0L).toFloat() / duration) * 1000).toInt().coerceIn(0, 1000),
                false,
            )
        }

        // 三个控制按钮
        val playPause = if (isPlaying) {
            action(ACTION_PLAY_PAUSE, android.R.drawable.ic_media_pause, "暂停")
        } else {
            action(ACTION_PLAY_PAUSE, android.R.drawable.ic_media_play, "播放")
        }

        builder.addAction(action(ACTION_PREV, android.R.drawable.ic_media_previous, "上一首"))
        builder.addAction(playPause)
        builder.addAction(action(ACTION_NEXT, android.R.drawable.ic_media_next, "下一首"))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                Notification.MediaStyle()
                    // 只保留三个按钮在折叠态
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }

        return builder.build()
    }

    private fun buildErrorNotification(message: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("无法连接 SPlayer")
            .setContentText(message)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun action(action: String, icon: Int, title: String): Notification.Action {
        val intent = Intent(this, RemoteService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getService(this, action.hashCode(), intent, flags)
        return Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), title, pending)
            .build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun readBaseUrl(): String {
        val prefs = getSharedPreferences("splayer_remote", MODE_PRIVATE)
        return SPlayerApi.normalizeBaseUrl(
            prefs.getString("base_url", SPlayerApi.DEFAULT_BASE_URL) ?: SPlayerApi.DEFAULT_BASE_URL
        )
    }

    companion object {
        private const val CHANNEL_ID = "splayer_remote_controls"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_INTERVAL_MS = 1_000L
        private const val RETRY_INTERVAL_MS = 3_000L

        const val ACTION_PLAY_PAUSE = "top.dsh.splayerremote.PLAY_PAUSE"
        const val ACTION_NEXT = "top.dsh.splayerremote.NEXT"
        const val ACTION_PREV = "top.dsh.splayerremote.PREV"
        const val ACTION_STOP_SERVICE = "top.dsh.splayerremote.STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, RemoteService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteService::class.java))
        }
    }
}
