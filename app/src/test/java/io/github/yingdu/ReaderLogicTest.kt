package io.github.yingdu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtocolTest {
    @Test fun crcCheckValue() = assertEquals(0x29B1, nimoCrc16("123456789".toByteArray()))

    @Test fun requestFrameLayout() {
        val f = NimoFrameCodec.encodeFrame(0x03, 0x02, byteArrayOf(0x08))
        assertEquals(13, f.size)
        assertEquals(0xBF, f[0].toInt() and 0xFF)
        assertEquals(listOf(3, 2, 1, 0, 8), f.copyOfRange(8, 13).map { it.toInt() })
    }

    @Test fun contentChunkIndices() {
        val frames = NimoFrameCodec.updateContentFrames(0x04, 0, 0, 0, ByteArray(1200))
        assertEquals(listOf(1, 2, 0), frames.map { it[6].toInt() })
        fun len(f: ByteArray) = (f[2].toInt() and 0xFF) or ((f[3].toInt() and 0xFF) shl 8)
        assertEquals(listOf(501, 501, 206), frames.map { len(it) })
    }

    private fun hex(s: String) = s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    /** 和实机收发的帧逐字节比对（固件 V0.1.1.4）。 */
    @Test fun prompterContentMatchesCapture() {
        val frames = NimoFrameCodec.updateContentFrames(0x06, 0, 0, 0x02, NimoFrameCodec.prompterTextContent("\n\nABC测试1234"))
        assertEquals(1, frames.size)
        val expectedApp = hex("07 04 21 00 06 00 00 02 00 00 00 00 0f 00 00 00 00 00 00 00 00 0a 0a 41 42 43 e6 b5 8b e8 af 95 31 32 33 34 00")
        assertEquals(expectedApp.toList(), frames[0].copyOfRange(8, frames[0].size).toList())
        // 传输头里的 CRC 也要一致（27 2c）
        assertEquals(listOf(0x27, 0x2c), listOf(frames[0][4].toInt() and 0xFF, frames[0][5].toInt() and 0xFF))
    }

    @Test fun prompterEnterMatchesCapture() {
        val token = hex("96 07 bf 6f 6b 87 47 ee ab 15 35 d9 c6 ba 3f 31")
        val f = NimoFrameCodec.encodeFrame(0x07, 0x01, NimoFrameCodec.enterPrompterPayload(9, token))
        val expected = hex("bf 02 21 00 38 ae 00 00 07 01 1d 00 06 00 00 00 00 01 05 02 00 09 00 00 00 96 07 bf 6f 6b 87 47 ee ab 15 35 d9 c6 ba 3f 31")
        assertEquals(expected.toList(), f.toList())
    }

    @Test fun prompterStatusMatchesCapture() {
        val f = NimoFrameCodec.updateContentFrames(0x06, 0, 1, 0x02, "倒计时10s".toByteArray())[0]
        val expected = hex("bf 02 14 00 fa e6 00 00 07 04 10 00 06 00 01 02 e5 80 92 e8 ae a1 e6 97 b6 31 30 73")
        assertEquals(expected.toList(), f.toList())
    }

    @Test fun decodeAndRejectBadCrc() {
        val app = byteArrayOf(0x02, 0x06, 0x04, 0x00, 0x00, 90, 85, 0, 1)
        val frame = NimoFrameCodec.transportHeader(app) + app
        assertNotNull(NimoFrameCodec.decode(frame))
        frame[10] = (frame[10] + 1).toByte()
        assertNull(NimoFrameCodec.decode(frame))
    }
}

class DashPullTest {
    @Test fun parseAndStateFrame() {
        // 版本 1, 标志 0, 要内容(位 0、1), 左侧 1（天气）, 右侧 4（股票）, 0, 请求号 0x00012345
        val p = NimoFrameCodec.parseDashPull(byteArrayOf(1, 0, 3, 1, 4, 0, 0x45, 0x23, 0x01, 0))!!
        assertEquals(4, p.right); assertEquals(1, p.left); assertEquals(0x12345L, p.requestId); assertTrue(p.wantsContent)
        // 萤读 5.4 及以前的回应：01 00 seq 00 00 00 01 04 05 00
        assertArrayEquals(byteArrayOf(1, 0, 0x2A, 0, 0, 0, 1, 4, 5, 0), NimoFrameCodec.dashPullState(0, 0x2A, 1, 4, 5))
        assertArrayEquals(byteArrayOf(1, 0, 0x45, 0x23, 0x01, 0, 1, 0, 15, 0), NimoFrameCodec.dashPullState(0, p.requestId, 1, 0, 15))
        assertNull(NimoFrameCodec.parseDashPull(byteArrayOf(2, 0, 3, 4, 1, 0, 1)))
        // 短包：请求号只有一个字节
        assertEquals(7L, NimoFrameCodec.parseDashPull(byteArrayOf(1, 0, 1, 0, 1, 0, 7))!!.requestId)
        assertFalse(NimoFrameCodec.parseDashPull(byteArrayOf(1, 0, 1, 0, 1, 0, 7))!!.wantsContent)
    }
}

class PaginatorTest {
    private val sample = buildString {
        append("序章 开端\n")
        repeat(30) { append("这是第${it}段，Nimo reader 测试 ABC。夜色渐深，街灯一盏接一盏亮起来。\n") }
        append("第一章 雪夜\n")
        repeat(20) { append("列车驶出隧道，窗外一片雪白。\n") }
        append("第二章 站台")
    }

    @Test fun decodesGbk() {
        val text = "第一章 你好\n世界"
        assertEquals(text, Book.decode(text.toByteArray(charset("GBK"))))
        assertEquals(text, Book.decode(text.toByteArray(Charsets.UTF_8)))
    }

    @Test fun linesCoverTextAndRespectLimits() {
        val book = Book("t", Book.normalize(sample))
        assertEquals(3, book.chapters.size)
        for ((perLine, perPage) in listOf(8 to 3, 16 to 5, 27 to 5, 20 to 1)) {
            val p = Paginator(book, perLine, perPage)
            val rebuilt = StringBuilder()
            var top = 0
            while (top < p.lineCount) {
                val v = p.view(top)
                val lines = v.text.split('\n')
                assertTrue(lines.size <= perPage)
                for (l in lines) assertTrue("line too wide: $l", l.fold(0) { acc, c -> acc + if (c.code < 0x80) 1 else 2 } <= perLine * 2 + 2)
                rebuilt.append(v.text.replace("\n", ""))
                top += perPage
            }
            assertEquals(book.text.replace("\n", ""), rebuilt.toString())
            // 每个章节标题都从一行的开头开始
            for (ch in book.chapters) assertEquals(ch.offset, p.lineStartOf(p.lineIndexForOffset(ch.offset)))
        }
    }

    @Test fun scrollingByOneLineShiftsView() {
        val book = Book("t", Book.normalize(sample))
        val p = Paginator(book, 10, 5)
        val a = p.view(3).text.split('\n')
        val b = p.view(4).text.split('\n')
        assertEquals(a.drop(1), b.dropLast(1))
        assertEquals(p.lineCount - 5, p.maxTop)
    }
}

class PixelFontTest {
    @Test fun punctuationNeverStartsALine() {
        val dir = "app/src/main/assets/fonts/"
        GlassesFonts.init(PixelFont.parse(java.io.File(dir + "yingdu_pixel16.ydpf").readBytes()), PixelFont.parse(java.io.File(dir + "yingdu_pixel12.ydpf").readBytes()))
        val f = GlassesFonts.text(16)
        val w = f.measure("一".repeat(10))
        val lines = f.wrap("一".repeat(10) + "，二三。", w)
        assertEquals(listOf("一".repeat(9), "一，二三。"), lines)
        assertTrue(lines.none { it.first() in "，。" })
        assertEquals(listOf("一".repeat(10), "二三"), f.wrap("一".repeat(10) + "二三", w))   // 不是标点照常断
    }

    @Test fun sightIntroFillsOneScreen() {
        val dir = "app/src/main/assets/fonts/"
        GlassesFonts.init(PixelFont.parse(java.io.File(dir + "yingdu_pixel16.ydpf").readBytes()), PixelFont.parse(java.io.File(dir + "yingdu_pixel12.ydpf").readBytes()))
        val long = "东京晴空塔是位于日本东京都墨田区的电波塔，高634米，是世界最高的自立式电波塔。塔内设有两座展望台，天气晴朗时可以远眺富士山。" +
            "塔下的商业设施东京晴空街道有三百多家商店和餐厅，还有水族馆和天象馆。晴空塔于2008年动工，2012年5月正式开放，" +
            "开放第一年就吸引了超过五百万名游客，是东京最受欢迎的观光地之一。"
        for (px in listOf(16, 20)) {
            val f = GlassesFonts.text(px); val w = DashboardImage.notificationWidth(); val max = DashboardImage.notificationLines(px)
            val fit = DashboardImage.fitNotificationText(long, px)
            val lines = f.wrap(fit, w)
            assertEquals("${px}px 刚好 $max 行：$fit", max, lines.size)
            // 最后一行也基本填满（或者正好在句号处结束）
            assertTrue(fit.endsWith("。") || f.measure(lines.last()) > w * 3 / 4)
            assertTrue(long.startsWith(fit.removeSuffix("…")))
        }
        assertEquals("短介绍不动。", DashboardImage.fitNotificationText("短介绍不动。", 16))
    }

    // 两个 3×4 的小字："A" 宽 3，"中" 宽 4
    private val tiny = PixelFont.of(4, 3, mapOf(
        'A' to listOf(".#.", "#.#", "###", "#.#"),
        '中' to listOf("..#.", "####", "####", "..#."),
    ))

    /** 按 .ydpf 格式手工拼一个文件，确认解析和 PixelFont.of 一致。 */
    @Test fun parsesFileFormat() {
        val out = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(out)
        d.writeBytes("YDPF"); d.writeByte(1); d.writeByte(2); d.writeByte(1); d.writeByte(2); d.writeInt(1)
        d.writeShort('B'.code); d.writeByte(3)
        d.writeShort(0b1010_0000_0000_0000); d.writeShort(0b0110_0000_0000_0000)
        val f = PixelFont.parse(out.toByteArray())
        assertEquals(1, f.glyphCount)
        assertEquals(3, f.advance('B'))
        assertEquals(listOf(true, false, true, false, true, true), listOf(f.pixel('B', 0, 0), f.pixel('B', 1, 0), f.pixel('B', 2, 0),
            f.pixel('B', 0, 1), f.pixel('B', 1, 1), f.pixel('B', 2, 1)))
        assertEquals(-1, f.advance('C'))
    }

    private fun render(t: PixelText, s: String): List<String> {
        val w = t.measure(s)
        val g = Array(t.height) { CharArray(w) { '.' } }
        t.draw(s, 0, 0) { x, y, rw, rh -> for (yy in y until y + rh) for (xx in x until x + rw) g[yy][xx] = '#' }
        return g.map { String(it) }
    }

    @Test fun drawsRunsAtNativeSize() {
        assertEquals(listOf(".#...#.", "#.#####", "#######", "#.#..#."), render(PixelText(tiny, 4), "A中"))
    }

    @Test fun scalesByWholePixels() {
        val big = PixelText(tiny, 8)
        assertEquals(8, big.height)
        assertEquals(6, big.advance('A'))
        assertEquals(listOf("..##..", "..##..", "##..##", "##..##", "######", "######", "##..##", "##..##"), render(big, "A"))
    }

