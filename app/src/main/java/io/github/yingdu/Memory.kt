package io.github.yingdu

import io.github.jaredmdobson.concentus.OpusDecoder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全天记忆：只用眼镜麦克风、只在有人说话时录音。每一段先临时存成 .ogg 等着转文字，
 * 转好文字就删掉，手机上只留文字；之后每晚总结。
 * 两种"怎么知道有人在说话"的方式（设置里可以切换）：
 *  - ALWAYS 眼镜麦克风一直开，手机判断哪些是说话（最耗眼镜电）；
 *  - DUTY 间歇开麦：每隔一会儿开眼镜麦克风听 2.5 秒，有人说话就一直录到安静下来。
 */
enum class ListenMode(val zh: String, val desc: String) {
    ALWAYS("一直开", "眼镜麦克风一直开着（最耗眼镜电）"),
    DUTY("间歇开麦", "每隔一会儿开眼镜麦克风听一下，有人说话才一直录"),
}

/** 录好的一段说话。 */
class MemorySegment(val file: File, val startWall: Long, val durationMs: Long)

/**
 * 录音引擎（全部在主线程上跑）。眼镜的音频帧从外面送进来；
 * 开关眼镜麦克风、存文件通过 Host 交给服务。
 */
class MemoryEngine(private val host: Host) {
    interface Host {
        fun post(delayMs: Long, r: Runnable)
        fun cancel(r: Runnable)
        fun uptime(): Long
        fun wallTime(): Long
        fun glassesReady(): Boolean
        fun setGlassesMic(on: Boolean)
        fun log(msg: String)
        fun segmentSaved(seg: MemorySegment)
        fun segmentDir(dayStart: Long): File
    }

    var running = false; private set
    var mode = ListenMode.DUTY; private set
    /** 间歇开麦：每隔多少秒听一次。 */
    var dutyIntervalMs = 10_000L

    // ---- 状态（界面上显示） ----
    var recording = false; private set
    var glassesMicOn = false; private set
    /** 间歇开麦时正在试听。 */
    var probing = false; private set
    val stats = Stats()

    class Stats {
        var startedWall = 0L
        var startedUp = 0L
        var glassesMicMs = 0L
        var segments = 0
        var dropped = 0
        var speechMs = 0L
        var glassesBatteryStart = -1
        var phoneBatteryStart = -1
    }

    private var micOnSince = 0L
    private var lastGlassesFrameAt = 0L
    private var lastSpeechAt = 0L
    private var probeStart = 0L
    private var nextProbeAt = 0L
    private var micRetryAt = 0L

    private var decoder = OpusDecoder(NimoMic.SAMPLE_RATE, 1)
    private val pcm = ShortArray(960)
    private val glassesVad = SpeechDetector()

    /** 没在录时，最近 0.6 秒的眼镜音频包（开始录时接在前面，不丢第一个字）。 */
    private val glassesRing = ArrayDeque<ByteArray>()

    private var seg: Builder? = null

