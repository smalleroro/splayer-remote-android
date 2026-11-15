package top.dsh.splayerremote.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.dsh.splayerremote.data.ProgressStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 可换皮的自绘进度条。
 *
 * 之所以不用 Material 的 Slider：滑块外观、轨道粗细、头形状全被组件内部写死，
 * 想换样式只能改主题，做不到「用户自己选」。
 *
 * 触摸行为与系统滑块一致：按住任意位置直接跳到该处，拖动实时跟随，松手提交。
 */
@Composable
fun SeekBar(
    progress: Float,
    style: ProgressStyle,
    onSeekStart: () -> Unit,
    onSeekChange: (Float) -> Unit,
    onSeekCommit: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 播放中才让波浪流动；暂停时摊平 */
    animate: Boolean = false,
    /** 波形样式用的种子（同一首歌波形固定） */
    seed: Int = 0,
    activeColor: Color,
    inactiveColor: Color,
    touchHeight: Dp = 44.dp,
) {
    val p = progress.coerceIn(0f, 1f)

    // 波浪相位：只在播放时前进，暂停时停在原地（摊平动画另外处理）
    val transition = rememberInfiniteTransition(label = "seek-wave")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (style == ProgressStyle.Wavy) 2600 else 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "seek-wave-phase",
    )
    // 暂停后振幅收敛到 0，即「摊平」
    val amplitude by animateFloatAsState(
        targetValue = if (animate && style != ProgressStyle.Thin) 1f else 0f,
        animationSpec = tween(durationMillis = 420),
        label = "seek-wave-amp",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(touchHeight)
            .pointerInput(enabled, style) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = { offset ->
                        onSeekStart()
                        onSeekChange((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                        tryAwaitRelease()
                        onSeekCommit()
                    },
                )
            }
            .pointerInput(enabled, style) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        onSeekStart()
                        onSeekChange((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                    },
                    onHorizontalDrag = { change, _ ->
                        onSeekChange((change.position.x / size.width.toFloat()).coerceIn(0f, 1f))
                        change.consume()
                    },
                    onDragEnd = { onSeekCommit() },
                    onDragCancel = { onSeekCommit() },
                )
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(touchHeight)) {
            val cw = size.width
            val cy = size.height / 2f
            val played = cw * p
            val dim = if (enabled) 1f else 0.4f

            when (style) {
                ProgressStyle.Classic -> drawClassic(cw, cy, played, activeColor, inactiveColor, dim)
                ProgressStyle.Wavy -> drawWavy(cw, cy, played, p, t, amplitude, activeColor, inactiveColor, dim)
                ProgressStyle.Expressive -> drawExpressive(cw, cy, played, activeColor, inactiveColor, dim)
                ProgressStyle.Thin -> drawThin(cw, cy, played, activeColor, inactiveColor, dim)
                ProgressStyle.Segment -> drawSegment(cw, cy, played, activeColor, inactiveColor, dim)
                ProgressStyle.Waveform -> drawWaveform(cw, cy, played, seed, activeColor, inactiveColor, dim)
            }
        }
    }
}

private fun DrawScope.drawClassic(
    cw: Float,
    cy: Float,
    played: Float,
    active: Color,
    inactive: Color,
    dim: Float,
) {
    val h = 6.dp.toPx()
    drawLine(inactive.copy(alpha = 0.85f * dim), Offset(0f, cy), Offset(cw, cy), h, StrokeCap.Round)
    if (played > 0f) {
        drawLine(active.copy(alpha = dim), Offset(0f, cy), Offset(played, cy), h, StrokeCap.Round)
        drawCircle(active.copy(alpha = dim), radius = 7.dp.toPx(), center = Offset(played, cy))
    }
}