    @Test fun fallsBackAndEllipsizes() {
        val other = PixelFont.of(8, 6, mapOf('Z' to List(8) { "########" }))
        val t = PixelText(tiny, 8, fallback = other)
        assertEquals(8, t.advance('Z'))            // 后备字体按字高缩放：8 点阵画成 8 像素高
        assertEquals(8, t.advance('字'))           // 都没有的全角字：留一个整格
        assertEquals(4, t.advance('x'))            // 都没有的半角字：留半格
        val s = "AAAA"
        assertEquals(s, t.ellipsize(s, t.measure(s)))
        assertTrue(t.measure(t.ellipsize(s, 15)) <= 15)
        assertTrue(t.ellipsize(s, 15).endsWith("…"))
    }

    /** 非整数倍缩放：最近邻，每个源像素变成 1 或 2 个像素，笔画一条不丢。 */
    @Test fun scalesByNearestBetweenWholeSizes() {
        val t = PixelText(tiny, 6)                   // 4 → 6，1.5 倍
        assertEquals(6, t.advance('中'))
        assertEquals(listOf("...##.", "...##.", "######", "######", "######", "...##."), render(t, "中"))
    }

    private fun asset(name: String): ByteArray {
        val f = listOf("src/main/assets/fonts/$name", "app/src/main/assets/fonts/$name").map { java.io.File(it) }.first { it.exists() }
        return f.readBytes()
    }

    private fun sample(): String {
        val f = listOf("../samples/sample_utf8.txt", "samples/sample_utf8.txt").map { java.io.File(it) }.first { it.exists() }
        return f.readText()
    }

    /** 常用 6763 个汉字（GB2312）标准字号必须全有，大号加上后备字体后也全有。 */
    @Test fun bundledFontsCoverCommonHanzi() {
        val p16 = PixelFont.parse(asset("yingdu_pixel16.ydpf"))
        val p12 = PixelFont.parse(asset("yingdu_pixel12.ydpf"))
        assertEquals(16, p16.size); assertEquals(12, p12.size)
        val gb = charset("GB2312")
        var n = 0
        for (hi in 0xB0..0xF7) for (lo in 0xA1..0xFE) {
            if (hi == 0xD7 && lo > 0xF9) continue
            val c = String(byteArrayOf(hi.toByte(), lo.toByte()), gb)[0]
            assertTrue("缺字 $c", p16.has(c))
            n++
        }
        assertEquals(6763, n)
        val large = PixelText(p12, 24, fallback = p16)
        for (c in "，。“”‘’！？…—·《》（）▲▽□°%0123456789ABCxyz") assertTrue("缺字 $c", p12.has(c) || p16.has(c))
        assertEquals(24, large.advance('中')); assertEquals(12, large.advance('A'))
    }

    /** 12～32px 每一档：排版后每一行都放得进 452 像素宽的画面，行数也放得下。 */
    @Test fun everyFontSizeFitsTheScreen() {
        val p16 = PixelFont.parse(asset("yingdu_pixel16.ydpf"))
        val p12 = PixelFont.parse(asset("yingdu_pixel12.ydpf"))
        val book = Book("t", Book.normalize(sample()))
        assertEquals(ReaderImage.Layout(16, 7, 27, 7), ReaderImage.layout(16))   // 16px 和 v2.0 的「标准」一样
        for (px in GlassesFonts.MIN_PX..GlassesFonts.MAX_PX) {
            val lay = ReaderImage.layout(px)
            val font = GlassesFonts.textFor(p16, p12, px)
            assertEquals(px, font.height)
            assertTrue("$px 行数放不下", lay.rows * lay.px + (lay.rows - 1) * lay.gap <= ReaderImage.H - 5)
            assertTrue(lay.rows >= 3)
            val p = Paginator(book, lay.charsPerLine, lay.rows)
            for (i in 0 until p.lineCount) {
                val v = p.view(i).text.split('\n')[0]
                assertTrue("${px}px 超宽：$v", 2 + font.measure(v) <= ReaderImage.W)
            }
        }
    }
}

class DashCardsTest {
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, zone: java.util.TimeZone = java.util.TimeZone.getDefault()) =
        java.util.Calendar.getInstance(zone).apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis

    @Test fun stockRows() {
        val rows = CardContent.stocks(listOf(Quote("7203.T", 2931.5, -1.234), Quote("^N225", null, null), Quote("NBIS", 237.331, 2.5)))
        assertEquals(CardRow("7203", "2932", "▽1.23%"), rows[0])
        assertEquals(CardRow("N225", "--"), rows[1])
        assertEquals(CardRow("NBIS", "237.33", "▲2.50%"), rows[2])
    }

    @Test fun aShareQuotes() {
        assertEquals("sh600519", DashboardData.cnCode("600519.SS"))
        assertEquals("sz000001", DashboardData.cnCode("000001.SZ"))
        assertEquals("bj830799", DashboardData.cnCode("830799.BJ"))
        assertNull(DashboardData.cnCode("7203.T")); assertNull(DashboardData.cnCode("NBIS")); assertNull(DashboardData.cnCode("^N225"))
        val body = "v_sh600519=\"1~贵州茅台~600519~1500.00~1470.00~1471.00~27000~13000~14000~\";\n"
        val q = DashboardData.parseTencent("600519.SS", body)!!
        assertEquals("贵州茅台", q.name); assertEquals(1500.0, q.price!!, 1e-9); assertEquals(2.0408, q.changePct!!, 1e-3)
        // 停牌：现价 0 按昨收、涨跌 0
        val halted = DashboardData.parseTencent("000001.SZ", "v_sz000001=\"51~平安银行~000001~0.00~11.20~0.00~\";")!!
        assertEquals(11.2, halted.price!!, 1e-9); assertEquals(0.0, halted.changePct!!, 1e-9)
        assertNull(DashboardData.parseTencent("1.SS", "v_pv_none_match=\"1\";"))
        // 看板上 A 股显示中文简称
        assertEquals(CardRow("贵州茅台", "1500", "▲2.04%"), CardContent.stocks(listOf(q))[0])
    }

    @Test fun aShareSearch() {
        val body = "v_hint=\"sh~600519~\\u8d35\\u5dde\\u8305\\u53f0~gzmt~GP-A^hk~00700~\\u817e\\u8baf~tx~GP^sz~000001~\\u5e73\\u5b89\\u94f6\\u884c~payh~GP-A^bj~830799~\\u827e\\u878d~ar~GP\""
        val r = StockStore.parseCnHint(body)
        assertEquals(listOf("600519.SS", "000001.SZ", "830799.BJ"), r.map { it.symbol })
        assertEquals(listOf("贵州茅台", "平安银行", "艾融"), r.map { it.name })
        assertEquals("A股", r[0].market)
        assertEquals("600519.SS", StockStore.guessCn("600519")); assertEquals("300750.SZ", StockStore.guessCn("300750"))
        assertEquals("000001.SZ", StockStore.guessCn("000001")); assertEquals("920001.BJ", StockStore.guessCn("920001"))
        assertNull(StockStore.guessCn("NVDA")); assertNull(StockStore.guessCn("12345"))
    }

    @Test fun stocksOnlyUseBothColumns() {
        fun rows(n: Int) = (1..n).map { CardRow("S$it", "1.00", "▲1.00%") }
        // 不多于 4 只：一屏整行显示（和原来一样）
        assertEquals(listOf(DashScreen(rows(4), null)), DashLayout.screens(listOf(rows(4)), 4, pairSingle = true))
        // 5～8 只：一屏左右两栏
        val s6 = DashLayout.screens(listOf(rows(6)), 4, pairSingle = true)
        assertEquals(1, s6.size); assertEquals(4, s6[0].left.size); assertEquals(2, s6[0].right!!.size)
        // 14 只：两屏，第二屏左栏 4 只、右栏 2 只
        val s14 = DashLayout.screens(listOf(rows(14)), 4, pairSingle = true)
        assertEquals(2, s14.size); assertEquals(listOf("S13", "S14"), s14[1].right!!.map { it.left })
        // 9 只：第二屏只有左栏 1 只，右栏空但仍按两栏排
        assertEquals(DashScreen(rows(9).drop(8), emptyList()), DashLayout.screens(listOf(rows(9)), 4, pairSingle = true)[1])
        // 没开这个选项（股票之外还有别的卡片）时不变
        assertEquals(2, DashLayout.screens(listOf(rows(6)), 4).size)
    }

    @Test fun cardHeadsFollowTheirCards() {
        fun rows(n: Int) = (1..n).map { CardRow("S$it") }
        val hs = CardHead("stock", "股票", "10:32"); val ha = CardHead("cal", "日程待办"); val hp = CardHead("clock", "像素表盘")
        // 空卡片跳过，标题跟着各自的卡片走
        val s = DashLayout.screens(listOf(rows(2), emptyList(), rows(3), rows(1)), 4, heads = listOf(hs, ha, hp, CardHead("memo", "今日回顾")))
        assertEquals(2, s.size)   // 股票 + 表盘一屏，今日回顾单独一屏
        assertEquals(hs, s[0].leftHead); assertEquals(hp, s[0].rightHead)
        assertEquals("今日回顾", s[1].leftHead!!.title); assertNull(s[1].right); assertNull(s[1].rightHead)
        // 只开股票分两栏：左右是同一个标题（画成一个大框）
        val two = DashLayout.screens(listOf(rows(6)), 4, pairSingle = true, heads = listOf(hs))
        assertTrue(two[0].leftHead === two[0].rightHead)
        // 单独一张占满整屏：右边没有标题
        assertNull(DashLayout.screens(listOf(rows(3)), 4, heads = listOf(ha))[0].rightHead)
        assertEquals("rain", DashboardImage.weatherIcon("小雨")); assertEquals("cloud", DashboardImage.weatherIcon("多云")); assertEquals("sun", DashboardImage.weatherIcon(null))
    }

    @Test fun countdownSortsAndSkipsPast() {
        val rows = CardContent.countdown("元旦 01-01\n春节 2027-02-06\n已过 2026-01-01\n今天 09-27\n写错的一行", at(2026, 9, 27))
        assertEquals(listOf(CardRow("今天", right = "就是今天"), CardRow("元旦", right = "还有 96 天"), CardRow("春节", right = "还有 132 天")), rows)
    }

    @Test fun clocksShowDayAndTime() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val now = at(2026, 9, 27, 12, utc)
        val rows = CardContent.clocks("纽约 America/New_York\n圣诞岛 Pacific/Kiritimati\n乱写 Not/AZone", now, java.util.TimeZone.getTimeZone("Asia/Tokyo"))
        assertEquals(listOf(CardRow("纽约", right = "周日 08:00"), CardRow("圣诞岛", right = "明天 02:00")), rows)
    }

    @Test fun parsesWebTextAndJson() {
        assertEquals(listOf(CardRow("客厅", right = "24°"), CardRow("3 路", "5 分钟", "下一班 12 分钟"), CardRow("取快递", todo = true)),
            CardContent.parseWeb("客厅 | 24°\n\n3 路\t5 分钟\t下一班 12 分钟\n□ 取快递\n"))
        assertEquals(listOf(CardRow("一行"), CardRow("左", "中", "右"), CardRow("买菜", todo = true)),
            CardContent.parseWeb("""{"rows": ["一行", ["左", "中", "右"], {"left": "买菜", "todo": true}]}"""))
        assertEquals(listOf(CardRow("a"), CardRow("b")), CardContent.parseWeb("""["a", "b"]"""))
    }

    @Test fun forecastRows() {
        val hours = (16..27).map { HourForecast(it % 24, "晴", it.toDouble()) }
        val days = listOf(DayForecast("晴", 10.0, 20.0), DayForecast("小雨", 12.4, 18.9), DayForecast("阴", 11.0, 17.0))
        assertEquals(listOf(CardRow("18时 晴", right = "18°"), CardRow("21时 晴", right = "21°"),
            CardRow("明天 小雨", right = "12~18°"), CardRow("后天 阴", right = "11~17°")), CardContent.forecast(hours, days))
    }

    @Test fun agendaFillsWithTodos() {
        val now = at(2026, 9, 27, 9)
        val ev = listOf(CalEvent(at(2026, 9, 27, 14), false, "开会"), CalEvent(at(2026, 9, 28, 0), true, "生日"))
        val rows = CardContent.agenda(ev, listOf(Todo("买菜"), Todo("已完成", true), Todo("取快递"), Todo("第三件")), now, 4)
        assertEquals(listOf(CardRow("14:00 开会"), CardRow("明天 生日"), CardRow("买菜", todo = true), CardRow("取快递", todo = true)), rows)
    }

    private fun rows(n: Int, tag: String) = (1..n).map { CardRow("$tag$it") }

    @Test fun layoutPairsCardsAndPagesLongOnes() {
        val s = DashLayout.screens(listOf(rows(6, "股"), emptyList(), rows(2, "日"), rows(3, "钟")), 4)
        assertEquals(3, s.size)
        assertEquals(rows(4, "股"), s[0].left); assertEquals(rows(2, "日"), s[0].right)   // 空卡片跳过，股票和日程并排
        assertEquals(listOf(CardRow("股5"), CardRow("股6")), s[1].left); assertEquals(rows(2, "日"), s[1].right)   // 日程只有一屏，每屏都在
        assertEquals(rows(3, "钟"), s[2].left); assertNull(s[2].right)                 // 单数的最后一张占满整屏
        assertEquals(1, DashLayout.screens(listOf(emptyList()), 4).size)
    }

    @Test fun textModeBody() {
        val body = DashboardFormatter.body(DashScreen(listOf(CardRow("NBIS", "237.33", "▲2.50%")), listOf(CardRow("买菜", todo = true))), 27)
        assertEquals("NBIS 237.33 ▲2.50%  │ □ 买菜", body)   // 左栏补到偶数宽度，分隔线才对得齐
    }
}

