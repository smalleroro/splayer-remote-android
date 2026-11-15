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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 常驻通知服务。
 *
 * 它的职责只有两件：把会话状态渲染成通知、把通知按钮转成会话指令。
 * **它不再自己轮询**——之前 ViewModel 和本服务各建一个 SPlayerApi、
 * 各自发请求，造成重复流量、断连判定不一致、改地址后通知还连旧机器。
 */
class RemoteService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var session: SessionRepository
    private var stateJob: Job? = null
    private var settingsJob: Job? = null

    /** 上次通知的内容指纹，避免每帧重建 */
    private var lastSignature: String? = null

    private var coverCacheKey: String? = null
    private var coverBitmap: Bitmap? = null

    /** 封面下载用短超时的独立客户端，避免拖住通知更新 */
    private val imageClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        session = applicationContext.session
        createChannel()
        // 先把占位通知挂上去，startForeground 必须尽快调用
        startForeground(NOTIFICATION_ID, buildPlaceholder())

        // 服务存活期间保持轮询
        session.acquire()

        stateJob = scope.launch {
            session.state.collectLatest { state ->
                renderNotification(state)
            }
        }

        // 设置变更：关掉常驻通知就自行退场；封面开关即时生效
        settingsJob = scope.launch {
            session.settings.settings.collectLatest { settings ->
                if (!settings.persistentNotification) {
                    stopSelf()
                } else {
                    lastSignature = null // 强制重绘一次，让开关立刻生效
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> session.togglePlayPause()
            ACTION_NEXT -> session.next()
            ACTION_PREV -> session.prev()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateJob?.cancel()
        settingsJob?.cancel()
        session.release()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 通知渲染

    private suspend fun renderNotification(state: SessionState) {
        val track = state.track
        val status = state.status?.value
        val settings = session.settings.current

        // 进度条要动，就得把 position 也放进指纹（按 5 秒粒度，避免每帧重绘）
        val positionBucket = if (status?.isPlaying == true) status.position / 5_000 else 0L
        val signature = buildString {
            append(track?.id ?: "none")
            append('|').append(status?.state)
            append('|').append(status?.volume)
            append('|').append(positionBucket)
            append('|').append(settings.notificationCover)
            append('|').append(state.connection)
        }
        if (signature == lastSignature) return
        lastSignature = signature

        val cover = if (settings.notificationCover) {
            loadCover(track?.cover ?: track?.album?.cover)
        } else {
            null
        }

        val notification = buildNotification(state, track, status, cover)
        withContext(Dispatchers.Main) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        state: SessionState,
        track: top.dsh.splayerremote.data.Track?,
        status: top.dsh.splayerremote.data.PlaybackStatus?,
        cover: Bitmap?,
    ): Notification {
        val isPlaying = status?.isPlaying == true
        val connected = state.connection == Connection.Connected

        val title = when {
            track != null -> track.title.ifBlank { "SPlayer 遥控器" }
            connected -> "SPlayer 遥控器"
            else -> "未连接到 SPlayer"
        }
        val subtitle = when {
            !connected -> state.error ?: state.baseUrl
            else -> track?.artists?.joinToString(" / ") { it.name }.orEmpty().ifBlank { "已连接" }
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (cover != null) builder.setLargeIcon(cover)

        val duration = status?.duration ?: 0L
        if (duration > 0L) {
            val progress = (((status?.position ?: 0L).toFloat() / duration) * 1000)
                .toInt().coerceIn(0, 1000)
            builder.setProgress(1000, progress, false)
        }

        val toggle = if (isPlaying) {
            action(ACTION_PLAY_PAUSE, android.R.drawable.ic_media_pause, "暂停")
        } else {
            action(ACTION_PLAY_PAUSE, android.R.drawable.ic_media_play, "播放")
        }
        builder.addAction(action(ACTION_PREV, android.R.drawable.ic_media_previous, "上一首"))
        builder.addAction(toggle)
        builder.addAction(action(ACTION_NEXT, android.R.drawable.ic_media_next, "下一首"))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
        }
        return builder.build()
    }

    private fun buildPlaceholder(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("SPlayer 遥控器")
            .setContentText("正在准备…")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun createChannel() {
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * 封面下载。
     *
     * 走带超时的客户端并解码成 Bitmap 后再进通知——之前用
     * URL.openStream() 无超时，网络稍差就会卡住通知更新链路。
     */
    private suspend fun loadCover(url: String?): Bitmap? {
        if (url == null) return null
        if (url == coverCacheKey) return coverBitmap
        return withContext(Dispatchers.IO) {
            try {
                imageClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val bytes = resp.body?.bytes() ?: return@withContext null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.also {
                    coverCacheKey = url
                    coverBitmap = it
                }
            } catch (e: Exception) {
                coverCacheKey = url
                coverBitmap = null
                null
            }
        }
    }

    private fun action(action: String, icon: Int, title: String): Notification.Action {
        val intent = Intent(this, RemoteService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getService(this, action.hashCode(), intent, flags)
        return Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon),
            title,
            pending,
        ).build()
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

    companion object {
        private const val CHANNEL_ID = "splayer_remote_controls"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY_PAUSE = "top.dsh.splayerremote.PLAY_PAUSE"
        const val ACTION_NEXT = "top.dsh.splayerremote.NEXT"
        const val ACTION_PREV = "top.dsh.splayerremote.PREV"

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