private fun DrawScope.drawExpressive(
    cw: Float,
    cy: Float,
    played: Float,
    active: Color,
    inactive: Color,
    dim: Float,
) {
    val h = 12.dp.toPx()
    val gap = 6.dp.toPx()
    // M3 新版：进度与尾段之间留缝，头部是一条竖线而不是圆点
    if (played > 0f) {
        drawLine(active.copy(alpha = dim), Offset(0f, cy), Offset(played, cy), h, StrokeCap.Round)
    }
    val restStart = played + gap
    if (restStart < cw) {
        drawLine(
            inactive.copy(alpha = 0.85f * dim),
            Offset(restStart, cy),
            Offset(cw, cy),
            h,
            StrokeCap.Round,
        )
    }
    val headW = 4.dp.toPx()
    val headH = 22.dp.toPx()
    drawRoundRect(
        color = active.copy(alpha = dim),
        topLeft = Offset(played - headW / 2f, cy - headH / 2f),
        size = Size(headW, headH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(headW / 2f),
    )
}

private fun DrawScope.drawThin(
    cw: Float,
    cy: Float,
    played: Float,
    active: Color,
    inactive: Color,
    dim: Float,
) {
    val h = 3.dp.toPx()
    drawLine(inactive.copy(alpha = 0.7f * dim), Offset(0f, cy), Offset(cw, cy), h, StrokeCap.Round)
    if (played > 0f) {
        drawLine(active.copy(alpha = dim), Offset(0f, cy), Offset(played, cy), h, StrokeCap.Round)
    }
}

/**
 * 波浪：已播部分是流动的正弦，暂停时振幅归零变回一条直线。
 * 波长取已播长度（即一个完整「～」），所以波形始终是「一个波浪」而不会碎成一串小涟漪。
 */
private fun DrawScope.drawWavy(
    cw: Float,
    cy: Float,
    played: Float,
    p: Float,
    phase: Float,
    amplitude: Float,
    active: Color,
    inactive: Color,
    dim: Float,
) {
    val baseH = 4.dp.toPx()
    drawLine(inactive.copy(alpha = 0.7f * dim), Offset(0f, cy), Offset(cw, cy), baseH, StrokeCap.Round)

    if (played <= 0f) return
    val amp = 4.dp.toPx() * amplitude * (played / 64.dp.toPx()).coerceIn(0f, 1f)
    val wavelength = played.coerceAtLeast(1f)

    val path = Path()
    path.moveTo(0f, cy)
    var x = 0f
    val stepPx = 2.dp.toPx()
    while (x < played) {
        val y = cy + sin((x / wavelength) * 2f * PI.toFloat() * -1f + phase) * amp
        path.lineTo(x, y)
        x += stepPx
    }
    // 端点收在该处，避免落后于真实进度
    val endY = cy + sin((played / wavelength) * 2f * PI.toFloat() * -1f + phase) * amp
    path.lineTo(played, endY)

    drawPath(
        path = path,
        color = active.copy(alpha = dim),
        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
    )
    // 进度头：小圆点，方便看清当前位置
    drawCircle(active.copy(alpha = dim), radius = 3.5.dp.toPx(), center = Offset(played, endY))
    if (p < 1f) {
        drawCircle(active.copy(alpha = 0.35f * dim), radius = 2.dp.toPx(), center = Offset(played, cy))
    }
}

private fun DrawScope.drawSegment(
    cw: Float,
    cy: Float,
    played: Float,
    active: Color,
    inactive: Color,
    dim: Float,
) {
    // 用「短横」而不是圆点：宽一点、扁一点，一眼能看出是分段而不是虚线
    val count = 32
    val slot = cw / count
    val barW = (slot * 0.75f).coerceAtLeast(2f)
    val h = 4.dp.toPx()
    for (i in 0 until count) {
        val left = i * slot + (slot - barW) / 2f
        val on = left + barW <= played + 0.5f
        drawRoundRect(
            color = if (on) active.copy(alpha = dim) else inactive.copy(alpha = 0.8f * dim),
            topLeft = Offset(left, cy - h / 2f),
            size = Size(barW, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f),
        )
    }
}

private fun DrawScope.drawWaveform(
    cw: Float,
    cy: Float,
    played: Float,
    seed: Int,
    active: Color,
    inactive: Color,
    dim: Float,
) {
    val count = 56
    val slot = cw / count
    val barW = (slot * 0.5f).coerceAtLeast(1.2f)
    val maxH = 22.dp.toPx()
    val rnd = java.util.Random(seed.toLong())
    val heights = FloatArray(count) {
        // 平滑一点的伪波形，避免杂乱
        0.25f + abs(rnd.nextFloat()) * 0.55f + 0.2f * sin(it * 0.7f)
    }
    for (i in 0 until count) {
        val left = i * slot + (slot - barW) / 2f
        val h = (maxH * heights[i].coerceIn(0.15f, 1f))
        val on = left + barW <= played + 0.5f
        drawRoundRect(
            color = if (on) active.copy(alpha = dim) else inactive.copy(alpha = 0.6f * dim),
            topLeft = Offset(left, cy - h / 2f),
            size = Size(barW, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f),
        )
    }
}