    private inner class Builder(val startWall: Long, val startUp: Long) {
        val packets = ArrayList<ByteArray>()
        var speechFrames = 0
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            host.post(TICK_MS, this)
        }
    }

    fun start(mode: ListenMode, glassesBattery: Int, phoneBattery: Int) {
        if (running) stop()
        this.mode = mode
        running = true
        stats.apply {
            startedWall = host.wallTime(); startedUp = host.uptime(); glassesMicMs = 0; segments = 0; dropped = 0; speechMs = 0
            glassesBatteryStart = glassesBattery; phoneBatteryStart = phoneBattery
        }
        nextProbeAt = 0; lastSpeechAt = 0
        host.log("全天记忆：开始（${mode.zh}）")
        host.post(0, tick)
    }

    fun stop() {
        if (!running) return
        endSegment()
        setMic(false)
        running = false; probing = false
        host.cancel(tick)
        host.log("全天记忆：停止")
    }

    /** 眼镜断开：麦克风当成关了，连上后在 step 里重新打开。 */
    fun onGlassesLost() {
        if (glassesMicOn) { stats.glassesMicMs += host.uptime() - micOnSince; glassesMicOn = false }
        probing = false
        if (recording) endSegment()
    }

    fun onGlassesFrames(frames: List<NimoMic.Frame>) {
        if (!running) return
        val now = host.uptime()
        lastGlassesFrameAt = now
        for (f in frames) {
            val n = try { decoder.decode(f.packet, 0, f.packet.size, pcm, 0, pcm.size, false) } catch (e: Exception) { 0 }
            if (n > 0) glassesVad.feed(pcm, n) { if (it.speaking) { lastSpeechAt = now; seg?.let { s -> s.speechFrames++ } } }
            val s = seg
            if (s != null) s.packets.add(f.packet)
            else {
                glassesRing.addLast(f.packet)
                while (glassesRing.size > GLASSES_PREROLL) glassesRing.removeFirst()
            }
        }
        if (seg == null && glassesVad.speaking) startSegment()
    }

    private fun startSegment() {
        val now = host.uptime()
        val preMs = glassesRing.size * 10L
        val b = Builder(host.wallTime() - preMs, now - preMs)
        b.packets.addAll(glassesRing)
        glassesRing.clear()
        seg = b
        recording = true
        probing = false
        lastSpeechAt = now
        setMic(true)
    }

    private fun endSegment() {
        val b = seg ?: return
        seg = null
        recording = false
        val durMs = b.packets.size * 10L
        if (b.speechFrames < MIN_SPEECH_FRAMES || b.packets.isEmpty()) { stats.dropped++; return }
        stats.segments++
        stats.speechMs += durMs
        val dir = host.segmentDir(b.startWall)
        val name = SimpleDateFormat("HHmmss", Locale.US).format(Date(b.startWall)) + "-$durMs.ogg"
        val file = File(dir, name)
        try {
            dir.mkdirs()
            val w = OggOpusWriter(FileOutputStream(file))
            b.packets.forEach { w.add(it) }
            w.close()
            host.segmentSaved(MemorySegment(file, b.startWall, durMs))
        } catch (e: Exception) {
            host.log("全天记忆：存录音失败 ${e.message}")
        }
    }

    private fun setMic(on: Boolean) {
        if (on == glassesMicOn) return
        val now = host.uptime()
        if (on) {
            if (!host.glassesReady()) return
            micOnSince = now; micRetryAt = now + MIC_RETRY_MS
            glassesVad.let { } // 眼镜的检测器继续用，底噪会自己更新
        } else stats.glassesMicMs += now - micOnSince
        glassesMicOn = on
        host.setGlassesMic(on)
    }

    /** 每 0.5 秒：结束安静下来的一段、按模式开关眼镜麦克风。 */
    private fun step() {
        val now = host.uptime()
        if (!host.glassesReady() && glassesMicOn) onGlassesLost()
        val s = seg
        if (s != null) {
            if (now - lastSpeechAt > END_SILENCE_MS) endSegment()
            else if (now - s.startUp > MAX_SEGMENT_MS) { endSegment(); startSegment() }
        }
        when (mode) {
            ListenMode.ALWAYS -> if (!glassesMicOn) setMic(true)
            ListenMode.DUTY -> when {
                recording -> {}
                probing -> if (now - probeStart > PROBE_MS && !glassesVad.speaking) {
                    probing = false; setMic(false); nextProbeAt = now + dutyIntervalMs
                }
                glassesMicOn -> { setMic(false); nextProbeAt = now + dutyIntervalMs }   // 刚录完一段
                now >= nextProbeAt && host.glassesReady() -> { probing = true; probeStart = now; setMic(true) }
            }
        }
        // 开着麦克风却一直收不到声音：再发一次开麦（眼镜偶尔没接到）
        if (glassesMicOn && now > micRetryAt && now - lastGlassesFrameAt > MIC_RETRY_MS) {
            micRetryAt = now + MIC_RETRY_MS
            host.log("全天记忆：眼镜麦克风没声音，重新打开")
            host.setGlassesMic(false); host.setGlassesMic(true)
        }
    }

    /** 眼镜麦克风开着的时间（毫秒，含当前这次）。 */
    fun glassesMicMs(): Long = stats.glassesMicMs + if (glassesMicOn) host.uptime() - micOnSince else 0

    companion object {
        const val TICK_MS = 500L
        const val END_SILENCE_MS = 3_000L       // 安静 3 秒算这段说完
        const val MAX_SEGMENT_MS = 120_000L     // 一段最长 2 分钟（转文字服务一般有上限）
        const val PROBE_MS = 2_500L             // 间歇开麦每次听 2.5 秒（前 0.5 秒眼镜还没来声音）
        const val MIC_RETRY_MS = 6_000L
        const val MIN_SPEECH_FRAMES = 60        // 至少 0.6 秒像说话，才留下
        const val GLASSES_PREROLL = 60          // 0.6 秒
    }
}

