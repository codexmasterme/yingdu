package io.github.yingdu

/**
 * 有没有人在说话（16 kHz 单声道 PCM，每 10 ms 判断一次）。纯 Kotlin，不依赖 Android，有单元测试。
 *
 * 眼镜麦克风底噪很大（一直有嘶嘶声，约 -20 dBFS），只看音量分不出来，所以同时看两样：
 *  1. 这一帧比"底噪"响多少：底噪取最近 3 秒平滑能量的最小值（说话中间的停顿就能露出真实底噪）；
 *  2. 是不是有周期（人声的基频 60～500 Hz）：在 8 kHz 上算 32 ms 的归一化自相关。
 * 两样都满足算"有声帧"；最近 300 ms 里有声帧占 40% 以上就算"在说话"。
 * 白噪声、持续的单音（冰箱、空调嗡嗡声会慢慢变成底噪）都不会触发。
 * 参数用真实的眼镜录音调过。
 */
class SpeechDetector {
    private var prevIn = 0.0
    private var hp = 0.0
    private var half = 0.0
    private var halfOdd = false
    private val buf = DoubleArray(WIN)          // 最近 32 ms（8 kHz）
    private val frame = DoubleArray(FRAME8)
    private var frameN = 0
    private var smooth = Double.NaN
    private val hist = ArrayDeque<Double>()     // 最近 3 秒的平滑能量
    private val votes = ArrayDeque<Boolean>()
    private var voteCount = 0

    /** 最近一帧是不是有声帧。 */
    var voiced = false; private set
    /** 现在是不是在说话。 */
    var speaking = false; private set
    /** 最近一帧比底噪高多少 dB。 */
    var aboveFloorDb = 0.0; private set
    /** 最近一帧的能量（dB，去掉低频嗡嗡声后）。 */
    var levelDb = 0.0; private set
    /** 最近一帧的基频（Hz，没有周期时为 0）。 */
    var pitchHz = 0.0; private set
    /** 处理过的 10 ms 帧数。 */
    var frames = 0L; private set

    /** 送入 16 kHz PCM；每满 10 ms 调用一次 onFrame（可为 null）。 */
    fun feed(pcm: ShortArray, n: Int = pcm.size, onFrame: ((SpeechDetector) -> Unit)? = null) {
        for (i in 0 until n) {
            val x = pcm[i].toDouble()
            hp = 0.97 * (hp + x - prevIn); prevIn = x          // 去掉直流和很低的嗡嗡声
            if (!halfOdd) { half = hp; halfOdd = true; continue }
            halfOdd = false
            frame[frameN++] = (half + hp) / 2                   // 降到 8 kHz
            if (frameN == FRAME8) { frameN = 0; step(); onFrame?.invoke(this) }
        }
    }

    private fun step() {
        frames++
        System.arraycopy(buf, FRAME8, buf, 0, WIN - FRAME8)
        System.arraycopy(frame, 0, buf, WIN - FRAME8, FRAME8)
        var sq = 0.0
        for (v in frame) sq += v * v
        val e = 10 * Math.log10(sq / FRAME8 + 1)
        smooth = if (smooth.isNaN()) e else 0.7 * smooth + 0.3 * e
        hist.addLast(smooth); if (hist.size > FLOOR_FRAMES) hist.removeFirst()
        var floor = Double.MAX_VALUE
        for (h in hist) if (h < floor) floor = h
        aboveFloorDb = e - floor
        levelDb = e
        val per = periodicity()
        pitchHz = if (per > PERIODICITY) 8000.0 / bestLag else 0.0
        voiced = aboveFloorDb > ABOVE_FLOOR_DB && per > PERIODICITY
        votes.addLast(voiced); if (voiced) voteCount++
        if (votes.size > VOTE_FRAMES && votes.removeFirst()) voteCount--
        speaking = voteCount >= VOTE_NEED
    }

    private var bestLag = MIN_LAG
    private val corr = DoubleArray(MAX_LAG)

    /**
     * 32 ms 窗口的归一化自相关最大值（基频 62～500 Hz）。
     * 基频的周期记在 bestLag：取第一个接近最大值的峰（最大值常落在两倍周期上，那样会把音高算低一个八度）。
     */
    private fun periodicity(): Double {
        var mean = 0.0
        for (v in buf) mean += v
        mean /= WIN
        var r0 = 0.0
        for (i in 0 until WIN) { val v = buf[i] - mean; r0 += v * v }
        if (r0 <= 1e-9) return 0.0
        var best = 0.0
        for (lag in MIN_LAG until MAX_LAG) {
            var s = 0.0
            for (i in 0 until WIN - lag) s += (buf[i] - mean) * (buf[i + lag] - mean)
            val r = s / r0 * WIN / (WIN - lag)
            corr[lag] = r
            if (r > best) best = r
        }
        bestLag = MIN_LAG
        for (lag in MIN_LAG + 1 until MAX_LAG - 1)
            if (corr[lag] >= 0.9 * best && corr[lag] >= corr[lag - 1] && corr[lag] >= corr[lag + 1]) { bestLag = lag; break }
        return best
    }

    companion object {
        private const val FRAME8 = 80           // 10 ms @ 8 kHz
        private const val WIN = 256             // 32 ms @ 8 kHz
        private const val MIN_LAG = 16          // 500 Hz
        private const val MAX_LAG = 128         // 62.5 Hz
        private const val FLOOR_FRAMES = 300    // 3 秒
        private const val VOTE_FRAMES = 30      // 300 ms
        private const val VOTE_NEED = 12        // 40%
        const val ABOVE_FLOOR_DB = 6.0
        const val PERIODICITY = 0.55
    }
}
