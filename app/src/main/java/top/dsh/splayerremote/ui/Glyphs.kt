package top.dsh.splayerremote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 手绘矢量图标。
 *
 * 不用 material-icons-extended：那个包 7000+ 图标、83MB，只用得着其中七八个。
 * 也不用 Unicode 字符：不同机型字体不同，⏮ ⏸ ⏹ 这类符号在某些系统上
 * 会被渲染成彩色 emoji 或粗细不匀，看起来廉价。
 *
 * 这里用 Canvas 按 24×24 的设计网格直接画路径，风格完全可控，
 * 且不增加任何依赖体积。
 */
private const val GRID = 24f

/** 把 24 网格坐标换算成实际像素 */
private fun DrawScope.px(v: Float): Float = v / GRID * size.minDimension

@Composable
fun VectorIcon(
    icon: Glyph,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String? = null,
) {
    val desc = contentDescription
    Canvas(
        modifier = modifier
            .size(size)
            .then(
                if (desc != null) Modifier.semantics { this.contentDescription = desc } else Modifier
            ),
    ) {
        drawGlyph(icon, tint)
    }
}

enum class Glyph { Play, Pause, Stop, Prev, Next, VolumeLow, VolumeHigh, Music, Link, AutoFind }

private fun DrawScope.drawGlyph(icon: Glyph, tint: Color) {
    when (icon) {
        Glyph.Play -> drawPlay(tint)
        Glyph.Pause -> drawPause(tint)
        Glyph.Stop -> drawStop(tint)
        Glyph.Prev -> drawSkip(tint, forward = false)
        Glyph.Next -> drawSkip(tint, forward = true)
        Glyph.VolumeLow -> drawVolume(tint, waves = 1)
        Glyph.VolumeHigh -> drawVolume(tint, waves = 2)
        Glyph.Music -> drawMusic(tint)
        Glyph.Link -> drawLink(tint)
        Glyph.AutoFind -> drawAutoFind(tint)
    }
}

/** 播放：实心三角，略微右移以视觉居中 */
private fun DrawScope.drawPlay(color: Color) {
    val path = Path().apply {
        moveTo(px(8f), px(5f))
        lineTo(px(19f), px(12f))
        lineTo(px(8f), px(19f))
        close()
    }
    drawPath(path, color)
}

/** 暂停：两根圆角竖条 */
private fun DrawScope.drawPause(color: Color) {
    val barW = px(4f)
    val r = barW / 2
    drawRoundRect(
        color = color,
        topLeft = Offset(px(7f), px(5f)),
        size = Size(barW, px(14f)),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(px(14f), px(5f)),
        size = Size(barW, px(14f)),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
    )
}

/** 停止：圆角方形 */
private fun DrawScope.drawStop(color: Color) {
    val side = px(13f)
    val r = px(2.5f)
    drawRoundRect(
        color = color,
        topLeft = Offset(px(5.5f), px(5.5f)),
        size = Size(side, side),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
    )
}

/** 上/下一首：双三角 + 竖线 */
private fun DrawScope.drawSkip(color: Color, forward: Boolean) {
    val barW = px(3f)
    if (forward) {
        val tri = Path().apply {
            moveTo(px(4f), px(6f))
            lineTo(px(13f), px(12f))
            lineTo(px(4f), px(18f))
            close()
        }
        val tri2 = Path().apply {
            moveTo(px(11f), px(6f))
            lineTo(px(20f), px(12f))
            lineTo(px(11f), px(18f))
            close()
        }
        drawPath(tri, color)
        drawPath(tri2, color)
        drawRoundRect(
            color = color,
            topLeft = Offset(px(18f), px(6f)),
            size = Size(barW, px(12f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(px(1f), px(1f)),
        )
    } else {
        val tri = Path().apply {
            moveTo(px(20f), px(6f))
            lineTo(px(11f), px(12f))
            lineTo(px(20f), px(18f))
            close()
        }
        val tri2 = Path().apply {
            moveTo(px(13f), px(6f))
            lineTo(px(4f), px(12f))
            lineTo(px(13f), px(18f))
            close()
        }
        drawPath(tri, color)
        drawPath(tri2, color)
        drawRoundRect(
            color = color,
            topLeft = Offset(px(3f), px(6f)),
            size = Size(barW, px(12f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(px(1f), px(1f)),
        )
    }
}

/** 音量：喇叭 + 弧线波 */
private fun DrawScope.drawVolume(color: Color, waves: Int) {
    val speaker = Path().apply {
        moveTo(px(3f), px(9f))
        lineTo(px(7f), px(9f))
        lineTo(px(12f), px(5f))
        lineTo(px(12f), px(19f))
        lineTo(px(7f), px(15f))
        lineTo(px(3f), px(15f))
        close()
    }
    drawPath(speaker, color)

    val strokeW = px(1.8f)
    repeat(waves) { index ->
        val radius = px(4f + index * 4f)
        val center = Offset(px(12f), px(12f))
        drawArc(
            color = color,
            startAngle = -50f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = strokeW, cap = StrokeCap.Round),
        )
    }
}

/** 音符：一个带符干的椭圆 */
private fun DrawScope.drawMusic(color: Color) {
    val noteHead = Size(px(7f), px(5.5f))
    drawOval(
        color = color,
        topLeft = Offset(px(6f), px(14f)),
        size = noteHead,
    )
    val stem = Path().apply {
        moveTo(px(12.5f), px(16f))
        lineTo(px(12.5f), px(4f))
        lineTo(px(19f), px(6f))
    }
    drawPath(stem, color, style = Stroke(width = px(2f), cap = StrokeCap.Round))
}

/** 链环：服务器连接 */
private fun DrawScope.drawLink(color: Color) {
    val w = px(2f)
    drawArc(
        color = color,
        startAngle = 120f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(px(4f), px(3f)),
        size = Size(px(9f), px(9f)),
        style = Stroke(width = w, cap = StrokeCap.Round),
    )
    drawArc(
        color = color,
        startAngle = 300f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(px(11f), px(12f)),
        size = Size(px(9f), px(9f)),
        style = Stroke(width = w, cap = StrokeCap.Round),
    )
    drawLine(
        color = color,
        start = Offset(px(8f), px(16f)),
        end = Offset(px(16f), px(8f)),
        strokeWidth = w,
        cap = StrokeCap.Round,
    )
}

/** 自动发现：放大镜 + 波纹 */
private fun DrawScope.drawAutoFind(color: Color) {
    val w = px(2f)
    val r = px(6f)
    val center = Offset(px(11f), px(11f))
    drawCircle(
        color = color,
        radius = r,
        center = center,
        style = Stroke(width = w),
    )
    drawLine(
        color = color,
        start = Offset(px(15.5f), px(15.5f)),
        end = Offset(px(20f), px(20f)),
        strokeWidth = w,
        cap = StrokeCap.Round,
    )
    drawArc(
        color = color,
        startAngle = 200f,
        sweepAngle = 60f,
        useCenter = false,
        topLeft = Offset(center.x - r * 0.5f, center.y - r * 0.5f),
        size = Size(r, r),
        style = Stroke(width = w * 0.8f, cap = StrokeCap.Round),
    )
}
