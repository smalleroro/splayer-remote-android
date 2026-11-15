package top.dsh.splayerremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.dsh.splayerremote.Connection
import top.dsh.splayerremote.SessionState
import top.dsh.splayerremote.data.AppSettings
import top.dsh.splayerremote.ui.components.LyricsPanel
import top.dsh.splayerremote.ui.components.NowPlayingHeader
import top.dsh.splayerremote.ui.components.ProgressRow
import top.dsh.splayerremote.ui.components.QueueList
import top.dsh.splayerremote.ui.components.TransportRow
import top.dsh.splayerremote.ui.components.VolumeRow

/**
 * 主界面骨架。
 *
 * 这里只负责布局与状态分发，具体控件都在 ui/components 下。
 * 之前这个文件 857 行，同时装着歌词渲染、插值算法、时间格式化，
 * 任何改动都要在八百行里找位置。
 */
@Composable
fun RemoteScreen(
    state: SessionState,
    settings: AppSettings,
    onReconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onStop: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onLoadQueueIndex: (Int) -> Unit,
    onConsumeMessage: () -> Unit,
) {
    var isSeeking by remember { mutableStateOf(false) }
    var seekPreview by remember { mutableLongStateOf(0L) }
    var showLyrics by remember { mutableStateOf(false) }

    // 位置只在这里创建、不在这一层读取——
    // 读一次就会让整个界面每帧重组。拖动状态直接交给它处理，
    // 这样进度条与歌词看到的是同一个位置。
    val positionState = rememberSmoothPosition(
        status = state.status,
        speed = state.speed,
        isSeeking = isSeeking,
        seekPreviewMs = seekPreview,
    )

    val hasLyrics = state.lyrics.isNotEmpty()
    val message = state.message
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(2_500)
            onConsumeMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background,
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        ConnectionBar(
            state = state,
            onReconnect = onReconnect,
            onSettingsClick = onOpenSettings,
        )

        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(12.dp))

        NowPlayingHeader(track = state.track, coverSize = settings.coverSize)

        Spacer(Modifier.height(14.dp))

        // 拖动时由 rememberSmoothPosition 内部切到预览值，这里不再另存一份
        ProgressRow(
            durationMs = state.status?.value?.duration ?: 0L,
            positionState = positionState,
            style = settings.progressStyle,
            isPlaying = state.status?.value?.isPlaying == true,
            // 同一首歌波形固定：用「标题+歌手」喂种子，换歌才换形
            seed = (state.track?.id ?: "").hashCode(),
            onSeekStart = {
                isSeeking = true
                seekPreview = positionState.value
            },
            onSeekChange = { seekPreview = it },
            onSeekCommit = {
                isSeeking = false
                onSeek(seekPreview)
            },
        )

        Spacer(Modifier.height(2.dp))

        TransportRow(
            isPlaying = state.status?.value?.isPlaying == true,
            enabled = state.connection == Connection.Connected,
            onToggle = onTogglePlayPause,
            onPrev = onPrev,
            onNext = onNext,
            onStop = onStop,
        )

        Spacer(Modifier.height(2.dp))

        VolumeRow(
            volume = state.volume,
            enabled = state.connection == Connection.Connected,
            style = settings.progressStyle,
            onChange = onVolumeChange,
        )

        Spacer(Modifier.height(12.dp))

        PanelHeader(
            showLyrics = showLyrics,
            lyricCount = state.lyrics.size,
            queueTotal = state.queue.total,
            onToggle = { showLyrics = !showLyrics },
        )

        Spacer(Modifier.height(4.dp))

        if (showLyrics) {
            if (hasLyrics) {
                LyricsPanel(
                    lyrics = state.lyrics,
                    positionState = positionState,
                    settings = settings,
                    modifier = Modifier.weight(1f),
                )
            } else {
                // 没歌词时也要给个明确交代，而不是让按钮消失、让人以为坏了
                Text(
                    text = "这首还没有歌词，换歌后会自动重试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                Spacer(Modifier.weight(1f))
            }
        } else if (state.queue.total == 0) {
            Text(
                text = if (state.connection == Connection.Connected) "队列为空" else "尚未连接",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            Spacer(Modifier.weight(1f))
        } else {
            QueueList(
                queue = state.queue,
                onLoadIndex = onLoadQueueIndex,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ConnectionBar(
    state: SessionState,
    onReconnect: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val (dotColor, label) = when (state.connection) {
        Connection.Connected -> androidx.compose.ui.graphics.Color(0xFF4ADE80) to "已连接"
        Connection.Connecting -> androidx.compose.ui.graphics.Color(0xFFFBBF24) to "连接中"
        Connection.Failed -> androidx.compose.ui.graphics.Color(0xFFF87171) to "未连接"
        Connection.Unknown -> androidx.compose.ui.graphics.Color.Gray to "未知"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = buildString {
                    append(state.baseUrl.removePrefix("http://"))
                    state.serverInfo?.version?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.discovering) {
            Text(
                text = "扫描中…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
        }
        IconButton(onClick = onReconnect, modifier = Modifier.size(40.dp)) {
            VectorIcon(icon = Glyph.Link, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onSettingsClick, modifier = Modifier.size(40.dp)) {
            VectorIcon(icon = Glyph.AutoFind, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    state.error?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun PanelHeader(
    showLyrics: Boolean,
    lyricCount: Int,
    queueTotal: Int,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (showLyrics) "歌词" else "播放队列",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (showLyrics) {
                if (lyricCount > 0) "$lyricCount 行" else "暂无"
            } else {
                "$queueTotal 首"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        // 始终可切换：歌词是异步就绪的，按钮跟着歌词有无而闪现会让人以为坏了
        Text(
            text = if (showLyrics) "看队列" else "看歌词",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onToggle() }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
