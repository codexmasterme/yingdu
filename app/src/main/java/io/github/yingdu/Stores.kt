package io.github.yingdu

import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar

// =====================================================================
// 股票
// =====================================================================

/** on：看板上显示（取消勾选只是暂时不看，比如白天看日股、晚上看美股；真的不关注了才删掉）。 */
data class StockItem(val symbol: String, val name: String, val market: String, val on: Boolean = true)

/** 自选股列表（顺序即显示顺序），存在 SharedPreferences 里。 */
class StockStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("stocks", Context.MODE_PRIVATE)

    var items: List<StockItem>
        get() {
            val raw = prefs.getString("list", null) ?: return emptyList()
            val a = JSONArray(raw)
            return (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                StockItem(o.getString("s"), o.optString("n", o.getString("s")), o.optString("m", ""), o.optBoolean("o", true))
            }
        }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("s", it.symbol).put("n", it.name).put("m", it.market).put("o", it.on)) }
            prefs.edit().putString("list", a.toString()).apply()
        }

    fun symbols() = items.map { it.symbol }
    /** 勾选了要在看板上显示的。 */
    fun shown() = items.filter { it.on }.map { it.symbol }

    fun setOn(symbol: String, on: Boolean) { items = items.map { if (it.symbol == symbol) it.copy(on = on) else it } }

    fun add(item: StockItem) { if (items.none { it.symbol == item.symbol }) items = items + item }
    fun remove(symbol: String) { items = items.filter { it.symbol != symbol } }
    fun move(symbol: String, delta: Int) {
        val l = items.toMutableList()
        val i = l.indexOfFirst { it.symbol == symbol }
        val j = i + delta
        if (i < 0 || j !in l.indices) return
        l.add(j, l.removeAt(i)); items = l
    }

    /** 从旧版本的"逗号分隔"设置迁移过来。 */
    fun migrateFrom(csv: String) {
        if (prefs.contains("list")) return
        items = csv.split(',', '，', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            .map { StockItem(it, it, marketOf(it, "")) }
    }

    companion object {
        fun marketOf(symbol: String, exch: String): String = when {
            symbol.endsWith(".T") -> "JP"
            symbol.endsWith(".HK") -> "HK"
            symbol.endsWith(".SS") || symbol.endsWith(".SZ") || symbol.endsWith(".BJ") -> "A股"
            symbol.startsWith("^") -> "指数"
            exch.contains("Tokyo", true) -> "JP"
            else -> "US"
        }

        /**
         * 搜索：A 股先用腾讯的搜索（中文名、拼音缩写、6 位代码都能搜），再加上雅虎财经的结果（美股、日股、港股等）。
         */
        fun search(q: String): List<StockItem> {
            val cn = runCatching { searchCn(q) }.getOrDefault(emptyList())
            val other = runCatching { searchYahoo(q) }.getOrElse { if (cn.isEmpty()) throw it else emptyList() }
            return (cn + other.filter { o -> cn.none { it.symbol == o.symbol } }).take(15)
        }

        /** 6 位 A 股代码按开头猜交易所：6/9/5 上海，0/1/2/3 深圳，4/8/92 北京。 */
        fun guessCn(code: String): String? = when {
            code.length != 6 || !code.all { it.isDigit() } -> null
            code.startsWith("92") || code[0] == '4' || code[0] == '8' -> "$code.BJ"
            code[0] == '6' || code[0] == '9' || code[0] == '5' -> "$code.SS"
            else -> "$code.SZ"
        }

        /**
         * 腾讯证券的搜索：v_hint="sh~600519~\u8d35\u5dde\u8305\u53f0~gzmt~GP-A^sz~000001~…"，
         * 每一项是 市场~代码~名称~拼音~类型，名称是 \u 转义的。只取沪深北三地的。
         */
        fun parseCnHint(body: String): List<StockItem> {
            val s = Regex("\\\\u([0-9a-fA-F]{4})").replace(body.substringAfter('"', "").substringBefore('"')) {
                it.groupValues[1].toInt(16).toChar().toString()
            }
            return s.split('^').mapNotNull { e ->
                val f = e.split('~')
                if (f.size < 3 || f[1].length != 6) return@mapNotNull null
                val suffix = when (f[0]) { "sh" -> "SS"; "sz" -> "SZ"; "bj" -> "BJ"; else -> return@mapNotNull null }
                StockItem("${f[1]}.$suffix", f[2].ifEmpty { f[1] }, "A股")
            }
        }

        private fun searchCn(q: String): List<StockItem> {
            val c = URL("https://smartbox.gtimg.cn/s3/?v=2&t=all&q=" + URLEncoder.encode(q.trim(), "UTF-8")).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NimoReader")
            val found = try { parseCnHint(c.inputStream.bufferedReader().use { it.readText() }) } catch (e: Exception) { emptyList() } finally { c.disconnect() }
            if (found.isNotEmpty()) return found
            // 搜索接口没结果时，6 位代码直接按开头猜交易所，用行情取中文简称
            val sym = guessCn(q.trim()) ?: return emptyList()
            val quote = DashboardData.quote(sym)
            return if (quote.price == null) emptyList() else listOf(StockItem(sym, quote.name ?: sym, "A股"))
        }

        /** 雅虎财经的搜索接口：代码、英文名都能搜；中文名不一定搜得到。 */
        private fun searchYahoo(q: String): List<StockItem> {
            val url = "https://query2.finance.yahoo.com/v1/finance/search?quotesCount=12&newsCount=0&listsCount=0&q=" +
                URLEncoder.encode(q, "UTF-8")
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NimoReader")
            val body = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
            val arr = JSONObject(body).optJSONArray("quotes") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val sym = o.optString("symbol").ifEmpty { return@mapNotNull null }
                val type = o.optString("quoteType")
                if (type !in listOf("EQUITY", "ETF", "INDEX", "MUTUALFUND", "CRYPTOCURRENCY")) return@mapNotNull null
                val name = o.optString("shortname").ifEmpty { o.optString("longname").ifEmpty { sym } }
                StockItem(sym, name, marketOf(sym, o.optString("exchDisp")))
            }
        }
    }
}