// =====================================================================
// 存储：每天一个文件夹：等着转文字的临时录音 HHmmss-时长.ogg（转好就删）、transcript.jsonl（每行一段文字）、summary.json
// =====================================================================

class MemoryEntry(val start: Long, val durationMs: Long, val file: String, val text: String,
                  /** 分了说话人时，每一轮的文字和声音特征（旧的记录没有）。 */
                  val turns: List<MemoryTurn> = emptyList()) {
    /** 按说话人分好的几句；连着同一个人说的接在一起。没分过的整段算一句、不知道是谁。 */
    fun lines(isMe: (Speakers.Voice?) -> Boolean?): List<SpeakerLine> {
        if (turns.isEmpty()) return if (text.isBlank()) emptyList() else listOf(SpeakerLine(null, text.trim(), emptyList()))
        return SpeakerLine.merge(turns.filter { it.text.isNotBlank() }.map { t ->
            SpeakerLine(isMe(t.voice), t.text.trim(), listOfNotNull(t.voice))
        })
    }
}

/** 一个人连着说的几句：[me] true 是自己、false 是别人、null 分不出；[voices] 是这几轮的声音特征（用户纠正时用）。 */
class SpeakerLine(val me: Boolean?, val text: String, val voices: List<Speakers.Voice>) {
    companion object {
        fun merge(ls: List<SpeakerLine>): List<SpeakerLine> {
            val out = ArrayList<SpeakerLine>()
            for (l in ls) {
                val p = out.lastOrNull()
                if (p != null && p.me == l.me) out[out.size - 1] = SpeakerLine(p.me, MemoryGroup.join(p.text, l.text), p.voices + l.voices)
                else out.add(l)
            }
            return out
        }
    }
}

/** 一段录音里一个人连着说的一轮。[offsetMs] 是从这段录音开头算起的时间。 */
class MemoryTurn(val offsetMs: Long, val text: String, val voice: Speakers.Voice?)

/**
 * 一段对话：前后两句之间隔得很短（不超过 gapMs）的几段录音合在一起显示。
 * 录音本身还是按"安静 3 秒"切开存（转文字、删除都按段），只是显示时合并。
 */
class MemoryGroup(val entries: List<MemoryEntry>) {
    val start get() = entries.first().start
    val end get() = entries.last().let { it.start + it.durationMs }
    /** 真正说话的总时长（不含中间的停顿）。 */
    val speechMs get() = entries.sumOf { it.durationMs }
    /** 几句接起来：前一句末尾没有标点就补一个句号。 */
    val text: String get() = entries.fold("") { acc, e -> join(acc, e.text.trim()) }

    /** 按说话人分好的几行（相邻同一个人的接在一起）。 */
    fun lines(isMe: (Speakers.Voice?) -> Boolean?): List<SpeakerLine> = SpeakerLine.merge(entries.flatMap { it.lines(isMe) })

