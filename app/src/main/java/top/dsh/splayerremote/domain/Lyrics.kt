package top.dsh.splayerremote.domain

import top.dsh.splayerremote.data.LyricLine

/**
 * 歌词行定位与高亮进度计算。
 *
 * 全部是纯函数：输入歌词与播放位置，输出"该高亮哪一行""这一行亮到哪里"。
 * 之所以从 UI 里抽出来，是因为这套规则（二分定位、逐字 vs 逐行、
 * 宁可慢不许快的扫过系数）是整个项目最需要回归测试的部分，
 * 而它们不应该依赖 Compose 才能被验证。
 */

/**
 * 找出某一时刻应该高亮哪一行。
 *
 * 返回满足 `startTime <= positionMs` 的最后一行下标；没有则 -1。
 * 歌词按 startTime 升序，用二分而非线性扫描——高频轮询时也不构成瓶颈。
 */
fun lyricIndexAt(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    var low = 0
    var high = lines.size - 1
    var result = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].startTime <= positionMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

/** 一行里的一段文字及其已亮比例 */
data class LyricSpan(val text: String, val progress: Float)

/**
 * 逐行歌词的扫过系数。
 *
 * 行内进度先除以它、再摊到每个字上，等于让扫过慢一截：
 * 0.85 表示整行读到 85% 时全部点亮，剩下 15% 当滞后缓冲。
 * 目的是把「演唱不均匀导致的提前亮」变成「略微滞后」——
 * 听众更容易接受慢半拍，而不容易接受抢拍。
 */
const val SCAN_LEAD_RATIO = 0.85f

/**
 * 计算当前行的分段高亮。
 *
 * 两种来源两套算法：
 *  - 有字级时间（TTML/QRC/KRC/YRC）：每个词按自己的起止算进度，准确；
 *  - 只有整行时间（LRC/ASS/SRT）：把行内进度摊到每个字上，并乘扫过系数。
 *
 * 返回的进度是 0f..1f，颜色插值交给 UI。这样这段逻辑可以脱离 Compose 测试。
 */
fun highlightSpans(line: LyricLine, positionMs: Long): List<LyricSpan> {
    val hasWordTiming = line.words.size > 1 &&
        line.words.any { it.endTime - it.startTime > 0L }

    if (hasWordTiming) {
        return line.words.map { w ->
            val span = (w.endTime - w.startTime).coerceAtLeast(1L)
            val p = ((positionMs - w.startTime).toFloat() / span.toFloat()).coerceIn(0f, 1f)
            LyricSpan(w.word, p)
        }
    }

    val full = line.text.ifEmpty { "·" }
    val span = (line.endTime - line.startTime).coerceAtLeast(1L)
    val lineP = ((positionMs - line.startTime).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    val effectiveP = (lineP / SCAN_LEAD_RATIO).coerceIn(0f, 1f)
    val n = full.length.coerceAtLeast(1)

    return full.mapIndexed { i, ch ->
        val charStart = i.toFloat() / n
        val charEnd = (i + 1).toFloat() / n
        val charP = when {
            effectiveP <= charStart -> 0f
            effectiveP >= charEnd -> 1f
            else -> (effectiveP - charStart) / (charEnd - charStart)
        }
        LyricSpan(ch.toString(), charP)
    }
}
