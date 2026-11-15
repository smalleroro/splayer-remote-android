package top.dsh.splayerremote.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackInterpolationTest {

    private fun anchor(position: Long, at: Long, playing: Boolean, speed: Float = 1f) =
        PositionAnchor(positionMs = position, atMs = at, playing = playing, speed = speed)

    @Test
    fun `playing advances with elapsed time`() {
        val a = anchor(10_000, 1_000, playing = true)
        assertEquals(12_000L, PlaybackInterpolation.estimate(a, now = 3_000, durationMs = 60_000))
    }

    @Test
    fun `paused does not advance`() {
        val a = anchor(10_000, 1_000, playing = false)
        assertEquals(10_000L, PlaybackInterpolation.estimate(a, now = 99_000, durationMs = 60_000))
    }

    @Test
    fun `extrapolation is capped so a stale sample cannot run away`() {
        val a = anchor(10_000, 0, playing = true)
        // 距离锚点 60 秒，但最多只外推 MAX_EXTRAPOLATE_MS
        assertEquals(
            10_000L + PlaybackInterpolation.MAX_EXTRAPOLATE_MS,
            PlaybackInterpolation.estimate(a, now = 60_000, durationMs = 600_000),
        )
    }

    @Test
    fun `position is clamped to duration`() {
        val a = anchor(59_000, 0, playing = true)
        assertEquals(60_000L, PlaybackInterpolation.estimate(a, now = 5_000, durationMs = 60_000))
    }

    @Test
    fun `speed scales the advance`() {
        val a = anchor(0, 0, playing = true, speed = 2f)
        assertEquals(4_000L, PlaybackInterpolation.estimate(a, now = 2_000, durationMs = 0))
    }

    @Test
    fun `latency compensation only applies while playing`() {
        assertEquals(
            10_500L,
            PlaybackInterpolation.compensateLatency(10_000, playing = true, requestAt = 0, now = 500),
        )
        assertEquals(
            10_000L,
            PlaybackInterpolation.compensateLatency(10_000, playing = false, requestAt = 0, now = 500),
        )
    }

    @Test
    fun `latency compensation is capped`() {
        assertEquals(
            10_000L + PlaybackInterpolation.MAX_LATENCY_COMPENSATION_MS,
            PlaybackInterpolation.compensateLatency(
                10_000,
                playing = true,
                requestAt = 0,
                now = 100_000,
            ),
        )
    }
}
