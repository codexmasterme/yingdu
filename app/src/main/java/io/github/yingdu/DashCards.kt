package io.github.yingdu

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 看板卡片（插件）：看板由若干张卡片组成，每张卡片产出若干行，两张一屏并排显示，多屏自动轮换。
 * 这个文件只放纯 Kotlin 的部分（卡片类型、各卡片的内容、分屏），方便单元测试；
 * 存储和联网在 DashCardStore / ReaderService 里。
 */

/** 卡片里的一行：左边是主要内容；中间、右边右对齐。有中间列时右边列半亮（例如股票的涨跌幅）。 */
data class CardRow(val left: String, val mid: String = "", val right: String = "", val todo: Boolean = false,
                   /** 像素表盘卡片：整栏画成这种表盘（PixelClock 的样式），left 是文字模式下的时间。 */
                   val face: Int = -1,
                   /** 0..1：左右两段文字中间画一条进度条（番茄钟）。 */
                   val progress: Float = -1f,
                   /** 紧跟在 left 后面的半亮小字（副标题）。 */
                   val sub: String = "",
                   /** 0..1：这一行下面画一条细进度线（进度）。 */
                   val underline: Float = -1f,
                   /** 最多折成几行；放不下的末尾加省略号。 */
                   val lines: Int = 1,
                   /** 整行半亮。 */
                   val dim: Boolean = false,
                   /** 中间那一栏前面的小图标（DashboardImage.ICONS 的名字，美股的太阳 / 月亮）。 */
                   val midIcon: String = "") {
    /** 提词器文字模式下用的一行。 */
    fun plain(): String = (if (todo) "□ " else "") +
        listOf(left, sub, mid, if (progress >= 0f) CardContent.bar(progress * 100.0) else "", right,
            if (underline >= 0f) CardContent.bar(underline * 100.0) else "").filter { it.isNotBlank() }.joinToString(" ")
}

enum class CardType(val title: String, val desc: String, val builtIn: Boolean = true) {
    STOCKS("股票", "自选股价格和涨跌幅"),
    AGENDA("日程待办", "手机日历的日程和待办"),
    FORECAST("天气预报", "接下来几个小时和明后两天"),
    COUNTDOWN("倒数日", "离重要日子还有几天"),
    CLOCKS("世界时钟", "其他城市现在几点"),
    FACE("像素表盘", "翻页钟、数码管、二进制钟"),
    POMODORO("番茄钟", "剩余时间（没开始时隐藏）"),
    MEMORY("今日回顾", "全天记忆的总结和要做的事"),
    SIGHTS("附近景点", "3 公里内的景点，从近到远"),
    WEB("网络卡片", "从网址读内容，如公交、快递", builtIn = false),
}

/** 一张卡片的设置。text 是倒数日、世界时钟的列表；url / headers / body / minutes 给网络卡片用。 */
data class CardConfig(
    val id: String,
    val type: CardType,
    val enabled: Boolean = true,
    val name: String = type.title,
    val text: String = "",
    val url: String = "",
    val headers: String = "",
    val body: String = "",
    val minutes: Int = 5,
) {
    companion object {
        const val DEFAULT_COUNTDOWN = "元旦 01-01\n春节 2027-02-06"
        const val DEFAULT_CLOCKS = "纽约 America/New_York\n伦敦 Europe/London\n东京 Asia/Tokyo"

        /** 第一次使用时的卡片：和 v2.1 之前的看板一样只开股票和日程，其余可以在 app 里打开。 */
        fun defaults(): List<CardConfig> = listOf(
            CardConfig("STOCKS", CardType.STOCKS),
            CardConfig("AGENDA", CardType.AGENDA),
            CardConfig("FORECAST", CardType.FORECAST, enabled = false),
            CardConfig("COUNTDOWN", CardType.COUNTDOWN, enabled = false, text = DEFAULT_COUNTDOWN),
            CardConfig("CLOCKS", CardType.CLOCKS, enabled = false, text = DEFAULT_CLOCKS),
            CardConfig("FACE", CardType.FACE, enabled = false, text = "flip"),
            CardConfig("POMODORO", CardType.POMODORO),
            CardConfig("MEMORY", CardType.MEMORY),
            CardConfig("SIGHTS", CardType.SIGHTS, enabled = false),
        )
    }
}

data class HourForecast(val hour: Int, val desc: String, val temp: Double)
data class DayForecast(val desc: String, val low: Double, val high: Double)

object CardContent {
    private val WEEK = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    fun stocks(quotes: List<Quote>): List<CardRow> = quotes.map { q ->
        val name = q.name ?: q.symbol.substringBefore('.').removePrefix("^").uppercase(Locale.US)
        if (q.price == null) CardRow(name, "--")
        else CardRow(name,
            if (q.price >= 1000) String.format(Locale.US, "%.0f", q.price) else String.format(Locale.US, "%.2f", q.price),
            // 涨用实心三角、跌用空心三角，单色屏上也能一眼分辨
            q.changePct?.let { (if (it >= 0) "▲" else "▽") + String.format(Locale.US, "%.2f%%", kotlin.math.abs(it)) } ?: "",
            // 美股：正常时段的价格前面画太阳，盘前 / 盘后 / 夜盘画月亮
            midIcon = if (!q.us) "" else if (q.session == null) "sun" else "moon")
    }

