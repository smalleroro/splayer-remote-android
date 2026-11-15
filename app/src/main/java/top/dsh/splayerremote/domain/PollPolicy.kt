package top.dsh.splayerremote.domain

/**
 * 轮询节奏与自动发现的决策。
 *
 * 从 ViewModel 里抽出来的理由：这些是「策略」，不是「副作用」。
 * 抽成纯函数后可以写表驱动单测，不必起模拟器、不必连服务器。
 */
object PollPolicy {

    /** 用户设置的下限；再低对局域网也没意义，只会白耗电 */
    const val MIN_INTERVAL_MS = 500L

    /** 单次轮询的上限，防止设置被拖到很大时界面几乎不更新 */
    const val MAX_INTERVAL_MS = 5_000L

    /** 判定「真的断了」需要的连续失败次数 */
    const val DISCONNECT_THRESHOLD = 2

    /** 容忍期内的重试间隔范围 */
    private const val RETRY_MIN_MS = 300L
    private const val RETRY_MAX_MS = 1_000L

    /** 认定断开后的重试间隔 */
    const val OFFLINE_RETRY_MS = 3_000L

    /** 两次自动发现之间至少隔这么久，避免每轮失败都扫一遍 /24 */
    const val DISCOVERY_MIN_GAP_MS = 30_000L

    /**
     * 下一次正常轮询的间隔。
     *
     * 三个因子：用户设置、是否正在播放、应用是否在前台。
     * 暂停时没必要那么频繁——位置是静止的，本地插值也不会漂；
     * 退到后台时同理，还省电。
     */
    fun pollDelay(baseIntervalMs: Long, playing: Boolean, foreground: Boolean): Long {
        var interval = baseIntervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        if (!playing) interval *= 2
        if (!foreground) interval *= 2
        return interval.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }

    /** 容忍期内的短重试：快速恢复，但不至于把网络压满 */
    fun retryDelay(baseIntervalMs: Long): Long =
        (baseIntervalMs / 2).coerceIn(RETRY_MIN_MS, RETRY_MAX_MS)

    /** 连续失败到阈值才算断开 */
    fun isDisconnected(consecutiveFailures: Int): Boolean =
        consecutiveFailures >= DISCONNECT_THRESHOLD

    /**
     * 这一轮失败后该不该去扫局域网。
     *
     * 三个条件缺一不可：用户开了自动发现、已经判定断开、距上次扫描超过间隔。
     * 之前代码每轮失败都扫，与注释写的行为相反。
     */
    fun shouldDiscover(
        autoDiscoverEnabled: Boolean,
        consecutiveFailures: Int,
        lastDiscoveryAt: Long,
        now: Long,
    ): Boolean {
        if (!autoDiscoverEnabled) return false
        if (!isDisconnected(consecutiveFailures)) return false
        if (lastDiscoveryAt == 0L) return true
        return now - lastDiscoveryAt >= DISCOVERY_MIN_GAP_MS
    }
}
