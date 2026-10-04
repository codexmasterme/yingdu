package io.github.yingdu

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 分辨"我"（戴眼镜的人）和旁边的人。纯 Kotlin，有单元测试。
 *
 * 眼镜麦克风离自己的嘴只有十几厘米，所以自己说话时：
 *  - 响得多（通常比对面的人高 10 dB 以上）；
 *  - 低频更重（近讲效应，还有一部分从骨头传过来）；
 *  - 音高是自己的。
 * 一段录音先按短停顿切成小块，每块算这三样；相邻两块差得多就当换了人，切成几"轮"，每轮单独转文字。
 * 每轮是谁，现场用 [SpeakerModel] 判断（它会随着使用越来越准）：
 *  - 用户在界面上点过"这是我 / 这是别人"：按最像的那一方；
 *  - 否则按最近录到的音量自动分成"响的一群"和"轻的一群"，响的是自己；
 *  - 两群分不开（比如一直只有自己在说）时不标。
 */
object Speakers {
    /** 一块（或一轮）说话的特征。 */
    class Voice(val db: Double, val pitch: Double, val low: Double) {
        fun toJson(): JSONArray = JSONArray().put(r1(db)).put(r1(pitch)).put(r3(low))
        companion object {
            fun fromJson(a: JSONArray?): Voice? = if (a == null || a.length() < 3) null
                else Voice(a.optDouble(0), a.optDouble(1), a.optDouble(2))
            private fun r1(x: Double) = Math.round(x * 10) / 10.0
            private fun r3(x: Double) = Math.round(x * 1000) / 1000.0
        }
    }

    /** 一小块说话：从 [from] 到 [to]（10 ms 帧号，不含 to），[voiced] 是有声帧数。 */
    class Chunk(val from: Int, val to: Int, val voiced: Int, val voice: Voice)

    /** 一轮：同一个人连着说的，[from]/[to] 是采样号。 */
    class Turn(val from: Int, val to: Int, val voice: Voice, val voicedMs: Long)

    private const val FRAME = 160            // 10 ms @ 16 kHz
    /** 停顿多长就切一块。 */
    const val GAP_FRAMES = 30
    /** 少于这么多有声帧的块太短，特征不可靠，并到旁边。 */
    const val MIN_CHUNK_VOICED = 15
    /** 一轮至少这么多有声帧（0.8 秒），太短的并到更像的那一边。 */
    const val MIN_TURN_VOICED = 80
    /** 一段录音最多切几轮（每轮要单独转一次文字）。 */
    const val MAX_TURNS = 6

    /** 每 10 ms 的特征。 */
    class Frames(val voiced: BooleanArray, val db: DoubleArray, val pitch: DoubleArray, val low: DoubleArray) {
        val size get() = voiced.size
    }

    fun frames(pcm: ShortArray): Frames {
        val n = pcm.size / FRAME
        val voiced = BooleanArray(n); val db = DoubleArray(n); val pitch = DoubleArray(n); val low = DoubleArray(n)
        val det = SpeechDetector()
        // 低频比例：两级一阶低通（约 400 Hz）后的能量 / 总能量
        val a = exp(-2 * Math.PI * 400.0 / NimoMic.SAMPLE_RATE)
        var l1 = 0.0; var l2 = 0.0; var dc = 0.0
        for (f in 0 until n) {
            var eAll = 0.0; var eLow = 0.0
            for (i in f * FRAME until (f + 1) * FRAME) {
                val x0 = pcm[i].toDouble()
                dc += (x0 - dc) * 0.001
                val x = x0 - dc
                l1 = a * l1 + (1 - a) * x
                l2 = a * l2 + (1 - a) * l1
                eAll += x * x; eLow += l2 * l2
            }
            det.feed(pcm.copyOfRange(f * FRAME, (f + 1) * FRAME))
            voiced[f] = det.voiced
            db[f] = det.levelDb
            pitch[f] = det.pitchHz
            low[f] = if (eAll > 1.0) eLow / eAll else 0.0
        }
        return Frames(voiced, db, pitch, low)
    }

