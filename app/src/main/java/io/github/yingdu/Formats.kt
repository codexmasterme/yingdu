package io.github.yingdu

import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** 解析出来的书：正文 + 章节（章节偏移指向正文里标题所在行的开头）。 */
class ParsedBook(val text: String, val chapters: List<Chapter>)

/**
 * HTML → 纯文本。段落、换行、标题都变成换行；h1～h3 标题记为章节。
 * 纯 Kotlin 实现，不依赖 Android，可以单元测试。
 */
object HtmlText {
    private val DROP = Regex("(?is)<(script|style|head)[^>]*>.*?</\\1\\s*>")
    private val HEADING = Regex("(?is)<h([1-3])[^>]*>(.*?)</h\\1\\s*>")
    private val BLOCK = Regex("(?i)<\\s*(br|/p|/div|/li|/tr|/h[1-6]|/blockquote|p|div|mbp:pagebreak)[^>]*>")
    private val TAG = Regex("(?s)<[^>]*>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val NAMED = mapOf("nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
        "middot" to "·", "emsp" to "　", "ensp" to " ", "thinsp" to " ", "copy" to "©")

    const val MARK = '\u0001'   // 标题标记，转换完再去掉

    fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> NAMED[e] ?: m.value
        }
    }

    /** 转成纯文本；标题行前面加 MARK，方便之后定位章节。 */
    fun toText(html: String): String {
        var s = DROP.replace(html, "")
        s = HEADING.replace(s) { m ->
            val t = decodeEntities(TAG.replace(m.groupValues[2], "")).replace(Regex("\\s+"), " ").trim()
            if (t.isEmpty()) "\n" else "\n$MARK$t\n"
        }
        s = BLOCK.replace(s, "\n")
        s = TAG.replace(s, "")
        return decodeEntities(s)
    }

    /** 目录标记：\u0002序号\u0003，插在 HTML 里目录项指向的位置，转换完再换算成章节偏移。 */
    private val TOC_MARK = Regex("\u0002(\\d+)\u0003")
    fun tocMarker(i: Int) = "\u0002$i\u0003"

    /**
     * 把若干段 HTML 文本合成一本书：规范化空白，记录章节。
     * 有目录（tocTitles 非空）就按目录标记定位章节；没有目录再用 h1～h3 标题。
     */
    fun assemble(parts: List<Pair<String?, String>>, tocTitles: List<String> = emptyList()): ParsedBook {
        val sb = StringBuilder()
        val headChapters = ArrayList<Chapter>()
        val tocChapters = ArrayList<Chapter>()
        val pendingToc = ArrayList<Int>()
        for ((fallbackTitle, raw) in parts) {
            var firstInPart = true
            for (line in raw.split('\n')) {
                // 取出目录标记
                TOC_MARK.findAll(line).forEach { m -> m.groupValues[1].toIntOrNull()?.let { pendingToc.add(it) } }
                var t = TOC_MARK.replace(line, "").trim { it.isWhitespace() || it == '　' || it == '\u00A0' || it == '\uFEFF' }
                val isHead = t.startsWith(MARK)
                if (isHead) t = t.substring(1).trim()
                if (t.isEmpty()) continue       // 空行上的目录标记留给下一行
                if (sb.isNotEmpty()) sb.append('\n')
                val offset = sb.length
                for (i in pendingToc) tocTitles.getOrNull(i)?.let { tocChapters.add(Chapter(it.take(40), offset)) }
                pendingToc.clear()
                if (isHead) headChapters.add(Chapter(t.take(40), offset))
                else if (firstInPart && fallbackTitle != null && (headChapters.isEmpty() || headChapters.last().offset < offset))
                    headChapters.add(Chapter(fallbackTitle.take(40), offset))
                sb.append(t)
                firstInPart = false
            }
        }
        // 同一位置有多个目录项（比如"第一章"是一页插图，紧接着"一"），保留最后一个（最具体的）
        val toc = tocChapters.reversed().distinctBy { it.offset }.reversed()
        val chapters = if (toc.size >= 2) toc else headChapters.distinctBy { it.offset }
        return ParsedBook(sb.toString(), chapters)
    }
}

