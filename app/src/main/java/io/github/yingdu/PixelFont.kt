package io.github.yingdu

import java.nio.ByteBuffer

/**
 * 点阵字体（.ydpf，由 tools/make_pixel_fonts.py 生成）。
 * 眼镜画面逐像素画点阵，不经过系统的字体渲染，笔画不会因为缩放、抗锯齿发糊变粗。
 * 这里只用纯 Kotlin，方便单元测试。
 */
class PixelFont private constructor(
    /** 字高（像素），也是一行的高度。 */
    val size: Int,
    /** 基线位置（从顶部算）。 */
    val ascent: Int,
    private val rowBytes: Int,
    private val codes: CharArray,
    private val advances: ByteArray,
    private val bits: ByteArray,
) {
    val glyphCount: Int get() = codes.size

    private fun index(c: Char): Int = codes.binarySearch(c)

    fun has(c: Char): Boolean = index(c) >= 0

    /** 字宽（像素）；没有这个字时返回 -1。 */
    fun advance(c: Char): Int {
        val i = index(c)
        return if (i < 0) -1 else advances[i].toInt() and 0xFF
    }

    /** 第 y 行第 x 列是否点亮。 */
    fun pixel(c: Char, x: Int, y: Int): Boolean {
        val i = index(c)
        if (i < 0 || y !in 0 until size || x !in 0 until (advances[i].toInt() and 0xFF)) return false
        val b = bits[(i * size + y) * rowBytes + (x shr 3)].toInt()
        return (b shr (7 - (x and 7))) and 1 == 1
    }

    companion object {
        fun parse(data: ByteArray): PixelFont {
            val buf = ByteBuffer.wrap(data)
            val magic = ByteArray(4).also { buf.get(it) }
            require(String(magic, Charsets.US_ASCII) == "YDPF") { "不是点阵字体文件" }
            require(buf.get().toInt() == 1) { "点阵字体版本不对" }
            val size = buf.get().toInt() and 0xFF
            val ascent = buf.get().toInt() and 0xFF
            val rowBytes = buf.get().toInt() and 0xFF
            val count = buf.int
            val codes = CharArray(count) { buf.short.toInt().toChar() }
            val advances = ByteArray(count).also { buf.get(it) }
            val bits = ByteArray(count * size * rowBytes).also { buf.get(it) }
            return PixelFont(size, ascent, rowBytes, codes, advances, bits)
        }

        /** 测试用：从字形表直接构造（每个字是若干行 "#" / "." 组成的字符串）。 */
        fun of(size: Int, ascent: Int, glyphs: Map<Char, List<String>>): PixelFont {
            val sorted = glyphs.toSortedMap()
            val codes = sorted.keys.toCharArray()
            val advances = ByteArray(codes.size) { sorted.getValue(codes[it]).maxOf { r -> r.length }.toByte() }
            val bits = ByteArray(codes.size * size * 2)
            sorted.values.forEachIndexed { i, rows ->
                rows.forEachIndexed { y, row ->
                    row.forEachIndexed { x, ch ->
                        if (ch == '#') {
                            val k = (i * size + y) * 2 + (x shr 3)
                            bits[k] = (bits[k].toInt() or (0x80 ushr (x and 7))).toByte()
                        }
                    }
                }
            }
            return PixelFont(size, ascent, 2, codes, advances, bits)
        }
    }
}

/**
 * 用点阵字体排一行字，字高可以是任意像素数（height）。
 * 按最近邻缩放：每个点只有亮和不亮，边缘干净不发糊；不是整数倍时，个别笔画会是 2 像素宽。
 * 只放大、不缩小（见 GlassesFonts.textFor），这样不会丢笔画。
 * 主字体里没有的字用后备字体，同样缩放到这个字高。
 */
/** 不能放在行首的标点。 */
private const val NO_LINE_START = "，。、；：！？）」』”’》〉】…,.;:!?)]"

class PixelText(private val font: PixelFont, val height: Int, private val fallback: PixelFont? = null) {
    /** 基线位置（从字的顶部算）。 */
    val ascent: Int get() = font.ascent * height / font.size

