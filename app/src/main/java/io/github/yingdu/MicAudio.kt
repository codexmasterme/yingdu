package io.github.yingdu

import io.github.jaredmdobson.concentus.OpusDecoder
import java.io.OutputStream

/**
 * 眼镜麦克风（2026-09-28 实测）：
 * 音频从属性句柄 0x0003 的特征值通知过来，一条通知：
 *   52 03 [长度 u16] [计数 u16，每条 +9] [单元数 u16，=9]，后面若干单元；
 * 每个单元：[后面的字节数 u8]，后面是 opus_demo 格式的记录：[包长 u32 大端][final range u32 大端][Opus 包]，
 * 一个单元里先是真正的一帧（Opus CELT 宽带 16 kHz、10 ms、单声道），再跟一个 3 字节的小包 b0 ff fe（不用）。
 */
object NimoMic {
    const val SAMPLE_RATE = 16_000
    const val FRAME_SAMPLES = 160          // 10 ms
    val START = byteArrayOf(0x52, 0x01, 0x00, 0x00)
    val STOP = byteArrayOf(0x52, 0x00, 0x00, 0x00)

    class Frame(val packet: ByteArray, val finalRange: Long)

    /** 解析一条音频通知；不是音频数据或格式不对时返回空列表（不抛异常）。 */
    fun parse(n: ByteArray): List<Frame> {
        if (n.size < 8 || n[0] != 0x52.toByte() || n[1] != 0x03.toByte()) return emptyList()
        val count = (n[6].toInt() and 0xFF) or ((n[7].toInt() and 0xFF) shl 8)
        val out = ArrayList<Frame>(count)
        var off = 8
        repeat(count) {
            if (off >= n.size) return out
            val unitLen = n[off].toInt() and 0xFF
            val unitEnd = off + 1 + unitLen
            if (unitEnd > n.size || unitLen < 8) return out
            val len = be32(n, off + 1)
            val range = be32(n, off + 5)
            if (len in 1..(unitEnd - off - 9)) out.add(Frame(n.copyOfRange(off + 9, off + 9 + len.toInt()), range))
            off = unitEnd
        }
        return out
    }

    private fun be32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
            ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)
}

/**
 * 把 Opus 包原样写成 .ogg（Ogg Opus）：全天记忆等着转文字的临时录音，一小时约 7 MB。
 * 每 50 包（0.5 秒）写一页，中途断掉也只丢最后半秒。
 */
class OggOpusWriter(private val out: OutputStream, private val inputRate: Int = NimoMic.SAMPLE_RATE) {
    private val serial = (System.nanoTime() and 0x7FFFFFFF).toInt()
    private var pageSeq = 0
    private var granule = 0L                  // 48 kHz 采样数
    private val packets = ArrayList<ByteArray>()
    private var closed = false

    init {
        val head = ByteArray(19)
        "OpusHead".toByteArray().copyInto(head)
        head[8] = 1; head[9] = 1                                           // 版本 1，单声道
        le32(head, 12, inputRate)                                          // 原始采样率（仅供参考），预跳过和增益为 0
        writePage(listOf(head), 0, flags = 0x02)
        val vendor = "Yingdu".toByteArray()
        val tags = ByteArray(8 + 4 + vendor.size + 4)
        "OpusTags".toByteArray().copyInto(tags)
        le32(tags, 8, vendor.size); vendor.copyInto(tags, 12)
        writePage(listOf(tags), 0, flags = 0)
    }

    /** 加一包（samples48k：这一包在 48 kHz 下的采样数，10 ms = 480）。 */
    fun add(packet: ByteArray, samples48k: Int = 480) {
        if (closed) return
        packets.add(packet); granule += samples48k
        if (packets.size >= 50) flushPage(false)
    }

    fun close() {
        if (closed) return
        flushPage(true)
        closed = true
        out.close()
    }

    private fun flushPage(last: Boolean) {
        if (packets.isEmpty() && !last) return
        writePage(packets.toList(), granule, flags = if (last) 0x04 else 0)
        packets.clear()
        out.flush()
    }

    private fun writePage(pkts: List<ByteArray>, gran: Long, flags: Int) {
        val lacing = ArrayList<Int>()
        for (p in pkts) {
            var n = p.size
            while (n >= 255) { lacing.add(255); n -= 255 }
            lacing.add(n)
        }
        val h = ByteArray(27 + lacing.size)
        "OggS".toByteArray().copyInto(h)
        h[4] = 0; h[5] = flags.toByte()
        for (i in 0 until 8) h[6 + i] = (gran ushr (8 * i)).toByte()
        le32(h, 14, serial); le32(h, 18, pageSeq++)
        h[26] = lacing.size.toByte()
        lacing.forEachIndexed { i, v -> h[27 + i] = v.toByte() }
        var crc = crc(0, h)
        for (p in pkts) crc = crc(crc, p)
        le32(h, 22, crc)
        out.write(h)
        for (p in pkts) out.write(p)
    }

    companion object {
        private val TABLE = IntArray(256) { i ->
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            r
        }
        fun crc(start: Int, b: ByteArray): Int {
            var c = start
            for (x in b) c = (c shl 8) xor TABLE[((c ushr 24) xor (x.toInt() and 0xFF)) and 0xFF]
            return c
        }
        private fun le32(b: ByteArray, i: Int, v: Int) { for (k in 0 until 4) b[i + k] = (v ushr (8 * k)).toByte() }
    }
}

/**
 * 眼镜麦克风的一串音频包：解码（纯 Java 的 Concentus，和 libopus 逐位一致）、算音量、统计异常帧（不存文件）。
 */
class MicSession {
    private val decoder = OpusDecoder(NimoMic.SAMPLE_RATE, 1)
    private val pcm = ShortArray(NimoMic.FRAME_SAMPLES * 6)
    val startedAt = System.currentTimeMillis()
    var frames = 0; private set
    var bytes = 0L; private set
    /** 解码时 final range 和眼镜给的不一致的帧数（正常应该是 0）。 */
    var mismatched = 0; private set
    var decodeErrors = 0; private set
    var lastFrameAt = 0L; private set
    /** 最近约 5 秒、每 100 ms 一格的音量（dBFS，-90～0）。 */
    val levels = ArrayDeque<Float>()
    private var sumSq = 0.0
    private var sumN = 0
    /** 每解码出一段 PCM 调用（以后接语音检测、转文字）。 */
    var onPcm: ((ShortArray, Int) -> Unit)? = null

    fun feed(list: List<NimoMic.Frame>) {
        for (f in list) {
            frames++; bytes += f.packet.size; lastFrameAt = System.currentTimeMillis()
            val n = try {
                decoder.decode(f.packet, 0, f.packet.size, pcm, 0, pcm.size, false)
            } catch (e: Exception) { decodeErrors++; continue }
            if ((decoder.finalRange.toLong() and 0xFFFFFFFFL) != f.finalRange) mismatched++
            for (i in 0 until n) { val v = pcm[i].toDouble(); sumSq += v * v }
            sumN += n
            if (sumN >= NimoMic.SAMPLE_RATE / 10) {
                val rms = Math.sqrt(sumSq / sumN)
                levels.addLast((20 * Math.log10(rms / 32768.0 + 1e-9)).toFloat().coerceIn(-90f, 0f))
                while (levels.size > 50) levels.removeFirst()
                sumSq = 0.0; sumN = 0
            }
            onPcm?.invoke(pcm, n)
        }
    }

    /** 录了多少秒的声音（按收到的帧数算，每帧 10 ms）。 */
    val audioSeconds get() = frames / 100.0
}