    private fun median(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    fun voiceOf(fr: Frames, from: Int, to: Int): Voice {
        val idx = (from until to).filter { fr.voiced[it] }
        val use = idx.ifEmpty { (from until to).toList() }
        return Voice(median(use.map { fr.db[it] }), median(use.map { fr.pitch[it] }.filter { it > 0 }),
            use.map { fr.low[it] }.average().takeIf { !it.isNaN() } ?: 0.0)
    }

    /** 按停顿切成小块（只看有声帧）。 */
    fun chunks(fr: Frames): List<Chunk> {
        val out = ArrayList<Chunk>()
        var start = -1; var last = -1; var cnt = 0
        fun close() {
            if (start >= 0) out.add(Chunk(start, last + 1, cnt, voiceOf(fr, start, last + 1)))
            start = -1; cnt = 0
        }
        for (f in 0 until fr.size) {
            if (!fr.voiced[f]) continue
            if (start >= 0 && f - last > GAP_FRAMES) close()
            if (start < 0) start = f
            last = f; cnt++
        }
        close()
        // 太短的块并到前一块（没有前一块就并到后一块）
        val merged = ArrayList<Chunk>()
        for (c in out) {
            val p = merged.lastOrNull()
            if (p != null && (c.voiced < MIN_CHUNK_VOICED || p.voiced < MIN_CHUNK_VOICED)) {
                merged[merged.size - 1] = Chunk(p.from, c.to, p.voiced + c.voiced, voiceOf(fr, p.from, c.to))
            } else merged.add(c)
        }
        return merged
    }

    /** 两块声音差多少（大于 1 算换了人）。音量差 7 dB、音高差 30%、低频比例差 0.2 各算 1。 */
    fun distance(a: Voice, b: Voice): Double {
        val dDb = (a.db - b.db) / 7.0
        val dP = if (a.pitch > 0 && b.pitch > 0) ln(a.pitch / b.pitch) / ln(1.3) else 0.0
        val dL = (a.low - b.low) / 0.2
        return sqrt(dDb * dDb + dP * dP * 0.5 + dL * dL * 0.5)
    }

    /**
     * 一段录音切成几轮：相邻的块声音相近就接在一起，差得多（像是换了人）就断开。
     * 轮与轮的分界放在两块中间的停顿里，整段的声音一点不丢。
     */
    fun turns(pcm: ShortArray): List<Turn> = turns(frames(pcm), pcm.size)

    fun turns(fr: Frames, samples: Int): List<Turn> {
        val cs = chunks(fr)
        if (cs.isEmpty()) return listOf(Turn(0, samples, voiceOf(fr, 0, fr.size), 0))
        // 分组：和当前这一组的声音比
        val groups = ArrayList<MutableList<Chunk>>()
        for (c in cs) {
            val g = groups.lastOrNull()
            if (g == null) { groups.add(mutableListOf(c)); continue }
            val gv = voiceOf(fr, g.first().from, g.last().to)
            if (distance(gv, c.voice) > 1.0) groups.add(mutableListOf(c)) else g.add(c)
        }
        // 太短的一轮并到更像的邻居；轮数太多也从最短的开始并
        fun voiced(g: List<Chunk>) = g.sumOf { it.voiced }
        fun gv(g: List<Chunk>) = voiceOf(fr, g.first().from, g.last().to)
        while (groups.size > 1) {
            val i = groups.indices.minByOrNull { voiced(groups[it]) }!!
            if (voiced(groups[i]) >= MIN_TURN_VOICED && groups.size <= MAX_TURNS) break
            val left = if (i > 0) i - 1 else null
            val right = if (i < groups.size - 1) i + 1 else null
            val into = when {
                left == null -> right!!
                right == null -> left
                distance(gv(groups[i]), gv(groups[left])) <= distance(gv(groups[i]), gv(groups[right])) -> left
                else -> right
            }
            if (into < i) { groups[into].addAll(groups[i]) } else { groups[into].addAll(0, groups[i]) }
            groups.removeAt(i)
        }
        // 并过之后相邻两轮可能变得很像了，再接一次
        var k0 = 1
        while (k0 < groups.size) {
            if (Speakers.distance(gv(groups[k0 - 1]), gv(groups[k0])) <= 1.0) { groups[k0 - 1].addAll(groups[k0]); groups.removeAt(k0) }
            else k0++
        }
        val out = ArrayList<Turn>()
        for ((k, g) in groups.withIndex()) {
            val from = if (k == 0) 0 else (groups[k - 1].last().to + g.first().from) / 2 * FRAME
            val to = if (k == groups.size - 1) samples else (g.last().to + groups[k + 1].first().from) / 2 * FRAME
            out.add(Turn(from, to.coerceAtMost(samples), gv(g), voiced(g) * 10L))
        }
        return out
    }

    /**
     * 一维两群分割（Otsu）：返回分界值；两群的中心差不到 [minGapDb] 或少的一群不到 15% 时返回 null。
     */
    fun loudSplit(dbs: List<Double>, minGapDb: Double = 8.0): Double? {
        if (dbs.size < 8) return null
        val s = dbs.sorted()
        var best = -1.0; var bestK = -1
        for (k in 1 until s.size) {
            val a = s.subList(0, k); val b = s.subList(k, s.size)
            val ma = a.average(); val mb = b.average()
            val w = k.toDouble() * (s.size - k) * (mb - ma) * (mb - ma)
            if (w > best) { best = w; bestK = k }
        }
        val a = s.subList(0, bestK); val b = s.subList(bestK, s.size)
        if (a.size < s.size * 0.15 || b.size < s.size * 0.15) return null
        if (b.average() - a.average() < minGapDb) return null
        return (a.last() + b.first()) / 2
    }
}

/**
 * 判断一轮话是不是"我"说的，并记住用户纠正过的例子。存在 speaker.json。
 */
class SpeakerModel(private val file: File?) {
    private val me = ArrayList<Speakers.Voice>()
    private val other = ArrayList<Speakers.Voice>()
    /** 最近录到的每轮音量（自动分两群用）。 */
    private val levels = ArrayList<Double>()

