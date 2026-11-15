package top.dsh.splayerremote.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import top.dsh.splayerremote.data.PlaybackStatus
import top.dsh.splayerremote.data.Sample
import top.dsh.splayerremote.domain.Clock
import top.dsh.splayerremote.domain.MonotonicClock
import top.dsh.splayerremote.domain.PlaybackInterpolation
import top.dsh.splayerremote.domain.PositionAnchor

/**
 * 由本地时钟推进的播放位置。
 *
 * 返回 State 而不是裸 Long：歌词当前行的逐字高亮需要在叶子里读取，
 * 否则外层一旦用 derivedStateOf 缓存行号，位置变化就传不下去。
 */
@Composable
fun rememberSmoothPosition(
    status: Sample<PlaybackStatus>?,
    speed: Float,
    isSeeking: Boolean,
    seekPreviewMs: Long,
    clock: Clock = MonotonicClock,
): State<Long> {
    val position = remember { mutableLongStateOf(status?.value?.position ?: 0L) }
    val anchor = remember { AnchorHolder() }

    // 每次采样到手就重设锚点
    LaunchedEffect(status?.fetchedAt) {
        val sample = status ?: return@LaunchedEffect
        val now = clock.now()
        val playing = sample.value.isPlaying
        val compensated = PlaybackInterpolation.compensateLatency(
            positionMs = sample.value.position,
            playing = playing,
            requestAt = sample.fetchedAt,
            now = now,
        )
        anchor.value = PositionAnchor(
            positionMs = compensated,
            atMs = now,
            playing = playing,
            speed = speed,
        )
    }

    // 倍速变化不用重建锚点，但下一帧要用新倍速，所以同步进锚点
    LaunchedEffect(speed) {
        anchor.value = anchor.value.copy(speed = speed)
    }

    // 拖动时不跟随，直接用预览值
    LaunchedEffect(isSeeking, seekPreviewMs) {
        if (isSeeking) position.longValue = seekPreviewMs
    }

    // 每帧推进。key = Unit：只启动一次，
    // 播放状态与时长都在帧里现读，避免把快照固化进循环。
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos {
                if (isSeeking) return@withFrameNanos
                val a = anchor.value
                position.longValue = PlaybackInterpolation.estimate(
                    anchor = a,
                    now = clock.now(),
                    durationMs = status?.value?.duration ?: 0L,
                )
            }
        }
    }

    return position
}

/** 锚点的可变容器：帧循环里要读最新值，不应触发重组 */
private class AnchorHolder {
    var value: PositionAnchor = PositionAnchor(0L, 0L, false, 1f)
}