/** EPUB：zip 包 → container.xml 找到 OPF → 按 spine 顺序读 XHTML → 纯文本。目录名来自 toc.ncx / nav。 */
object EpubReader {
    fun parse(bytes: ByteArray): ParsedBook {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) files[e.name] = z.readBytes()
            }
        }
        fun str(path: String) = files[path]?.toString(Charsets.UTF_8)
        val container = str("META-INF/container.xml") ?: throw IllegalArgumentException("不是有效的 EPUB（缺少 container.xml）")
        val opfPath = Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(container)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("EPUB 里找不到 OPF")
        val opf = str(opfPath) ?: throw IllegalArgumentException("EPUB 里找不到 $opfPath")
        val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

        val manifest = HashMap<String, Pair<String, String>>()   // id → (href, media-type)
        for (m in Regex("(?is)<item\\b([^>]*)/?>").findAll(opf)) {
            val a = m.groupValues[1]
            val id = attr(a, "id") ?: continue
            val href = attr(a, "href") ?: continue
            manifest[id] = resolve(base, decodeUrl(href)) to (attr(a, "media-type") ?: "")
        }
        val spine = Regex("(?is)<itemref\\b([^>]*)/?>").findAll(opf).mapNotNull { attr(it.groupValues[1], "idref") }.toList()
        if (spine.isEmpty()) throw IllegalArgumentException("EPUB 没有阅读顺序（spine）")

        // 目录（按阅读顺序，带层级）：优先 toc.ncx，没有就用 EPUB3 的 nav 文件
        val toc = ArrayList<TocEntry>()
        manifest.values.firstOrNull { it.second == "application/x-dtbncx+xml" }?.first?.let { p -> str(p)?.let { toc.addAll(parseNcx(it, dirOf(p))) } }
        if (toc.isEmpty()) {
            Regex("(?is)<item\\b([^>]*properties\\s*=\\s*\"[^\"]*\\bnav\\b[^\"]*\"[^>]*)/?>").find(opf)?.let { m ->
                attr(m.groupValues[1], "href")?.let { href -> val p = resolve(base, decodeUrl(href)); str(p)?.let { toc.addAll(parseNav(it, dirOf(p))) } }
            }
        }
        // 子目录项带上上级标题，例如"第一章 一"
        val titles = toc.map { e -> if (e.parent != null && e.parent != e.title) "${e.parent} ${e.title}" else e.title }

        val parts = ArrayList<Pair<String?, String>>()
        for (idref in spine) {
            val (path, type) = manifest[idref] ?: continue
            if (!type.contains("html") && !path.endsWith("html") && !path.endsWith("htm")) continue
            var html = str(path) ?: continue
            // 在目录项指向的位置插入标记：有锚点就插在带这个 id 的标签前面，没有就插在文件开头
            val here = toc.withIndex().filter { it.value.file == path }
            for ((i, e) in here.reversed()) {
                val marker = HtmlText.tocMarker(i)
                val frag = e.fragment
                val at = if (frag.isNullOrEmpty()) -1 else Regex("(?i)\\b(id|name)\\s*=\\s*[\"']" + Regex.escape(frag) + "[\"']").find(html)?.range?.first
                    ?.let { html.lastIndexOf('<', it) } ?: -1
                html = if (at >= 0) html.substring(0, at) + marker + html.substring(at)
                       else { val b = Regex("(?i)<body[^>]*>").find(html)?.range?.last?.plus(1) ?: 0; html.substring(0, b) + marker + html.substring(b) }
            }
            parts.add(null to HtmlText.toText(html))
        }
        return HtmlText.assemble(parts, titles)
    }

    class TocEntry(val title: String, val file: String, val fragment: String?, val parent: String?)

    private fun dirOf(p: String) = p.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

    /** toc.ncx：按出现顺序读 navPoint，记录上一级标题。 */
    fun parseNcx(ncx: String, dir: String): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        val stack = ArrayList<String?>()   // 每层 navPoint 的标题
        val token = Regex("(?is)<navPoint\\b|</navPoint>|<text>(.*?)</text>|<content\\b[^>]*src\\s*=\\s*[\"']([^\"']+)[\"']")
        var pendingTitle: String? = null
        for (m in token.findAll(ncx)) {
            val v = m.value
            when {
                v.startsWith("<navPoint", true) -> { stack.add(null); pendingTitle = null }
                v.startsWith("</navPoint", true) -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                v.startsWith("<text", true) -> { pendingTitle = HtmlText.decodeEntities(m.groupValues[1]).trim(); if (stack.isNotEmpty()) stack[stack.size - 1] = pendingTitle }
                else -> {
                    val title = pendingTitle ?: continue
                    val src = m.groupValues[2]
                    val parent = if (stack.size >= 2) stack[stack.size - 2] else null
                    out.add(TocEntry(title, resolve(dir, decodeUrl(src.substringBefore('#'))), src.substringAfter('#', "").ifEmpty { null }, parent))
                    pendingTitle = null
                }
            }
        }
        return out
    }

    /** EPUB3 的 nav 文件：&lt;nav epub:type="toc"&gt; 里的链接，按 &lt;ol&gt; 嵌套算层级。 */
    fun parseNav(html: String, dir: String): List<TocEntry> {
        val navStart = Regex("(?is)<nav\\b[^>]*toc[^>]*>").find(html)?.range?.last ?: return emptyList()
        val navEnd = html.indexOf("</nav>", navStart).let { if (it < 0) html.length else it }
        val body = html.substring(navStart, navEnd)
        val out = ArrayList<TocEntry>()
        val stack = ArrayList<String?>()
        var last: String? = null
        for (m in Regex("(?is)<ol\\b[^>]*>|</ol>|<a\\b[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>").findAll(body)) {
            val v = m.value
            when {
                v.startsWith("<ol", true) -> stack.add(last)
                v.startsWith("</ol", true) -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> {
                    val title = HtmlText.decodeEntities(m.groupValues[2].replace(Regex("<[^>]+>"), "")).trim()
                    val href = m.groupValues[1]
                    out.add(TocEntry(title, resolve(dir, decodeUrl(href.substringBefore('#'))), href.substringAfter('#', "").ifEmpty { null },
                        stack.lastOrNull()))
                    last = title
                }
            }
        }
        return out
    }

    private fun attr(a: String, name: String) =
        Regex("(?i)\\b$name\\s*=\\s*[\"']([^\"']*)[\"']").find(a)?.groupValues?.get(1)

    private fun decodeUrl(s: String) = try { java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (e: Exception) { s }

    /** 处理 ../ 这样的相对路径。 */
    fun resolve(base: String, href: String): String {
        val parts = ArrayList<String>()
        for (p in (base + href).split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(p)
            }
        }
        return parts.joinToString("/")
    }
}