class GamesTest {
    @Test fun wrapsTextByPixels() {
        val f = PixelFont.of(4, 3, mapOf('中' to List(4) { "####" }, 'a' to List(4) { "##" }, 'b' to List(4) { "##" }, ' ' to List(4) { ".." }))
        val t = PixelText(f, 4)
        assertEquals(listOf("中中", "中", "ab ab", "aaaaa", "aaa"), t.wrap("中中中\nab ab\naaaaaaaa", 10))
    }

    @Test fun game2048MergesAndScores() {
        val g = Game2048(java.util.Random(1))
        assertEquals(listOf(4, 4, 0, 0), g.mergeLine(intArrayOf(2, 2, 2, 2)).first.toList())
        assertEquals(listOf(4, 2, 0, 0), g.mergeLine(intArrayOf(2, 2, 2, 0)).first.toList())
        assertEquals(listOf(8, 8, 0, 0), g.mergeLine(intArrayOf(4, 0, 4, 8)).first.toList())   // 合出来的 8 不会再和旁边的 8 合
        g.load(intArrayOf(2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 0)
        assertTrue(g.move(Dir.RIGHT))
        assertEquals(4, g.cells[3]); assertEquals(4, g.score)
        assertEquals(2, g.cells.count { it != 0 })            // 合并后的 4 + 新生成的一个
        g.load(intArrayOf(2, 4, 2, 4, 4, 2, 4, 2, 2, 4, 2, 4, 4, 2, 4, 2), 0)
        assertTrue(g.over); assertFalse(g.move(Dir.LEFT))
    }

    @Test fun snakeMovesGrowsAndDies() {
        val s = SnakeGame(10, 5, java.util.Random(3))
        assertEquals(listOf(5 to 2, 4 to 2, 3 to 2), s.body.toList())
        s.placeFood(6 to 2)
        s.step()
        assertEquals(1, s.score); assertEquals(4, s.body.size); assertEquals(6 to 2, s.body.first())
        s.turn(Dir.LEFT)                 // 不能直接掉头
        s.step(); assertEquals(7 to 2, s.body.first())
        s.turnLeft(); s.step(); assertEquals(7 to 1, s.body.first())   // 向右走时左转 = 向上
        s.step(); s.step()
        assertTrue(s.over)               // 撞到上边
    }

    @Test fun tetrisRotatesClearsAndScores() {
        val t = Tetris(java.util.Random(7))
        // 所有方块转四次回到原样，每种都是 4 格
        for (type in 1..7) {
            assertEquals(4, t.shape(type, 0).size)
            assertEquals(t.shape(type, 0).toSet(), t.shape(type, 4).toSet())
        }
        // 最底下一行只差最右边一格：把一块竖着的 I 放进去，消掉一行
        for (x in 0 until 9) t.setCell(x, 19, 3)
        while (t.type != 1) t.hardDrop()            // 等到一块 I
        for (x in 0 until 10) for (y in 0 until 19) t.setCell(x, y, 0)
        for (x in 0 until 9) t.setCell(x, 19, 3)
        t.rotate()                                   // 竖起来
        while (t.move(1)) {}                         // 推到最右边
        val before = t.score
        t.hardDrop()
        assertEquals(1, t.lines)
        assertTrue(t.score - before >= 100)
        assertEquals(0, (0 until 9).count { t.cells[19 * 10 + it] == 3 })   // 被消掉的那一行没了
        assertTrue(t.gravityMs in 150..900)
    }

    @Test fun tetrisTopOutEndsGame() {
        val t = Tetris(java.util.Random(1))
        var n = 0
        while (!t.over && n < 200) { t.hardDrop(); n++ }
        assertTrue(t.over)
    }

    @Test fun minesweeperFirstClickSafeAndFloodFill() {
        repeat(20) { seed ->
            val m = Minesweeper(20, 7, 32, java.util.Random(seed.toLong()))
            m.reveal(3 * 20 + 10)
            assertFalse(m.lost)
            assertEquals(32, m.mine.count { it })
            assertTrue((m.neighbors(3 * 20 + 10) + (3 * 20 + 10)).none { m.mine[it] })
        }
        val m = Minesweeper(5, 3, 1)
        m.place(listOf(4))                            // 右上角一颗雷
        m.reveal(10)                                  // 左下角：空白，一路展开
        assertTrue(m.won)
        assertEquals(14, m.open.count { it })
        val lose = Minesweeper(5, 3, 1).apply { place(listOf(4)) }
        lose.reveal(4); assertTrue(lose.lost); assertEquals(4, lose.boom)
    }

    @Test fun minesweeperChord() {
        val m = Minesweeper(3, 3, 1)
        m.place(listOf(0))
        m.reveal(4)                                   // 中间是 1
        assertEquals(1, m.count(4)); assertTrue(m.open[4]); assertFalse(m.open[8])
        m.toggleFlag(0)
        m.reveal(4)                                   // 旗子数对上了：其余邻格自动点开
        assertTrue(m.won)
    }

    @Test fun sudokuGeneratorGivesUniquePuzzle() {
        val r = java.util.Random(42)
        val (puzzle, solution) = SudokuSolver.generate(r, 30)
        assertEquals(1, SudokuSolver.count(puzzle))
        assertTrue(puzzle.count { it != 0 } in 17..40)
        for (i in 0 until 81) if (puzzle[i] != 0) assertEquals(solution[i], puzzle[i])
        val s = Sudoku(puzzle, solution)
        val empty = (0 until 81).first { puzzle[it] == 0 }
        val wrong = (1..9).first { d -> (0 until 9).any { s.values[(empty / 9) * 9 + it] == d } }
        s.set(empty, wrong); assertTrue(s.conflict(empty))
        for (i in 0 until 81) s.set(i, solution[i])
        assertTrue(s.solved())
        s.set(0, 0); assertEquals(puzzle[0], s.values[0])   // 原题的数字改不了
    }

    @Test fun slidePuzzleShufflesSolvably() {
        val p = SlidePuzzle(4, java.util.Random(5))
        assertFalse(p.solved()); assertEquals(0, p.moves)
        // 可解性：逆序数 + 空格所在行（从下往上数）的奇偶性
        val t = p.tiles.filter { it != 0 }
        var inv = 0
        for (i in t.indices) for (j in i + 1 until t.size) if (t[i] > t[j]) inv++
        val blankRowFromBottom = 4 - p.tiles.indexOf(0) / 4
        assertEquals(1, (inv + blankRowFromBottom) % 2)
        val q = SlidePuzzle(3, java.util.Random(1))
        q.load(intArrayOf(1, 2, 3, 4, 5, 6, 7, 0, 8))
        assertTrue(q.slide(Dir.LEFT)); assertTrue(q.solved()); assertEquals(1, q.moves)
        assertFalse(q.slide(Dir.UP))                 // 空格在最下面，没有数字能往上滑进去
    }

    /** 推箱子求解器（按推的次数做广度优先搜索），证明每一关都能过。 */
    private fun sokobanSolvable(level: String): Boolean {
        val g = Sokoban(level)
        val w = g.w
        fun reach(p: Int, boxes: Set<Int>): Set<Int> {
            val seen = hashSetOf(p); val stack = arrayListOf(p)
            while (stack.isNotEmpty()) {
                val c = stack.removeAt(stack.size - 1)
                for (d in intArrayOf(1, -1, w, -w)) { val n = c + d; if (n !in g.walls && n !in boxes && seen.add(n)) stack.add(n) }
            }
            return seen
        }
        val start = g.boxes.toSet()
        val seen = HashSet<Pair<Int, Set<Int>>>()
        val q = ArrayDeque<Pair<Int, Set<Int>>>().apply { add(g.player to start) }
        while (q.isNotEmpty()) {
            val (p, b) = q.removeFirst()
            if (b == g.goals) return true
            val r = reach(p, b)
            if (!seen.add(r.minOrNull()!! to b)) continue
            for (box in b) for (d in intArrayOf(1, -1, w, -w)) {
                if (box - d !in r) continue
                val t = box + d
                if (t in g.walls || t in b) continue
                q.add(box to (b - box + t))
            }
            if (seen.size > 500_000) return false
        }
        return false
    }

    @Test fun everySokobanLevelIsSolvableAndFits() {
        for ((i, lv) in Sokoban.LEVELS.withIndex()) {
            val g = Sokoban(lv)
            assertEquals("第 ${i + 1} 关箱子和目标数不一样", g.goals.size, g.boxes.size)
            assertTrue("第 ${i + 1} 关太高", g.h <= 8)
            assertTrue("第 ${i + 1} 关解不开", sokobanSolvable(lv))
        }
    }

    @Test fun sokobanMovePushUndo() {
        val g = Sokoban(Sokoban.LEVELS[0])            // #@ $ .#
        assertTrue(g.move(Dir.RIGHT)); assertEquals(0, g.pushes)
        assertTrue(g.move(Dir.RIGHT)); assertEquals(1, g.pushes)
        assertFalse(g.solved())
        assertTrue(g.move(Dir.RIGHT)); assertTrue(g.solved())
        assertFalse(g.move(Dir.RIGHT))               // 箱子后面是墙，推不动
        assertTrue(g.undo()); assertFalse(g.solved()); assertEquals(2, g.moves)
    }

    @Test fun gomokuDetectsFiveAndAiBlocks() {
        val g = Gomoku(java.util.Random(0))
        // 玩家横着连四个，电脑必须去堵
        for (x in 3..6) { g.board[7 * 15 + x] = 1; g.history.add(7 * 15 + x) }
        val ai = g.aiMove()
        assertTrue(ai == 7 * 15 + 2 || ai == 7 * 15 + 7)
        g.board[7 * 15 + 7] = 1
        assertTrue(g.fiveAt(7 * 15 + 7))
        // 电脑自己有四个时，直接连五
        val h = Gomoku(java.util.Random(0))
        for (y in 2..5) { h.board[y * 15 + 10] = 2; h.history.add(y * 15 + 10) }
        h.board[0] = 1; h.history.add(0)
        val win = h.aiMove()
        assertTrue(win == 1 * 15 + 10 || win == 6 * 15 + 10)
    }

    @Test fun gomokuPlayAndUndo() {
        val g = Gomoku(java.util.Random(0))
        assertTrue(g.play(7 * 15 + 7))
        assertEquals(2, g.history.size)              // 电脑马上应了一步
        assertFalse(g.play(7 * 15 + 7))              // 同一格不能再下
        assertTrue(g.undo()); assertEquals(0, g.history.size)
    }

    @Test fun memoryPairsMatchAndHide() {
        val m = MemoryGame(4, 2, java.util.Random(3))
        val first = 0
        val pair = (1 until 8).first { m.cards[it] == m.cards[first] }
        val other = (1 until 8).first { m.cards[it] != m.cards[first] }
        m.flip(first); m.flip(other)
        assertTrue(m.mismatchShowing()); m.hide(); assertTrue(m.up.isEmpty())
        m.flip(first); m.flip(pair)
        assertTrue(m.matched[first] && m.matched[pair]); assertEquals(2, m.moves)
        assertFalse(m.flip(first))                   // 配对过的不能再翻
        for (i in 0 until 8) if (!m.matched[i]) {
            val j = (0 until 8).first { it != i && m.cards[it] == m.cards[i] }
            m.flip(i); m.flip(j)
        }
        assertTrue(m.won())
    }
}

class PomodoroTest {
    private val set = PomoSettings(focusMin = 25, shortMin = 5, longMin = 15, longEvery = 4)

