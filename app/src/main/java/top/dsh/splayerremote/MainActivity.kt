package top.dsh.splayerremote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import top.dsh.splayerremote.data.ThemeMode
import top.dsh.splayerremote.ui.RemoteScreen
import top.dsh.splayerremote.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private lateinit var session: SessionRepository

    /** 是否已申请过通知权限，避免每次回前台都弹 */
    private var permissionAsked = false

    /** 前台服务当前是否在跑，供设置页显示按钮状态 */
    private var serviceRunning by mutableStateOf(false)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            permissionAsked = true
            // 无论授予与否都要起服务：服务本身能跑，只是通知不可见。
            // 之前被拒就不启动，导致"常驻控制"直接消失且没有任何提示。
            startServiceIfEnabled()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        session = applicationContext.session

        ensureNotificationPermission()

        setContent {
            val settings by session.settings.settings.collectAsState()
            val state by session.state.collectAsState()

            val darkTheme = isSystemInDarkTheme()
            val useDark = when (settings.themeMode) {
                ThemeMode.System -> darkTheme
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
            }
            val context = LocalContext.current
            val colorScheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                }
                useDark -> darkColorScheme()
                else -> lightColorScheme()
            }

            var showSettings by remember { mutableStateOf(false) }

            MaterialTheme(colorScheme = colorScheme) {
                Surface {
                    if (showSettings) {
                        SettingsScreen(
                            settings = settings,
                            onUpdate = { transform -> session.settings.update(transform) },
                            onApplyBaseUrl = { session.setBaseUrl(it) },
                            onBack = { showSettings = false },
                            onDiscover = { session.discover() },
                            isDiscovering = state.discovering,
                            serviceRunning = serviceRunning,
                            onStopService = {
                                RemoteService.stop(this)
                                serviceRunning = false
                                // 停掉服务等于关掉常驻通知，设置里同步反映
                                session.settings.update { it.copy(persistentNotification = false) }
                            },
                            notificationPermissionGranted = hasNotificationPermission(),
                        )
                    } else {
                        RemoteScreen(
                            state = state,
                            settings = settings,
                            onReconnect = { session.reconnect() },
                            onOpenSettings = { showSettings = true },
                            onTogglePlayPause = { session.togglePlayPause() },
                            onNext = { session.next() },
                            onPrev = { session.prev() },
                            onStop = { session.stopPlayback() },
                            onVolumeChange = { session.setVolume(it) },
                            onSeek = { session.seekTo(it) },
                            onLoadQueueIndex = { session.ensureQueueLoaded(it) },
                            onConsumeMessage = { session.consumeMessage() },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        session.setForeground(true)
        session.acquire()
    }

    override fun onStop() {
        super.onStop()
        session.setForeground(false)
        session.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 仅在用户主动退出（而非配置变更重建）时执行
        if (isFinishing && session.settings.current.stopServiceOnExit) {
            RemoteService.stop(this)
        }
    }

    // ---------------------------------------------------------------- 通知权限与服务

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun ensureNotificationPermission() {
        if (hasNotificationPermission()) {
            startServiceIfEnabled()
            return
        }
        if (!permissionAsked) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startServiceIfEnabled()
        }
    }

    /** 用户开着「常驻通知」就拉起服务；关着就不拉 */
    private fun startServiceIfEnabled() {
        if (session.settings.current.persistentNotification) {
            RemoteService.start(this)
            serviceRunning = true
        }
    }
}
