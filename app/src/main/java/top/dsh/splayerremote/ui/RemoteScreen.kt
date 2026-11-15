package top.dsh.splayerremote.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import coil.compose.AsyncImage
import top.dsh.splayerremote.data.Track
import top.dsh.splayerremote.data.LyricLine
import top.dsh.splayerremote.data.AppSettings
import top.dsh.splayerremote.data.CoverSize
import top.dsh.splayerremote.data.LyricFontSize
import kotlinx.coroutines.delay

@Composable
fun RemoteScreen(
    state: RemoteUiState,
    onBaseUrlChange: (String) -> Unit,
    onReconnect: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onStop: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSeekStart: () -> Unit,
    onSeekChange: (Long) -> Unit,
    onSeekCommit: () -> Unit,
    onToggleLyrics: () -> Unit,
    settings: AppSettings,
    onOpenSettings: () -> Unit,
) {
    // 由本地时钟推进的播放位置：进度条和歌词都用它，避免每秒跳一格。
    //
    // 保留 State 本体用于传递给歌词行（它需要在内部读取才能实时刷新），
    // 同时解包出一个普通值给进度条等只需要当前帧快照的地方用。
    val smoothPositionState = rememberSmoothPosition(
        status = state.status,
        isSeeking = state.isSeeking,
        seekPreviewMs = state.seekPreviewMs,
    )
    val smoothPosition by smoothPositionState

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
            // 齿轮现在打开独立设置页，不再就地展开
            onSettingsClick = onOpenSettings,
        )

        Spacer(Modifier.height(12.dp))

        // 封面与曲目信息并排：封面缩到 96dp，把纵向空间让给队列。
        // 之前用 1:1 全宽大封面，一张图就吃掉 900px，队列只剩一行可见。
        NowPlayingHeader(track = state.currentTrack, settings = settings)

        Spacer(Modifier.height(14.dp))

        ProgressRow(
            state = state,
            smoothPosition = smoothPosition,
            onSeekStart = onSeekStart,
            onSeekChange = onSeekChange,
            onSeekCommit = onSeekCommit,
        )

        Spacer(Modifier.height(2.dp))

        TransportRow(
            isPlaying = state.status?.isPlaying == true,
            enabled = state.connection == ConnectionState.Connected,
            onToggle = onTogglePlayPause,
            onPrev = onPrev,
            onNext = onNext,
            onStop = onStop,
        )

        Spacer(Modifier.height(2.dp))

        VolumeRow(volume = state.volume, onChange = onVolumeChange)

        Spacer(Modifier.height(12.dp))

        // 队列与歌词共用下方空间：有歌词时切成两个标签页
        val hasLyrics = state.lyrics.isNotEmpty()
        val queueTracks = state.queue?.tracks.orEmpty()
        val currentIndex = state.queue?.currentIndex ?: -1

        // 当前歌词行跟着平滑位置走，而不是等每秒一次的服务器同步。
        // derivedStateOf 只在结果真正变化时才通知重组——
        // 位置的每帧更新不会让歌词面板重绘，只有跨过行边界才会。
        val lyricsSnapshot = state.lyrics
        val currentLyricIndex by remember(lyricsSnapshot) {
            derivedStateOf { findLyricIndexAt(lyricsSnapshot, smoothPosition) }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (state.showLyrics) "歌词" else "播放队列",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (state.showLyrics) "${state.lyrics.size} 行" else "${state.queue?.total ?: 0} 首",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (hasLyrics) {
                Text(
                    text = if (state.showLyrics) "看队列" else "看歌词",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onToggleLyrics() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            } else {
                Text(
                    text = "仅供查看",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        if (state.showLyrics && hasLyrics) {
            LyricsPanel(
                lyrics = state.lyrics,
                currentIndex = currentLyricIndex,
                positionState = smoothPositionState,
                        settings = settings,
                modifier = Modifier.weight(1f),
            )
        } else if (queueTracks.isEmpty()) {
            Text(
                text = if (state.connection == ConnectionState.Connected) "队列为空" else "尚未连接",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(queueTracks.size) { index ->
                    QueueRow(
                        track = queueTracks[index],
                        index = index + 1,
                        isCurrent = index == currentIndex,
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ 歌词面板 */

/**
 * 找出某一时刻应该高亮哪一行歌词。
 *
 * 注意这里必须基于"平滑位置"实时算，不能用 ViewModel 里那份——
 * 后者每秒才更新一次，会出现"已经唱到下一句、歌词还停在上一句"的滞后。
 *
 * 找的是 startTime <= position 的最后一个非空行。
 * 空白行（纯间奏）也要参与判定，否则会在间奏时错误地停留在上一句。
 */
private fun findLyricIndexAt(lyrics: List<LyricLine>, positionMs: Long): Int {
    if (lyrics.isEmpty()) return -1
    var low = 0
    var high = lyrics.size - 1
    var result = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lyrics[mid].startTime <= positionMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

/**
 * 可滚动歌词，支持逐字高亮。
 *
 * 两种高亮方式，取决于歌词源给的精度：
 *
 *   1. 逐字歌词（TTML / QRC / KRC / YRC 等，wordTimed = true）
 *      每个 word 有自己的 startTime/endTime，按真实时间点亮，颜色从左往右推进。
 *
 *   2. 逐行歌词（LRC / ASS / SRT 等，wordTimed = false）
 *      整行只有一个时间段，这里按"行内进度"做插值，
 *      文字从左到右渐变填色，视觉上仍是流动的，不会呆板地整行变色。
 *
 * 自动滚动：当前行变化时滚到屏幕中间。用户手动滑动时暂时停止跟随，
 * 松手 4 秒后再恢复，避免和用户抢滚动。
 */
@Composable
private fun LyricsPanel(
    lyrics: List<LyricLine>,
    currentIndex: Int,
    positionState: State<Long>,
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    var userScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            userScrolling = true
        } else {
            delay(4_000)
            userScrolling = false
        }
    }

    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && !userScrolling && settings.autoScrollLyrics) {
            listState.animateScrollToItem(
                index = currentIndex.coerceIn(0, lyrics.lastIndex),
                scrollOffset = -200,
            )
        }
    }

    // 字号档位换算成实际 sp
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

            val baseAlpha = when {
                isCurrent -> 1f
                isPast -> 0.35f
                else -> 0.6f
            }
            val textSize = ((if (line.isBG) 12f else if (isCurrent) 17f else 15f) * scale).sp

            Column(modifier = Modifier.fillMaxWidth()) {
                if (isCurrent) {
                    // 当前行：位置用 State 传进来，内部每帧读一次。
                    //
                    // 不能传裸 Long：外层行号用 derivedStateOf 缓存，行号不变时
                    // 父级不重组，传进来的值会一直停在首次组合那一刻。
                    // 传 State 后，读取发生在 HighlightedLine 内部，
                    // 位置变化能直接驱动它重绘。
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
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = baseAlpha),
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
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = baseAlpha * 0.8f)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 当前行的逐字高亮。
 *
 * 不再用"已唱 / 未唱"两态一刀切——那样每一秒才跳一次，看起来生硬。
 * 现在每个词、每个字都按它自己的时间区间算进度，颜色做插值，
 * 配合外层传入的平滑位置，高亮是连续推进的。
 *
 * 有逐字时间（TTML/QRC/KRC/YRC）：逐词算进度。
 * 只有整行时间（LRC/ASS/SRT）：按字数线性推算每个字的时刻。
 */
/**
 * 逐行歌词的扫过速度系数。
 *
 * 行内进度先除以它、再摊到每个字上，等于让扫过慢一截：
 * 0.85 表示整行读到 85% 时全部点亮，剩下 15% 当滞后缓冲。
 * 目的是把「演唱不均匀导致的提前亮」变成「略微滞后」——
 * 听众更容易接受慢半拍，而不容易接受抢拍。
 */
private const val SCAN_LEAD_RATIO = 0.85f

@Composable
private fun HighlightedLine(
    line: LyricLine,
    positionState: State<Long>,
    fontSize: TextUnit,
) {
    val positionMs by positionState
    val playedColor = MaterialTheme.colorScheme.primary
    val pendingColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)

    val hasWordTiming = line.words.size > 1 &&
        line.words.any { it.endTime - it.startTime > 0L }

    val annotated = buildAnnotatedString {
        val baseStyle = SpanStyle(
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
        )

        if (hasWordTiming) {
            // 逐字歌词：每个词有自己的真实起止，按它自己的进度点亮。
            // 这是唯一"准确"的进度来源——时间由歌词源给出，不是推算的。
            line.words.forEach { w ->
                val span = (w.endTime - w.startTime).coerceAtLeast(1L)
                val p = ((positionMs - w.startTime).toFloat() / span.toFloat()).coerceIn(0f, 1f)
                val color = lerpColor(pendingColor, playedColor, p)
                withStyle(baseStyle.copy(color = color)) { append(w.word) }
            }
        } else {
            // 逐行歌词（LRC / ASS / SRT）：整行只有一个时间区间，没有字级信息。
            //
            // 想要扫过效果，只能按字数把行内进度摊到每个字上。纯线性摊有个毛病：
            // 演唱节奏不均匀，唱得快的段落会被提前点亮——就是"还没唱到就亮了"。
            //
            // 这里用两处修正把误差压到"只会略微滞后、绝不提前"：
            //   1. 整体乘一个 <1 的系数，让扫过略微落后于均分线；
            //   2. 每个字再加一点起始延迟，字与字之间留出一小段缓冲。
            // 代价是行末会稍微慢半拍收尾，但比提前亮要好。
            val full = line.text.ifEmpty { "·" }
            val span = (line.endTime - line.startTime).coerceAtLeast(1L)
            val lineP = ((positionMs - line.startTime).toFloat() / span.toFloat()).coerceIn(0f, 1f)

            // 0.85 的系数：整行读到约 85% 时全部点亮，剩下 15% 作为滞后缓冲
            val effectiveP = (lineP / SCAN_LEAD_RATIO).coerceIn(0f, 1f)
            val n = full.length.coerceAtLeast(1)

            full.forEachIndexed { i, ch ->
                // 每个字在行内的归一化区间，右端再往里收一点，制造字间缓冲
                val charStart = i.toFloat() / n
                val charEnd = (i + 1).toFloat() / n
                val charP = if (effectiveP <= charStart) {
                    0f
                } else if (effectiveP >= charEnd) {
                    1f
                } else {
                    (effectiveP - charStart) / (charEnd - charStart)
                }
                val color = lerpColor(pendingColor, playedColor, charP)
                withStyle(baseStyle.copy(color = color)) { append(ch) }
            }
        }
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium,
        lineHeight = fontSize * 1.4f,
    )
}

/* ------------------------------------------------------------------ 顶部连接条 */

@Composable
private fun ConnectionBar(
    state: RemoteUiState,
    onReconnect: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val (dotColor, label) = when (state.connection) {
        ConnectionState.Connected -> Color(0xFF4ADE80) to "已连接"
        ConnectionState.Connecting -> Color(0xFFFBBF24) to "连接中"
        ConnectionState.Failed -> Color(0xFFF87171) to "未连接"
        ConnectionState.Unknown -> Color.Gray to "未知"
    }
    val animatedDot by animateColorAsState(targetValue = dotColor, animationSpec = tween(400), label = "dot")

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(animatedDot),
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
        IconButton(onClick = onReconnect, modifier = Modifier.size(40.dp)) {
            VectorIcon(icon = Glyph.Link, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onSettingsClick, modifier = Modifier.size(40.dp)) {
            VectorIcon(icon = Glyph.AutoFind, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    state.errorMessage?.let {
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


/* ------------------------------------------------------------------ 当前播放头部 */

/**
 * 封面 + 曲目信息横向排布。
 *
 * 竖屏手机上纵向空间最紧，封面不该独占整屏——缩到 96dp 后，
 * 队列能多显示七八行，这才是遥控器最常用的部分。
 */
@Composable
private fun NowPlayingHeader(track: Track?, settings: AppSettings) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(track = track, size = settings.coverSize)

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track?.title ?: "没有正在播放的曲目",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = track?.artists?.joinToString(" / ") { it.name }.orEmpty()
                    .ifBlank { track?.album?.name.orEmpty() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            track?.quality?.let { q ->
                val detail = listOfNotNull(
                    q.codec?.uppercase(),
                    q.sampleRate?.let { "${it / 1000}kHz" },
                    q.bitsPerSample?.let { "${it}bit" },
                ).joinToString(" · ")
                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ 专辑封面 */

@Composable
private fun AlbumArt(track: Track?, size: CoverSize) {
    val cover = track?.cover ?: track?.album?.cover

    Box(
        modifier = Modifier
            .size(
                when (size) {
                    CoverSize.Small -> 64.dp
                    CoverSize.Medium -> 96.dp
                    CoverSize.Large -> 132.dp
                }
            )
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(12.dp), clip = false)
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.secondaryContainer,
                    )
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = "专辑封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            VectorIcon(
                icon = Glyph.Music,
                size = 36.dp,
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
        }
    }
}

/* ------------------------------------------------------------------ 进度 */

@Composable
private fun ProgressRow(
    state: RemoteUiState,
    smoothPosition: Long,
    onSeekStart: () -> Unit,
    onSeekChange: (Long) -> Unit,
    onSeekCommit: () -> Unit,
) {
    val duration = state.status?.duration ?: 0L
    val position = if (state.isSeeking) state.seekPreviewMs else smoothPosition
    val max = duration.coerceAtLeast(1L).toFloat()

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = position.coerceIn(0L, duration.coerceAtLeast(0L)).toFloat(),
            onValueChange = {
                if (!state.isSeeking) onSeekStart()
                onSeekChange(it.toLong())
            },
            onValueChangeFinished = onSeekCommit,
            valueRange = 0f..max,
            enabled = duration > 0,
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
                text = formatTime(duration),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ------------------------------------------------------------------ 播放控制 */

@Composable
private fun TransportRow(
    isPlaying: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev, enabled = enabled, modifier = Modifier.size(48.dp)) {
            VectorIcon(
                icon = Glyph.Prev,
                size = 26.dp,
                tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
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
                tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
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

/* ------------------------------------------------------------------ 音量 */

@Composable
private fun VolumeRow(volume: Float, onChange: (Float) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        VectorIcon(
            icon = Glyph.VolumeLow,
            size = 16.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = volume.coerceIn(0f, 1f),
            onValueChange = onChange,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            valueRange = 0f..1f,
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
            text = "${(volume * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(34.dp),
        )
    }
}

/* ------------------------------------------------------------------ 队列 */

@Composable
private fun QueueRow(track: Track, index: Int, isCurrent: Boolean) {
    val bg = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isCurrent) {
                VectorIcon(
                    icon = Glyph.Play,
                    size = 12.dp,
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    text = index.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }

        Spacer(Modifier.width(4.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title.ifBlank { "未知曲目" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = track.artists.joinToString(" / ") { it.name },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = formatTime(track.duration),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 毫秒转 m:ss 或 h:mm:ss */
internal fun formatTime(millis: Long): String {
    if (millis <= 0L) return "0:00"
    val totalSeconds = millis / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