    init { load() }

    val taught get() = me.size + other.size
    val meCount get() = me.size
    val otherCount get() = other.size

    @Synchronized
    fun isMe(v: Speakers.Voice?): Boolean? {
        v ?: return null
        if (me.isNotEmpty() || other.isNotEmpty()) {
            val dm = me.minOfOrNull { Speakers.distance(it, v) }
            val dOther = other.minOfOrNull { Speakers.distance(it, v) }
            return when {
                dm != null && dOther != null -> dm <= dOther
                dm != null -> if (dm <= 1.5) true else if (v.db < me.map { it.db }.average() - 8) false else null
                else -> if (dOther!! <= 1.5) false else if (v.db > other.map { it.db }.average() + 8) true else null
            }
        }
        val split = Speakers.loudSplit(levels) ?: return null
        return v.db >= split
    }

    @Synchronized
    fun observe(v: Speakers.Voice) {
        levels.add(v.db)
        while (levels.size > MAX_LEVELS) levels.removeAt(0)
        save()
    }

    /** 用户说"这一轮是我 / 是别人"。 */
    @Synchronized
    fun teach(v: Speakers.Voice, isMe: Boolean) {
        val (to, from) = if (isMe) me to other else other to me
        from.removeAll { Speakers.distance(it, v) < 0.3 }
        to.add(v)
        while (to.size > MAX_EXAMPLES) to.removeAt(0)
        save()
    }

    @Synchronized
    fun reset() { me.clear(); other.clear(); levels.clear(); save() }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            fun list(k: String, into: MutableList<Speakers.Voice>) = o.optJSONArray(k)?.let { a ->
                for (i in 0 until a.length()) Speakers.Voice.fromJson(a.optJSONArray(i))?.let { into.add(it) }
            }
            list("me", me); list("other", other)
            o.optJSONArray("levels")?.let { a -> for (i in 0 until a.length()) levels.add(a.optDouble(i)) }
        }
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(JSONObject()
                .put("me", JSONArray(me.map { it.toJson() }))
                .put("other", JSONArray(other.map { it.toJson() }))
                .put("levels", JSONArray(levels.map { Math.round(it * 10) / 10.0 })).toString())
        }
    }

    companion object {
        const val MAX_LEVELS = 400
        const val MAX_EXAMPLES = 40
    }
}
