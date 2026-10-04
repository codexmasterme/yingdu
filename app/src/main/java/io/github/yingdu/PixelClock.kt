package io.github.yingdu

/*
 * 像素表盘、番茄钟进度环这些「画格子」的部分（不依赖 Android，方便单元测试）。
 * 画法统一成一个 fill(x, y, w, h, 亮度) 回调：亮度 0 全亮、1 半亮、2 微亮；眼镜上和手机预览都用它。
 */

/** 填一个矩形：亮度 [PixelClock.FULL] / [PixelClock.DIM] / [PixelClock.FAINT]，或者 [PixelClock.BLACK]（擦成黑色）。 */
typealias Fill = (x: Int, y: Int, w: Int, h: Int, level: Int) -> Unit

object PixelClock {
    const val FULL = 0
    const val DIM = 1
    const val FAINT = 2
    const val BLACK = -1

    const val FLIP = 0
    const val SEGMENT = 1
    const val BINARY = 2
    val STYLES = listOf("翻页钟", "数码管", "二进制")
    /** 看板卡片里存的样式名（CardConfig.text）。 */
    val KEYS = listOf("flip", "seg", "bin")

    fun styleOf(key: String): Int = KEYS.indexOf(key.trim().lowercase()).takeIf { it >= 0 } ?: FLIP

    /** 5×7 的数字点阵（翻页钟用）。 */
    val DIGITS_5X7 = listOf(
        listOf(" ### ", "#   #", "#   #", "#   #", "#   #", "#   #", " ### "),
        listOf("  #  ", " ##  ", "  #  ", "  #  ", "  #  ", "  #  ", " ### "),
        listOf(" ### ", "#   #", "    #", "   # ", "  #  ", " #   ", "#####"),
        listOf("#####", "   # ", "  #  ", "   # ", "    #", "#   #", " ### "),
        listOf("   # ", "  ## ", " # # ", "#  # ", "#####", "   # ", "   # "),
        listOf("#####", "#    ", "#### ", "    #", "    #", "#   #", " ### "),
        listOf("  ## ", " #   ", "#    ", "#### ", "#   #", "#   #", " ### "),
        listOf("#####", "    #", "   # ", "  #  ", " #   ", " #   ", " #   "),
        listOf(" ### ", "#   #", "#   #", " ### ", "#   #", "#   #", " ### "),
        listOf(" ### ", "#   #", "#   #", " ####", "    #", "   # ", " ##  "),
    )

    /** 3×5 的小数字（二进制钟下面的读数）。 */
    val DIGITS_3X5 = listOf(
        listOf("###", "# #", "# #", "# #", "###"), listOf(" # ", "## ", " # ", " # ", "###"),
        listOf("###", "  #", "###", "#  ", "###"), listOf("###", "  #", "###", "  #", "###"),
        listOf("# #", "# #", "###", "  #", "  #"), listOf("###", "#  ", "###", "  #", "###"),
        listOf("###", "#  ", "###", "# #", "###"), listOf("###", "  #", "  #", "  #", "  #"),
        listOf("###", "# #", "###", "# #", "###"), listOf("###", "# #", "###", "  #", "###"),
    )

    /**
     * 七段数码管每个数字亮哪几段：位 0..6 依次是 a（上）b（右上）c（右下）d（下）e（左下）f（左上）g（中）。
     */
    val SEGMENTS = intArrayOf(0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F)

    /** 二进制钟（8421 码）：每一位数字一列，从下往上是 1、2、4、8。时十位 2 格、分秒十位 3 格、个位 4 格。 */
    fun bcd(h: Int, m: Int, s: Int?): List<Pair<Int, Int>> {
        val digits = mutableListOf(h / 10 to 2, h % 10 to 4, m / 10 to 3, m % 10 to 4)
        if (s != null) { digits.add(s / 10 to 3); digits.add(s % 10 to 4) }
        return digits
    }

    /** 画点阵图案（'#' 用 level 画，其余不画），只画 [clipTop, clipBottom) 之间的部分（翻页的上下半张）。 */
    fun pattern(rows: List<String>, x: Int, y: Int, scale: Int, level: Int, fill: Fill,
                clipTop: Int = Int.MIN_VALUE, clipBottom: Int = Int.MAX_VALUE) {
        rows.forEachIndexed { r, line ->
            line.forEachIndexed cell@{ c, ch ->
                if (ch != '#') return@cell
                val top = maxOf(y + r * scale, clipTop)
                val bottom = minOf(y + (r + 1) * scale, clipBottom)
                if (bottom > top) fill(x + c * scale, top, scale, bottom - top, level)
            }
        }
    }