    /** 全天记忆的回顾：先列要做的事（方框），再列聊过的话题。 */
    fun memory(todos: List<Pair<String, String>>, topics: List<String>): List<CardRow> =
        todos.map { CardRow(it.first, right = it.second, todo = true) } + topics.map { CardRow(it) }

    /** 从现在起最近的日程（最多 limit 项）；不足时用未完成的待办补上。 */
    fun agenda(events: List<CalEvent>, todos: List<Todo>, now: Long, limit: Int): List<CardRow> {
        val today = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val day = 24L * 3600_000
        val hm = java.text.SimpleDateFormat("HH:mm", Locale.US)
        val md = java.text.SimpleDateFormat("M/d", Locale.US)
        val ev = events.take(limit).map { e ->
            val d = when {
                e.start < today + day -> ""
                e.start < today + 2 * day -> "明天 "
                else -> md.format(java.util.Date(e.start)) + " "
            }
            CardRow(d + (if (e.allDay) "" else hm.format(java.util.Date(e.start)) + " ") + e.title)
        }
        val fill = (limit - ev.size).coerceAtLeast(0)
        return ev + todos.filter { !it.done }.take(fill).map { CardRow(it.text, todo = true) }
    }

    /** 用 ■□ 画一条 5 格的进度条。 */
    fun bar(v: Double): String { val n = ((v + 10) / 20).toInt().coerceIn(0, 5); return "■".repeat(n) + "□".repeat(5 - n) }

    /** 像素表盘：一行，画的时候整栏是表盘（文字模式下显示 HH:MM）。 */
    fun face(style: Int, hour: Int, minute: Int): List<CardRow> =
        listOf(CardRow(String.format(Locale.US, "%02d:%02d", hour, minute), face = style))

    /** 番茄钟（开始了才显示）：阶段和剩余时间、进度、今天完成几个。 */
    fun pomodoro(phase: String, running: Boolean, leftMs: Long, progress: Double, done: Int): List<CardRow> = listOf(
        CardRow("番茄 · $phase", right = PomoLogic.clock(leftMs) + if (running) "" else " 暂停"),
        CardRow("", progress = progress.toFloat().coerceIn(0f, 1f)),
        CardRow("今天完成", right = "$done 个"),
    )

    /** 接下来每 3 小时两个时段 + 明后天。 */
    fun forecast(hours: List<HourForecast>, days: List<DayForecast>): List<CardRow> {
        val out = ArrayList<CardRow>()
        hours.filterIndexed { i, _ -> i % 3 == 2 }.take(2).forEach { out.add(CardRow("${it.hour}时 ${it.desc}", right = "${it.temp.toInt()}°")) }
        days.drop(1).take(2).forEachIndexed { i, d ->
            out.add(CardRow((if (i == 0) "明天 " else "后天 ") + d.desc, right = "${d.low.toInt()}~${d.high.toInt()}°"))
        }
        return out
    }

    /**
     * 倒数日：每行「名称 日期」，日期写 2027-02-06（某一天）或 12-25（每年这一天）。
     * 已经过去的不显示；按远近排序。
     */
    fun countdown(text: String, now: Long): List<CardRow> {
        val today = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val items = ArrayList<Pair<String, Int>>()
        for (raw in text.lines()) {
            val line = raw.trim()
            val m = Regex("""^(.*?)\s*(?:(\d{4})[-/.])?(\d{1,2})[-/.](\d{1,2})$""").find(line) ?: continue
            val name = m.groupValues[1].trim()
            if (name.isEmpty()) continue
            val yearly = m.groupValues[2].isEmpty()
            val month = m.groupValues[3].toInt() - 1
            val dayOfMonth = m.groupValues[4].toInt()
            val target = Calendar.getInstance().apply {
                clear(); set(if (yearly) today.get(Calendar.YEAR) else m.groupValues[2].toInt(), month, dayOfMonth)
            }
            if (yearly && target.before(today)) target.add(Calendar.YEAR, 1)
            val days = Math.round((target.timeInMillis - today.timeInMillis) / 86_400_000.0).toInt()
            if (days >= 0) items.add(name to days)
        }
        return items.sortedBy { it.second }.map { (name, d) -> CardRow(name, right = if (d == 0) "就是今天" else "还有 $d 天") }
    }

