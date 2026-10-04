package io.github.yingdu

import io.github.jaredmdobson.concentus.OpusDecoder
import java.util.concurrent.Executors

/**
 * 实时字幕：把眼镜麦克风听到的话切成一句一句（说话停顿约 0.8 秒算一句说完），
 * 说的过程中每隔一会儿把已经说的部分送去转一次文字（先显示个大概），说完再转一次整句（定稿）。
 * 纯 Kotlin，不依赖 Android，有单元测试。PCM 都是 16 kHz。
 */
class CaptionSegmenter(private val listener: Listener) {
    interface Listener {
        /** 这一句还在说：到目前为止的声音（id 相同的是同一句）。 */
        fun partial(id: Int, pcm: ShortArray)
        /** 这一句说完了。 */
        fun final(id: Int, pcm: ShortArray)
        /** 这一句太短（咳嗽、碰一下），不要了。 */
        fun dropped(id: Int)
    }

    private val vad = SpeechDetector()
    private val pre = ArrayDeque<ShortArray>()
    private var buf: ShortBuilder? = null
    private var id = 0
    private var quietFrames = 0
    private var voicedFrames = 0
    private var framesSincePartial = 0
    private var piece = ShortArray(FRAME)
    private var pieceN = 0

    /** 正在说的那一句的编号（没在说时为 null）。 */
    val current: Int? get() = if (buf != null) id else null

    fun feed(pcm: ShortArray, n: Int = pcm.size) {
        var i = 0
        while (i < n) {
            val k = minOf(FRAME - pieceN, n - i)
            System.arraycopy(pcm, i, piece, pieceN, k)
            pieceN += k; i += k
            if (pieceN == FRAME) { step(piece); piece = ShortArray(FRAME); pieceN = 0 }
        }
    }

    private fun step(p: ShortArray) {
        vad.feed(p)
        val b = buf
        if (b == null) {
            pre.addLast(p); if (pre.size > PREROLL_FRAMES) pre.removeFirst()
            if (vad.speaking) {
                val nb = ShortBuilder()
                pre.forEach { nb.add(it) }; pre.clear()
                buf = nb; id++; quietFrames = 0; voicedFrames = 0; framesSincePartial = 0
            }
            return
        }
        b.add(p)
        if (vad.voiced) voicedFrames++
        quietFrames = if (vad.speaking) 0 else quietFrames + 1
        framesSincePartial++
        val frames = b.size / FRAME
        when {
            quietFrames >= END_QUIET_FRAMES -> finish(keepGoing = false)
            frames >= MAX_FRAMES -> finish(keepGoing = true)
            framesSincePartial >= PARTIAL_FRAMES && voicedFrames >= MIN_VOICED -> {
                framesSincePartial = 0
                listener.partial(id, b.toArray())
            }
        }
    }

    private fun finish(keepGoing: Boolean) {
        val b = buf ?: return
        // 末尾的安静部分只留 0.3 秒
        val trim = if (keepGoing) 0 else maxOf(0, quietFrames - TAIL_FRAMES) * FRAME
        val out = b.toArray(b.size - trim)
        if (voicedFrames >= MIN_VOICED) listener.final(id, out) else listener.dropped(id)
        if (keepGoing) { buf = ShortBuilder(); id++; voicedFrames = 0; framesSincePartial = 0; quietFrames = 0 }
        else buf = null
    }

    /** 停止：正在说的那句当作说完。 */
    fun flush() { if (buf != null) finish(keepGoing = false) }

    private class ShortBuilder {
        var a = ShortArray(FRAME * 200); var size = 0
        fun add(p: ShortArray) {
            if (size + p.size > a.size) a = a.copyOf(maxOf(a.size * 2, size + p.size))
            System.arraycopy(p, 0, a, size, p.size); size += p.size
        }
        fun toArray(n: Int = size) = a.copyOf(n)
    }

    companion object {
        const val FRAME = 160                 // 10 ms
        const val PREROLL_FRAMES = 40         // 开始说话前留 0.4 秒，不丢第一个字
        const val END_QUIET_FRAMES = 50       // 停顿约 0.8 秒（说话判断本身还有 0.3 秒余量）算说完
        const val TAIL_FRAMES = 30
        const val MAX_FRAMES = 1200           // 一句最长 12 秒，再长就先断开
        const val PARTIAL_FRAMES = 120        // 说的过程中每 1.2 秒转一次
        const val MIN_VOICED = 25             // 有声的部分不到 0.25 秒不算一句
    }
}