    companion object {
        fun join(a: String, b: String): String = when {
            b.isEmpty() -> a
            a.isEmpty() -> b
            a.last() !in PUNCT -> "$a。$b"
            else -> a + b
        }

        private const val PUNCT = "。！？，、；：….!?,;:~）)」”\""
        /** 按时间先后分组；gapMs <= 0 不合并（一段一组）。只看有文字的段。 */
        fun group(entries: List<MemoryEntry>, gapMs: Long): List<MemoryGroup> {
            val out = ArrayList<MemoryGroup>()
            var cur = ArrayList<MemoryEntry>()
            for (e in entries.filter { it.text.isNotBlank() }.sortedBy { it.start }) {
                val prev = cur.lastOrNull()
                if (prev != null && (gapMs <= 0 || e.start - (prev.start + prev.durationMs) > gapMs)) { out.add(MemoryGroup(cur)); cur = ArrayList() }
                cur.add(e)
            }
            if (cur.isNotEmpty()) out.add(MemoryGroup(cur))
            return out
        }
    }
}

class MemorySummary(
    val day: String, val generatedAt: Long, val overview: String, val topics: List<String>,
    val todos: List<Pair<String, String>>, val notes: List<String>, val model: String,
    /** 一句吐槽风格的标题（日历下面显示），旧的总结没有。 */
    val title: String = "",
    /** 代表这一天心情的一个表情（日历格子里显示），旧的总结没有。 */
    val mood: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("day", day).put("generatedAt", generatedAt).put("overview", overview)
        .put("title", title).put("mood", mood)
        .put("topics", JSONArray(topics)).put("notes", JSONArray(notes)).put("model", model)
        .put("todos", JSONArray(todos.map { JSONObject().put("text", it.first).put("when", it.second) }))

    companion object {
        fun fromJson(o: JSONObject) = MemorySummary(
            o.optString("day"), o.optLong("generatedAt"), o.optString("overview"),
            o.optJSONArray("topics").strings(), o.optJSONArray("todos")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { it.optString("text") to it.optString("when") } }
            } ?: emptyList(),
            o.optJSONArray("notes").strings(), o.optString("model"), o.optString("title"), o.optString("mood"))

        private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
    }
}

class MemoryStore(val base: File) {
    private val dayFmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun dayOf(wall: Long): String = dayFmt.format(Date(wall))
    fun dirOf(day: String) = File(base, day)