    @Test fun startPauseResume() {
        var s = PomoState()
        assertFalse(PomoLogic.started(s))
        assertEquals(25 * 60_000L, PomoLogic.remaining(s, set, 0))
        s = PomoLogic.start(s, set, 1000)
        assertEquals(1000 + 25 * 60_000L, s.endAt)
        assertEquals(20 * 60_000L, PomoLogic.remaining(s, set, 1000 + 5 * 60_000L))
        assertEquals(0.2, PomoLogic.progress(s, set, 1000 + 5 * 60_000L), 1e-9)
        s = PomoLogic.pause(s, set, 1000 + 5 * 60_000L)
        assertFalse(s.running)
        assertTrue(PomoLogic.started(s))
        assertEquals(20 * 60_000L, PomoLogic.remaining(s, set, 99_999_999))    // 暂停时不走
        s = PomoLogic.start(s, set, 10_000_000)
        assertEquals(10_000_000 + 20 * 60_000L, s.endAt)
        assertEquals(0L, PomoLogic.remaining(s, set, 99_999_999))
        assertEquals(25 * 60_000L, PomoLogic.remaining(PomoLogic.reset(s), set, 0))
    }

    @Test fun longBreakEveryFourFocusSessions() {
        var s = PomoState()
        val phases = ArrayList<PomoPhase>()
        repeat(8) { s = PomoLogic.next(s, set); phases.add(s.phase) }
        assertEquals(listOf(PomoPhase.SHORT, PomoPhase.FOCUS, PomoPhase.SHORT, PomoPhase.FOCUS, PomoPhase.SHORT, PomoPhase.FOCUS,
            PomoPhase.LONG, PomoPhase.FOCUS), phases)
        assertEquals(4, s.done)
        assertFalse(s.running)
        assertEquals("番茄钟 · 专注结束" to "休息 15 分钟吧", PomoLogic.alert(PomoPhase.FOCUS, PomoPhase.LONG, set))
        assertEquals("番茄钟 · 休息结束" to "开始下一个 25 分钟专注", PomoLogic.alert(PomoPhase.SHORT, PomoPhase.FOCUS, set))
        assertEquals("25:00", PomoLogic.clock(25 * 60_000L))
        assertEquals("00:01", PomoLogic.clock(1))           // 最后不到一秒也显示 1 秒
        assertEquals("00:00", PomoLogic.clock(0))
    }
}

class PartyTest {
    @Test fun deckNeverRepeatsWithinARound() {
        val d = Deck(listOf("a", "b", "c", "d"), java.util.Random(1))
        val round1 = List(4) { d.draw()!! }
        assertEquals(setOf("a", "b", "c", "d"), round1.toSet())
        assertEquals(0, d.left)
        val round2 = List(4) { d.draw()!! }
        assertEquals(setOf("a", "b", "c", "d"), round2.toSet())
        assertNull(Deck(emptyList<String>(), java.util.Random()).draw())
    }

    @Test fun diceAndLots() {
        val r = java.util.Random(7)
        repeat(200) { val v = PartyLogic.roll(3, r); assertEquals(3, v.size); assertTrue(v.all { it in 1..6 }) }
        assertEquals(6, PartyLogic.roll(99, r).size)
        for (f in 1..6) {
            val p = PartyLogic.pips(f)
            assertEquals(f, p.size)
            assertEquals(f, p.toSet().size)
            assertTrue(p.all { (a, b) -> a in 0..2 && b in 0..2 })
        }
        assertEquals(listOf("张三", "李四"), PartyLogic.parseLots("  张三 \n\n李四\n   "))
        assertEquals(30, PartyLogic.TRUTHS.size); assertEquals(30, PartyLogic.DARES.size)
        assertEquals(30, PartyLogic.TRUTHS.toSet().size); assertEquals(30, PartyLogic.DARES.toSet().size)
    }
}

class PixelClockTest {
    /** 记录所有画的格子，检查都在给定的区域里。 */
    private fun rects(style: Int, w: Int, h: Int, secs: Int?, prev: String? = null): List<IntArray> {
        val out = ArrayList<IntArray>()
        PixelClock.draw(style, 23, 59, secs, 10, 20, w, h, { x, y, rw, rh, l -> out.add(intArrayOf(x, y, rw, rh, l)) }, prev = prev)
        for (r in out) {
            assertTrue("style $style ${r.toList()} in $w×$h", r[0] >= 10 && r[1] >= 20 && r[0] + r[2] <= 10 + w && r[1] + r[3] <= 20 + h && r[2] > 0 && r[3] > 0)
        }
        return out
    }

    @Test fun facesFitFullScreenAndCards() {
        for (style in 0..2) for ((w, h) in listOf(440 to 134, 218 to 128, 440 to 128, 120 to 60)) {
            for (secs in listOf(null, 7)) assertTrue(rects(style, w, h, secs).isNotEmpty())
        }
        // 翻到一半：上下两半数字不同，也不出界
        assertTrue(rects(PixelClock.FLIP, 440, 134, null, prev = "2358").isNotEmpty())
    }

    @Test fun binaryUsesBcdAndSegmentsMatchDigits() {
        assertEquals(listOf(2 to 2, 3 to 4, 5 to 3, 9 to 4, 0 to 3, 7 to 4), PixelClock.bcd(23, 59, 7))
        assertEquals(4, PixelClock.bcd(9, 5, null).size)
        // 数码管：8 七段全亮，1 只有 b、c
        assertEquals(7, Integer.bitCount(PixelClock.SEGMENTS[8]))
        assertEquals(0b110, PixelClock.SEGMENTS[1])
        // 不画底影时，亮的段数就是画的矩形数
        for (d in 0..9) {
            var n = 0
            PixelClock.segmentDigit(d, 0, 0, 3, { _, _, _, _, _ -> n++ }, ghost = false)
            assertEquals(Integer.bitCount(PixelClock.SEGMENTS[d]), n)
        }
        assertEquals(PixelClock.SEGMENT, PixelClock.styleOf(" SEG "))
        assertEquals(PixelClock.FLIP, PixelClock.styleOf("???"))
        assertTrue(PixelClock.DIGITS_5X7.all { d -> d.size == 7 && d.all { it.length == 5 } })
        assertTrue(PixelClock.DIGITS_3X5.all { d -> d.size == 5 && d.all { it.length == 3 } })
    }

    @Test fun ringStartsAtTopClockwise() {
        val r = PixelClock.ring(60, 100, 80, 50)
        assertEquals(60, r.size)
        assertEquals(100 to 30, r[0])        // 正上方
        assertEquals(150 to 80, r[15])       // 3 点钟方向
        assertEquals(100 to 130, r[30])
        assertEquals(50 to 80, r[45])
    }

    @Test fun liveCards() {
        assertEquals(listOf(CardRow("07:05", face = 2)), CardContent.face(2, 7, 5))
        val p = CardContent.pomodoro("专注", false, 90_000, 0.94, 3)
        assertEquals(listOf("番茄 · 专注 01:30 暂停", "■■■■■", "今天完成 3 个"), p.map { it.plain() })
        // 新卡片都在默认列表里
        assertTrue(CardConfig.defaults().map { it.type }.containsAll(listOf(CardType.FACE, CardType.POMODORO)))
    }
}

class MicTest {
    /** 按眼镜的格式打包：52 03 [长度] [计数] [单元数]，每单元 [字节数] + opus_demo 记录 + 固定的 3 字节小包。 */
    private fun glassesNotification(frames: List<Pair<ByteArray, Int>>, seq: Int): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        for ((pkt, range) in frames) {
            val rec = java.nio.ByteBuffer.allocate(8 + pkt.size + 11)
            rec.putInt(pkt.size).putInt(range).put(pkt)
            rec.put(byteArrayOf(0, 0, 0, 3, 1, 0, 0, 0, 0xB0.toByte(), 0xFF.toByte(), 0xFE.toByte()))
            body.write(rec.capacity()); body.write(rec.array())
        }
        val b = body.toByteArray()
        val h = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put(0x52).put(0x03).putShort((b.size + 4).toShort()).putShort(seq.toShort()).putShort(frames.size.toShort())
        return h.array() + b
    }

    private fun encodeTone(seconds: Double, amp: Double): List<Pair<ByteArray, Int>> {
        val enc = io.github.jaredmdobson.concentus.OpusEncoder(16000, 1, io.github.jaredmdobson.concentus.OpusApplication.OPUS_APPLICATION_RESTRICTED_LOWDELAY)
        enc.setBitrate(16000)
        val out = ArrayList<Pair<ByteArray, Int>>()
        val pcm = ShortArray(160); val buf = ByteArray(400); var t = 0
        repeat((seconds * 100).toInt()) {
            for (i in 0 until 160) { pcm[i] = (amp * 32767 * Math.sin(2 * Math.PI * 440 * t / 16000.0)).toInt().toShort(); t++ }
            val n = enc.encode(pcm, 0, 160, buf, 0, buf.size)
            out.add(buf.copyOf(n) to enc.finalRange)
        }
        return out
    }