/**
 * 实时字幕（全屏功能）：打开后一直开着眼镜麦克风，听到的话降噪后用「全天记忆」里设置的转文字服务转成文字，
 * 最新的一句在最下面、最亮。能分出是自己说的会标「我」。
 */
class CaptionsApp(private val host: AppHost, private val env: Env) : GlassesApp {
    interface Env {
        /** 转文字服务：(接口地址, Key, 模型)；没设置 Key 时为 null。 */
        fun asr(): Triple<String, String, String>?
        /** "zh" 或 ""（自动）。 */
        fun language(): String
        fun setMic(on: Boolean)
        fun isMe(v: Speakers.Voice?): Boolean?
        fun denoise(): Boolean
    }

    class Line(val id: Int, var text: String, var final: Boolean, var me: Boolean?, val at: Long)

    override val title = "实时字幕"

    /** 最近的字幕（手机上显示；最多留 300 句）。 */
    val lines = ArrayList<Line>()
    var running = false; private set
    var error = ""; private set
    /** 正在说话（还没说完的那句）。 */
    val hearing get() = seg?.current != null

    private var seg: CaptionSegmenter? = null
    private var decoder: OpusDecoder? = null
    private val pcm = ShortArray(960)
    private val exec = Executors.newFixedThreadPool(2)
    private var busy = false
    private val finals = ArrayDeque<Pair<Int, ShortArray>>()
    private var lastFrameAt = 0L

    /**
     * 这次打开之前最大的句子编号：每次打开分句器都从 1 重新编号，加上它才不会和上一次的句子撞号
     * （撞号时新的文字会写进上一次的旧句子里）；眼镜上也只显示这之后的句子，每次打开都从空白屏开始。
     */
    private var base = 0
    /** 眼镜上只显示编号大于它的句子（暂停后恢复也从空白屏开始；手机上的记录不受影响）。 */
    private var screenFrom = 0
    /** 用过的最大句子编号（清空记录后也不回退）。 */
    private var maxId = 0

    override fun onOpen() {
        running = true; error = ""
        decoder = OpusDecoder(NimoMic.SAMPLE_RATE, 1)
        base = maxId
        screenFrom = base
        busy = false; finals.clear()
        seg = CaptionSegmenter(object : CaptionSegmenter.Listener {
            override fun partial(id0: Int, pcm: ShortArray) {
                val id = base + id0
                if (!busy && finals.isEmpty()) request(id, pcm, final = false)
                else if (line(id) == null) { add(Line(id, "", false, null, System.currentTimeMillis())) }
            }
            override fun final(id0: Int, pcm: ShortArray) {
                val id = base + id0
                if (line(id) == null) add(Line(id, "", false, null, System.currentTimeMillis()))
                finals.addLast(id to pcm); pump()
            }
            override fun dropped(id0: Int) { line(base + id0)?.let { if (it.text.isEmpty()) { lines.remove(it); show() } } }
        })
        if (env.asr() == null) error = "先在「全天记忆 › 设置」里填转文字的 API Key"
        env.setMic(true)
        lastFrameAt = System.currentTimeMillis()
        host.main.removeCallbacks(watch); host.main.postDelayed(watch, 5_000)
        show()
    }

    override fun onClose() {
        running = false
        seg?.flush(); seg = null
        env.setMic(false)
        host.main.removeCallbacks(watch)
        host.changed()
    }

