package top.dsh.splayerremote.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.dsh.splayerremote.data.LyricLine
import top.dsh.splayerremote.data.LyricWord

class LyricsTest {

    private fun line(start: Long, end: Long, text: String) = LyricLine(
        words = listOf(LyricWord(start, end, text)),
        startTime = start,
        endTime = end,
    )

    private fun wordedLine(vararg words: LyricWord) = LyricLine(
        words = words.toList(),
        startTime = words.first().startTime,
        endTime = words.last().endTime,
    )

    private val lines = listOf(
        line(0, 2_000, "first"),
        line(2_000, 4_000, "second"),
        line(4_000, 6_000, "third"),
    )

    @Test
    fun `index is minus one before first line`() {
        assertEquals(-1, lyricIndexAt(lines, -1))
    }

    @Test
    fun `index follows start time boundaries`() {
        assertEquals(0, lyricIndexAt(lines, 0))
        assertEquals(0, lyricIndexAt(lines, 1_999))
        assertEquals(1, lyricIndexAt(lines, 2_000))
        assertEquals(2, lyricIndexAt(lines, 5_999))
        // 超过最后一行仍停在最后一行
        assertEquals(2, lyricIndexAt(lines, 60_000))
    }

    @Test
    fun `index sits on last line during gap after it ends`() {
        // 行已结束但下一行未开始：仍高亮该行，避免间奏时高亮消失
        assertEquals(2, lyricIndexAt(lines, 6_500))
    }

    @Test
    fun `empty lyrics gives minus one`() {
        assertEquals(-1, lyricIndexAt(emptyList(), 1_000))
    }

    @Test
    fun `word timed line lights each word by its own range`() {
        val l = wordedLine(
            LyricWord(0, 1_000, "aa"),
            LyricWord(1_000, 2_000, "bb"),
        )
        val spans = highlightSpans(l, 1_500)
        assertEquals("aa", spans[0].text)
        assertEquals(1f, spans[0].progress, 0.001f)
        assertEquals("bb", spans[1].text)
        assertEquals(0.5f, spans[1].progress, 0.001f)
    }

    @Test
    fun `line timed sweep never runs ahead of the line`() {
        // 长行 + 多字：扫过系数 0.85 意味着行内 50% 时不应该全部点亮
        val text = "0123456789"
        val l = line(0, 10_000, text)
        val spans = highlightSpans(l, 5_000)
        val fullyLit = spans.count { it.progress >= 1f }
        assertTrue("不应超过一半以上字符已全亮，实际 $fullyLit", fullyLit <= 6)
        // 第一个字符必然已亮
        assertEquals(1f, spans.first().progress, 0.001f)
    }

    @Test
    fun `line timed sweep completes near line end`() {
        val l = line(0, 10_000, "abcd")
        val spans = highlightSpans(l, 9_000)
        assertTrue("行末应基本点亮", spans.all { it.progress > 0.9f })
    }

    @Test
    fun `single word line falls back to character sweep`() {
        // words.size == 1 视为逐行歌词，即使有时间
        val l = line(0, 4_000, "abcd")
        val spans = highlightSpans(l, 2_000)
        assertEquals(4, spans.size)
        assertEquals("a", spans[0].text)
    }
}
