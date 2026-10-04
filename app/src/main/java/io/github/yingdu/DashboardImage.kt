package io.github.yingdu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.util.Calendar
import java.util.Locale

/**
 * 眼镜画面（都画成 452×170 的图片，显示在导航页的大图区域）。
 * 统一规则：同一种点阵字；主要信息全亮，次要信息半亮。
 */
object DashboardImage {
    const val W = NimoProtocol.NAV_LARGE_MAP_WIDTH
    const val H = NimoProtocol.NAV_LARGE_MAP_HEIGHT
    const val ROWS = 4                       // 每栏最多 4 项，行距更宽松
    const val MAX_PX = 20                    // 看板字号跟随阅读字号，但最大 20px，否则放不下

    const val FULL = Color.WHITE
    const val DIM = 0xFFA0A0A0.toInt()       // 2bpp 里约等于第 2 级亮度
    private const val LINE = 0xFF606060.toInt()

    private val WEEK = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    /** 顶部一行：日期、步数、城市、天气（时间和电量眼镜自己的状态栏已经有了；放不下时先省略最高最低温）。 */
    fun header(cal: Calendar, steps: Long?, city: String, w: Weather?, font: PixelText, maxW: Int): String {
        val date = "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)} ${WEEK[cal.get(Calendar.DAY_OF_WEEK) - 1]}"
        val st = steps?.let { String.format(Locale.US, "%,d步", it) } ?: ""
        fun build(full: Boolean): String {
            val wt = w?.let {
                "${it.desc} ${it.temp.toInt()}°" + if (full) " ${it.low.toInt()}~${it.high.toInt()}°" else ""
            } ?: ""
            return listOf(date, st, city, wt).filter { it.isNotBlank() }.joinToString("  ")
        }
        return build(true).takeIf { font.measure(it) <= maxW } ?: font.ellipsize(build(false), maxW)
    }

    /** 虚线（点阵风格的分隔线）。 */
    /**
     * 通知页：看板关屏收起时来通知，眼镜自己的弹窗在"开屏"之后常常不弹（v3.7/v3.8 实测三四次才成一次），
     * 所以画成一张图显示在导航页上，走和抬头唤醒一样可靠的"先发内容、再开屏"。
     * 和看板卡片一样的圆角细框（正文短时框跟着变矮）；第一行：铃铛图标 + app 名（半亮）+ 发件人 / 标题（全亮），右边时间（半亮）；
     * 下面一条虚线，再下面是正文（全亮，折行，放不下的末尾加省略号）。
     */
    fun renderNotification(app: String, title: String, text: String, time: String, fontPx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val px = Px(c)
        val f = GlassesFonts.text(minOf(fontPx, MAX_PX))
        val x0 = 3; val y0 = 3; val x1 = W - 4; val y1 = H - 4        // 框最大的范围；正文短时框收到正文下面
        val left = x0 + 12; val right = x1 - 12

        // 第一行
        val top = y0 + 8
        px.icon(if (app == "景点") "pin" else "bell", left, top + f.height / 2 - 4, DIM)
        var x = left + 14
        val tw = f.measure(time)
        val room = right - tw - 12 - x                          // 时间左边能用的宽度
        val who = title.trim().takeIf { it.isNotEmpty() && it != app }
        val appName = if (who == null) f.ellipsize(app, room) else f.ellipsize(app, minOf(f.measure(app), room / 2))
        x = f.draw(c, appName, x, top, DIM)
        if (who != null) f.draw(c, f.ellipsize(who, right - tw - 12 - (x + 10)), x + 10, top, FULL)
        f.draw(c, time, right, top, DIM, alignRight = true)

        // 分隔虚线 + 正文
        val sep = top + f.height + 6
        dotted(c, left.toFloat(), sep.toFloat(), right + 1f, sep.toFloat())
        val lineH = f.height + 6
        var y = sep + 8
        val lines = f.wrap(text.trim(), right - left)
        val max = notificationLines(fontPx)
        lines.take(max).forEachIndexed { i, s ->
            val last = i == max - 1 && lines.size > max
            f.draw(c, if (last) f.ellipsize("$s……", right - left) else s, left, y, FULL)
            y += lineH
        }
        px.frame(x0, y0, x1, minOf(y1, y + 2), 8, LINE)
        return bmp
    }