    /**
     * 在 (x, y, w, h) 这块地方居中画一个表盘，返回实际用掉的 [x, y, w, h]。
     * @param seconds 要显示的秒（null = 不显示秒：看板卡片一分钟才刷新一次）
     * @param prev 翻页钟正在翻的那一刻：上半张是新时间、下半张还是旧时间（"HHMM"）；null 表示没在翻
     * @param colon 数码管中间的冒号亮不亮（一秒闪一下）
     * @param ghost 数码管不亮的段画成微亮（全屏时有真数码管的味道；看板卡片里小，关掉更好认）
     */
    fun draw(style: Int, hh: Int, mm: Int, seconds: Int?, x: Int, y: Int, w: Int, h: Int, fill: Fill,
             prev: String? = null, colon: Boolean = true, ghost: Boolean = true): IntArray = when (style) {
        SEGMENT -> segmentClock(hh, mm, seconds, x, y, w, h, fill, colon, ghost)
        BINARY -> binaryClock(hh, mm, seconds, x, y, w, h, fill)
        else -> flipClock(hh, mm, x, y, w, h, fill, prev)
    }

    private fun two(n: Int) = String.format(java.util.Locale.US, "%02d", n)

    // ---------- 翻页钟 ----------

    /** 两张翻页卡片（时、分），每张 15s×11s；微亮的卡片、全亮的数字，中间一道黑缝。 */
    private fun flipClock(hh: Int, mm: Int, x: Int, y: Int, w: Int, h: Int, fill: Fill, prev: String?): IntArray {
        val s = minOf(w / 32, h / 11).coerceAtLeast(1)
        val cw = 15 * s; val ch = 11 * s; val gap = 2 * s
        val tw = cw * 2 + gap
        val x0 = x + (w - tw) / 2; val y0 = y + (h - ch) / 2
        val now = two(hh) + two(mm)
        for (card in 0..1) {
            val cx = x0 + card * (cw + gap)
            // 卡片：切掉四个角，看起来是圆角
            val r = maxOf(1, s / 2)
            fill(cx + r, y0, cw - 2 * r, ch, FAINT)
            fill(cx, y0 + r, r, ch - 2 * r, FAINT)
            fill(cx + cw - r, y0 + r, r, ch - 2 * r, FAINT)
            val mid = y0 + ch / 2
            for (d in 0..1) {
                val dx = cx + 2 * s + d * 6 * s
                val gy = y0 + 2 * s
                val newDigit = now[card * 2 + d] - '0'
                if (prev == null) pattern(DIGITS_5X7[newDigit], dx, gy, s, FULL, fill)
                else {
                    // 翻到一半：上半张已经是新数字，下半张还是旧数字
                    pattern(DIGITS_5X7[newDigit], dx, gy, s, FULL, fill, clipBottom = mid)
                    pattern(DIGITS_5X7[prev[card * 2 + d] - '0'], dx, gy, s, FULL, fill, clipTop = mid)
                }
            }
            // 中间一道黑缝（连数字一起切开，像真的翻页卡片）
            val seam = maxOf(1, s / 3)
            fill(cx, mid - seam / 2, cw, seam, BLACK)
        }
        return intArrayOf(x0, y0, tw, ch)
    }

    // ---------- 数码管 ----------

    /** 一个七段数字：宽 6u、高 11u，笔画粗 u；不亮的段画成微亮（像真的数码管那样看得见底）。 */
    fun segmentDigit(d: Int, x: Int, y: Int, u: Int, fill: Fill, ghost: Boolean = true) {
        val on = SEGMENTS[d]
        val w = 6 * u; val h = 11 * u; val t = u
        val half = h / 2
        fun seg(i: Int, sx: Int, sy: Int, sw: Int, sh: Int) {
            val lit = on shr i and 1 == 1
            if (lit) fill(sx, sy, sw, sh, FULL) else if (ghost) fill(sx, sy, sw, sh, FAINT)
        }
        seg(0, x + t, y, w - 2 * t, t)                           // a
        seg(1, x + w - t, y + t, t, half - t - t / 2)             // b
        seg(2, x + w - t, y + half + (t + 1) / 2, t, half - t - (t + 1) / 2 + (h - 2 * half)) // c
        seg(3, x + t, y + h - t, w - 2 * t, t)                   // d
        seg(4, x, y + half + (t + 1) / 2, t, half - t - (t + 1) / 2 + (h - 2 * half))         // e
        seg(5, x, y + t, t, half - t - t / 2)                     // f
        seg(6, x + t, y + half - t / 2, w - 2 * t, t)             // g
    }