    /** 几秒没收到声音：再开一次麦克风（蓝牙偶尔丢了开麦的命令）。 */
    private val watch: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            if (System.currentTimeMillis() - lastFrameAt > 5_000) env.setMic(true)
            host.main.postDelayed(this, 5_000)
        }
    }

    /** 眼镜上暂停后又恢复显示：从空白屏开始。 */
    override fun onResume() { screenFrom = maxId; show() }

    /** 清空：眼镜上从空白屏开始，手机上的记录也清掉。 */
    fun clear() { screenFrom = maxId; lines.clear(); show() }

    /** 眼镜麦克风的声音（主线程）。 */
    fun onFrames(frames: List<NimoMic.Frame>) {
        val s = seg ?: return
        val d = decoder ?: return
        lastFrameAt = System.currentTimeMillis()
        val wasHearing = s.current != null
        for (f in frames) {
            val n = try { d.decode(f.packet, 0, f.packet.size, pcm, 0, pcm.size, false) } catch (e: Exception) { 0 }
            if (n > 0) s.feed(pcm, n)
        }
        if (wasHearing != (s.current != null)) show()
    }

    private fun line(id: Int) = lines.lastOrNull { it.id == id }

    private fun add(l: Line) {
        lines.add(l)
        if (l.id > maxId) maxId = l.id
        while (lines.size > MAX_LINES) lines.removeAt(0)
    }

    private fun pump() {
        if (busy) return
        val (id, p) = finals.removeFirstOrNull() ?: return
        request(id, p, final = true)
    }

    private fun request(id: Int, p: ShortArray, final: Boolean) {
        val cfg = env.asr() ?: run { error = "先在「全天记忆 › 设置」里填转文字的 API Key"; finals.clear(); show(); return }
        busy = true
        val lang = env.language(); val denoise = env.denoise()
        fun asr(x: ShortArray): String {
            val clean = AudioClean.process(x, if (denoise) AudioClean.Mode.WIENER else AudioClean.Mode.NONE, floor = 0.2f, boost = true)
            return SpeechToText.transcribe(cfg.first, cfg.second, cfg.third, lang, "caption.wav", "audio/wav", OggOpus.wav(clean)).trim()
        }
        exec.execute {
            val r = runCatching {
                val voice = if (final) Speakers.voiceOf(Speakers.frames(p), 0, p.size / 160) else null
                asr(p) to voice
            }
            host.main.post {
                busy = false
                if (!running) return@post
                r.onSuccess { (text, voice) ->
                    error = ""
                    val l = line(id) ?: Line(id, "", false, null, System.currentTimeMillis()).also { add(it) }
                    if (!l.final) {
                        if (text.isNotEmpty() || final) l.text = text
                        if (final) { l.final = true; l.me = env.isMe(voice) }
                    }
                    if (final && l.text.isEmpty()) lines.remove(l)
                }.onFailure { error = "转文字出错：${it.message ?: it}" ; host.log("实时字幕：$error") }
                show()
                pump()
            }
        }
    }

    private fun show() { host.redraw(this); host.changed() }

    override fun status() = if (hearing) "$title · 在听" else title

    override fun onInput(input: GlassesInput) {
        if (input == GlassesInput.DOUBLE_RIGHT || input == GlassesInput.DOUBLE_LEFT) clear()
    }

    override fun render(fontPx: Int): ByteArray = draw(fontPx).done()

    fun draw(fontPx: Int): Frame {
        val f = Frame(fontPx)
        val top = f.header(title, when {
            error.isNotEmpty() -> "出错"
            hearing -> "● 在听"
            busy -> "转写中"
            else -> "等人说话"
        })
        val lineH = f.font.height + 6
        val maxW = f.w - 12
        // 从最新的一句往上排，排满为止
        val rows = ArrayList<Pair<String, Int>>()
        val shown = lines.filter { it.id > screenFrom && it.text.isNotEmpty() }
        val msg = if (error.isNotEmpty()) error else if (shown.isEmpty()) "说话后字幕会显示在这里" else null
        if (msg != null) f.font.wrap(msg, maxW).asReversed().forEach { rows.add(it to Frame.DIM) }
        for ((k, l) in shown.withIndex().reversed()) {
            val newest = k == shown.size - 1
            val prefix = when (l.me) { true -> "我："; false -> ""; null -> "" }
            val color = if (newest && msg == null) Frame.FULL else Frame.DIM
            for (w in f.font.wrap(prefix + l.text + if (!l.final) "…" else "", maxW).asReversed()) rows.add(w to color)
            if (rows.size * lineH > f.h - top) break
        }
        // 放得下时从最上面一行往下排；排满了以后最新的一行贴着底，旧的往上滚出去
        val room = (f.h - 4 - top) / lineH
        if (rows.size <= room) {
            var y = top + 2
            for ((t, c) in rows.asReversed()) { f.text(t, 6, y, c); y += lineH }
        } else {
            var y = f.h - 4 - lineH
            for ((t, c) in rows) {
                if (y < top) break
                f.text(t, 6, y, c)
                y -= lineH
            }
        }
        return f
    }

    companion object {
        const val MAX_LINES = 300
    }
}
