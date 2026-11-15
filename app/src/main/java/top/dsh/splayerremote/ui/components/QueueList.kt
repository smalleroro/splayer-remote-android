package top.dsh.splayerremote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.dsh.splayerremote.QueueState
import top.dsh.splayerremote.domain.formatTime

/**
 * 队列列表，按需分页。
 *
 * 服务端可能有上千首，不可能一次全要。列表按总长度铺开，
 * 未加载的位置显示占位，滚到附近就请求对应页——
 * 于是"当前曲目在第 700 首"也能正确高亮，不会因为只取了前 100 首而错位。
 */
@Composable
fun QueueList(
    queue: QueueState,
    onLoadIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // 首屏先按当前曲目定位，否则用户一进来看到的是第 0 首
    LaunchedEffect(queue.total, queue.currentIndex) {
        if (queue.currentIndex >= 0 && queue.total > 0) {
            onLoadIndex(queue.currentIndex)
        }
    }

    // 滚动到接近末尾时加载后续页
    val lastVisible by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.visibleItemsInfo.lastOrNull()?.index ?: 0
        }
    }
    LaunchedEffect(lastVisible, queue.total) {
        if (queue.total > 0) {
            onLoadIndex((lastVisible + 8).coerceAtMost(queue.total - 1))
        }
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        items(queue.total) { index ->
            val track = queue[index]
            if (track == null) {
                PlaceholderRow(index = index, onNeedLoad = { onLoadIndex(index) })
            } else {
                QueueRow(
                    index = index,
                    title = track.title,
                    artists = track.artists.joinToString(" / ") { it.name },
                    durationMs = track.duration,
                    isCurrent = index == queue.currentIndex,
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    index: Int,
    title: String,
    artists: String,
    durationMs: Long,
    isCurrent: Boolean,
) {
    val bg = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                text = if (isCurrent) "▶" else (index + 1).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                },
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { "未知曲目" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (artists.isNotBlank()) {
                Text(
                    text = artists,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatTime(durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlaceholderRow(index: Int, onNeedLoad: () -> Unit) {
    LaunchedEffect(index) { onNeedLoad() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                text = (index + 1).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
        Text(
            text = "载入中…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}
