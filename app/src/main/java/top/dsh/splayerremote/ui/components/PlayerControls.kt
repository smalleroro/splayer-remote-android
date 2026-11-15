package top.dsh.splayerremote.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.dsh.splayerremote.domain.formatTime
import top.dsh.splayerremote.ui.Glyph
import top.dsh.splayerremote.ui.VectorIcon

/** 进度：位置从 State 里读，避免外层缓存导致进度条不动 */
@Composable
fun ProgressRow(
    durationMs: Long,
    positionState: State<Long>,
    onSeekStart: () -> Unit,
    onSeekChange: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val position by positionState
    val max = durationMs.coerceAtLeast(1L).toFloat()

    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = position.coerceIn(0L, durationMs.coerceAtLeast(0L)).toFloat(),
            onValueChange = {
                onSeekStart()
                onSeekChange(it.toLong())
            },
            onValueChangeFinished = onSeekCommit,
            valueRange = 0f..max,
            enabled = durationMs > 0,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(position),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatTime(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun TransportRow(
    isPlaying: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev, enabled = enabled, modifier = Modifier.size(48.dp)) {
            VectorIcon(
                icon = Glyph.Prev,
                size = 26.dp,
                tint = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            )
        }
        Spacer(Modifier.width(20.dp))
        FilledIconButton(
            onClick = onToggle,
            enabled = enabled,
            modifier = Modifier
                .size(68.dp)
                .shadow(12.dp, CircleShape),
        ) {
            VectorIcon(
                icon = if (isPlaying) Glyph.Pause else Glyph.Play,
                size = 30.dp,
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.width(20.dp))
        IconButton(onClick = onNext, enabled = enabled, modifier = Modifier.size(48.dp)) {
            VectorIcon(
                icon = Glyph.Next,
                size = 26.dp,
                tint = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            )
        }
        Spacer(Modifier.width(12.dp))
        IconButton(onClick = onStop, enabled = enabled, modifier = Modifier.size(36.dp)) {
            VectorIcon(
                icon = Glyph.Stop,
                size = 16.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 音量。
 *
 * 拖动期间只用本地值（拖动结束、或松手后由会话层合并成一次请求），
 * 同时不去读服务端回读值——否则轮询会把滑块拽回旧位置。
 */
@Composable
fun VolumeRow(
    volume: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableFloatStateOf(-1f) }
    val shown = if (dragging >= 0f) dragging else volume

    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        VectorIcon(
            icon = Glyph.VolumeLow,
            size = 16.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = shown.coerceIn(0f, 1f),
            onValueChange = {
                dragging = it
                onChange(it)
            },
            onValueChangeFinished = { dragging = -1f },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            valueRange = 0f..1f,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
        VectorIcon(
            icon = Glyph.VolumeHigh,
            size = 16.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${(shown * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(34.dp),
            fontSize = 11.sp,
        )
    }
}
