package top.dsh.splayerremote.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PollPolicyTest {

    @Test
    fun `playing in foreground keeps base interval`() {
        assertEquals(1000L, PollPolicy.pollDelay(1000L, playing = true, foreground = true))
    }

    @Test
    fun `paused doubles the interval`() {
        assertEquals(2000L, PollPolicy.pollDelay(1000L, playing = false, foreground = true))
    }

    @Test
    fun `background doubles the interval`() {
        assertEquals(2000L, PollPolicy.pollDelay(1000L, playing = true, foreground = false))
    }

    @Test
    fun `paused in background is clamped to max`() {
        // 1000 × 2 × 2 = 4000，未超上限
        assertEquals(4000L, PollPolicy.pollDelay(1000L, playing = false, foreground = false))
        // 5000 已经是上限，暂停+后台不应突破
        assertEquals(5000L, PollPolicy.pollDelay(5000L, playing = false, foreground = false))
    }

    @Test
    fun `interval below minimum is raised`() {
        assertEquals(500L, PollPolicy.pollDelay(100L, playing = true, foreground = true))
    }

    @Test
    fun `retry delay stays within bounds`() {
        assertEquals(300L, PollPolicy.retryDelay(400L))
        assertEquals(500L, PollPolicy.retryDelay(1000L))
        assertEquals(1000L, PollPolicy.retryDelay(5000L))
    }

    @Test
    fun `disconnect needs two consecutive failures`() {
        assertFalse(PollPolicy.isDisconnected(0))
        assertFalse(PollPolicy.isDisconnected(1))
        assertTrue(PollPolicy.isDisconnected(2))
        assertTrue(PollPolicy.isDisconnected(5))
    }

    @Test
    fun `discovery requires enabled and disconnected`() {
        assertFalse(
            PollPolicy.shouldDiscover(
                autoDiscoverEnabled = false,
                consecutiveFailures = 5,
                lastDiscoveryAt = 0L,
                now = 100_000L,
            )
        )
        assertFalse(
            PollPolicy.shouldDiscover(
                autoDiscoverEnabled = true,
                consecutiveFailures = 1,
                lastDiscoveryAt = 0L,
                now = 100_000L,
            )
        )
        assertTrue(
            PollPolicy.shouldDiscover(
                autoDiscoverEnabled = true,
                consecutiveFailures = 2,
                lastDiscoveryAt = 0L,
                now = 100_000L,
            )
        )
    }

    @Test
    fun `discovery is throttled after first scan`() {
        val first = 1_000L
        // 刚扫过 5 秒，不该再扫
        assertFalse(
            PollPolicy.shouldDiscover(true, 3, first, first + 5_000L)
        )
        // 过了 30 秒，可以再扫
        assertTrue(
            PollPolicy.shouldDiscover(true, 3, first, first + PollPolicy.DISCOVERY_MIN_GAP_MS)
        )
    }
}