    fun days(): List<String> = base.listFiles { f -> f.isDirectory && f.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
        ?.map { it.name }?.sortedDescending() ?: emptyList()

    fun entries(day: String): List<MemoryEntry> {
        val f = File(dirOf(day), "transcript.jsonl")
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { l ->
            runCatching { JSONObject(l) }.getOrNull()?.let { o ->
                val turns = o.optJSONArray("turns")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) }.map {
                        MemoryTurn(it.optLong("o"), it.optString("t"), Speakers.Voice.fromJson(it.optJSONArray("v")))
                    }
                } ?: emptyList()
                MemoryEntry(o.optLong("t"), o.optLong("d"), o.optString("f"), o.optString("text"), turns)
            }
        }.sortedBy { it.start }
    }

    @Synchronized
    fun addEntry(day: String, e: MemoryEntry) {
        val d = dirOf(day); d.mkdirs()
        File(d, "transcript.jsonl").appendText(
            JSONObject().put("t", e.start).put("d", e.durationMs).put("f", e.file).put("text", e.text).apply {
                if (e.turns.isNotEmpty()) put("turns", JSONArray(e.turns.map { t ->
                    JSONObject().put("o", t.offsetMs).put("t", t.text).apply { t.voice?.let { put("v", it.toJson()) } }
                }))
            }.toString() + "\n")
    }

    /** 还没转文字的录音（最早的在前）。 */
    fun pending(): List<File> = days().sorted().flatMap { day ->
        val done = entries(day).map { it.file }.toSet()
        dirOf(day).listFiles { f -> f.name.endsWith(".ogg") && f.name !in done }?.sortedBy { it.name } ?: emptyList()
    }

    /** 录音的开始时间和时长（从文件夹名和文件名来）。 */
    fun segmentInfo(f: File): Pair<Long, Long> {
        val day = f.parentFile?.name ?: ""
        val (hms, dur) = f.nameWithoutExtension.split('-').let { it[0] to (it.getOrNull(1)?.toLongOrNull() ?: 0L) }
        val start = runCatching { SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).parse("$day $hms")!!.time }.getOrDefault(f.lastModified())
        return start to dur
    }

    fun summary(day: String): MemorySummary? =
        File(dirOf(day), "summary.json").takeIf { it.exists() }?.let { runCatching { MemorySummary.fromJson(JSONObject(it.readText())) }.getOrNull() }

    fun saveSummary(s: MemorySummary) { dirOf(s.day).mkdirs(); File(dirOf(s.day), "summary.json").writeText(s.toJson().toString(1)) }

    /**
     * 清理录音：转好文字的全部删掉（正常情况下转完马上就删了，这里兜底）；
     * 一直没转成的（比如没填转文字的 Key）超过 maxPendingMs 也删掉，不在手机上长期留录音。返回删掉的个数。
     */
    fun purgeAudio(now: Long, maxPendingMs: Long): Int {
        var n = 0
        for (day in days()) {
            val done = entries(day).map { it.file }.toSet()
            dirOf(day).listFiles { f -> f.name.endsWith(".ogg") }?.forEach {
                val stale = it.name !in done && now - segmentInfo(it).first > maxPendingMs
                if ((it.name in done || stale) && it.delete()) n++
            }
        }
        return n
    }
}

// =====================================================================
// Ogg Opus → WAV（上传转文字用）
// =====================================================================

object OggOpus {
    /** 读出 Ogg 里的所有 Opus 包（跳过头两个包 OpusHead / OpusTags）。 */
    fun packets(b: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val cur = java.io.ByteArrayOutputStream()
        var off = 0
        var index = 0
        while (off + 27 <= b.size && String(b, off, 4, Charsets.US_ASCII) == "OggS") {
            val segs = b[off + 26].toInt() and 0xFF
            var p = off + 27 + segs
            for (i in 0 until segs) {
                val len = b[off + 27 + i].toInt() and 0xFF
                cur.write(b, p, len); p += len
                if (len < 255) { if (index++ >= 2) out.add(cur.toByteArray()); cur.reset() }
            }
            off = p
        }
        return out
    }

    /** 解码成 16 kHz 单声道 PCM。 */
    fun toPcm(ogg: ByteArray): ShortArray {
        val dec = OpusDecoder(NimoMic.SAMPLE_RATE, 1)
        val buf = ShortArray(960)
        var out = ShortArray(NimoMic.SAMPLE_RATE * 10)
        var len = 0
        for (p in packets(ogg)) {
            val n = try { dec.decode(p, 0, p.size, buf, 0, buf.size, false) } catch (e: Exception) { 0 }
            if (len + n > out.size) out = out.copyOf(maxOf(out.size * 2, len + n))
            System.arraycopy(buf, 0, out, len, n); len += n
        }
        return out.copyOf(len)
    }

    /**
     * 解码成 16 kHz 单声道 WAV（转文字用）：按 mode 降噪（转文字用便宜的维纳滤波，增益下限 0.2，压太狠会伤识别），
     * 再把太小声的录音拉到正常音量（[AudioClean]）。
     */
    fun toWav(ogg: ByteArray, mode: AudioClean.Mode = AudioClean.Mode.NONE, boost: Boolean = false): ByteArray {
        var pcm = toPcm(ogg)
        if (mode != AudioClean.Mode.NONE || boost) pcm = AudioClean.process(pcm, mode, floor = 0.2f, boost = boost)
        return wav(pcm)
    }

