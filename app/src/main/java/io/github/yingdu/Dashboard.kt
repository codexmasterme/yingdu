package io.github.yingdu

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * name：有中文简称时（A 股）显示简称，否则显示代码。
 * session：美股不在正常交易时段时，价格来自哪个时段（"盘前" / "盘后" / "夜盘"），涨跌幅相对最近一个收盘价；正常时段为 null。
 */
data class Quote(val symbol: String, val price: Double?, val changePct: Double?, val name: String? = null, val session: String? = null,
                 /** 美股（正常时段的价格前面画太阳，盘前盘后夜盘画月亮）。 */
                 val us: Boolean = false)

/**
 * 美股的盘前、盘后、夜盘（纯 Kotlin，有单元测试）。雅虎的接口只有正常时段的价格，盘前盘后要取分钟线、夜盘干脆没有；
 * 微牛（Webull）的行情接口一次给全：正常时段收盘价 close、最新的盘前 / 盘后 / 夜盘价 pPrice、最新成交时间 tradeTime。
 */
object UsQuote {
    private val NY = java.util.TimeZone.getTimeZone("America/New_York")

    /** 雅虎的美股代码（没有交易所后缀，比如 NVDA、BRK-B）。 */
    fun isUs(symbol: String) = Regex("^[A-Z][A-Z0-9-]{0,9}$").matches(symbol)

    /** 这个时间（毫秒）在纽约时间属于哪个时段：正常时段（周一到周五 9:30～16:00）返回 null。 */
    fun session(millis: Long): String? {
        val c = java.util.Calendar.getInstance(NY).apply { timeInMillis = millis }
        val m = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        val weekday = c.get(java.util.Calendar.DAY_OF_WEEK) in java.util.Calendar.MONDAY..java.util.Calendar.FRIDAY
        return when {
            weekday && m in 570 until 960 -> null
            m in 240 until 570 -> "盘前"
            m in 960 until 1200 -> "盘后"
            else -> "夜盘"
        }
    }

    private fun time(s: String): Long? = runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", java.util.Locale.US).parse(s)!!.time
    }.getOrNull()

    /**
     * 解析微牛的 getQuote：最新成交在正常时段以外、而且比正常时段最后一笔新，就显示盘前 / 盘后 / 夜盘价（涨跌相对收盘价）；
     * 否则显示正常时段的价格和涨跌。
     */
    fun parseWebull(symbol: String, json: String): Quote? {
        val o = org.json.JSONObject(json)
        val close = o.optString("close").toDoubleOrNull() ?: return null
        val chg = o.optString("changeRatio").toDoubleOrNull()?.times(100)
        val p = o.optString("pPrice").toDoubleOrNull()
        val trade = time(o.optString("tradeTime"))
        val regular = time(o.optString("mkTradeTime"))
        if (p != null && p > 0 && trade != null && (regular == null || trade > regular)) {
            val s = session(trade)
            if (s != null) return Quote(symbol, p, o.optString("pChRatio").toDoubleOrNull()?.times(100), session = s, us = true)
        }
        return Quote(symbol, close, chg, us = true)
    }

    private fun instant(s: String): Long? = runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()

    /** Robinhood 的代码：BRK-B 写成 BRK.B。 */
    fun robinhoodSymbol(symbol: String) = symbol.replace('-', '.')

    /**
     * 解析 Robinhood 的 24 小时行情（bounds=24_5，不用登录）：盘前、盘后、夜盘（Blue Ocean）都有最新成交。
     * 正常时段以外的成交比正常时段最后一笔新，就显示它（涨跌相对最近一个收盘价 previous_close）；否则显示正常时段的价格。
     */
    fun parseRobinhood(symbol: String, json: String): Quote? {
        val r = org.json.JSONObject(json).optJSONArray("results")?.optJSONObject(0) ?: return null
        val prev = r.optString("previous_close").toDoubleOrNull()
        fun pct(p: Double) = prev?.takeIf { it > 0 }?.let { (p / it - 1) * 100 }
        val reg = r.optString("last_trade_price").toDoubleOrNull()
        val regT = instant(r.optString("venue_last_trade_time"))
        val ext = r.optString("last_non_reg_trade_price").toDoubleOrNull()
        val extT = instant(r.optString("venue_last_non_reg_trade_time"))
        if (ext != null && ext > 0 && extT != null && (regT == null || extT > regT)) {
            val s = session(extT)
            if (s != null) return Quote(symbol, ext, pct(ext), session = s, us = true)
        }
        return reg?.let { Quote(symbol, it, pct(it), us = true) }
    }

    /** 比较代码时去掉分隔符（雅虎的 BRK-B 在微牛是「BRK B」，显示成 BRK-B）。 */
    private fun norm(s: String) = s.uppercase(java.util.Locale.US).replace(Regex("[-. ]"), "")

    /** 微牛搜索时用的关键词。 */
    fun searchKey(symbol: String) = symbol.replace('-', ' ')

    /** 微牛搜索结果里这个美股代码的 tickerId。 */
    fun parseTickerId(symbol: String, json: String): Long? {
        val want = norm(symbol)
        val arr = org.json.JSONObject(json).optJSONArray("data") ?: return null
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            if (d.optString("regionCode") == "US" && (norm(d.optString("symbol")) == want || norm(d.optString("disSymbol")) == want))
                return d.optLong("tickerId").takeIf { it > 0 }
        }
        return null
    }
}
data class Weather(val desc: String, val temp: Double, val high: Double, val low: Double,
                   val hours: List<HourForecast> = emptyList(), val days: List<DayForecast> = emptyList())
