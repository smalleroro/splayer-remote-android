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
import androidx.lifecycle.viewmodel.compose.viewModel
import top.dsh.splayerremote.ui.RemoteScreen
import top.dsh.splayerremote.ui.SettingsScreen
import top.dsh.splayerremote.ui.RemoteViewModel
import top.dsh.splayerremote.data.ThemeMode

class MainActivity : ComponentActivity() {

    /** Android 13+ 需要运行时申请通知权限，否则常驻通知不显示 */
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                RemoteService.start(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ensureNotificationPermissionAndStartService()

        setContent {
            // Material You：Android 12+ 跟随壁纸取色，低版本回退到内置配色
            val darkTheme = isSystemInDarkTheme()
            val context = LocalContext.current
            val vm: RemoteViewModel = viewModel()
            val settings by vm.settingsRepo.settings.collectAsState()

            // 主题跟随用户设置，而不是只看系统
            val useDark = when (settings.themeMode) {
                ThemeMode.System -> darkTheme
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
            }

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
                    val vm: RemoteViewModel = viewModel()
                    val state by vm.ui.collectAsState()
                    val settings by vm.settingsRepo.settings.collectAsState()

                    if (showSettings) {
                        SettingsScreen(
                            settings = settings,
                            onUpdate = vm::updateSettings,
                            onBack = { showSettings = false },
                            onDiscover = vm::discoverServer,
                            isDiscovering = state.discovering,
                        )
                    } else {
                        RemoteScreen(
                            state = state,
                            onBaseUrlChange = vm::updateBaseUrl,
                            onReconnect = vm::connect,
                            onTogglePlayPause = vm::togglePlayPause,
                            onNext = vm::next,
                            onPrev = vm::prev,
                            onStop = vm::stop,
                            onVolumeChange = vm::setVolume,
                            onSeekStart = vm::onSeekStart,
                            onSeekChange = vm::onSeekChange,
                            onSeekCommit = vm::onSeekCommit,
                            onToggleLyrics = vm::toggleLyrics,
                            settings = settings,
                            onOpenSettings = { showSettings = true },
                        )
                    }
                }
            }
        }
    }

    /**
     * 拉起常驻通知服务。
     *
     * Android 13 起 POST_NOTIFICATIONS 是运行时权限，没拿到的话
     * 前台服务依然能跑，但通知不会显示——用户会觉得"常驻控制没了"，
     * 所以这里主动申请一次。
     */
    private fun ensureNotificationPermissionAndStartService() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED

        if (needsPermission) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            RemoteService.start(this)
        }
    }
}