    /** 16 kHz 单声道 PCM 包成 WAV。 */
    fun wav(pcm: ShortArray): ByteArray {
        val data = ByteArray(pcm.size * 2)
        for (i in pcm.indices) { data[2 * i] = (pcm[i].toInt() and 0xFF).toByte(); data[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(NimoMic.SAMPLE_RATE).putInt(NimoMic.SAMPLE_RATE * 2)
            .putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.size)
        return h.array() + data
    }
}

// =====================================================================
// 记忆检索：在所有天的转写文字和每天的回顾里找（纯逻辑，有单元测试）
// =====================================================================

object MemorySearch {
    /** 一条结果：一段对话（group 不为空）或当天回顾里的一条（group 为空）。ranges 是 text 里要高亮的位置。 */
    class Hit(val day: String, val group: MemoryGroup?, val label: String, val text: String, val score: Double, val ranges: List<IntRange>) {
        val time: Long get() = group?.start ?: 0L
    }

    class Result(val hits: List<Hit>, val fuzzy: Boolean)

    /** 空格分开的几个词，都要出现（不分大小写）。 */
    fun terms(q: String): List<String> = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** 所有词都出现时返回每个出现位置；有一个词没出现返回 null。 */
    fun exactRanges(text: String, terms: List<String>): List<IntRange>? {
        if (terms.isEmpty()) return null
        val t = text.lowercase()
        val out = ArrayList<IntRange>()
        for (w in terms) {
            var i = t.indexOf(w)
            if (i < 0) return null
            while (i >= 0) { out.add(i until i + w.length); i = t.indexOf(w, i + w.length) }
        }
        return merge(out)
    }

    /** 只留字母、数字、汉字，转小写；返回 (规整后的字符串, 每个字符在原文里的位置)。 */
    private fun norm(s: String): Pair<String, IntArray> {
        val sb = StringBuilder(); val pos = ArrayList<Int>()
        s.forEachIndexed { i, c -> if (c.isLetterOrDigit()) { sb.append(c.lowercaseChar()); pos.add(i) } }
        return sb.toString() to pos.toIntArray()
    }

    /** 问句里的虚词、疑问词、代词：模糊匹配时去掉（不然"我跟张三约在哪见面"里一半的字都对不上）。长的放前面。 */
    private val STOP = listOf("什么时候", "是不是", "有没有", "为什么", "怎么样", "什么", "怎么", "哪里", "哪儿", "多少", "没有",
        "那个", "这个", "一下", "我们", "你们", "他们", "她们", "上次", "之前", "最近", "那天", "今天", "昨天", "前天",
        "我", "你", "他", "她", "它", "的", "了", "吗", "呢", "吧", "啊", "呀", "跟", "和", "与", "在", "是", "哪", "谁", "几",
        "有", "约", "说", "过", "去", "要", "会", "还", "就", "都", "也", "把", "被", "给", "对", "从", "到")

    /**
     * 问题里的关键两字组：去掉虚词后剩下的几段，每段里相邻两个字一组（不跨段）；一段只有一个字就用这个字。
     * 中文问句没有空格，用它来模糊匹配。
     */
    fun bigrams(s: String): Set<String> {
        var n = norm(s).first
        for (w in STOP) n = n.replace(w, " ")
        val out = LinkedHashSet<String>()
        for (seg in n.split(' ').filter { it.isNotEmpty() }) {
            if (seg.length == 1) out.add(seg) else for (i in 0 until seg.length - 1) out.add(seg.substring(i, i + 2))
        }
        return out
    }

    /** 问题的两字组有多大比例出现在文字里（0～1），以及出现的位置。 */
    fun fuzzy(text: String, q: Set<String>): Pair<Double, List<IntRange>> {
        if (q.isEmpty()) return 0.0 to emptyList()
        val (n, pos) = norm(text)
        var found = 0
        val ranges = ArrayList<IntRange>()
        for (g in q) {
            var i = n.indexOf(g)
            if (i >= 0) found++
            while (i >= 0) { ranges.add(pos[i]..pos[i + g.length - 1]); i = n.indexOf(g, i + 1) }
        }
        return found.toDouble() / q.size to merge(ranges)
    }

