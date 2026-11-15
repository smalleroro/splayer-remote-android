package top.dsh.splayerremote.domain

/**
 * 播放位置的本地插值。
 *
 * 服务器状态每秒才回来一次，直接把 position 交给界面有两个毛病：
 * 进度条比实际慢一拍；逐字歌词一秒跳一格。
 *
 * 做法：把每次拿到的 (position, 本地时刻) 当锚点，两帧之间按帧时间自己往前推。
 * 下一次采样到来时重新对齐，误差不累积。
 */
data class PositionAnchor(
    val positionMs: Long,
    val atMs: Long,
    val playing: Boolean,
    val speed: Float,
)

object PlaybackInterpolation {

    /** 两次采样之间最多外推这么久；超过说明已经拿不到服务器状态了 */
    const val MAX_EXTRAPOLATE_MS = 6_000L

    /** 网络往返补偿上限，超过说明网络异常，不硬补 */
    const val MAX_LATENCY_COMPENSATION_MS = 2_000L

    /**
     * 估算当前播放位置。
     *
     * 暂停时返回锚点本身，不做外推——那时位置是静止的，
     * 继续跑只会让进度条慢慢飘走。
     */
    fun estimate(anchor: PositionAnchor, now: Long, durationMs: Long): Long {
        if (!anchor.playing) return anchor.positionMs
        val elapsed = (now - anchor.atMs).coerceIn(0L, MAX_EXTRAPOLATE_MS)
        val scaled = (elapsed * anchor.speed.coerceAtLeast(0.1f)).toLong()
        val raw = anchor.positionMs + scaled
        return if (durationMs > 0L) raw.coerceIn(0L, durationMs) else raw
    }

    /**
     * 采样到手时的往返补偿。
     *
     * 服务端给的 position 是它处理请求那一刻的值，到我们手里已经过了半个 RTT。
     * 播放中把它补回来，否则歌词会系统性延迟。暂停时不补——位置静止，补了会跳。
     */
    fun compensateLatency(positionMs: Long, playing: Boolean, requestAt: Long, now: Long): Long {
        if (!playing) return positionMs
        val rtt = (now - requestAt).coerceIn(0L, MAX_LATENCY_COMPENSATION_MS)
        return positionMs + rtt
    }
}