    /** 通知页正文每行多宽（和 renderNotification 一致）。 */
    fun notificationWidth(): Int = (W - 4 - 12) - (3 + 12)
    /** 通知页正文最多几行（和 renderNotification 一致）。 */
    fun notificationLines(fontPx: Int): Int {
        val f = GlassesFonts.text(minOf(fontPx, MAX_PX))
        val sep = 3 + 8 + f.height + 6
        return (((H - 4) - 4 - (sep + 8)) / (f.height + 6)).coerceAtLeast(1)
    }

    /**
     * 把一段介绍裁成刚好一屏（景点介绍用）：按眼镜上真实的字宽折行，尽量把最后一行也填满；
     * 句号结尾的前缀能占到 85% 以上就在句号处结束，否则填满最后一行、末尾加省略号。
     */
    fun fitNotificationText(text: String, fontPx: Int): String {
        val f = GlassesFonts.text(minOf(fontPx, MAX_PX))
        val w = notificationWidth(); val max = notificationLines(fontPx)
        val t = text.trim()
        fun fits(s: String) = f.wrap(s, w).size <= max
        if (fits(t)) return t
        var lo = 0; var hi = t.length                 // 带省略号还放得下的最长前缀
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (fits(t.substring(0, mid) + "…")) lo = mid else hi = mid - 1 }
        val end = t.lastIndexOfAny(charArrayOf('。', '！', '？', '；'), lo - 1)
        if (end >= 0 && end + 1 >= lo * 0.85) return t.substring(0, end + 1)
        return t.substring(0, lo).trimEnd('，', '、', '；', '：', ' ') + "…"
    }

    private fun dotted(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        val p = Paint().apply { color = LINE }
        if (y0 == y1) { var x = x0; while (x < x1) { c.drawRect(x, y0, x + 2f, y0 + 1f, p); x += 5f } }
        else { var y = y0; while (y < y1) { c.drawRect(x0, y, x0 + 1f, y + 2f, p); y += 5f } }
    }

    /** 9×9 的小图标（卡片标题、顶部一行用），'#' 亮。 */
    val ICONS: Map<String, List<String>> = mapOf(
        "stock" to listOf(".........", "........#", ".......#.", "..#...#..", ".#.#.#...", "#...#....", ".........", "#########", "........."),
        "cal" to listOf(".#.....#.", "#########", "#.......#", "#########", "#.#.#.#.#", "#.......#", "#.#.#.#.#", "#########", "........."),
        "moon" to listOf("...###...", ".###.....", ".##......", "###......", "###......", "###.....#", ".###...##", "..######.", "...####.."),
        "sun" to listOf("....#....", ".#.....#.", "...###...", "..#####..", "#.#####.#", "..#####..", "...###...", ".#.....#.", "....#...."),
        "cloud" to listOf(".........", ".........", "...###...", "..#...#..", ".#.....##", "#.......#", "#.......#", ".#######.", "........."),
        "rain" to listOf("...###...", "..#...#..", ".#.....##", "#.......#", ".#######.", ".........", ".#..#..#.", "#..#..#..", "........."),
        "snow" to listOf("....#....", ".#..#..#.", "..#.#.#..", "...###...", "#########", "...###...", "..#.#.#..", ".#..#..#.", "....#...."),
        "tomato" to listOf("...#.#...", "..#####..", ".#######.", "#########", "#########", "#########", ".#######.", "..#####..", "........."),
        "memo" to listOf("#######..", "#.....##.", "#.###..#.", "#.....##.", "#.####.#.", "#......#.", "#.###..#.", "########.", "........."),
        "flag" to listOf("#........", "######...", "#######..", "######...", "#........", "#........", "#........", "#........", "........."),
        "globe" to listOf("..#####..", ".#..#..#.", "#...#...#", "#########", "#...#...#", "#########", "#...#...#", ".#..#..#.", "..#####.."),
        "clock" to listOf("..#####..", ".#.....#.", "#...#...#", "#...#...#", "#...###.#", "#.......#", "#.......#", ".#.....#.", "..#####.."),
        "web" to listOf("..#####..", ".#..#..#.", "#..#.#..#", "#.#...#.#", "#########", "#.#...#.#", "#..#.#..#", ".#..#..#.", "..#####.."),
        "pin" to listOf("..#####..", ".#######.", "###...###", "###...###", ".#######.", "..#####..", "...###...", "....#....", "........."),
        "bell" to listOf("....#....", "...###...", "..#####..", "..#####..", "..#####..", ".#######.", "#########", ".........", "...###..."),
        "steps" to listOf("..##.....", ".####....", ".####....", "..##.##..", ".....###.", "....####.", ".....##..", ".........", "........."),
    )

    /** 天气描述对应的小图标。 */
    fun weatherIcon(desc: String?): String = when {
        desc == null -> "sun"
        desc.contains("雪") -> "snow"
        desc.contains("雨") || desc.contains("雷") -> "rain"
        desc.contains("云") || desc.contains("阴") || desc.contains("雾") -> "cloud"
        else -> "sun"
    }

    /** 逐像素画图的小工具（圆角框、圆角块、图标）。 */
    private class Px(val c: Canvas) {
        private val p = Paint()
        fun rect(x: Int, y: Int, w: Int, h: Int, color: Int) { if (w > 0 && h > 0) { p.color = color; c.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), p) } }
        fun dot(x: Int, y: Int, color: Int) = rect(x, y, 1, 1, color)
        /** 1 像素宽的圆角框。 */
        fun frame(x0: Int, y0: Int, x1: Int, y1: Int, r: Int, color: Int) {
            rect(x0 + r, y0, x1 - x0 - 2 * r + 1, 1, color); rect(x0 + r, y1, x1 - x0 - 2 * r + 1, 1, color)
            rect(x0, y0 + r, 1, y1 - y0 - 2 * r + 1, color); rect(x1, y0 + r, 1, y1 - y0 - 2 * r + 1, color)
            var x = r; var y = 0; var err = 1 - r
            while (x >= y) {
                for ((dx, dy) in listOf(x to y, y to x)) {
                    dot(x1 - r + dx, y1 - r + dy, color); dot(x0 + r - dx, y1 - r + dy, color)
                    dot(x1 - r + dx, y0 + r - dy, color); dot(x0 + r - dx, y0 + r - dy, color)
                }
                y++; if (err < 0) err += 2 * y + 1 else { x--; err += 2 * (y - x) + 1 }
            }
        }
        /** 实心圆角块。 */
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, r: Int, color: Int) {
            if (x1 < x0 || y1 < y0) return
            for (y in y0..y1) {
                val dy = when { y < y0 + r -> y0 + r - y; y > y1 - r -> y - (y1 - r); else -> 0 }
                val inset = if (dy == 0) 0 else r - Math.sqrt((r * r - dy * dy).toDouble().coerceAtLeast(0.0)).toInt()
                rect(x0 + inset, y, x1 - x0 + 1 - 2 * inset, 1, color)
            }
        }
        fun icon(name: String, x: Int, y: Int, color: Int) {
            ICONS[name]?.forEachIndexed { j, row -> row.forEachIndexed { i, ch -> if (ch == '#') dot(x + i, y + j, color) } }
        }
    }

    /**
     * 看板的一屏（2026-09 改版：圆角卡片）：
     * 顶部一行小字：日期、步数、城市天气（带小图标），右边是翻页的小圆点；
     * 下面每张卡片一个圆角细框，框里左上角图标 + 卡片名，右上角更新时间 / 等级这类小字，再下面最多 ROWS 行。
     * 只开股票分成左右两栏时是一个大框、中间一条竖虚线。
     * @param fontPx 正文字号（和阅读一致，超过 MAX_PX 按 MAX_PX）；顶部和卡片名固定用 12px
     */
    fun render(cal: Calendar, steps: Long?, city: String, weather: Weather?, screen: DashScreen,
               page: Int, pages: Int, fontPx: Int): ByteArray =
        toGlasses(renderBitmap(cal, steps, city, weather, screen, page, pages, fontPx))

    /** 同样的画面画成位图（手机上预览用）。 */
    fun renderBitmap(cal: Calendar, steps: Long?, city: String, weather: Weather?, screen: DashScreen,
                     page: Int, pages: Int, fontPx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val px = Px(c)
        val f = GlassesFonts.text(minOf(fontPx, MAX_PX))
        val small = GlassesFonts.text(12)

        // ---- 顶部一行：和正文一样大的字（12px 在眼镜上看不清） ----
        val top = 4
        val mid = top + f.height / 2                      // 图标、圆点和字的中线对齐
        val dotsW = if (pages > 1) pages * 10 + 4 else 0
        var x = 8
        val date = "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)} ${WEEK[cal.get(Calendar.DAY_OF_WEEK) - 1]}"
        x = f.draw(c, date, x, top, DIM) + 14
        if (steps != null) {
            px.icon("steps", x, mid - 4, DIM); x += 12
            x = f.draw(c, String.format(Locale.US, "%,d", steps), x, top, DIM) + 14
        }
        if (weather != null || city.isNotBlank()) {
            val full = listOf(city, weather?.let { "${it.desc} ${it.temp.toInt()}°" } ?: "", weather?.let { " ${it.low.toInt()}~${it.high.toInt()}°" } ?: "")
            var wt = (full[0] + " " + full[1]).trim() + full[2]
            val room = W - 8 - dotsW - x - 12
            if (f.measure(wt) > room) wt = f.ellipsize((full[0] + " " + full[1]).trim(), room)   // 放不下先省略最高最低温
            if (room > 24) { px.icon(weatherIcon(weather?.desc), x, mid - 4, DIM); f.draw(c, wt, x + 12, top, DIM) }
        }
        for (i in 0 until if (pages > 1) pages else 0) {
            val cx = W - 10 - (pages - 1 - i) * 10
            if (i == page) px.fill(cx - 3, mid - 3, cx + 3, mid + 3, 3, FULL) else px.frame(cx - 3, mid - 3, cx + 3, mid + 3, 3, LINE)
        }

        // ---- 卡片（从顶部一行下面开始） ----
        val y0 = top + f.height + 5; val y1 = H - 3
        val right = screen.right
        val sameCard = right != null && screen.leftHead != null && screen.leftHead === screen.rightHead
        when {
            right == null || screen.left.isEmpty() && !sameCard ->
                card(c, px, f, small, screen.left.ifEmpty { right ?: emptyList() }, if (screen.left.isEmpty()) screen.rightHead ?: screen.leftHead else screen.leftHead, 3, y0, W - 4, y1, cal)
            sameCard -> {
                // 一张卡片分左右两栏：一个大框，中间竖虚线
                card(c, px, f, small, screen.left, screen.leftHead, 3, y0, W - 4, y1, cal, rightRows = right)
            }
            else -> {
                card(c, px, f, small, screen.left, screen.leftHead, 3, y0, 223, y1, cal)
                card(c, px, f, small, right, screen.rightHead, 228, y0, W - 4, y1, cal)
            }
        }
        return bmp
    }

    /** 一张卡片：圆角框 + 标题 + 行；rightRows 不为空时框里分左右两栏。 */
    private fun card(c: Canvas, px: Px, f: PixelText, small: PixelText, rows: List<CardRow>, head: CardHead?,
                     x0: Int, y0: Int, x1: Int, y1: Int, cal: Calendar, rightRows: List<CardRow>? = null) {
        px.frame(x0, y0, x1, y1, 8, LINE)
        var top = y0 + 6
        if (head != null) {
            val iconW = if (ICONS.containsKey(head.icon)) 13 else 0
            if (iconW > 0) px.icon(head.icon, x0 + 9, y0 + 7, DIM)
            val metaW = if (head.meta.isEmpty()) 0 else small.measure(head.meta) + 8
            small.draw(c, small.ellipsize(head.title, x1 - x0 - 18 - iconW - metaW), x0 + 9 + iconW, y0 + 5, DIM)
            if (head.meta.isNotEmpty()) small.draw(c, head.meta, x1 - 9, y0 + 5, LINE, alignRight = true)
            top = y0 + 20
        }
        if (rightRows == null) column(c, px, f, rows, x0 + 10, x1 - 10, top, y1 - 3, cal)
        else {
            val mid = (x0 + x1) / 2
            column(c, px, f, rows, x0 + 10, mid - 10, top, y1 - 3, cal)
            var y = top + 4
            while (y < y1 - 6) { px.rect(mid, y, 1, 2, LINE); y += 5 }
            column(c, px, f, rightRows, mid + 10, x1 - 10, top, y1 - 3, cal)
        }
    }

    /**
     * 一栏卡片：左边主要内容；中间一列右对齐（半栏时紧挨右边列）；右边一列右对齐到栏的右边。
     * 有中间列时（股票）涨的全亮、跌的半亮；"■■■■□" 画成一排小方块；待办画成圆角方框；进度条是圆头的粗线。
     */
    private fun column(c: Canvas, px: Px, f: PixelText, rows: List<CardRow>, x0: Int, x1: Int, top: Int, bottom: Int,
                       cal: Calendar, maxRows: Int = ROWS) {
        rows.firstOrNull()?.takeIf { it.face >= 0 }?.let { r ->
            // 像素表盘占满整栏
            PixelClock.draw(r.face, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), null, x0, top, x1 - x0, bottom - top, { x, y, w, h, level ->
                px.rect(x, y, w, h, when (level) { PixelClock.FULL -> FULL; PixelClock.DIM -> DIM; PixelClock.FAINT -> LINE; else -> Color.BLACK })
            }, ghost = false)
            return
        }
        val rowH = (bottom - top) / maxRows
        // 可以折行的先按栏宽拆成几行，每行占一格；放不下的末尾加省略号
        val shown = ArrayList<CardRow>()
        for (r in rows) {
            if (shown.size >= maxRows) break
            if (r.lines <= 1) { shown.add(r); continue }
            val ws = wrapPhrases(f, r.left.trim(), x1 - x0)
            val room = minOf(r.lines, maxRows - shown.size)
            ws.take(room).forEachIndexed { k, line ->
                val cut = k == room - 1 && ws.size > room
                shown.add(r.copy(left = if (cut) f.ellipsize("$line……", x1 - x0) else line, lines = 1))
            }
        }
        // 半栏里：中间列紧挨着右边列，给名称多留地方（A 股的中文简称）
        val narrowMidX = if (x1 - x0 < W * 2 / 3 && shown.any { it.mid.isNotEmpty() })
            x1 - (shown.filter { it.mid.isNotEmpty() }.maxOfOrNull { f.measure(it.right) } ?: 0) - 10 else null
        shown.forEachIndexed { i, r ->
            val y = top + rowH * i + (rowH - f.height) / 2
            val midX = narrowMidX ?: (x0 + (x1 - x0) * 3 / 5)
            val rightW = if (r.right.isEmpty()) 0 else f.measure(r.right)
            var x = x0
            if (r.todo) {
                val bs = maxOf(9, f.height * 11 / 16)
                val by = y + (f.height - bs) / 2
                px.frame(x, by, x + bs, by + bs, 2, DIM)
                x += bs + 7
            }
            if (r.progress >= 0f) {
                val lw = if (r.left.isEmpty()) 0 else f.draw(c, r.left, x, y, DIM) - x + 8
                val rw = if (r.right.isEmpty()) 0 else f.measure(r.right) + 8
                if (r.right.isNotEmpty()) f.draw(c, r.right, x1, y, DIM, alignRight = true)
                val bx = x + lw; val bw = x1 - rw - bx; val cy = y + f.height / 2
                px.fill(bx, cy - 3, bx + bw, cy + 3, 3, LINE)
                px.fill(bx, cy - 3, bx + (bw * r.progress.coerceIn(0f, 1f)).toInt(), cy + 3, 3, FULL)
                return@forEachIndexed
            }
            if (r.right.isNotEmpty() && r.right.all { it == '■' || it == '□' }) {
                // 饱腹、心情这种条：一排小方块
                f.draw(c, f.ellipsize(r.left, x1 - r.right.length * 12 - 8 - x), x, y, FULL)
                var bx = x1 - r.right.length * 12 + 3
                val by = y + (f.height - 8) / 2
                for (ch in r.right) { if (ch == '■') px.fill(bx, by, bx + 8, by + 7, 1, FULL) else px.frame(bx, by, bx + 8, by + 7, 1, DIM); bx += 12 }
                return@forEachIndexed
            }
            val iconW = if (r.mid.isNotEmpty() && ICONS.containsKey(r.midIcon)) 13 else 0
            val leftEnd = when {
                r.mid.isNotEmpty() -> midX - f.measure(r.mid) - 8 - iconW
                r.right.isNotEmpty() -> x1 - rightW - 10
                else -> x1
            }
            if (r.sub.isNotEmpty()) {
                // 主要内容全亮、副标题半亮，挤不下时主要内容至少占三分之二
                val room = leftEnd - x
                val lw = minOf(f.measure(r.left), maxOf(room * 2 / 3, room - f.measure(r.sub) - 8))
                val end = f.draw(c, f.ellipsize(r.left, lw), x, y, if (r.dim) DIM else FULL)
                if (leftEnd - end - 8 >= 24) f.draw(c, f.ellipsize(r.sub, leftEnd - end - 8), end + 8, y, DIM)
            } else f.draw(c, f.ellipsize(r.left, leftEnd - x), x, y, if (r.dim) DIM else FULL)
            if (r.underline >= 0f) {
                // 细进度线：底下一条最暗的线，播过的部分全亮
                val uy = y + f.height + minOf(4, (rowH - f.height) / 2)
                px.rect(x0, uy, x1 - x0, 2, LINE)
                px.rect(x0, uy, ((x1 - x0) * r.underline.coerceIn(0f, 1f)).toInt(), 2, FULL)
            }
            if (r.mid.isNotEmpty()) {
                f.draw(c, r.mid, midX, y, FULL, alignRight = true)
                if (iconW > 0) px.icon(r.midIcon, midX - f.measure(r.mid) - iconW, y + (f.height - 9) / 2, DIM)
            }
            if (r.right.isNotEmpty()) {
                val dim = r.mid.isNotEmpty() && !r.right.startsWith("▲")   // 股票：涨的全亮、跌的半亮
                f.draw(c, f.ellipsize(r.right, x1 - (if (r.mid.isNotEmpty()) midX + 8 else x0 + (x1 - x0) / 3)), x1, y,
                    if (dim) DIM else FULL, alignRight = true)
            }
        }
    }

    /** 按空格分的词组折行，一个词组比整行还长时才在字中间断开。 */
    fun wrapPhrases(f: PixelText, text: String, maxW: Int): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (w in text.split(' ').filter { it.isNotEmpty() }) {
            val tryLine = if (line.isEmpty()) w else "$line $w"
            if (f.measure(tryLine) <= maxW) { line = tryLine; continue }
            if (line.isNotEmpty()) out.add(line)
            if (f.measure(w) <= maxW) line = w
            else { val parts = f.wrap(w, maxW); out.addAll(parts.dropLast(1)); line = parts.last() }
        }
        if (line.isNotEmpty()) out.add(line)
        return out
    }

    /** 把"黑底亮字"的画布转成眼镜的 2bpp 格式（眼镜里 0 最亮、3 不亮，所以要取反）。 */
    fun toGlasses(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val levels = ByteArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val l = (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)).toInt()
            // 实测：背景全是 3、曲线是 0。
            levels[i] = (255 - l.coerceIn(0, 255)).toByte()
        }
        return NimoFrameCodec.pack2bpp(levels)
    }
}