    private fun merge(rs: List<IntRange>): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (r in rs.sortedBy { it.first }) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + 1) out[out.size - 1] = last.first..maxOf(last.last, r.last) else out.add(r)
        }
        return out
    }

    /** 当天回顾拆成一条一条（概要、每个话题、待办、值得记住的）。 */
    fun summaryLines(s: MemorySummary): List<Pair<String, String>> =
        listOf("当天概要" to s.overview).filter { it.second.isNotBlank() } +
            s.topics.map { "聊了什么" to it } +
            s.todos.map { "要做的事" to (it.first + if (it.second.isNotBlank()) "（${it.second}）" else "") } +
            s.notes.map { "值得记住" to it }

    /**
     * 搜：先按"所有词都出现"找，新的在前；一条都没有、又是一句话（像问句）时，退回模糊匹配，按相似度排。
     * @param days 从新到旧
     */
    fun search(days: List<String>, entriesOf: (String) -> List<MemoryEntry>, summaryOf: (String) -> MemorySummary?,
               query: String, gapMs: Long, limit: Int = 100): Result {
        val ts = terms(query)
        if (ts.isEmpty()) return Result(emptyList(), false)
        class Cand(val day: String, val group: MemoryGroup?, val label: String, val text: String)
        val cands = ArrayList<Cand>()
        for (d in days) {
            summaryOf(d)?.let { s -> summaryLines(s).forEach { (l, t) -> cands.add(Cand(d, null, l, t)) } }
            MemoryGroup.group(entriesOf(d), gapMs).sortedByDescending { it.start }.forEach { g -> cands.add(Cand(d, g, "", g.text)) }
        }
        val exact = cands.mapNotNull { c -> exactRanges(c.text, ts)?.let { Hit(c.day, c.group, c.label, c.text, 1.0, it) } }
        if (exact.isNotEmpty()) return Result(exact.take(limit), false)
        val q = bigrams(query)
        if (q.isEmpty() || (ts.size == 1 && ts[0].length <= 2)) return Result(emptyList(), false)
        val fz = cands.mapNotNull { c ->
            val (score, ranges) = fuzzy(c.text, q)
            if (score >= 0.5 && ranges.isNotEmpty()) Hit(c.day, c.group, c.label, c.text, score, ranges) else null
        }.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.day }.thenByDescending { it.time })
        return Result(fz.take(limit), true)
    }

    /** 截一段显示：从第一个命中前 before 个字开始，最多 total 个字；返回截好的文字和平移后的高亮位置。 */
    fun snippet(text: String, ranges: List<IntRange>, before: Int = 30, total: Int = 120): Pair<String, List<IntRange>> {
        val first = ranges.firstOrNull()?.first ?: 0
        val start = (first - before).coerceAtLeast(0)
        val end = (start + total).coerceAtMost(text.length)
        val pre = if (start > 0) "…" else ""
        val out = pre + text.substring(start, end) + if (end < text.length) "…" else ""
        val shift = pre.length - start
        val rs = ranges.mapNotNull { r ->
            val a = maxOf(r.first, start); val b = minOf(r.last, end - 1)
            if (a > b) null else (a + shift)..(b + shift)
        }
        return out to rs
    }

    /**
     * 问 AI 用的材料：最近 maxDays 天的回顾（精简成一行一天），再加上和问题最像的对话原文，总长不超过 maxChars。
     */
    fun askContext(days: List<String>, entriesOf: (String) -> List<MemoryEntry>, summaryOf: (String) -> MemorySummary?,
                   question: String, gapMs: Long, maxDays: Int = 60, maxChars: Int = 40_000): String {
        val md = SimpleDateFormat("M月d日 HH:mm", Locale.US)
        val sb = StringBuilder()
        val sums = days.take(maxDays).mapNotNull { d -> summaryOf(d)?.let { s ->
            "【$d】" + s.overview + (if (s.topics.isNotEmpty()) " 话题：" + s.topics.joinToString("；") else "") +
                (if (s.todos.isNotEmpty()) " 待办：" + s.todos.joinToString("；") { it.first + if (it.second.isNotBlank()) "（${it.second}）" else "" } else "") +
                (if (s.notes.isNotEmpty()) " 记住：" + s.notes.joinToString("；") else "")
        } }
        if (sums.isNotEmpty()) {
            sb.append("# 每天的回顾\n")
            for (l in sums) { if (sb.length + l.length > maxChars / 2) break; sb.append(l).append('\n') }
        }
        val q = bigrams(question)
        val ts = terms(question)
        val scored = days.flatMap { d -> MemoryGroup.group(entriesOf(d), gapMs).map { g ->
            val s = if (exactRanges(g.text, ts) != null) 1.0 else fuzzy(g.text, q).first
            Triple(d, g, s)
        } }.filter { it.third >= 0.25 }.sortedByDescending { it.third }
        if (scored.isNotEmpty()) {
            sb.append("\n# 和问题最相关的原话（转写，可能有错字）\n")
            for ((_, g, _) in scored) {
                val line = "[${md.format(Date(g.start))}] ${g.text}"
                if (sb.length + line.length > maxChars) break
                sb.append(line).append('\n')
            }
        }
        return sb.toString().trim()
    }
}