    @Test fun parsesGlassesPacketsAndDecodesBitExact() {
        val frames = encodeTone(1.0, 0.5)
        assertEquals(0xB0, frames[0].first[0].toInt() and 0xFF)   // CELT 宽带 10 ms 单声道，和眼镜一样
        val s = MicSession()
        frames.chunked(9).forEachIndexed { i, c ->
            val parsed = NimoMic.parse(glassesNotification(c, i * 9))
            assertEquals(c.size, parsed.size)
            assertArrayEquals(c[0].first, parsed[0].packet)
            s.feed(parsed)
        }
        assertEquals(100, s.frames); assertEquals(1.0, s.audioSeconds, 1e-9)
        assertEquals(0, s.mismatched); assertEquals(0, s.decodeErrors)
        // 0.5 幅度的正弦波 ≈ -9 dBFS
        assertEquals(-9f, s.levels.last(), 2f)
    }

    @Test fun ignoresOtherData() {
        assertTrue(NimoMic.parse(byteArrayOf(0x52, 0x01, 0, 0)).isEmpty())
        assertTrue(NimoMic.parse(ByteArray(3)).isEmpty())
        // 截断的包：只取完整的单元，不抛异常
        val n = glassesNotification(encodeTone(0.09, 0.3), 0)
        val half = NimoMic.parse(n.copyOf(n.size / 2))
        assertTrue(half.size in 1..8)
    }