    private fun segmentClock(hh: Int, mm: Int, seconds: Int?, x: Int, y: Int, w: Int, h: Int, fill: Fill, colon: Boolean, ghost: Boolean): IntArray {
        // 宽度（以 u 计）：4 个数字 24u + 数字间 2×2u + 冒号区 6u = 34u；带秒再加 2u 空隙 + 两个半大的数字 7u
        val units = if (seconds != null) 43 else 34
        val u = minOf(w / units, h / 11).coerceAtLeast(1)
        val tw = units * u; val th = 11 * u
        var cx = x + (w - tw) / 2
        val y0 = y + (h - th) / 2
        val digits = two(hh) + two(mm)
        for (i in 0..3) {
            segmentDigit(digits[i] - '0', cx, y0, u, fill, ghost)
            cx += 6 * u
            when (i) {
                0, 2 -> cx += 2 * u
                1 -> {
                    // 冒号：两个方点
                    val lv = if (colon) FULL else FAINT
                    fill(cx + 2 * u, y0 + 3 * u, u + u / 2, u + u / 2, lv)
                    fill(cx + 2 * u, y0 + 7 * u, u + u / 2, u + u / 2, lv)
                    cx += 6 * u
                }
            }
        }
        if (seconds != null) {
            // 秒：半大的数码管，底部对齐
            val su = maxOf(1, u / 2)
            cx += 2 * u
            val sy = y0 + th - 11 * su
            segmentDigit(seconds / 10, cx, sy, su, fill, ghost); segmentDigit(seconds % 10, cx + 7 * su, sy, su, fill, ghost)
        }
        return intArrayOf(x + (w - tw) / 2, y0, tw, th)
    }

    // ---------- 二进制 ----------

    private fun binaryClock(hh: Int, mm: Int, seconds: Int?, x: Int, y: Int, w: Int, h: Int, fill: Fill): IntArray {
        val cols = bcd(hh, mm, seconds)
        val groups = cols.size / 2
        // 格子边长 c：列距 c/3、组距 c；下面留 3×5 小数字（放大 k 倍）和一点空隙
        fun width(c: Int) = cols.size * c + groups * (c / 3) + (groups - 1) * c
        fun labelScale(c: Int) = maxOf(1, c / 8)
        fun height(c: Int) = 4 * c + 3 * (c / 4) + c / 2 + 5 * labelScale(c)
        var c = 40
        while (c > 3 && (width(c) > w || height(c) > h)) c--
        val gIn = c / 3; val gRow = c / 4
        val tw = width(c); val th = height(c)
        val x0 = x + (w - tw) / 2; val y0 = y + (h - th) / 2
        var cx = x0
        cols.forEachIndexed { i, (v, bits) ->
            for (b in 0 until 4) {
                val cy = y0 + (3 - b) * (c + gRow)
                if (b >= bits) continue
                if (v shr b and 1 == 1) fill(cx, cy, c, c, FULL)
                else {
                    // 不亮的格子：微亮的 1 像素边框
                    val t = maxOf(1, c / 10)
                    fill(cx, cy, c, t, FAINT); fill(cx, cy + c - t, c, t, FAINT)
                    fill(cx, cy + t, t, c - 2 * t, FAINT); fill(cx + c - t, cy + t, t, c - 2 * t, FAINT)
                }
            }
            // 读数
            val k = labelScale(c)
            pattern(DIGITS_3X5[v], cx + (c - 3 * k) / 2, y0 + 4 * c + 3 * gRow + c / 2, k, DIM, fill)
            cx += c + if (i % 2 == 1) c else gIn
        }
        return intArrayOf(x0, y0, tw, th)
    }

    // ---------- 进度环（番茄钟） ----------

    /**
     * 一圈 n 个方格的中心坐标：从正上方开始顺时针。
     */
    fun ring(n: Int, cx: Int, cy: Int, r: Int): List<Pair<Int, Int>> = (0 until n).map { i ->
        val a = 2 * Math.PI * i / n
        Pair(cx + Math.round(r * Math.sin(a)).toInt(), cy - Math.round(r * Math.cos(a)).toInt())
    }
}
