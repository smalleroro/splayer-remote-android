package top.dsh.splayerremote.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import top.dsh.splayerremote.data.PlaybackStatus

/**
 * 本地插值播放位置。
 *
 * 为什么需要它：
 * 服务器状态是每秒轮询一次的，直接把 status.position 交给界面会有两个毛病——
 *   1. 进度条和歌词比实际慢一拍（最多滞后一个轮询周期）；
 *   2. 每秒钟跳一格，逐字歌词的高亮也是一秒跳一次，很不自然。
 *
 * 做法：把每次拿到的 (position, 本地时刻) 当锚点，在两帧之间按帧时间自己往前推。
 * 下一次轮询到来时重新对齐锚点，误差不会累积。
 */
@Composable
fun rememberSmoothPosition(
    status: PlaybackStatus?,
    isSeeking: Boolean,
    seekPreviewMs: Long,
): State<Long> {
    val position = remember { mutableLongStateOf(status?.position ?: 0L) }

    // 锚点：服务器给的 position 与收到它时的本地单调时钟。
    val anchor = remember { Anchor() }

    // 每次服务器状态更新就重设锚点。
    LaunchedEffect(status?.position, status?.state, status?.duration) {
        val s = status ?: return@LaunchedEffect
        val now = SystemClock.elapsedRealtime()

        // 轮询拿到的是「服务端处理请求那一刻」的位置，比真实时间旧了半个网络往返。
        // 播放中把它补偿回来，否则歌词会比声音慢一拍；
        // 暂停状态不补偿——那时位置是静止的，补偿反而会跳。
        val latencyCompensation = if (s.state == "playing") {
            (now - s.fetchedAt).coerceIn(0L, MAX_LATENCY_COMPENSATION_MS)
        } else {
            0L
        }

        anchor.position = s.position + latencyCompensation
        anchor.at = now
        anchor.playing = s.state == "playing"
        anchor.hasAnchor = true
        position.longValue = anchor.position
    }

    // 拖动时不跟随，直接用预览值
    LaunchedEffect(isSeeking, seekPreviewMs) {
        if (isSeeking) position.longValue = seekPreviewMs
    }

    // 每帧推进（key = Unit，只启动一次；playing 存在 anchor 里每帧实时读）
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos {
                if (isSeeking) return@withFrameNanos
                if (!anchor.hasAnchor) return@withFrameNanos

                val duration = status?.duration ?: 0L

                if (anchor.playing) {
                    val elapsed = (SystemClock.elapsedRealtime() - anchor.at)
                        .coerceIn(0L, MAX_EXTRAPOLATE_MS)
                    val estimated = anchor.position + elapsed
                    position.longValue = if (duration > 0L) {
                        estimated.coerceIn(0L, duration)
                    } else {
                        estimated
                    }
                } else {
                    // 暂停时停在锚点。这里必须每帧重设——
                    // 用户在暂停状态下 seek，锚点先更新，
                    // 若不同步给 position，界面会停在 seek 前的位置。
                    if (position.longValue != anchor.position) {
                        position.longValue = anchor.position
                    }
                }
            }
        }
    }

    return position
}

private class Anchor {
    var position: Long = 0L
    var at: Long = SystemClock.elapsedRealtime()
    var playing: Boolean = false
    var hasAnchor: Boolean = false
}

/** 网络往返补偿的上限，超过这个值说明网络异常，不硬补 */
private const val MAX_LATENCY_COMPENSATION_MS = 2_000L

/** 两次同步之间最多外推这么久 */
private const val MAX_EXTRAPOLATE_MS = 6_000L

/** 计算一行歌词在某一时刻的进度（0f..1f） */
fun lineProgress(line: top.dsh.splayerremote.data.LyricLine, positionMs: Long): Float {
    val duration = (line.endTime - line.startTime).coerceAtLeast(1L)
    val elapsed = (positionMs - line.startTime).coerceIn(0L, duration)
    return elapsed.toFloat() / duration.toFloat()
}

/** 把两个颜色按进度混合 */
fun lerpColor(
    from: androidx.compose.ui.graphics.Color,
    to: androidx.compose.ui.graphics.Color,
    t: Float,
): androidx.compose.ui.graphics.Color {
    val clamped = t.coerceIn(0f, 1f)
    return androidx.compose.ui.graphics.Color(
        red = from.red + (to.red - from.red) * clamped,
        green = from.green + (to.green - from.green) * clamped,
        blue = from.blue + (to.blue - from.blue) * clamped,
        alpha = from.alpha + (to.alpha - from.alpha) * clamped,
    )
}
