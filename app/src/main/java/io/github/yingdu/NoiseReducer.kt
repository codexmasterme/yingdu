package io.github.yingdu

/**
 * 录音降噪（16 kHz 单声道 PCM，整段处理）。纯 Kotlin，有单元测试。
 *
 * 眼镜麦克风底噪很大（一直有嘶嘶声和低频嗡嗡声，安静时约 -18 dBFS，说话只比它高 10 dB 左右），回放发糊，也影响转文字。
 * 做法是经典的"谱减 / 维纳滤波"：
 *  1. 分帧做 FFT（32 ms 一帧、一半重叠，平方根汉宁窗，处理完能无损拼回去）；
 *  2. 估计底噪：把整段里最安静的 20% 帧的频谱平均起来，当作每个频率上的底噪（一段录音里总有换气、停顿）；
 *  3. 每帧每个频率算一个增益：用"决策导向"的先验信噪比平滑（Ephraim–Malah），避免"音乐噪声"（叽叽喳喳的残留）；
 *     增益最低压到 floor（默认 -20 dB，不压成绝对安静，听起来自然，也不伤转文字）；80 Hz 以下直接压到最低；
 *  4. 拼回去后把音量拉到合适的大小（峰值到 -1 dBFS，最多放大 4 倍）。
 */
object NoiseReducer {
    private const val N = 512              // 32 ms
    private const val HOP = N / 2
    private const val BINS = N / 2 + 1

    /** @param floor 增益下限（0.1 = -20 dB）；@param over 过减系数（>1 压得更狠）。 */
    fun process(pcm: ShortArray, sampleRate: Int = 16_000, floor: Double = 0.1, over: Double = 1.5, normalize: Boolean = true): ShortArray {
        if (pcm.size < N * 4) return pcm.copyOf()
        val win = DoubleArray(N) { Math.sqrt(0.5 - 0.5 * Math.cos(2 * Math.PI * it / N)) }   // 平方根汉宁窗（周期形式）
        val frames = (pcm.size - N) / HOP + 1
        val re = Array(frames) { DoubleArray(N) }
        val im = Array(frames) { DoubleArray(N) }
        val pow = Array(frames) { DoubleArray(BINS) }
        val energy = DoubleArray(frames)
        for (t in 0 until frames) {
            val r = re[t]; val i = im[t]; val o = t * HOP
            for (k in 0 until N) r[k] = pcm[o + k] * win[k]
            fft(r, i)
            var e = 0.0
            for (b in 0 until BINS) { val p = r[b] * r[b] + i[b] * i[b]; pow[t][b] = p; e += p }
            energy[t] = e
        }
        // 底噪：最安静的 20% 帧（至少 5 帧）的平均频谱
        val quiet = energy.indices.sortedBy { energy[it] }.take(maxOf(5, frames / 5))
        val noise = DoubleArray(BINS)
        for (t in quiet) for (b in 0 until BINS) noise[b] += pow[t][b]
        for (b in 0 until BINS) noise[b] = noise[b] / quiet.size + 1e-9

        val lowBin = (80.0 * N / sampleRate).toInt()
        val prevGain2Post = DoubleArray(BINS)       // 上一帧的 G² × 后验信噪比
        val gain = DoubleArray(BINS)
        val alpha = 0.98
        for (t in 0 until frames) {
            for (b in 0 until BINS) {
                val post = pow[t][b] / (over * noise[b])
                val prio = if (t == 0) maxOf(post - 1, 0.0) else alpha * prevGain2Post[b] + (1 - alpha) * maxOf(post - 1, 0.0)
                var g = prio / (1 + prio)
                if (b <= lowBin) g = 0.0
                g = maxOf(g, floor)
                gain[b] = g
                prevGain2Post[b] = g * g * post
            }
            // 频率方向轻微平滑（3 点），进一步减少残留的"叽叽"声
            val r = re[t]; val i = im[t]
            for (b in 0 until BINS) {
                val g = if (b == 0 || b == BINS - 1) gain[b] else (gain[b - 1] + 2 * gain[b] + gain[b + 1]) / 4
                r[b] *= g; i[b] *= g
                if (b in 1 until N / 2) { r[N - b] = r[b]; i[N - b] = -i[b] }   // 保持共轭对称，逆变换是实数
            }
        }
        // 逆变换、加窗、重叠相加
        val out = DoubleArray(pcm.size)
        for (t in 0 until frames) {
            val r = re[t]; val i = im[t]
            for (k in 0 until N) i[k] = -i[k]
            fft(r, i)                                // 用正变换做逆变换：取共轭 → FFT → 取共轭 / N
            val o = t * HOP
            for (k in 0 until N) out[o + k] += r[k] / N * win[k]
        }
        // 开头、结尾各半帧（16 ms）只有一个窗覆盖，会渐入渐出，听不出来
        val scale = if (normalize) {
            val peak = out.maxOf { Math.abs(it) }.coerceAtLeast(1.0)
            (32767 * 0.89 / peak).coerceIn(1.0, 4.0)        // -1 dBFS，最多放大 4 倍（+12 dB）
        } else 1.0
        return ShortArray(pcm.size) { (out[it] * scale).toInt().coerceIn(-32768, 32767).toShort() }
    }

    /** 原地复数 FFT（基 2，长度是 2 的幂）。 */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * Math.PI / len
            val wr = Math.cos(ang); val wi = Math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci; val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