    @Test fun oggPagesAreValid() {
        val bos = java.io.ByteArrayOutputStream()
        val w = OggOpusWriter(bos)
        val frames = encodeTone(1.23, 0.3)
        frames.forEach { w.add(it.first) }
        w.close()
        val b = bos.toByteArray()
        var off = 0; var pages = 0; var lastGranule = -1L; var packets = 0; var flagsLast = 0
        while (off < b.size) {
            assertEquals("OggS", String(b, off, 4))
            val segs = b[off + 26].toInt() and 0xFF
            val bodyLen = (0 until segs).sumOf { b[off + 27 + it].toInt() and 0xFF }
            val total = 27 + segs + bodyLen
            val page = b.copyOfRange(off, off + total)
            val crc = java.nio.ByteBuffer.wrap(page, 22, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            for (k in 22 until 26) page[k] = 0
            assertEquals(crc, OggOpusWriter.crc(0, page))
            lastGranule = java.nio.ByteBuffer.wrap(b, off + 6, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
            if (pages >= 2) packets += (0 until segs).count { (b[off + 27 + it].toInt() and 0xFF) < 255 }
            flagsLast = b[off + 5].toInt()
            off += total; pages++
        }
        assertEquals(frames.size, packets)
        assertEquals(frames.size * 480L, lastGranule)   // 48 kHz 下的采样数
        assertEquals(4, flagsLast)                       // 最后一页带结束标记
        assertEquals("OpusHead", String(b, 28, 8))
    }
}

class NoiseReducerTest {
    @Test fun fftWorks() {
        // 冲激的频谱全是 1；正变换再"共轭-正变换-共轭/N"回到原信号
        val re = DoubleArray(16).also { it[0] = 1.0 }; val im = DoubleArray(16)
        NoiseReducer.fft(re, im)
        assertTrue(re.all { Math.abs(it - 1) < 1e-9 } && im.all { Math.abs(it) < 1e-9 })
        val x = DoubleArray(64) { Math.sin(it * 0.7) + 0.3 * Math.cos(it * 2.1) }
        val r = x.copyOf(); val i = DoubleArray(64)
        NoiseReducer.fft(r, i); for (k in i.indices) i[k] = -i[k]; NoiseReducer.fft(r, i)
        for (k in x.indices) assertEquals(x[k], r[k] / 64, 1e-9)
        // 单音落在对应的频点上
        val s = DoubleArray(512) { Math.cos(2 * Math.PI * 32 * it / 512) }; val si = DoubleArray(512)
        NoiseReducer.fft(s, si)
        assertEquals(256.0, Math.hypot(s[32], si[32]), 1e-6)
    }

    /** 模拟说话：0.5 秒有声（200 Hz 基频的谐波）、0.5 秒停顿，交替；加上很大的白噪声。 */
    private fun speechLike(seconds: Int, noiseAmp: Double, seed: Long = 1): Pair<ShortArray, BooleanArray> {
        val rnd = java.util.Random(seed)
        val n = 16000 * seconds
        val on = BooleanArray(n) { (it / 8000) % 2 == 1 }
        val pcm = ShortArray(n) { t ->
            var v = 0.0
            if (on[t]) for (h in 1..8) v += 2500.0 / h * Math.sin(2 * Math.PI * 200 * h * t / 16000.0)
            (v + rnd.nextGaussian() * noiseAmp).toInt().coerceIn(-32768, 32767).toShort()
        }
        return pcm to on
    }

    private fun db(x: ShortArray, mask: BooleanArray, want: Boolean): Double {
        var s = 0.0; var c = 0
        for (i in 800 until x.size - 800) if (mask[i] == want && mask[i - 800] == want && mask[i + 800] == want) { s += x[i].toDouble() * x[i]; c++ }
        return 10 * Math.log10(s / c + 1e-9)
    }

    @Test fun transparentWithoutReduction() {
        // 增益下限 1（什么都不压）、不调音量：除了头尾半帧，和原来一样
        val (pcm, _) = speechLike(2, 1000.0)
        val out = NoiseReducer.process(pcm, floor = 1.0, normalize = false)
        var maxErr = 0
        for (i in 512 until pcm.size - 512) maxErr = maxOf(maxErr, Math.abs(out[i] - pcm[i]))
        assertTrue("最大误差 $maxErr", maxErr <= 2)
    }

    @Test fun reducesNoiseKeepsSpeech() {
        val (pcm, on) = speechLike(6, 1500.0)
        val out = NoiseReducer.process(pcm, floor = 0.1, normalize = false)
        val clean = speechLike(6, 0.0).first            // 同样的"人声"，不加噪声
        val noiseDrop = db(pcm, on, false) - db(out, on, false)
        val speechDrop = db(clean, on, true) - db(out, on, true)   // 和干净的人声比（有声段里的噪声本来就该去掉）
        println("降噪：停顿里的底噪降了 %.1f dB，人声只小了 %.1f dB".format(noiseDrop, speechDrop))
        assertTrue(noiseDrop > 15)
        assertTrue(speechDrop < 3)
        // 调音量：峰值拉到 -1 dBFS（29160）附近，但最多放大 4 倍（很小声的不会把底噪也放得很大）
        fun peak(x: ShortArray) = x.maxOf { Math.abs(it.toInt()) }
        val quiet = ShortArray(pcm.size) { (pcm[it] / 20).toShort() }
        val quietPeak = peak(NoiseReducer.process(quiet, floor = 0.1, normalize = false))
        assertEquals(quietPeak * 4.0, peak(NoiseReducer.process(quiet, floor = 0.1)).toDouble(), 8.0)
        val loudClean = ShortArray(clean.size) { (clean[it] * 3).toShort() }      // 峰值约 20000，只需放大 1.5 倍
        assertEquals(29160.0, peak(NoiseReducer.process(loudClean, floor = 0.1)).toDouble(), 50.0)
        assertEquals(10, NoiseReducer.process(ShortArray(10) { 5 }).size)   // 太短的原样返回
    }
}

class NotifyDedupTest {
    @Test fun sameMessageOnceButRepeatedWordsStillShow() {
        NotifyDedup.clear()
        val t1 = 1_727_000_000_000L
        assertFalse(NotifyDedup.seenBefore("com.tencent.mm", "晚上吃什么", t1, 1_000))
        // 同一条消息又送来一次（时间戳一样）：挡掉；带了"[2条]"前缀也是同一条
        assertTrue(NotifyDedup.seenBefore("com.tencent.mm", "晚上吃什么", t1, 3_000))
        assertTrue(NotifyDedup.seenBefore("com.tencent.mm", "[2条] 晚上吃什么", t1, 4_000))
        // 别人又发了一句一模一样的话：新消息，时间戳不同，照常弹（哪怕只隔 1 秒）
        assertFalse(NotifyDedup.seenBefore("com.tencent.mm", "晚上吃什么", t1 + 1_000, 5_000))
        assertFalse(NotifyDedup.seenBefore("com.tencent.mm", "好的", t1 + 2_000, 6_000))
        assertFalse(NotifyDedup.seenBefore("com.tencent.mm", "好的", t1 + 3_000, 7_000))
        // 别的 app 同样的话、同样的时间：也照常弹
        assertFalse(NotifyDedup.seenBefore("jp.naver.line.android", "晚上吃什么", t1, 8_000))
        // 同一个 key 内容没变的刷新
        NotifyDedup.remember("k1", "张三|在吗"); assertTrue(NotifyDedup.sameAsLast("k1", "张三|在吗")); assertFalse(NotifyDedup.sameAsLast("k1", "张三|在吗？"))
        assertEquals("com.tencent.mm|5|好的", NotifyDedup.messageKey("com.tencent.mm", "[12条]好的", 5))
        NotifyDedup.clear()
    }
}

class NotifyIconsTest {
    @Test fun titleOnlyDropsAppNameWhenIconKnown() {
        // 编号没试出来（眼镜显示「短信」）：标题带上 app 名，看得出是哪个 app
        assertEquals("微信 · 张三", NotifyIcons.title("微信", "张三", known = false))
        // 编号试出来了（眼镜自己显示「微信」）：标题只放人名，避免「微信 微信 张三」
        assertEquals("张三", NotifyIcons.title("微信", "张三", known = true))
        assertEquals("微信", NotifyIcons.title("微信", "", known = true))
        assertEquals("微信", NotifyIcons.title("微信", "微信", known = false))
        assertEquals(null, NotifyIcons.appIdOf(null))
        // 编号表：短信 0、微信 16、飞书 17、钉钉 18、企业微信 19、抖音 20；QQ 不在表里
        assertEquals(16, NotifyIcons.appIdOf("com.tencent.mm"))
        assertEquals(0, NotifyIcons.appIdOf("com.google.android.apps.messaging"))
        assertEquals(19, NotifyIcons.appIdOf("com.tencent.wework"))
        assertEquals(null, NotifyIcons.appIdOf("com.tencent.mobileqq"))
    }
}

class NotifyTextTest {
    @Test fun callAlertOnlyWhenRinging() {
        // 新样式：1 来电才提醒，2 通话中、3 来电筛选不提醒；旧样式看有没有全屏界面
        assertTrue(NotifyCall.isIncoming(1, false)); assertFalse(NotifyCall.isIncoming(2, true)); assertFalse(NotifyCall.isIncoming(3, true))
        assertTrue(NotifyCall.isIncoming(0, true)); assertFalse(NotifyCall.isIncoming(0, false))
        // 同一通来电（响铃时通知会反复刷新）60 秒内只提醒一次
        assertTrue(NotifyCall.firstAlert("call1", 1_000)); assertFalse(NotifyCall.firstAlert("call1", 5_000))
        assertTrue(NotifyCall.firstAlert("call2", 5_000)); assertTrue(NotifyCall.firstAlert("call1", 70_000))
    }

    @Test fun unreadCountOnlyStrippedForSenderCheck() {
        assertEquals("唐如一号", NotifyText.cleanTitle("唐如一号(3条新消息)"))
        assertEquals("项目群", NotifyText.cleanTitle("项目群（12条新消息）"))
        assertEquals("张三", NotifyText.cleanTitle("张三"))
        assertEquals("(3条新消息)", NotifyText.cleanTitle("(3条新消息)"))   // 只剩这个时不删成空
        // 标题带未读数的单聊：发送人就是对方，正文不加“张三：”
        assertEquals("明天见", NotifyText.pick("", "", "明天见", "张三", NotifyText.cleanTitle("张三(3条新消息)")))
    }

    @Test fun picksNewestMessage() {
        // 消息列表里有最新一条：用它；群聊带上发送人
        assertEquals("明天见", NotifyText.pick("2 条新消息", "", "明天见", "张三", "张三"))
        assertEquals("李四：明天见", NotifyText.pick("2 条新消息", "", "明天见", "李四", "项目群"))
        assertEquals("李四：明天见", NotifyText.pick("", "", "李四：明天见", "李四", "项目群"))
        // 没有消息列表：长文是这一条被截断的完整版才用长文
        assertEquals("今晚七点在老地方见，别忘了带书", NotifyText.pick("今晚七点在老地方见…", "今晚七点在老地方见，别忘了带书", null, null, "张三"))
        // 长文是好几条累积的（开头不是这一条）：用正文（最新的一条）
        assertEquals("第三条", NotifyText.pick("第三条", "第一条\n第二条\n第三条", null, null, "张三"))
        assertEquals("只有长文", NotifyText.pick("", "只有长文", null, null, "x"))
        assertEquals("只有正文", NotifyText.pick("只有正文", "", null, null, "x"))
    }
}

class NotifySlotsTest {
    @Test fun slotsByHour() {
        // 工作 = 周一到周五 9:00～17:59，其余是休息
        assertEquals(NotifySlots.OFF, NotifySlots.current(8)); assertEquals(NotifySlots.WORK, NotifySlots.current(9))
        assertEquals(NotifySlots.WORK, NotifySlots.current(17)); assertEquals(NotifySlots.OFF, NotifySlots.current(18))
        assertEquals(NotifySlots.OFF, NotifySlots.current(0))
        // 周末全天算休息
        assertEquals(NotifySlots.OFF, NotifySlots.current(10, weekend = true))
        fun cal(y: Int, m: Int, d: Int, h: Int) = java.util.Calendar.getInstance().apply { set(y, m - 1, d, h, 30) }
        assertEquals(NotifySlots.WORK, NotifySlots.current(cal(2026, 9, 29, 10)))   // 周二 10 点
        assertEquals(NotifySlots.OFF, NotifySlots.current(cal(2026, 10, 3, 10)))    // 周六 10 点
        assertEquals(NotifySlots.OFF, NotifySlots.current(cal(2026, 10, 4, 15)))    // 周日 15 点
        assertEquals(NotifySlots.OFF, NotifySlots.current(cal(2026, 9, 29, 20)))    // 周二 20 点
        val work = NotifySlots.WORK; val off = NotifySlots.OFF
        assertTrue(NotifySlots.allows(NotifySlots.ALL, work) && NotifySlots.allows(NotifySlots.ALL, off))
        assertTrue(NotifySlots.allows(work, work)); assertFalse(NotifySlots.allows(work, off))
        assertTrue(NotifySlots.allows(off, off)); assertFalse(NotifySlots.allows(off, work))
        assertFalse(NotifySlots.allows(0, work)); assertFalse(NotifySlots.allows(0, off))      // 什么都不选 = 不通知
        // 点按钮：点别的换成它，点已选的取消
        assertEquals(NotifySlots.WORK, NotifySlots.pick(NotifySlots.ALL, NotifySlots.WORK))
        assertEquals(0, NotifySlots.pick(NotifySlots.WORK, NotifySlots.WORK))
        assertEquals(NotifySlots.ALL, NotifySlots.pick(0, NotifySlots.ALL))
    }
}

class SightsTest {
    @Test fun amapParse() {
        // 高德 v5 周边搜索：GCJ-02 坐标；世界遗产、国家级、省级景点算"有名"；城市广场这类不在类型里
        val json = """{"status":"1","info":"OK","pois":[
            {"name":"故宫博物院","id":"B000A8UIN8","location":"116.397029,39.917839","typecode":"110201","cityname":"北京市"},
            {"name":"中山公园","id":"B000A7BD6C","location":"116.393,39.912","typecode":"110101"},
            {"name":"远处的景点","id":"X1","location":"116.60,39.95","typecode":"110202"}]}"""
        val r = Sights.parseAmap(json, 39.9151, 116.3972)
        assertEquals(listOf("故宫博物院", "中山公园"), r.map { it.name })
        assertEquals("世界遗产", r[0].kind); assertTrue(r[0].famous); assertFalse(r[1].famous)
        assertEquals("aB000A8UIN8", r[0].id); assertEquals("北京", Sights.cityShort(r[0].city))
        assertEquals("香港", Sights.cityShort("香港特别行政区"))
        // 出错时带上高德的原因（比如 Key 不对）
        try { Sights.parseAmap("""{"status":"0","info":"INVALID_USER_KEY","infocode":"10001"}""", 0.0, 0.0); fail() }
        catch (e: java.io.IOException) { assertTrue(e.message!!.contains("INVALID_USER_KEY")) }
    }

    @Test fun googleParse() {
        val json = """{"places":[
            {"id":"ChIJ1","displayName":{"text":"浅草寺","languageCode":"zh"},"location":{"latitude":35.7148,"longitude":139.7967},
             "primaryTypeDisplayName":{"text":"佛寺"},"editorialSummary":{"text":"东京最古老的寺庙。"},"userRatingCount":80000},
            {"id":"ChIJ2","displayName":{"text":"小公园"},"location":{"latitude":35.7120,"longitude":139.7950},"userRatingCount":12}]}"""
        val r = Sights.parseGoogle(json, 35.7120, 139.7960)
        assertEquals(listOf("小公园", "浅草寺"), r.map { it.name })
        val senso = r.first { it.name == "浅草寺" }
        assertTrue(senso.famous); assertEquals("佛寺", senso.kind); assertEquals("东京最古老的寺庙。", senso.summary)
        assertEquals("景点", r[0].kind); assertFalse(r[0].famous)
        // 有名的排前面
        assertEquals("浅草寺", Sights.pick(r, emptySet())[0].name)
        assertTrue(Sights.googleBody(35.0, 139.0).contains("\"radius\":3000"))
    }

    @Test fun baikeParse() {
        assertEquals(null, Sights.parseBaike("{}"))
        val a = Sights.parseBaike("""{"title":"浅草寺","abstract":"浅草寺（日文：浅草寺，英文：Sensoji Temple），位于日本东京都台东区，是东京都内历史最悠久的佛教寺院..."}""")!!
        assertFalse(a.endsWith("..."))
        assertEquals("浅草寺，位于日本东京都台东区，是东京都内历史最悠久的佛教寺院", Sights.intro(a, "", "寺庙"))
        // 维基百科的消歧义页不当介绍
        assertEquals(null, Sights.parseSummary("""{"type":"disambiguation","extract":"中山公园可以指："}"""))
    }

    @Test fun gcjConvert() {
        // 天安门：WGS-84 → GCJ-02 大约往东北偏 600 多米；国外不变
        val (la, lo) = Sights.wgsToGcj(39.90742, 116.39139)
        val d = Sights.distance(39.90742, 116.39139, la, lo)
        assertTrue("偏移 $d 米", d in 400..800)
        assertTrue(lo > 116.39139 && la > 39.90742)
        assertEquals(35.7148 to 139.7967, Sights.wgsToGcj(35.7148, 139.7967))
    }

    @Test fun wikiAndIntro() {
        assertEquals("浅草寺是东京都内最古老的寺院。", Sights.parseSummary("""{"title":"浅草寺","extract":"浅草寺是东京都内最古老的寺院。"}"""))
        assertNull(Sights.parseSummary("""{"title":"x","extract":""}"""))
        // 去掉括号里的读音、外文名
        val long = "浅草寺（日语：浅草寺／せんそうじ Sensō-ji）是位于日本东京都台东区浅草二丁目的佛教寺院，山号为金龙山，本尊为圣观音。" +
            "该寺是东京都内最古老的寺院，相传建于628年。寺前的雷门和仲见世商店街是东京最著名的观光地之一，每年吸引大量游客前来参拜和观光。"
        val t = Sights.intro(long, "", "寺庙")
        assertTrue(t.startsWith("浅草寺是位于日本东京都台东区") && t.endsWith("观光。"))
        assertFalse(t.contains("せんそうじ"))
        // 裁字数：句号结尾能占到 85% 以上就停在句号，否则填满加省略号
        assertEquals("一".repeat(80) + "。", Sights.cut("一".repeat(80) + "。" + "二".repeat(50), 85))
        val filled = Sights.cut("一".repeat(30) + "。" + "二".repeat(100), 85)
        assertEquals(85, filled.length); assertTrue(filled.endsWith("二…"))
        assertEquals("短的不动。", Sights.cut("短的不动。", 85))
        assertEquals("浅草寺是寺院。", Sights.parseExtracts("""{"query":{"pages":{"123":{"title":"浅草寺","extract":" 浅草寺是寺院。 "}}}}"""))
        assertNull(Sights.parseExtracts("""{"batchcomplete":""}"""))
        // 没摘要用 Google 的简介，再没有就说类型
        assertEquals("位于东京都台东区的寺院。", Sights.intro(null, "位于东京都台东区的寺院", "寺庙"))
        assertEquals("博物馆", Sights.intro(null, "", "博物馆"))
        assertEquals("800 米", Sights.distanceText(796)); assertEquals("1.3 公里", Sights.distanceText(1320))
    }
}

class UsQuoteTest {
    private fun ny(s: String) = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("America/New_York") }.parse(s)!!.time

    @Test fun sessions() {
        assertEquals("盘前", UsQuote.session(ny("2026-09-29 04:00")))
        assertEquals("盘前", UsQuote.session(ny("2026-09-29 09:29")))
        assertEquals(null, UsQuote.session(ny("2026-09-29 09:30")))
        assertEquals(null, UsQuote.session(ny("2026-09-29 15:59")))
        assertEquals("盘后", UsQuote.session(ny("2026-09-29 16:00")))
        assertEquals("夜盘", UsQuote.session(ny("2026-09-29 20:00")))
        assertEquals("夜盘", UsQuote.session(ny("2026-09-30 03:59")))
        assertEquals("盘后", UsQuote.session(ny("2026-10-02 19:00")))   // 周五盘后
        assertTrue(UsQuote.isUs("NVDA")); assertTrue(UsQuote.isUs("BRK-B"))
        assertFalse(UsQuote.isUs("7203.T")); assertFalse(UsQuote.isUs("^GSPC")); assertFalse(UsQuote.isUs("600519.SS"))
    }

    @Test fun webull() {
        // 盘前（实际抓到的一条）：最新成交 13:21 UTC = 纽约 9:21，比昨天收盘那笔新 → 显示盘前价，涨跌相对收盘价
        val pre = """{"close":"228.86","changeRatio":"0.0168","pPrice":"230.44","pChRatio":"0.0069",
            "mkTradeTime":"2026-09-28T20:00:01.085+0000","tradeTime":"2026-09-29T13:21:47.878+0000","overnight":0}"""
        val q = UsQuote.parseWebull("NVDA", pre)!!
        assertEquals(230.44, q.price!!, 1e-9); assertEquals(0.69, q.changePct!!, 1e-9); assertEquals("盘前", q.session)
        // 正常时段：最新成交就是正常时段那笔 → 显示现价
        val reg = """{"close":"231.10","changeRatio":"0.0098","pPrice":"230.44","pChRatio":"0.0069",
            "mkTradeTime":"2026-09-29T15:00:00.000+0000","tradeTime":"2026-09-29T15:00:00.000+0000"}"""
        val r = UsQuote.parseWebull("NVDA", reg)!!
        assertEquals(231.10, r.price!!, 1e-9); assertEquals(null, r.session)
        // 夜盘：纽约 22:00 的成交
        val night = """{"close":"231.10","changeRatio":"0.0098","pPrice":"232.00","pChRatio":"0.0039",
            "mkTradeTime":"2026-09-29T20:00:00.000+0000","tradeTime":"2026-09-30T02:00:00.000+0000","overnight":1}"""
        assertEquals("夜盘", UsQuote.parseWebull("NVDA", night)!!.session)
        // 在眼镜卡片上：盘前盘后夜盘画月亮，正常时段画太阳，不是美股不画
        assertEquals("230.44", CardContent.stocks(listOf(q))[0].mid)
        assertEquals("moon", CardContent.stocks(listOf(q))[0].midIcon)
        assertEquals("sun", CardContent.stocks(listOf(r))[0].midIcon)
        assertEquals("", CardContent.stocks(listOf(Quote("7203.T", 2920.0, 1.0)))[0].midIcon)
        assertEquals(913257561L, UsQuote.parseTickerId("NVDA", """{"data":[{"tickerId":913257561,"regionCode":"US","symbol":"NVDA"}]}"""))
        assertEquals(916040668L, UsQuote.parseTickerId("BRK-B", """{"data":[{"tickerId":7,"regionCode":"US","symbol":"BRKC"},{"tickerId":916040668,"regionCode":"US","symbol":"BRK B","disSymbol":"BRK-B"}]}"""))
        assertEquals("BRK B", UsQuote.searchKey("BRK-B"))
    }

    @Test fun robinhood() {
        // 夜盘（实际抓到的一条）：纽约 23:20 的 Blue Ocean 成交，比正常时段最后一笔新 → 夜盘价，涨跌相对当天收盘价
        val night = """{"results":[{"last_trade_price":"228.290000","venue_last_trade_time":"2026-09-30T19:59:59.998017891Z",
            "last_non_reg_trade_price":"230.070000","venue_last_non_reg_trade_time":"2026-10-01T03:20:55.795Z",
            "previous_close":"228.380000","symbol":"NVDA","last_non_reg_trade_price_source":"boats"}]}"""
        val q = UsQuote.parseRobinhood("NVDA", night)!!
        assertEquals(230.07, q.price!!, 1e-9); assertEquals("夜盘", q.session); assertTrue(q.us)
        assertEquals((230.07 / 228.38 - 1) * 100, q.changePct!!, 1e-9)
        // 正常时段：最后一笔就是正常时段的 → 现价、没有时段
        val reg = """{"results":[{"last_trade_price":"231.500000","venue_last_trade_time":"2026-10-01T15:00:00.123456789Z",
            "last_non_reg_trade_price":"229.900000","venue_last_non_reg_trade_time":"2026-10-01T13:29:00Z","previous_close":"228.380000"}]}"""
        val r = UsQuote.parseRobinhood("NVDA", reg)!!
        assertEquals(231.5, r.price!!, 1e-9); assertEquals(null, r.session)
        // 盘前
        val pre = reg.replace("2026-10-01T15:00:00.123456789Z", "2026-09-30T19:59:59Z")
        assertEquals("盘前", UsQuote.parseRobinhood("NVDA", pre)!!.session)
        assertNull(UsQuote.parseRobinhood("XXXX", """{"results":[null]}"""))
        assertEquals("BRK.B", UsQuote.robinhoodSymbol("BRK-B"))
    }
}

class ManualTest {
    @Test fun topicsAreComplete() {
        val ids = Manual.TOPICS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // 设置页右上角「说明」跳转用到的几页都在
        for (id in listOf("start", "notify", "sights", "dash", "memory", "captions", "read", "faq")) Manual.topic(id)
        for (t in Manual.TOPICS) {
            assertTrue(t.title.isNotBlank() && t.summary.isNotBlank() && t.items.isNotEmpty())
            val titles = t.items.map { it.title }
            assertEquals("「${t.title}」里标题重复", titles.size, titles.toSet().size)
            t.items.forEach { assertTrue("「${it.title}」没写说明", it.body.length >= 10) }
        }
    }
}

class PhoneIconsTest {
    @Test fun iconsAreWellFormed() {
        assertEquals(10, PhoneIcons.ALL.size)
        for (icon in PhoneIcons.ALL) {
            val w = icon.rows[0].length
            icon.rows.forEach { assertEquals("每行一样宽：$it", w, it.length) }
            // 放进 48dp 的方块里（每格 2dp），四周至少留 8dp
            assertTrue(icon.w <= 16 && icon.h <= 16)
            // 每个字符要么透明，要么有颜色；而且每个颜色都用到了
            val used = icon.rows.joinToString("").toSet() - '.'
            assertEquals(icon.colors.keys, used)
            // 第一行和最后一行、第一列和最后一列都有东西（不留多余的空边，才能居中）
            assertTrue(icon.rows.first().any { it != '.' } && icon.rows.last().any { it != '.' })
            assertTrue(icon.rows.any { it.first() != '.' } && icon.rows.any { it.last() != '.' })
        }
    }
}

class MemoryTest {
    @Test fun closeSentencesMergeIntoOneConversation() {
        val t0 = 1_700_000_000_000L
        fun e(startSec: Int, durSec: Int, text: String) = MemoryEntry(t0 + startSec * 1000L, durSec * 1000L, "", text)
        val entries = listOf(
            e(0, 4, "明天几点开会"), e(10, 3, "九点吧。"), e(40, 5, "好的，我跟小王说"),   // 间隔 6 秒、27 秒
            e(400, 2, "快递到了"),                                                            // 隔了 6 分钟：另一段
            e(405, 2, " "),                                                                   // 没转出文字的不算
            e(430, 3, "放门口就行"),
        )
        val g = MemoryGroup.group(entries, 60_000)
        assertEquals(2, g.size)
        assertEquals(3, g[0].entries.size)
        assertEquals("明天几点开会。九点吧。好的，我跟小王说", g[0].text)
        assertEquals(12_000L, g[0].speechMs)
        assertEquals(t0 + 45_000L, g[0].end)
        assertEquals("快递到了。放门口就行", g[1].text)
        // 间隔 20 秒：27 秒、28 秒那两次都断开
        assertEquals(listOf(2, 1, 1, 1), MemoryGroup.group(entries, 20_000).map { it.entries.size })
        // 不合并：一句一段
        assertEquals(5, MemoryGroup.group(entries, 0).size)
        // 顺序乱的也按时间排
        assertEquals("明天几点开会。九点吧。好的，我跟小王说", MemoryGroup.group(entries.take(3).reversed(), 60_000).single().text)
    }