/**
 * MOBI / AZW3（无 DRM）：PalmDB → 解压文本记录（PalmDOC 或 HUFF/CDIC）→ HTML → 纯文本。
 * 算法参考开源的 KindleUnpack。有 DRM 的书（从亚马逊买的）无法解析。
 */
object MobiReader {
    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    fun parse(data: ByteArray): ParsedBook {
        if (data.size < 86) throw IllegalArgumentException("文件太小，不是有效的 MOBI")
        val type = String(data, 60, 8, Charsets.ISO_8859_1)
        if (type != "BOOKMOBI" && type != "TEXtREAd") throw IllegalArgumentException("不是 MOBI 文件（$type）")
        val n = u16(data, 76)
        val offsets = IntArray(n) { u32(data, 78 + it * 8).toInt() }
        fun record(i: Int): ByteArray {
            val end = if (i + 1 < n) offsets[i + 1] else data.size
            return data.copyOfRange(offsets[i], end)
        }
        val r0 = record(0)
        val compression = u16(r0, 0)
        val textRecords = u16(r0, 8)
        val encryption = u16(r0, 12)
        if (encryption != 0) throw IllegalArgumentException("这本书有 DRM 加密，无法打开")
        var utf8 = true
        var extraFlags = 0
        var huffOffset = 0
        var huffCount = 0
        if (r0.size >= 24 && String(r0, 16, 4, Charsets.ISO_8859_1) == "MOBI") {
            val headerLen = u32(r0, 20).toInt()
            utf8 = u32(r0, 28) == 65001L
            val version = if (r0.size >= 40) u32(r0, 36).toInt() else 0
            if (r0.size >= 0x78) { huffOffset = u32(r0, 0x70).toInt(); huffCount = u32(r0, 0x74).toInt() }
            if (headerLen >= 0xE4 && version >= 5 && r0.size >= 0xF4) extraFlags = u16(r0, 0xF2)
        }
        val huff = if (compression == 17480) HuffCdic(record(huffOffset), (1 until huffCount).map { record(huffOffset + it) }) else null
        val out = ByteArrayOutputStream()
        for (i in 1..minOf(textRecords, n - 1)) {
            var rec = record(i)
            rec = rec.copyOfRange(0, (rec.size - trailingSize(rec, extraFlags)).coerceAtLeast(0))
            out.write(when (compression) {
                1 -> rec
                2 -> palmDoc(rec)
                17480 -> huff!!.unpack(rec)
                else -> throw IllegalArgumentException("不支持的压缩方式 $compression")
            })
        }
        val html = String(out.toByteArray(), if (utf8) Charsets.UTF_8 else charset("windows-1252"))
        return HtmlText.assemble(listOf(null to HtmlText.toText(html)))
    }

    /** 记录末尾的附加数据长度（需要先去掉才能解压）。 */
    fun trailingSize(rec: ByteArray, flags: Int): Int {
        var num = 0
        var f = flags shr 1
        while (f != 0) {
            if (f and 1 != 0) {
                var size = rec.size - num
                var bitpos = 0
                var result = 0
                while (size > 0) {
                    val v = rec[size - 1].toInt() and 0xFF
                    result = result or ((v and 0x7F) shl bitpos)
                    bitpos += 7
                    size--
                    if (v and 0x80 != 0 || bitpos >= 28 || size == 0) break
                }
                num += result
            }
            f = f shr 1
        }
        if (flags and 1 != 0 && rec.size - num - 1 >= 0) num += (rec[rec.size - num - 1].toInt() and 0x3) + 1
        return num
    }

