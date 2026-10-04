package io.github.yingdu

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

data class Chapter(val title: String, val offset: Int)

/** 一本已经解码、规范化的书。所有位置都用 text 中的字符偏移表示，换分页参数后进度不会丢。 */
class Book(val title: String, val text: String, explicitChapters: List<Chapter>? = null) {

    /** EPUB/MOBI 用书里自带的目录；TXT 按"第X章"之类的标题自动识别。 */
    val chapters: List<Chapter> = explicitChapters?.takeIf { it.isNotEmpty() } ?: detectChapters(text)

    fun chapterIndexAt(offset: Int): Int {
        var lo = 0
        var hi = chapters.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (chapters[mid].offset <= offset) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    companion object {
        private val CHAPTER_RE = Regex(
            "^[\\s　]*(第[0-9０-９零〇一二两三四五六七八九十百千万]+[章回节卷集部篇]|序章|序言|楔子|引子|尾声|后记|番外)"
        )

        fun isChapterTitle(line: String): Boolean {
            val t = line.trim()
            return t.isNotEmpty() && t.length <= 40 && CHAPTER_RE.containsMatchIn(t)
        }

        fun detectChapters(text: String): List<Chapter> {
            val list = ArrayList<Chapter>()
            var start = 0
            while (start < text.length) {
                var end = text.indexOf('\n', start)
                if (end < 0) end = text.length
                if (end - start <= 60) {
                    val line = text.substring(start, end)
                    if (isChapterTitle(line)) list.add(Chapter(line.trim(), start))
                }
                start = end + 1
            }
            return list
        }

        /** 按文件内容判断格式并解析：EPUB（zip）、MOBI/AZW3（PalmDB）或 TXT。 */
        fun load(title: String, bytes: ByteArray): Book {
            val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
            val isMobi = bytes.size > 68 && String(bytes, 60, 8, Charsets.ISO_8859_1).let { it == "BOOKMOBI" || it == "TEXtREAd" }
            val clean = title.replace(Regex("(?i)\\.(txt|epub|mobi|azw3|azw)$"), "")
                // 去掉括号里的网址（比如下载站的名字），只留书名和作者
                .replace(Regex("\\s*[（(][^()（）]*\\b[a-z0-9-]+\\.[a-z]{2,}\\b[^()（）]*[)）]", RegexOption.IGNORE_CASE), "")
                .trim()
            return when {
                isZip -> EpubReader.parse(bytes).let { Book(clean, it.text, it.chapters) }
                isMobi -> MobiReader.parse(bytes).let { Book(clean, it.text, it.chapters) }
                else -> Book(clean, decode(bytes))
            }
        }

        /** 依次尝试 BOM、严格 UTF-8、GB18030（兼容 GBK/GB2312，中文 TXT 最常见的编码）。 */
        fun decode(bytes: ByteArray): String {
            val raw = when {
                bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                    String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                else -> try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString()
                } catch (e: CharacterCodingException) {
                    String(bytes, Charset.forName("GB18030"))
                }
            }
            return normalize(raw)
        }

        /** 统一换行、去掉每段首尾空白和空行。 */
        fun normalize(raw: String): String {
            val sb = StringBuilder(raw.length)
            for (line in raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
                val t = line.trim { it.isWhitespace() || it == '　' || it == '\u00A0' || it == '\uFEFF' }
                if (t.isEmpty()) continue
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(t)
            }
            return sb.toString()
        }
    }
}

/** 屏幕上显示的一段文字，以及它在原文中覆盖的范围 [start, end)。 */
data class Page(val text: String, val start: Int, val end: Int)

/**
 * 按行排版：先把全书切成"屏幕行"，再以任意一行为顶部取出若干行作为一屏。
 * 这样整页翻页和逐行滚动用的是同一套数据。
 * 中文和全角字符算 1 格，ASCII 算半格；每个段落（包括章节标题）都从新的一行开始。
 */
class Paginator(private val book: Book, charsPerLine: Int, linesPerPage: Int) {
    private val lineCap = charsPerLine.coerceIn(4, 60) * 2   // 以"半格"为单位
    val rows = linesPerPage.coerceIn(1, 20)

    private val lineStart: IntArray
    private val lineEnd: IntArray   // 不含换行符

    init {
        val starts = IntArrayList()
        val ends = IntArrayList()
        val text = book.text
        var i = 0
        while (i < text.length) {
            // 一个段落 [i, pEnd)
            var pEnd = text.indexOf('\n', i)
            if (pEnd < 0) pEnd = text.length
            var ls = i
            var w = 0
            var j = i
            while (j < pEnd) {
                val c = text[j]
                val cw = widthOf(c)
                if (w + cw > lineCap && j > ls && !(isClosingPunct(c) && w + cw <= lineCap + 2)) {
                    starts.add(ls); ends.add(j)
                    ls = j; w = 0
                }
                w += cw
                j++
            }
            if (pEnd > ls) { starts.add(ls); ends.add(pEnd) }
            i = pEnd + 1
        }
        if (starts.size == 0) { starts.add(0); ends.add(0) }
        lineStart = starts.toArray()
        lineEnd = ends.toArray()
    }

    val lineCount: Int get() = lineStart.size

    /** 最后一屏的顶部行（保证最后一屏是满的，除非全书不足一屏）。 */
    val maxTop: Int get() = maxOf(0, lineCount - rows)

    val pageCount: Int get() = (lineCount + rows - 1) / rows

    fun lineStartOf(line: Int): Int = lineStart[line.coerceIn(0, lineCount - 1)]

    /** 包含该字符偏移的那一行。 */
    fun lineIndexForOffset(offset: Int): Int {
        var lo = 0
        var hi = lineCount - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lineStart[mid] <= offset) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /** 以第 top 行为顶部的一屏。 */
    fun view(top: Int): Page {
        val t = top.coerceIn(0, lineCount - 1)
        val last = minOf(lineCount, t + rows)
        val sb = StringBuilder()
        for (k in t until last) {
            if (k > t) sb.append('\n')
            sb.append(book.text, lineStart[k], lineEnd[k])
        }
        return Page(sb.toString(), lineStart[t], lineEnd[last - 1])
    }

    private fun widthOf(c: Char): Int = if (c.code < 0x80) 1 else 2

    /** 避头标点：这些符号不放在行首，允许"挂"在上一行末尾（所以每行字数要比实测少设 1）。 */
    private fun isClosingPunct(c: Char): Boolean = c in "，。！？；：、）》」』”’…—,.!?;:)"

    private class IntArrayList {
        private var a = IntArray(1024)
        var size = 0; private set
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun toArray(): IntArray = a.copyOf(size)
    }
}