// =====================================================================
// 日程待办
// =====================================================================

data class Todo(val text: String, val done: Boolean = false)
data class CalEvent(val start: Long, val allDay: Boolean, val title: String)

class TodoStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("todos", Context.MODE_PRIVATE)

    var items: List<Todo>
        get() {
            val raw = prefs.getString("list", null) ?: return emptyList()
            val a = JSONArray(raw)
            return (0 until a.length()).map { a.getJSONObject(it).let { o -> Todo(o.getString("t"), o.optBoolean("d")) } }
        }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("t", it.text).put("d", it.done)) }
            prefs.edit().putString("list", a.toString()).apply()
        }

    fun add(text: String) { if (text.isNotBlank()) items = items + Todo(text.trim()) }
    fun toggle(i: Int) { items = items.mapIndexed { k, t -> if (k == i) t.copy(done = !t.done) else t } }
    fun remove(i: Int) { items = items.filterIndexed { k, _ -> k != i } }
    fun clearDone() { items = items.filter { !it.done } }

    fun migrateFrom(lines: String) {
        if (prefs.contains("list")) return
        items = lines.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { Todo(it) }
    }
}

/** 读取手机日历里今天和明天的日程（需要日历权限）。 */
object CalendarReader {
    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** 从现在起、往后 [days] 天内的日程，按开始时间排序（已经开始的不算）。 */
    fun upcoming(ctx: Context, days: Int = 14): List<CalEvent> {
        if (!hasPermission(ctx)) return emptyList()
        val now = System.currentTimeMillis()
        val end = now + days * 24L * 3600_000
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString()).appendPath(end.toString()).build()
        val out = ArrayList<CalEvent>()
        ctx.contentResolver.query(uri,
            arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.TITLE),
            null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(2) ?: continue
                val allDay = c.getInt(1) == 1
                // 全天日程的时间是 UTC 零点，换成本地当天零点再比较
                val begin = if (allDay) {
                    val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = c.getLong(0) }
                    java.util.Calendar.getInstance().apply {
                        clear(); set(utc.get(java.util.Calendar.YEAR), utc.get(java.util.Calendar.MONTH), utc.get(java.util.Calendar.DAY_OF_MONTH))
                    }.timeInMillis
                } else c.getLong(0)
                if (begin >= now) out.add(CalEvent(begin, allDay, title))
            }
        }
        return out.sortedWith(Comparator { a, b -> a.start.compareTo(b.start) })
    }
}

// =====================================================================
// 看板卡片
// =====================================================================

/** 看板卡片的顺序和设置，存在 SharedPreferences 里。内置卡片缺了会自动补上（关闭状态）。 */
class DashCardStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("dashCards", Context.MODE_PRIVATE)

    var items: List<CardConfig>
        get() {
            val raw = prefs.getString("list", null) ?: return CardConfig.defaults()
            val a = JSONArray(raw)
            val list = (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val type = runCatching { CardType.valueOf(o.getString("type")) }.getOrNull() ?: return@mapNotNull null
                CardConfig(o.getString("id"), type, o.optBoolean("on", true), o.optString("name", type.title),
                    o.optString("text"), o.optString("url"), o.optString("headers"), o.optString("body"), o.optInt("min", 5))
            }
            // 新版本加的卡片：按它自己的默认开关补上
            val missing = CardConfig.defaults().filter { d -> list.none { it.id == d.id } }
            return list + missing
        }
        set(v) {
            val a = JSONArray()
            v.forEach {
                a.put(JSONObject().put("id", it.id).put("type", it.type.name).put("on", it.enabled).put("name", it.name)
                    .put("text", it.text).put("url", it.url).put("headers", it.headers).put("body", it.body).put("min", it.minutes))
            }
            prefs.edit().putString("list", a.toString()).apply()
        }

    fun update(c: CardConfig) { items = items.map { if (it.id == c.id) c else it } }
    fun add(c: CardConfig) { items = items + c }
    fun remove(id: String) { items = items.filter { it.id != id } }
    fun move(id: String, delta: Int) {
        val l = items.toMutableList()
        val i = l.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in l.indices) return
        l.add(j, l.removeAt(i)); items = l
    }
}