data class Place(val name: String, val lat: Double, val lon: Double)

/**
 * 看板的提词器文字排版（纯文字，不依赖 Android，可以单元测试）。
 * 一屏的两张卡片左右并排，中间用竖线隔开；只有一张时占满整行。
 */
object DashboardFormatter {
    /** 宽度单位：半格。中文等全角字符 2，ASCII 1。 */
    fun width(s: String) = s.sumOf { c: Char -> if (c.code < 0x80) 1.toInt() else 2.toInt() }

    private fun truncate(s: String, maxHalf: Int): String {
        if (width(s) <= maxHalf) return s
        val sb = StringBuilder()
        var w = 0
        for (c in s) {
            val cw = if (c.code < 0x80) 1 else 2
            if (w + cw > maxHalf - 2) break
            sb.append(c); w += cw
        }
        return sb.append('…').toString()
    }

    /** 用全角空格补整格、半角空格补半格，尽量让分隔线对齐。 */
    private fun padTo(s: String, half: Int): String {
        var gap = half - width(s)
        if (gap <= 0) return s
        val sb = StringBuilder(s)
        while (gap >= 2) { sb.append('\u3000'); gap -= 2 }
        if (gap == 1) sb.append(' ')
        return sb.toString()
    }

    /**
     * 排出一屏的正文。
     * @param charsPerLine 每行能放的中文字数
     */
    fun body(screen: DashScreen, charsPerLine: Int): String {
        val lineHalf = charsPerLine * 2
        val ls = screen.left.map { it.plain() }
        val rs = screen.right?.map { it.plain() } ?: emptyList()
        if (ls.isEmpty() && rs.isEmpty()) return ""
        val sep = " │ "
        if (rs.isEmpty()) return ls.joinToString("\n") { truncate(it, lineHalf) }
        if (ls.isEmpty()) return rs.joinToString("\n") { truncate(it, lineHalf) }
        // 左栏按最长的一行定宽，但不超过一半
        val left = minOf(ls.maxOf { width(it) }, lineHalf / 2).let { if (it % 2 == 1) it + 1 else it }
        val right = lineHalf - left - width(sep)
        return (0 until maxOf(ls.size, rs.size)).joinToString("\n") { i ->
            val l = padTo(truncate(ls.getOrElse(i) { "" }, left), left)
            val r = rs.getOrNull(i)?.let { truncate(it, right) } ?: ""
            if (r.isEmpty()) l.trimEnd('\u3000', ' ') else l + sep + r
        }
    }