    private class Glyph(val width: Int, val on: BooleanArray)
    private val cache = HashMap<Char, Glyph?>()

    private fun sourceOf(c: Char): PixelFont? = when {
        font.has(c) -> font
        fallback != null && fallback.has(c) -> fallback
        else -> null
    }

    fun advance(c: Char): Int {
        val src = sourceOf(c) ?: return if (c.code < 0x80) height / 2 else height   // 都没有：留出空位
        return src.advance(c) * height / src.size
    }

    fun measure(s: CharSequence): Int {
        var w = 0
        for (c in s) w += advance(c)
        return w
    }

    /**
     * 按像素宽度折行：中文逐字，连续的英文字母和数字尽量不拆开（整个词放不下一行时才拆）；
     * 行首的空格去掉，原文的换行保留。
     */
    fun wrap(text: String, maxW: Int): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            val line = StringBuilder()
            var w = 0
            var i = 0
            while (i < para.length) {
                var j = i + 1
                if (para[i].code < 0x80 && para[i].isLetterOrDigit()) while (j < para.length && para[j].code < 0x80 && para[j].isLetterOrDigit()) j++
                val piece = para.substring(i, j)
                val pw = measure(piece)
                if (line.isNotEmpty() && w + pw > maxW) {
                    // 避头：句号、逗号这类标点不放在行首，把上一行最后一个字一起带下来
                    val carry = if (piece.length == 1 && piece[0] in NO_LINE_START && line.length >= 2 && line.last() !in NO_LINE_START && line.last() != ' ')
                        line.last() else null
                    if (carry != null) line.setLength(line.length - 1)
                    out.add(line.toString().trimEnd()); line.setLength(0); w = 0
                    if (carry != null) { line.append(carry); w = advance(carry) }
                }
                if (line.isEmpty() && piece == " ") { i = j; continue }
                if (pw > maxW) {
                    // 一个词比整行还长：逐字拆
                    for (ch in piece) {
                        val cw = advance(ch)
                        if (line.isNotEmpty() && w + cw > maxW) { out.add(line.toString()); line.setLength(0); w = 0 }
                        line.append(ch); w += cw
                    }
                } else { line.append(piece); w += pw }
                i = j
            }
            out.add(line.toString().trimEnd())
        }
        return out
    }

    /** 超出宽度时截断并加省略号。 */
    fun ellipsize(s: String, maxW: Int): String {
        if (measure(s) <= maxW) return s
        val dots = measure("…")
        var w = 0
        var end = 0
        while (end < s.length && w + advance(s[end]) + dots <= maxW) w += advance(s[end++])
        return s.substring(0, end) + "…"
    }

    /** 把一个字按最近邻缩放到 height 高。 */
    @Synchronized
    private fun glyph(c: Char): Glyph? = cache.getOrPut(c) {
        val src = sourceOf(c) ?: return@getOrPut null
        val srcW = src.advance(c)
        val w = advance(c)
        val on = BooleanArray(w * height)
        if (w > 0 && srcW > 0) {
            for (y in 0 until height) for (x in 0 until w) on[y * w + x] = src.pixel(c, x * srcW / w, y * src.size / height)
        }
        Glyph(w, on)
    }

    /**
     * 画一行字，(x, top) 是左上角。每一像素行里连续点亮的一段交给 fill(x, y, 宽, 高)。
     * @return 画完后的横坐标
     */
    fun draw(s: CharSequence, x: Int, top: Int, fill: (Int, Int, Int, Int) -> Unit): Int {
        var cx = x
        for (c in s) {
            val g = glyph(c)
            if (g != null) {
                for (dy in 0 until height) {
                    var run = -1
                    for (dx in 0..g.width) {
                        val lit = dx < g.width && g.on[dy * g.width + dx]
                        if (lit && run < 0) run = dx
                        if (!lit && run >= 0) { fill(cx + run, top + dy, dx - run, 1); run = -1 }
                    }
                }
            }
            cx += advance(c)
        }
        return cx
    }
}
