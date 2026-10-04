package io.github.yingdu

/**
 * 录音清理：降噪 + 音量拉齐 + 限幅（16 kHz 单声道）。
 *
 *  1. 音量：按“说话部分”的平均响度拉到 -20 dBFS（最多放大 +24 dB、最多压小 6 dB），不按峰值——
 *     一声咳嗽或碰麦不会让整段都拉不上去；原本很小声的录音能拉到正常音量；
 *  2. 降噪：
 *     - [Mode.WIENER]：维纳滤波（[NoiseReducer]），便宜、人声失真小，转文字前用；
 *     - [Mode.NONE]：不降噪，只调音量；
 *  3. 限幅：提前 5 ms 看到要超 -1 dBFS 的峰就平滑地压下来（不削波、不爆音），100 ms 慢慢放开。
 */
object AudioClean {
    const val RATE = 16_000

    enum class Mode { NONE, WIENER }

    /** 整段处理（转文字、测试用）。 */
    fun process(pcm: ShortArray, mode: Mode, floor: Float = 0.05f, boost: Boolean = true): ShortArray =
        Cleaner(pcm, mode, floor, boost).readAll()

    /**
     * 算把“说话部分”的平均响度拉到 target dBFS 要乘的倍数（限制在 [minDb, maxDb]）。
     * 20 ms 一帧，取比最响的 5% 帧低 20 dB 以内的帧算平均功率（停顿、底噪不算进去）。几乎没声音就不动。
     */
    internal fun gainFor(x: FloatArray, target: Double, maxDb: Double, minDb: Double): Float {
        val fl = RATE / 50
        val frames = x.size / fl
        if (frames < 5) return 1f
        val pow = DoubleArray(frames) { f ->
            var s = 0.0
            for (i in f * fl until (f + 1) * fl) s += x[i].toDouble() * x[i]
            s / fl
        }
        val loud = pow.sortedArray()[(frames * 0.95).toInt().coerceAtMost(frames - 1)]
        if (loud < 1.0) return 1f                          // 全段不到 -90 dBFS：静音
        val thr = loud / 100                               // -20 dB
        var sum = 0.0; var cnt = 0
        for (p in pow) if (p >= thr) { sum += p; cnt++ }
        val levelDb = 10 * Math.log10(sum / cnt / (32768.0 * 32768.0))
        val g = (target - levelDb).coerceIn(minDb, maxDb)
        return Math.pow(10.0, g / 20).toFloat()
    }

    /**
     * 一段录音的清理器：[read] 一次取一块，[readAll] 一次取完。一个实例只能一个线程用。
     * @param floor 降噪增益下限（转文字用 0.2，压太狠会伤识别）
     */
    class Cleaner(pcm: ShortArray, mode: Mode, floor: Float, boost: Boolean) {
        val size = pcm.size
        private val y = FloatArray(size)        // 降噪 + 增益后、限幅前
        private var done = 0                    // 已经交出去的
        // 限幅
        private val look = RATE / 200           // 5 ms
        private val ceil = 32767f * 0.891f      // -1 dBFS
        private val rel = (1 - Math.exp(-1.0 / (0.1 * RATE))).toFloat()
        private val mRing = FloatArray(look + 1)
        private var acc = 0.0
        private var g = 1f

        init {
            val raw = FloatArray(size) { pcm[it].toFloat() }
            val gain = if (boost) gainFor(raw, target = -20.0, maxDb = 24.0, minDb = -6.0) else 1f
            val src = if (mode != Mode.NONE && size >= 2048) NoiseReducer.process(pcm, RATE, floor = floor.toDouble(), normalize = false) else pcm
            for (i in 0 until size) y[i] = src[i] * gain
        }

        /** 取接下来最多 len 个采样到 dst，返回取到的个数；取完了返回 -1。 */
        fun read(dst: ShortArray, off: Int, len: Int): Int {
            val want = minOf(len, size - done)
            if (want <= 0) return -1
            for (k in 0 until want) {
                val i = done + k
                // m：往后 5 ms 内最多能乘多少（保证峰值那一刻已经压到位）
                var m = 1f
                val end = minOf(size - 1, i + look)
                for (j in i..end) { val a = Math.abs(y[j]); if (a > ceil) { val r = ceil / a; if (r < m) m = r } }
                // 对最近 look+1 个 m 做滑动平均，得到平滑的下压斜坡；恢复按 100 ms 慢慢来
                val slot = i % (look + 1)
                if (i > look) acc -= mRing[slot]
                mRing[slot] = m; acc += m
                val avg = (acc / minOf(i + 1, look + 1)).toFloat()
                g = if (avg < g) avg else g + (avg - g) * rel
                dst[off + k] = Math.round(y[i] * g).coerceIn(-32768, 32767).toShort()
            }
            done += want
            return want
        }

        fun readAll(): ShortArray {
            val out = ShortArray(size)
            var off = 0
            while (off < size) { val n = read(out, off, minOf(16_000, size - off)); if (n <= 0) break; off += n }
            return out
        }
    }
}