    /** 合成"说话"：基频 120～200 Hz 起伏的谐波，每 0.4 秒一个音节（0.25 秒响、0.15 秒停），再加底噪。 */
    private fun speech(seconds: Double, rnd: java.util.Random): ShortArray {
        var ph = 0.0   // 相位按瞬时频率累加，基频才真的在 100～200 Hz 之间起伏
        return ShortArray((seconds * 16000).toInt()) { i ->
            val t = i / 16000.0
            ph += 2 * Math.PI * (150 + 50 * Math.sin(2 * Math.PI * 0.7 * t)) / 16000
            var s = 0.0
            for (k in 1..10) s += Math.sin(k * ph) / k
            val env = if (t % 0.4 < 0.25) 1.0 else 0.0
            (3000 * s * env + rnd.nextGaussian() * 1200).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
    private fun noise(seconds: Double, rnd: java.util.Random) = ShortArray((seconds * 16000).toInt()) { (rnd.nextGaussian() * 1200).toInt().toShort() }

    @Test fun detectorIgnoresNoiseAndToneButHearsSpeech() {
        val rnd = java.util.Random(1)
        fun ratio(p: ShortArray): Double { val d = SpeechDetector(); var sp = 0; d.feed(p) { if (it.speaking) sp++ }; return sp.toDouble() / d.frames }
        assertEquals(0.0, ratio(noise(5.0, rnd)), 0.0)
        val tone = ShortArray(16000 * 6) { (3000 * Math.sin(2 * Math.PI * 200 * it / 16000.0) + rnd.nextGaussian() * 300).toInt().toShort() }
        val d = SpeechDetector(); var late = 0
        d.feed(tone) { if (it.frames > 300 && it.speaking) late++ }   // 持续的单音 3 秒后变成底噪
        assertEquals(0, late)
        assertTrue(ratio(noise(2.0, rnd) + speech(5.0, rnd)) > 0.55)
    }

    /** 虚拟时钟的 Host：记录眼镜麦克风开关、存下的段。 */
    private class FakeHost(val dir: java.io.File) : MemoryEngine.Host {
        var now = 1_000_000L
        val tasks = java.util.PriorityQueue<Pair<Long, Runnable>>(compareBy { it.first })
        var ready = true
        val micLog = ArrayList<Pair<Long, Boolean>>()
        val saved = ArrayList<MemorySegment>()
        override fun post(delayMs: Long, r: Runnable) { tasks.add(now + delayMs to r) }
        override fun cancel(r: Runnable) { tasks.removeIf { it.second === r } }
        override fun uptime() = now
        override fun wallTime() = 1_760_000_000_000L + now
        override fun glassesReady() = ready
        override fun setGlassesMic(on: Boolean) { micLog.add(now to on) }
        override fun log(msg: String) {}
        override fun segmentSaved(seg: MemorySegment) { saved.add(seg) }
        override fun segmentDir(dayStart: Long) = dir
        fun advance(ms: Long) {
            val end = now + ms
            while (true) { val t = tasks.peek() ?: break; if (t.first > end) break; tasks.poll(); now = t.first; t.second.run() }
            now = end
        }
    }

    /** 按眼镜的方式：PCM 编码成 Opus，每 90 ms 一批送进引擎（只有麦克风开着时才有）。 */
    private class Glasses(val host: FakeHost, val engine: MemoryEngine) {
        val enc = io.github.jaredmdobson.concentus.OpusEncoder(16000, 1, io.github.jaredmdobson.concentus.OpusApplication.OPUS_APPLICATION_RESTRICTED_LOWDELAY).apply { bitrate = 16000 }
        val out = ByteArray(400)
        fun play(pcm: ShortArray) {
            var i = 0
            while (i + 1440 <= pcm.size) {
                val micOn = host.micLog.lastOrNull()?.second == true
                // 眼镜开麦后约 0.5 秒才来声音
                val live = micOn && host.now - host.micLog.last().first >= 500
                if (live) engine.onGlassesFrames((0 until 9).map { k ->
                    val n = enc.encode(pcm, i + k * 160, 160, out, 0, out.size); NimoMic.Frame(out.copyOf(n), enc.finalRange.toLong() and 0xFFFFFFFFL)
                })
                host.advance(90); i += 1440
            }
        }
    }

    private fun tmp() = java.nio.file.Files.createTempDirectory("mem").toFile()

    @Test fun alwaysModeRecordsOnlySpeech() {
        val h = FakeHost(tmp()); val e = MemoryEngine(h); val g = Glasses(h, e); val rnd = java.util.Random(2)
        e.start(ListenMode.ALWAYS, 80, 90)
        h.advance(600)
        assertTrue(e.glassesMicOn)
        g.play(noise(4.0, rnd)); assertEquals(0, h.saved.size)
        g.play(speech(4.0, rnd)); g.play(noise(5.0, rnd))
        assertEquals(1, h.saved.size)
        val seg = h.saved[0]
        assertTrue("一段应该约 4 秒说话 + 3 秒安静，实际 ${seg.durationMs}", seg.durationMs in 5_000L..9_000L)
        assertEquals(seg.durationMs / 10, OggOpus.packets(seg.file.readBytes()).size.toLong())
        assertTrue(e.glassesMicOn)   // 一直开
        e.stop(); assertFalse(e.glassesMicOn)
    }

    @Test fun dutyModeProbesAndKeepsListeningWhileTalking() {
        val h = FakeHost(tmp()); val e = MemoryEngine(h); val g = Glasses(h, e); val rnd = java.util.Random(3)
        e.dutyIntervalMs = 10_000
        e.start(ListenMode.DUTY, 80, 90)
        g.play(noise(30.0, rnd))
        val ons = h.micLog.count { it.second }
        assertTrue("30 秒安静里应该试听 2～3 次，实际 $ons", ons in 2..4)
        assertTrue("安静时麦克风开着的时间应该很少", e.glassesMicMs() < 12_000)
        assertEquals(0, h.saved.size)
        // 在试听的时候开始说话：一直录到说完
        while (!e.probing) g.play(noise(0.18, rnd))
        g.play(speech(8.0, rnd)); g.play(noise(5.0, rnd))
        assertEquals(1, h.saved.size)
        assertTrue(h.saved[0].durationMs >= 7_000)
        assertFalse(e.glassesMicOn)   // 说完关掉
    }

    @Test fun storeAndOgg() {
        val dir = tmp(); val st = MemoryStore(dir)
        val day = "2026-09-28"; st.dirOf(day).mkdirs()
        val f = java.io.File(st.dirOf(day), "093015-4200.ogg")
        val w = OggOpusWriter(java.io.FileOutputStream(f))
        val enc = io.github.jaredmdobson.concentus.OpusEncoder(16000, 1, io.github.jaredmdobson.concentus.OpusApplication.OPUS_APPLICATION_RESTRICTED_LOWDELAY)
        val pcm = speech(4.2, java.util.Random(6)); val out = ByteArray(400)
        for (i in 0 until 420) { val n = enc.encode(pcm, i * 160, 160, out, 0, out.size); w.add(out.copyOf(n)) }
        w.close()
        assertEquals(listOf(f), st.pending())
        val (start, dur) = st.segmentInfo(f)
        assertEquals(4200L, dur)
        assertEquals("09:30:15", java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(start)))
        val wav = OggOpus.toWav(f.readBytes())
        assertEquals("RIFF", String(wav, 0, 4)); assertEquals(44 + 420 * 160 * 2, wav.size)
        st.addEntry(day, MemoryEntry(start, dur, f.name, "今天好像都没什么人"))
        assertTrue(st.pending().isEmpty())
        assertEquals("今天好像都没什么人", st.entries(day)[0].text)
        val sum = MemorySummary(day, 1L, "概要", listOf("话题"), listOf("交报告" to "周五前"), listOf("小王的电话"), "m")
        st.saveSummary(sum)
        assertEquals("交报告" to "周五前", st.summary(day)!!.todos[0])
        assertEquals(listOf(day), st.days())
    }

    @Test fun glassesDisconnectEndsSegment() {
        val h = FakeHost(tmp()); val e = MemoryEngine(h); val g = Glasses(h, e); val rnd = java.util.Random(5)
        e.start(ListenMode.ALWAYS, 80, 90)
        h.advance(600)
        g.play(speech(3.0, rnd))
        assertTrue(e.recording)
        h.ready = false; h.advance(600)
        assertFalse(e.recording); assertFalse(e.glassesMicOn)
        assertEquals(1, h.saved.size)
    }

    @Test fun audioDeletedOnceTranscribedOrStale() {
        val st = MemoryStore(tmp())
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HHmmss", java.util.Locale.US)
        val now = fmt.parse("2026-10-30 120000")!!.time
        fun seg(day: String, name: String, transcribed: Boolean): java.io.File {
            st.dirOf(day).mkdirs(); val f = java.io.File(st.dirOf(day), name); f.writeBytes(ByteArray(10))
            if (transcribed) st.addEntry(day, MemoryEntry(0, 1000, name, "文字"))
            return f
        }
        val done = seg("2026-10-30", "100000-1000.ogg", true)          // 转好了还没删：删
        val pending = seg("2026-10-30", "110000-1000.ogg", false)      // 刚录的、还没转：留着等转
        val yesterday = seg("2026-10-29", "130000-1000.ogg", false)    // 23 小时前还没转：留着
        val stale = seg("2026-10-29", "110000-1000.ogg", false)        // 25 小时前还没转：删
        assertEquals(2, st.purgeAudio(now, 24 * 3600_000L))
        assertFalse(done.exists()); assertTrue(pending.exists()); assertTrue(yesterday.exists()); assertFalse(stale.exists())
        assertEquals(listOf(yesterday, pending), st.pending())
        assertEquals("文字", st.entries("2026-10-30")[0].text)   // 文字留着
    }

    @Test fun transcriptionRequestAndResponse() {
        val body = String(SpeechToText.multipart("B", "FunAudioLLM/SenseVoiceSmall", "zh", "a.wav", "audio/wav", byteArrayOf(1, 2)), Charsets.ISO_8859_1)
        assertTrue(body.contains("name=\"model\"\r\n\r\nFunAudioLLM/SenseVoiceSmall\r\n"))
        assertTrue(body.contains("name=\"language\"\r\n\r\nzh\r\n"))
        assertTrue(body.contains("filename=\"a.wav\"\r\nContent-Type: audio/wav\r\n\r\n\u0001\u0002\r\n--B--"))
        assertFalse(String(SpeechToText.multipart("B", "m", "", "a.wav", "audio/wav", ByteArray(0))).contains("language"))
        // Whisper / OpenAI 转写模型带上"简体、加标点"的提示；SenseVoice、英文不带
        assertFalse(body.contains("name=\"prompt\""))
        assertTrue(String(SpeechToText.multipart("B", "whisper-large-v3", "zh", "a.wav", "audio/wav", ByteArray(0))).contains("name=\"prompt\"\r\n\r\n" + SpeechToText.ZH_PROMPT))
        assertTrue(SpeechToText.wantsPrompt("gpt-4o-mini-transcribe", "")); assertFalse(SpeechToText.wantsPrompt("whisper-1", "en"))
    }

    @Test fun qwenAsrRequestAndResponse() {
        assertTrue(SpeechToText.isChatAsr("qwen3-asr-flash")); assertFalse(SpeechToText.isChatAsr("FunAudioLLM/SenseVoiceSmall"))
        assertFalse(SpeechToText.isChatAsr("qwen-plus"))
        val audio = byteArrayOf(82, 73, 70, 70, 1, 2, 3)
        val body = org.json.JSONObject(SpeechToText.chatBody("qwen3-asr-flash", "zh", "audio/wav", audio))
        assertEquals("qwen3-asr-flash", body.getString("model")); assertFalse(body.getBoolean("stream"))
        val part = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("input_audio", part.getString("type"))
        val data = part.getJSONObject("input_audio").getString("data")
        assertTrue(data.startsWith("data:audio/wav;base64,"))
        assertArrayEquals(audio, java.util.Base64.getDecoder().decode(data.substringAfter(",")))
        assertEquals("zh", body.getJSONObject("asr_options").getString("language"))
        // 自动识别语言：不给 language，开 enable_lid
        val auto = org.json.JSONObject(SpeechToText.chatBody("qwen3-asr-flash", "", "audio/wav", audio)).getJSONObject("asr_options")
        assertFalse(auto.has("language")); assertTrue(auto.getBoolean("enable_lid"))
        // 返回：content 是字符串或 [{"text": ...}]
        assertEquals("明天九点开会。", SpeechToText.parseChat("""{"choices":[{"message":{"role":"assistant","content":"明天九点开会。","annotations":[{"type":"audio_info","language":"zh","emotion":"neutral"}]}}]}"""))
        assertEquals("你好世界", SpeechToText.parseChat("""{"choices":[{"message":{"content":[{"text":"你好"},{"text":"世界"}]}}]}"""))
    }

    @Test fun qwenAsrOverHttp() {
        // 本地起一个假的百炼服务，走一遍真正的请求
        var got: org.json.JSONObject? = null; var auth = ""; var path = ""
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            path = ex.requestURI.path; auth = ex.requestHeaders.getFirst("Authorization") ?: ""
            got = org.json.JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val resp = """{"choices":[{"message":{"role":"assistant","content":"周五前把报告交给小王"}}]}""".toByteArray()
            ex.sendResponseHeaders(200, resp.size.toLong()); ex.responseBody.use { it.write(resp) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}/compatible-mode/v1/"
            val text = SpeechToText.transcribe(base, "sk-test", "qwen3-asr-flash", "zh", "a.wav", "audio/wav", ByteArray(3000) { it.toByte() })
            assertEquals("周五前把报告交给小王", text)
            assertEquals("/compatible-mode/v1/chat/completions", path); assertEquals("Bearer sk-test", auth)
            assertEquals("qwen3-asr-flash", got!!.getString("model"))
        } finally { server.stop(0) }
        assertEquals("今天天气不错", SpeechToText.parseText("{\"text\":\"<|zh|><|NEUTRAL|><|Speech|>今天天气不错 \"}"))
    }

