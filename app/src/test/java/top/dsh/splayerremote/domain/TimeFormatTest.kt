package top.dsh.splayerremote.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {

    @Test
    fun `non positive values render as zero`() {
        assertEquals("0:00", formatTime(0))
        assertEquals("0:00", formatTime(-1))
    }

    @Test
    fun `under a minute`() {
        assertEquals("0:01", formatTime(1_000))
        assertEquals("0:59", formatTime(59_999))
    }

    @Test
    fun `minutes and seconds are zero padded`() {
        assertEquals("1:05", formatTime(65_000))
        assertEquals("59:59", formatTime(3_599_000))
    }

    @Test
    fun `hours appear only above one hour`() {
        assertEquals("1:00:00", formatTime(3_600_000))
        assertEquals("2:03:04", formatTime(7_384_000))
    }
}
