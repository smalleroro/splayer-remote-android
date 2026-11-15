package top.dsh.splayerremote.domain

import android.os.SystemClock

/**
 * 单调时钟。
 *
 * 抽成接口只为一个目的：让插值、退避这类逻辑能在 JVM 单测里跑，
 * 而不是每处直接调 SystemClock.elapsedRealtime() 导致无法断言时间。
 */
interface Clock {
    /** 毫秒，单调递增，不受系统时间调整影响 */
    fun now(): Long
}

object MonotonicClock : Clock {
    override fun now(): Long = SystemClock.elapsedRealtime()
}

/** 测试用：手动推进的时间 */
class FakeClock(private var current: Long = 0L) : Clock {
    override fun now(): Long = current
    fun advance(ms: Long) {
        current += ms
    }
}