    @Test fun claudeSummaryRequestAndParsing() {
        val b = DaySummarizer.claudeBody("claude-opus-5", "[09:30] 周五前把报告交给小王", "2026-09-28")
        assertEquals("claude-opus-5", b.getString("model"))
        assertEquals("default", b.getString("fallbacks"))
        val fmt = b.getJSONObject("output_config").getJSONObject("format")
        assertEquals("json_schema", fmt.getString("type"))
        assertFalse(fmt.getJSONObject("schema").getBoolean("additionalProperties"))
        assertFalse(fmt.getJSONObject("schema").getJSONObject("properties").getJSONObject("todos").getJSONObject("items").getBoolean("additionalProperties"))
        val ok = """{"stop_reason":"end_turn","content":[{"type":"text","text":"{\"overview\":\"忙\",\"topics\":[\"报告\"],\"todos\":[{\"text\":\"交报告\",\"when\":\"周五前\"}],\"notes\":[]}"}]}"""
        val s = DaySummarizer.toSummary("2026-09-28", DaySummarizer.parseClaude(ok), "claude-opus-5")
        assertEquals("忙", s.overview); assertEquals("交报告" to "周五前", s.todos[0])
        try { DaySummarizer.parseClaude("""{"stop_reason":"refusal","stop_details":{"category":"cyber"},"content":[]}"""); fail() }
        catch (e: java.io.IOException) { assertTrue(e.message!!.contains("拒绝")) }
        val oa = """{"choices":[{"message":{"content":"```json\n{\"overview\":\"x\",\"topics\":[],\"todos\":[\"买牛奶\"],\"notes\":[\"n\"]}\n```"}}]}"""
        assertEquals("买牛奶" to "", DaySummarizer.toSummary("d", DaySummarizer.parseOpenAi(oa), "m").todos[0])
        // 太长的转写分段、不截断
        val long = (1..5000).joinToString("\n") { "[10:00] 第 $it 句话，内容凑长一点点" }
        val parts = DaySummarizer.chunks(long)
        assertTrue(parts.size > 1); assertTrue(parts.all { it.length <= DaySummarizer.CHUNK_CHARS })
        assertEquals(long, parts.joinToString("\n"))
    }
}