    /** 世界时钟：每行「名称 时区」，时区写 IANA 名称，例如 America/New_York。 */
    fun clocks(text: String, now: Long, localZone: TimeZone = TimeZone.getDefault()): List<CardRow> {
        val local = Calendar.getInstance(localZone).apply { timeInMillis = now }
        return text.lines().mapNotNull { raw ->
            val parts = raw.trim().split(Regex("\\s+"))
            if (parts.size < 2) return@mapNotNull null
            val zoneId = parts.last()
            if (zoneId !in TimeZone.getAvailableIDs()) return@mapNotNull null
            val c = Calendar.getInstance(TimeZone.getTimeZone(zoneId)).apply { timeInMillis = now }
            val dayDiff = c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR) - (local.get(Calendar.YEAR) * 400 + local.get(Calendar.DAY_OF_YEAR))
            val tag = when { dayDiff > 0 -> "明天"; dayDiff < 0 -> "昨天"; else -> WEEK[c.get(Calendar.DAY_OF_WEEK) - 1] }
            CardRow(parts.dropLast(1).joinToString(" "), right = String.format(Locale.US, "%s %02d:%02d", tag, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)))
        }
    }

    /**
     * 网络卡片返回的内容（萤读卡片格式）：
     *  - 纯文本：每行一项；用 " | " 或 Tab 分成左、中、右；以 "□ " 开头画成待办方框；
     *  - JSON：{"rows": [...]} 或直接一个数组，每项是字符串、[左, 中, 右] 数组，或 {"left","mid","right","todo"} 对象。
     */
    fun parseWeb(body: String): List<CardRow> {
        val t = body.trim()
        if (t.startsWith("{") || t.startsWith("[")) {
            runCatching {
                val arr = if (t.startsWith("[")) org.json.JSONArray(t) else org.json.JSONObject(t).getJSONArray("rows")
                return (0 until arr.length()).map { i ->
                    when (val v = arr.get(i)) {
                        is org.json.JSONArray -> CardRow(v.optString(0), v.optString(1), v.optString(2))
                        is org.json.JSONObject -> CardRow(v.optString("left"), v.optString("mid"), v.optString("right"), v.optBoolean("todo"))
                        else -> textRow(v.toString())
                    }
                }.filter { it.left.isNotBlank() || it.right.isNotBlank() }
            }
        }
        return t.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.map { textRow(it) }
    }

    private fun textRow(line: String): CardRow {
        val todo = line.startsWith("□")
        val s = if (todo) line.removePrefix("□").trim() else line
        val cols = s.split(Regex("\t| \\| ")).map { it.trim() }
        return when (cols.size) {
            1 -> CardRow(cols[0], todo = todo)
            2 -> CardRow(cols[0], right = cols[1], todo = todo)
            else -> CardRow(cols[0], cols[1], cols.drop(2).joinToString(" "), todo)
        }
    }
}

/** 卡片框左上角的图标 + 名称，右上角的小字（更新时间、等级、项数……）。icon 见 DashboardImage.ICONS。 */
data class CardHead(val icon: String, val title: String, val meta: String = "")

/**
 * 看板的一屏：左右两张卡片的若干行；right 为 null 表示左边这张占满整屏。
 * leftHead / rightHead 是两张卡片的标题（画圆角框用）；同一张卡片分左右两栏（只开股票）时两个是同一个对象。
 */
data class DashScreen(val left: List<CardRow>, val right: List<CardRow>?,
                      val leftHead: CardHead? = null, val rightHead: CardHead? = null)

object DashLayout {
    /**
     * 把各卡片的行（空卡片跳过）两两并排分屏：一张卡片超过 rows 行时分成几屏，
     * 旁边那张只有一屏时每屏都重复显示（例如股票轮换时日程一直在）。
     */
    fun screens(cards: List<List<CardRow>>, rows: Int, pairSingle: Boolean = false, heads: List<CardHead?> = emptyList()): List<DashScreen> {
        val nonEmpty = cards.indices.filter { cards[it].isNotEmpty() }
        if (nonEmpty.isEmpty()) return listOf(DashScreen(listOf(CardRow("还没有可显示的卡片，在 app 的看板页打开")), null))
        fun head(k: Int) = heads.getOrNull(nonEmpty[k])
        // 只有一张卡片（例如只开了股票）且一屏放不下：左右两栏接着排，一屏放 2×rows 项
        if (pairSingle && nonEmpty.size == 1 && cards[nonEmpty[0]].size > rows)
            return cards[nonEmpty[0]].chunked(rows).chunked(2).map { DashScreen(it[0], it.getOrElse(1) { emptyList() }, head(0), head(0)) }
        val out = ArrayList<DashScreen>()
        for (k in nonEmpty.indices step 2) {
            val a = cards[nonEmpty[k]].chunked(rows)
            val b = nonEmpty.getOrNull(k + 1)?.let { cards[it].chunked(rows) }
            val n = maxOf(a.size, b?.size ?: 0)
            for (i in 0 until n) {
                fun pick(c: List<List<CardRow>>) = c.getOrElse(i) { if (c.size == 1) c[0] else emptyList() }
                out.add(DashScreen(pick(a), b?.let { pick(it) }, head(k), if (b != null) head(k + 1) else null))
            }
        }
        return out
    }
}