    /** PalmDOC（LZ77 变体）解压。 */
    fun palmDoc(src: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(src.size * 2)
        val buf = ArrayList<Byte>()
        fun put(b: Int) { out.write(b); buf.add(b.toByte()) }
        var i = 0
        while (i < src.size) {
            val c = src[i++].toInt() and 0xFF
            when {
                c in 1..8 -> repeat(c) { if (i < src.size) put(src[i++].toInt() and 0xFF) }
                c < 0x80 -> put(c)
                c >= 0xC0 -> { put(' '.code); put(c xor 0x80) }
                else -> {
                    if (i >= src.size) break
                    val pair = (c shl 8) or (src[i++].toInt() and 0xFF)
                    val dist = (pair shr 3) and 0x7FF
                    val len = (pair and 7) + 3
                    repeat(len) {
                        val idx = buf.size - dist
                        put(if (idx >= 0) buf[idx].toInt() and 0xFF else 0)
                    }
                }
            }
        }
        return out.toByteArray()
    }

    /** HUFF/CDIC 解压（亚马逊 kindlegen 生成的书常用）。 */
    class HuffCdic(huff: ByteArray, cdics: List<ByteArray>) {
        private val dict1Len = IntArray(256)
        private val dict1Term = BooleanArray(256)
        private val dict1Max = LongArray(256)
        private val minCode = LongArray(33)
        private val maxCode = LongArray(33)
        private val dict = ArrayList<ByteArray?>()
        private val dictDone = ArrayList<Boolean>()

        init {
            if (String(huff, 0, 4, Charsets.ISO_8859_1) != "HUFF") throw IllegalArgumentException("HUFF 记录无效")
            val off1 = u32(huff, 8).toInt()
            val off2 = u32(huff, 12).toInt()
            for (i in 0 until 256) {
                val v = u32(huff, off1 + i * 4)
                val len = (v and 0x1F).toInt()
                dict1Len[i] = len
                dict1Term[i] = v and 0x80 != 0L
                dict1Max[i] = (((v ushr 8) + 1) shl (32 - len)) - 1
            }
            for (len in 1..32) {
                val mn = u32(huff, off2 + (len - 1) * 8)
                val mx = u32(huff, off2 + (len - 1) * 8 + 4)
                minCode[len] = mn shl (32 - len)
                maxCode[len] = ((mx + 1) shl (32 - len)) - 1
            }
            for (c in cdics) {
                if (String(c, 0, 4, Charsets.ISO_8859_1) != "CDIC") throw IllegalArgumentException("CDIC 记录无效")
                val phrases = u32(c, 8).toInt()
                val bits = u32(c, 12).toInt()
                val count = minOf(1 shl bits, phrases - dict.size)
                for (k in 0 until count) {
                    val off = u16(c, 16 + k * 2)
                    val blen = u16(c, 16 + off)
                    val start = 16 + off + 2
                    dict.add(c.copyOfRange(start, start + (blen and 0x7FFF)))
                    dictDone.add(blen and 0x8000 != 0)
                }
            }
        }

        fun unpack(src: ByteArray): ByteArray {
            val data = src + ByteArray(8)
            var bitsLeft = src.size * 8L
            var pos = 0
            fun q(p: Int): Long {
                var r = 0L
                for (k in 0 until 8) r = (r shl 8) or (data[p + k].toLong() and 0xFF)
                return r
            }
            var x = q(0)
            var n = 32
            val out = ByteArrayOutputStream()
            while (true) {
                if (n <= 0) { pos += 4; x = q(pos); n += 32 }
                val code = (x ushr n) and 0xFFFFFFFFL
                val top = (code ushr 24).toInt()
                var len = dict1Len[top]
                var max = dict1Max[top]
                if (!dict1Term[top]) {
                    while (len < 32 && code < minCode[len]) len++
                    max = maxCode[len]
                }
                n -= len
                bitsLeft -= len
                if (bitsLeft < 0) break
                val r = ((max - code) ushr (32 - len)).toInt()
                if (r < 0 || r >= dict.size) break
                var slice = dict[r] ?: break
                if (!dictDone[r]) {
                    dict[r] = null
                    slice = unpack(slice)
                    dict[r] = slice
                    dictDone[r] = true
                }
                out.write(slice)
            }
            return out.toByteArray()
        }
    }
}
