package top.dsh.splayerremote.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.dsh.splayerremote.data.AppSettings
import top.dsh.splayerremote.data.LyricFontSize
import top.dsh.splayerremote.data.LyricLine
import top.dsh.splayerremote.domain.highlightSpans
import top.dsh.splayerremote.domain.lyricIndexAt

/** 程序滚动期间不要被当成"用户在滑" */
private const val AUTO_SCROLL_SETTLE_MS = 800L

/**
 * 歌词面板。
 *
 * 两个细节值得说明：
 *  1. 当前行由本组件用平滑位置实时算，而不是从 ViewModel 拿——
 *     后者每秒才更新一次，会出现"已经唱到下一句、高亮还停在上一句"。
 *  2. 自动滚动用 autoScrolling 标记区分程序滚动与用户手势，
 *     否则程序滚动会触发 isScrollInProgress，被误判成用户操作而暂停跟随。
 */
@Composable
fun LyricsPanel(
    lyrics: List<LyricLine>,
    positionState: State<Long>,
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    val currentIndex by remember(lyrics) {
        derivedStateOf { lyricIndexAt(lyrics, positionState.value) }
    }

    var userScrolling by remember { mutableStateOf(false) }
    var autoScrolling by remember { mutableStateOf(false) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress.and(!autoScrolling)) {
            // 用户手势：暂停跟随，松手后 4 秒恢复
            userScrolling = true
            delay(4_000)
            userScrolling = false
        }
    }

    LaunchedEffect(currentIndex, settings.autoScrollLyrics) {
        if (currentIndex >= 0 && settings.autoScrollLyrics && !userScrolling) {
            autoScrolling = true
            try {
                listState.animateScrollToItem(
                    index = currentIndex.coerceIn(0, lyrics.lastIndex),
                    scrollOffset = -200,
                )
            } finally {
                delay(AUTO_SCROLL_SETTLE_MS)
                autoScrolling = false
            }
        }
    }

    val scale = when (settings.lyricFontSize) {
        LyricFontSize.Small -> 0.85f
        LyricFontSize.Medium -> 1f
        LyricFontSize.Large -> 1.2f
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(lyrics.size) { index ->
            val line = lyrics[index]
            val isCurrent = index == currentIndex
            val isPast = index < currentIndex
            val alpha = when {
                isCurrent -> 1f
                isPast -> 0.35f
                else -> 0.6f
            }
            val textSize = ((if (line.isBG) 12f else if (isCurrent) 17f else 15f) * scale).sp

            Column(modifier = Modifier.fillMaxWidth()) {
                if (isCurrent) {
                    HighlightedLine(
                        line = line,
                        positionState = positionState,
                        fontSize = textSize,
                    )
                } else {
                    Text(
                        text = line.text.ifBlank { "·" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = textSize,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                        lineHeight = textSize * 1.4f,
                    )
                }

                if (settings.showTranslation && line.translatedLyric.isNotBlank()) {
                    Text(
                        text = line.translatedLyric,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = ((if (isCurrent) 13f else 11f) * scale).sp,
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.8f)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 当前行的扫过高亮。
 *
 * 位置在组件内部读：外层用 derivedStateOf 缓存了行号，行号不变时父级不重组，
 * 若把位置当参数传进来，它会停在首次组合那一刻，高亮就卡住了。
 */
@Composable
private fun HighlightedLine(
    line: LyricLine,
    positionState: State<Long>,
    fontSize: TextUnit,
) {
    val positionMs by positionState
    val played = MaterialTheme.colorScheme.primary
    val pending = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

    val annotated = buildAnnotatedString {
        val base = SpanStyle(fontSize = fontSize, fontWeight = FontWeight.Bold)
        highlightSpans(line, positionMs).forEach { span ->
            withStyle(base.copy(color = lerpColor(pending, played, span.progress))) {
                append(span.text)
            }
        }
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        lineHeight = fontSize * 1.4f,
    )
}

/** 按进度混合两个颜色 */
private fun lerpColor(
    from: androidx.compose.ui.graphics.Color,
    to: androidx.compose.ui.graphics.Color,
    t: Float,
): androidx.compose.ui.graphics.Color {
    val c = t.coerceIn(0f, 1f)
    return androidx.compose.ui.graphics.Color(
        red = from.red + (to.red - from.red) * c,
        green = from.green + (to.green - from.green) * c,
        blue = from.blue + (to.blue - from.blue) * c,
        alpha = from.alpha + (to.alpha - from.alpha) * c,
    )
}