/** 全天记忆页上面的日历：算一周 / 一个月要显示哪些天（周一开头）。日期都是 yyyy-MM-dd。 */
object MemoryCalendar {
    private fun fmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private fun cal(day: String) = java.util.Calendar.getInstance().apply {
        time = fmt().parse(day)!!; firstDayOfWeek = java.util.Calendar.MONDAY
        set(java.util.Calendar.HOUR_OF_DAY, 12)
    }
    private fun java.util.Calendar.day() = fmt().format(time)
    /** 往前退到周一。 */
    private fun java.util.Calendar.toMonday() {
        val back = (get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
        add(java.util.Calendar.DAY_OF_MONTH, -back)
    }

    /** 要显示的格子：周视图 7 天；月视图从 1 号所在那周的周一到月底所在那周的周日（5 或 6 行）。 */
    fun cells(anchor: String, month: Boolean): List<String> {
        val c = cal(anchor)
        if (!month) { c.toMonday(); return List(7) { c.day().also { c.add(java.util.Calendar.DAY_OF_MONTH, 1) } } }
        c.set(java.util.Calendar.DAY_OF_MONTH, 1)
        val m = c.get(java.util.Calendar.MONTH)
        c.toMonday()
        val out = ArrayList<String>()
        while (true) {
            repeat(7) { out.add(c.day()); c.add(java.util.Calendar.DAY_OF_MONTH, 1) }
            if (c.get(java.util.Calendar.MONTH) != m) break
        }
        return out
    }

    /** 前一周 / 后一周（月视图：前一个月 / 后一个月的 1 号）。 */
    fun shift(anchor: String, month: Boolean, dir: Int): String {
        val c = cal(anchor)
        if (month) { c.set(java.util.Calendar.DAY_OF_MONTH, 1); c.add(java.util.Calendar.MONTH, dir) }
        else c.add(java.util.Calendar.DAY_OF_MONTH, 7 * dir)
        return c.day()
    }

    /** 标题：2026年9月。 */
    fun title(anchor: String): String = cal(anchor).let { "${it.get(java.util.Calendar.YEAR)}年${it.get(java.util.Calendar.MONTH) + 1}月" }

    fun dayOfMonth(day: String): Int = day.substring(8).toInt()
    fun sameMonth(a: String, b: String) = a.substring(0, 7) == b.substring(0, 7)
}