    /** WMO 天气代码转中文。 */
    fun weatherDesc(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "晴间多云"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55, 56, 57 -> "毛毛雨"
        61, 63, 66 -> "小雨"
        65, 67 -> "大雨"
        71, 73, 75, 77 -> "雪"
        80, 81, 82 -> "阵雨"
        85, 86 -> "阵雪"
        95, 96, 99 -> "雷雨"
        else -> "—"
    }
}

/**
 * 联网取数据。股票用雅虎财经的公开行情接口（非官方，可能延迟或偶尔失效），
 * 天气和城市坐标用 Open-Meteo（免费，不需要 API Key）。都在后台线程调用。
 */
object DashboardData {
    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NimoReader")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun geocode(name: String): Place? {
        val json = org.json.JSONObject(get(
            "https://geocoding-api.open-meteo.com/v1/search?count=1&language=zh&format=json&name=${enc(name)}"))
        val r = json.optJSONArray("results")?.optJSONObject(0) ?: return null
        return Place(r.optString("name", name), r.getDouble("latitude"), r.getDouble("longitude"))
    }

    /** 免费的反向地理编码（不需要 API Key）。 */
    fun reverseGeocode(lat: Double, lon: Double): String? {
        val j = org.json.JSONObject(get(
            "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=$lat&longitude=$lon&localityLanguage=zh"))
        return listOf("city", "locality", "principalSubdivision").map { j.optString(it) }.firstOrNull { it.isNotBlank() }
    }

    /** 现在的天气 + 接下来 12 小时 + 今明后三天（给「天气预报」卡片用）。 */
    fun weather(lat: Double, lon: Double): Weather {
        val j = org.json.JSONObject(get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,weather_code" +
                "&hourly=temperature_2m,weather_code&timezone=auto&forecast_days=3"))
        val cur = j.getJSONObject("current")
        val daily = j.getJSONObject("daily")
        val hourly = j.getJSONObject("hourly")
        // 小时预报的时间是当地时间 "2026-09-27T15:00"，取当前时间之后的 12 个小时
        val nowKey = cur.getString("time").take(13)
        val ht = hourly.getJSONArray("time")
        val start = (0 until ht.length()).firstOrNull { ht.getString(it).take(13) > nowKey } ?: ht.length()
        val hours = (start until minOf(ht.length(), start + 12)).map { i ->
            HourForecast(ht.getString(i).substring(11, 13).toInt(),
                DashboardFormatter.weatherDesc(hourly.getJSONArray("weather_code").getInt(i)),
                hourly.getJSONArray("temperature_2m").getDouble(i))
        }
        val days = (0 until daily.getJSONArray("time").length()).map { i ->
            DayForecast(DashboardFormatter.weatherDesc(daily.getJSONArray("weather_code").getInt(i)),
                daily.getJSONArray("temperature_2m_min").getDouble(i), daily.getJSONArray("temperature_2m_max").getDouble(i))
        }
        return Weather(
            DashboardFormatter.weatherDesc(cur.getInt("weather_code")),
            cur.getDouble("temperature_2m"),
            daily.getJSONArray("temperature_2m_max").getDouble(0),
            daily.getJSONArray("temperature_2m_min").getDouble(0),
            hours, days)
    }

    /**
     * 网络卡片：按设置请求网址。有请求体就用 POST（例如 Home Assistant 的 /api/template），否则 GET。
     * headers 每行一个「名称: 值」。返回的内容交给 CardContent.parseWeb。
     */
    fun fetchCard(c: CardConfig): String {
        val conn = URL(c.url.trim()).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NimoReader")
        for (line in c.headers.lines()) {
            val k = line.substringBefore(':').trim()
            if (k.isNotEmpty() && line.contains(':')) conn.setRequestProperty(k, line.substringAfter(':').trim())
        }
        try {
            if (c.body.isNotBlank()) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                if (conn.getRequestProperty("Content-Type") == null) conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(c.body.toByteArray(Charsets.UTF_8)) }
            }
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * A 股代码（600519.SS 上海 / 000001.SZ 深圳 / 830799.BJ 北京）换成腾讯行情的写法 sh600519；不是 A 股返回 null。
     */
    fun cnCode(symbol: String): String? {
        val code = symbol.substringBefore('.')
        if (code.length != 6 || !code.all { it.isDigit() }) return null
        return when (symbol.substringAfter('.', "").uppercase(java.util.Locale.US)) {
            "SS", "SH" -> "sh"; "SZ" -> "sz"; "BJ" -> "bj"; else -> return null
        } + code
    }

    /**
     * 腾讯行情的一条：v_sh600519="1~贵州茅台~600519~现价~昨收~今开~…";
     * 用现价和昨收自己算涨跌幅；停牌时现价为 0，按昨收显示、涨跌 0。
     */
    fun parseTencent(symbol: String, body: String): Quote? {
        val f = body.substringAfter('"', "").substringBefore('"').split('~')
        if (f.size < 5) return null
        val name = f[1].trim().ifEmpty { null }
        val prev = f[4].toDoubleOrNull()
        val now = f[3].toDoubleOrNull()?.takeIf { it > 0 } ?: prev ?: return null
        return Quote(symbol, now, prev?.takeIf { it > 0 }?.let { (now - it) / it * 100 }, name)
    }

    private fun tencentQuote(symbol: String, code: String): Quote? {
        val c = URL("https://qt.gtimg.cn/q=$code").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NimoReader")
        return try {
            if (c.responseCode !in 200..299) null
            else parseTencent(symbol, String(c.inputStream.use { it.readBytes() }, java.nio.charset.Charset.forName("GBK")))
        } finally { c.disconnect() }
    }

    /**
     * A 股走腾讯行情（实时、带中文简称）；美股先走 Robinhood 的 24 小时行情（盘前、盘后、夜盘都有），
     * 不行再走微牛（有盘前盘后、没有夜盘）；取不到都退回雅虎。
     */
    fun quote(symbol: String): Quote {
        cnCode(symbol)?.let { code -> runCatching { tencentQuote(symbol, code) }.getOrNull()?.let { return it } }
        if (UsQuote.isUs(symbol)) {
            runCatching { robinhoodQuote(symbol) }.getOrNull()?.let { return it }
            runCatching { webullQuote(symbol) }.getOrNull()?.let { return it }
            return yahooQuote(symbol).let { if (it.price != null) it.copy(us = true) else it }
        }
        return yahooQuote(symbol)
    }

    private fun robinhoodQuote(symbol: String): Quote? = UsQuote.parseRobinhood(symbol, get(
        "https://api.robinhood.com/marketdata/quotes/?symbols=${enc(UsQuote.robinhoodSymbol(symbol))}&bounds=24_5"))

    /** 微牛的 tickerId（每只股票查一次记住）；查不到的记成 0，不再反复查。 */
    private val tickerIds = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun webullQuote(symbol: String): Quote? {
        val id = tickerIds.getOrPut(symbol) {
            runCatching { UsQuote.parseTickerId(symbol, get(
                "https://quotes-gw.webullfintech.com/api/search/pc/tickers?pageIndex=1&pageSize=5&keyword=${enc(UsQuote.searchKey(symbol))}")) }
                .getOrNull() ?: 0L
        }
        if (id == 0L) return null
        return UsQuote.parseWebull(symbol, get(
            "https://quotes-gw.webullfintech.com/api/stock/tickerRealTime/getQuote?tickerId=$id&includeSecu=1&includeQuote=1&more=1"))
    }

    private fun yahooQuote(symbol: String): Quote = try {
        val j = org.json.JSONObject(get(
            "https://query1.finance.yahoo.com/v8/finance/chart/${enc(symbol)}?range=1d&interval=1d"))
        val meta = j.getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta")
        val price = meta.getDouble("regularMarketPrice")
        val prev = when {
            meta.has("previousClose") -> meta.getDouble("previousClose")
            meta.has("chartPreviousClose") -> meta.getDouble("chartPreviousClose")
            else -> Double.NaN
        }
        Quote(symbol, price, if (prev.isNaN() || prev == 0.0) null else (price - prev) / prev * 100)
    } catch (e: Exception) {
        Quote(symbol, null, null)
    }
}
