package io.github.yingdu

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 景点介绍：按手机的精确位置，每 15 / 30 分钟查一次附近 3 公里内的景点，走通知的渠道在眼镜上弹出介绍。
 *
 * 数据：
 *  - 附近有哪些景点（二选一）：高德地图（周边搜索，国内准，要 Web 服务 Key）、
 *    Google 地图（Places API (New) 的附近搜索，海外准，要 API Key）；
 *  - 介绍：百度百科（词条摘要）和中文维基百科（摘要，简体），按设置的先后顺序取，前一个没有再用后一个；
 *    都没有时用 Google 的一句话描述。都裁到一屏以内。
 * 这个文件前半部分是纯 Kotlin（解析、挑选、裁剪文字），有单元测试；联网在 SightsNet。
 */
data class Sight(
    val id: String,            // "a…" 高德 / "g…" Google
    val name: String,
    val lat: Double,
    val lon: Double,
    val kind: String,          // 中文类型：博物馆、寺庙……
    val distance: Int = 0,     // 米
    /** 地图服务标的"有名"（高德的世界遗产 / 国家级 / 省级景点，Google 评价多或有编辑简介）。 */
    val famous: Boolean = false,
    /** Google 的编辑简介（一句话）。 */
    val summary: String = "",
    /** 所在城市（高德给的，比如"北京市"），查百度百科时用来排除别处的同名景点。 */
    val city: String = "",
    /** 高德的父 POI 编号（展柜、打卡点、展厅这类挂在景区、博物馆下面的子点才有）。 */
    val parent: String = "",
)

object Sights {
    const val RADIUS_M = 3000
    /** 一轮最多介绍几个（多了下一轮走动后再说）。 */
    const val PER_ROUND = 5
    /** 介绍之间隔多久（毫秒）。 */
    const val GAP_MS = 15_000L
    /** 介绍过的多少天内不再介绍。 */
    const val FORGET_DAYS = 30
    /** 没走出这么远（米）就不开始新的一轮（原地不动时不会一直推）。 */
    const val MOVE_M = 500
    /** 介绍最多留多少字（手机上的记录显示全文；眼镜上再按字号裁成刚好一屏，见 DashboardImage.fitNotificationText）。 */
    const val INTRO_CHARS = 400
    /** 眼镜自带的通知弹窗正文约 85 个汉字。 */
    const val POPUP_CHARS = 85
    /** 摘要少于这么多字时再去取完整的导言（一屏大约 5 行 × 26 字）。 */
    const val SHORT_CHARS = 130

    /** 两点距离（米）。 */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return (2 * r * Math.asin(Math.sqrt(a))).toInt()
    }

    /** 这一轮介绍哪些：没介绍过的，有名的优先，再按远近；最多 PER_ROUND 个。 */
    fun pick(all: List<Sight>, seen: Set<String>, max: Int = PER_ROUND): List<Sight> =
        all.filter { key(it) !in seen }
            // 不用标准库的 compareBy(多个条件)：它在 kotlin-stdlib 里是用 invokedynamic 编的，安卓上会报 LambdaMetafactory 找不到
            .sortedWith(Comparator { a, b -> fame(a).compareTo(fame(b)).takeIf { it != 0 } ?: a.distance.compareTo(b.distance) })
            .take(max)

    /**
     * 看板卡片「附近景点」：按现在的位置重新算距离，从近到远，最多 max 个。
     * 一行：名字（有名的前面加★）、距离（半屏放不下类型那一列，名字会被挤得只剩两个字）。
     */
    fun cardRows(all: List<Sight>, lat: Double, lon: Double, max: Int = 12): List<CardRow> =
        all.map { it to distance(lat, lon, it.lat, it.lon) }
            .sortedBy { it.second }
            .take(max)
            .map { (s, d) -> CardRow((if (s.famous) "★" else "") + s.name, right = distanceText(d)) }

    /** 存下来的附近景点（看板卡片用）。 */
    fun toJson(all: List<Sight>): String = org.json.JSONArray().also { a ->
        all.forEach { s ->
            a.put(JSONObject().put("id", s.id).put("n", s.name).put("lat", s.lat).put("lon", s.lon)
                .put("k", s.kind).put("f", s.famous).put("c", s.city).put("p", s.parent))
        }
    }.toString()

    fun fromJson(json: String?): List<Sight> = runCatching {
        val a = org.json.JSONArray(json ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Sight(o.getString("id"), o.getString("n"), o.getDouble("lat"), o.getDouble("lon"), o.optString("k"),
                famous = o.optBoolean("f"), city = o.optString("c"), parent = o.optString("p"))
        }
    }.getOrDefault(emptyList())

    // ---- 同一个景点里的子点（展柜、展厅、打卡点、入口……）只留整体 ----

    /** 名字里分隔"整体"和"里面某处"的符号：故宫博物院-太和殿、上海博物馆(东馆)、西湖·断桥。 */
    private val SEPS = charArrayOf('-', '－', '—', '·', '・', '(', '（')

    /** 名字的"整体"部分（没有分隔符时就是名字本身）。 */
    fun baseName(name: String): String = name.split(*SEPS).first().trim().ifEmpty { name }

    /** 一看就是设施、不是景点的：入口、售票处、游客中心、停车场、展柜、打卡点…… */
    private val FACILITY = listOf("入口", "出口", "东门", "西门", "南门", "北门", "正门", "售票处", "售票厅", "检票口", "票务",
        "游客中心", "服务中心", "服务点", "咨询处", "停车场", "卫生间", "洗手间", "厕所", "寄存处", "打卡点", "打卡处", "打卡地", "展柜")

    fun isFacility(name: String) = FACILITY.any { name.endsWith(it) }

    /** 子点离整体多远以内才算是它里面的（同名分馆可能在别处）。 */
    const val CHILD_M = 800

    /**
     * 把同一个景点里的子点并成整体：
     * 1. 高德标了父 POI、父 POI 也在结果里的子点，去掉；
     * 2. 名字是"整体名 + 分隔符 + 某处"、整体也在附近的，去掉；
     * 3. 整体本身没搜到、但有好几个子点的（同一个父 POI，或者同一个整体名），并成一个，用整体名（取最近那个子点的位置）；
     * 4. 入口、售票处、展柜、打卡点这类设施去掉。
     */
    fun collapse(all: List<Sight>): List<Sight> {
        val byId = all.associateBy { it.id }
        val byName = all.groupBy { it.name }
        fun near(a: Sight, b: Sight) = distance(a.lat, a.lon, b.lat, b.lon) <= CHILD_M
        val kept = all.filter { s ->
            if (isFacility(s.name)) return@filter false
            val p = byId[s.parent]
            if (p != null && p !== s) return@filter false
            val base = baseName(s.name)
            !(base != s.name && byName[base].orEmpty().any { it !== s && near(it, s) })
        }
        // 整体没搜到的子点：按父 POI（或者整体名）并起来
        val groups = LinkedHashMap<String, MutableList<Sight>>()
        for (s in kept) {
            val base = baseName(s.name)
            val key = when {
                s.parent.isNotEmpty() -> "p:" + s.parent
                base != s.name && base.length >= 2 && base.any { it in '\u4e00'..'\u9fff' } -> "n:" + base   // 外文名不按名字并
                else -> "s:" + s.id
            }
            groups.getOrPut(key) { ArrayList() }.add(s)
        }
        return groups.map { (key, g) ->
            if (g.size == 1 && !key.startsWith("n:")) return@map g[0]
            val first = g.minByOrNull { it.distance }!!
            // 只有一个子点、整体没搜到：还是用它自己的名字（比如"上海博物馆(东馆)"）
            if (g.size == 1) return@map first
            val names = g.map { baseName(it.name) }.distinct()
            first.copy(name = if (names.size == 1) names[0] else first.name, famous = g.any { it.famous })
        }.sortedBy { it.distance }
    }

    /** 地图服务标为有名的排前面。 */
    private fun fame(s: Sight) = if (s.famous) 0 else 1

    /** 记"介绍过"用的键：地图服务里的编号。 */
    fun key(s: Sight) = s.id

    /** 维基百科 REST 摘要接口的 extract（消歧义页不算）。 */
    fun parseSummary(json: String): String? = runCatching {
        val o = JSONObject(json)
        if (o.optString("type") == "disambiguation") null else o.optString("extract").trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    // ---------- 高德地图 ----------

    /** 高德周边搜索的类型：公园、动物园、植物园、水族馆、风景名胜（含寺庙、教堂、纪念馆、观景点……）、博物馆。 */
    const val AMAP_TYPES = "110101|110102|110103|110104|110200|140100"

    fun amapKind(code: String): String = when (code) {
        "110101" -> "公园"; "110102" -> "动物园"; "110103" -> "植物园"; "110104" -> "水族馆"
        "110201" -> "世界遗产"; "110202" -> "国家级景点"; "110203" -> "省级景点"; "110204" -> "纪念馆"
        "110205" -> "寺庙道观"; "110206" -> "教堂"; "110207" -> "清真寺"; "110208" -> "海滩"; "110209" -> "观景点"
        "140100" -> "博物馆"
        else -> "景点"
    }

    /** 解析高德 v5 周边搜索的结果（坐标是 GCJ-02，lat/lon 也要传 GCJ-02）。 */
    fun parseAmap(json: String, lat: Double, lon: Double): List<Sight> {
        val o = JSONObject(json)
        if (o.optString("status") != "1") throw java.io.IOException("高德：" + o.optString("info").ifEmpty { "查询失败" })
        val arr = o.optJSONArray("pois") ?: return emptyList()
        val out = ArrayList<Sight>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val loc = p.optString("location").split(",")
            val lo = loc.getOrNull(0)?.toDoubleOrNull() ?: continue
            val la = loc.getOrNull(1)?.toDoubleOrNull() ?: continue
            val name = p.optString("name").trim()
            if (name.isEmpty()) continue
            val code = p.optString("typecode").split("|").first()
            out.add(Sight("a" + p.optString("id"), name, la, lo, amapKind(code), distance = distance(lat, lon, la, lo),
                famous = code == "110201" || code == "110202" || code == "110203", city = p.optString("cityname"),
                parent = p.optString("parent").trim().takeIf { it.isNotEmpty() && it != "[]" }?.let { "a$it" } ?: ""))
        }
        return out.sortedBy { it.distance }.distinctBy { it.name }.filter { it.distance <= RADIUS_M + 200 }
    }

    // ---------- Google 地图 ----------

    /** Google Places API (New) 附近搜索的类型。 */
    val GOOGLE_TYPES = listOf("tourist_attraction", "museum", "art_gallery", "historical_landmark", "park", "zoo", "aquarium",
        "amusement_park", "church", "hindu_temple", "mosque", "synagogue")

    fun googleBody(lat: Double, lon: Double, types: List<String> = GOOGLE_TYPES): String = JSONObject()
        .put("includedTypes", org.json.JSONArray(types))
        .put("maxResultCount", 20)
        .put("languageCode", "zh-CN")
        .put("locationRestriction", JSONObject().put("circle", JSONObject()
            .put("center", JSONObject().put("latitude", lat).put("longitude", lon)).put("radius", RADIUS_M.toDouble())))
        .toString()

    fun parseGoogle(json: String, lat: Double, lon: Double): List<Sight> {
        val arr = JSONObject(json).optJSONArray("places") ?: return emptyList()
        val out = ArrayList<Sight>()
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val name = p.optJSONObject("displayName")?.optString("text")?.trim().orEmpty()
            if (name.isEmpty()) continue
            val l = p.optJSONObject("location") ?: continue
            val la = l.optDouble("latitude"); val lo = l.optDouble("longitude")
            if (la.isNaN() || lo.isNaN()) continue
            val summary = p.optJSONObject("editorialSummary")?.optString("text")?.trim().orEmpty()
            val kind = p.optJSONObject("primaryTypeDisplayName")?.optString("text")?.trim().orEmpty().ifEmpty { "景点" }
            out.add(Sight("g" + p.optString("id"), name, la, lo, kind, distance = distance(lat, lon, la, lo),
                famous = summary.isNotEmpty() || p.optInt("userRatingCount") >= 1000, summary = summary))
        }
        return out.sortedBy { it.distance }.distinctBy { it.name }.filter { it.distance <= RADIUS_M + 200 }
    }

    // ---------- 百度百科 ----------

    /** 百度百科词条卡片接口的摘要；没有这个词条时是 {}。 */
    fun parseBaike(json: String): String? = runCatching {
        JSONObject(json).optString("abstract").trim().removeSuffix("...").removeSuffix("…").trim().takeIf { it.length >= 10 }
    }.getOrNull()

    /** 城市名的简称（"北京市" → "北京"，"香港特别行政区" → "香港"），用来看百科摘要说的是不是这个城市的。 */
    fun cityShort(city: String) = city.trim().removeSuffix("特别行政区").removeSuffix("市").removeSuffix("地区")

    // ---------- 坐标 ----------

    /** 在不在中国大陆的大致范围（高德在这里要 GCJ-02 坐标）。 */
    fun inChina(lat: Double, lon: Double) = lon in 72.004..137.8347 && lat in 0.8293..55.8271

    /** WGS-84（手机定位）转 GCJ-02（高德、国内地图用的坐标）；国外原样返回。 */
    fun wgsToGcj(lat: Double, lon: Double): Pair<Double, Double> {
        if (!inChina(lat, lon)) return lat to lon
        val a = 6378245.0; val ee = 0.00669342162296594323
        val x = lon - 105.0; val y = lat - 35.0
        var dLat = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
        dLat += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        dLat += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        dLat += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        var dLon = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
        dLon += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        dLon += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        dLon += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        val radLat = lat / 180.0 * Math.PI
        var magic = Math.sin(radLat); magic = 1 - ee * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * Math.PI)
        dLon = (dLon * 180.0) / (a / sqrtMagic * Math.cos(radLat) * Math.PI)
        return (lat + dLat) to (lon + dLon)
    }

    /** action=query&prop=extracts 的结果（第一页的 extract）。 */
    fun parseExtracts(json: String): String? = runCatching {
        val pages = JSONObject(json).getJSONObject("query").getJSONObject("pages")
        pages.keys().asSequence().map { pages.getJSONObject(it).optString("extract").trim() }.firstOrNull { it.isNotEmpty() }
    }.getOrNull()

    /**
     * 介绍：去掉括号里的注音、外文名，合并空白，最多 INTRO_CHARS 字（眼镜上再裁成一屏）。
     * 没有摘要时用维基数据的一句话描述，再没有就只说是什么类型。
     */
    fun intro(extract: String?, desc: String, kind: String, max: Int = INTRO_CHARS): String {
        val raw = extract?.takeIf { it.isNotBlank() } ?: desc.takeIf { it.isNotBlank() }?.let { "$it。" } ?: return kind
        val s = raw.replace(Regex("（[^（）]*）"), "").replace(Regex("\\([^()]*\\)"), "")
            .replace(Regex("\\s+"), " ").replace(Regex(" ?([，。；：、]) ?"), "$1").trim()
        return cut(s, max)
    }

    /** 裁到 max 字以内并尽量填满：句号结尾的前缀能占到 85% 以上就在句号处结束，否则填满后加省略号。 */
    fun cut(s: String, max: Int): String {
        if (s.length <= max) return s
        val end = s.lastIndexOfAny(charArrayOf('。', '！', '？', '；', '.'), max - 1)
        return if (end + 1 >= max * 0.85) s.substring(0, end + 1) else s.substring(0, max - 1).trimEnd('，', '、', '；', '：', ' ') + "…"
    }

    /** 标题里的距离：800 米 / 1.2 公里。 */
    fun distanceText(m: Int) = if (m < 1000) "${(m + 5) / 10 * 10} 米" else String.format(java.util.Locale.US, "%.1f 公里", m / 1000.0)
}

/** 景点介绍用到的联网（后台线程里调用）。 */
object SightsNet {
    private const val UA = "Yingdu/1.0.1 (+https://github.com/codexmasterme/yingdu; Nimo smart glasses companion; Android)"
    /** 找景点用哪家：1 高德地图，2 Google 地图（0 是以前的 OpenStreetMap，已经去掉，按高德算）。 */
    const val SRC_AMAP = 1
    const val SRC_GOOGLE = 2
    /** 介绍先查哪个：0 百度百科，1 维基百科（前一个没有再查另一个）。 */
    const val INTRO_BAIKE = 0
    const val INTRO_WIKI = 1

    /** 按设置的地图服务找附近景点（要先填对应的 Key）。 */
    fun nearby(lat: Double, lon: Double, source: Int, amapKey: String, googleKey: String): List<Sight> = Sights.collapse(
        if (source == SRC_GOOGLE) {
            if (googleKey.isBlank()) throw java.io.IOException("还没填 Google Key")
            google(lat, lon, googleKey)
        } else {
            if (amapKey.isBlank()) throw java.io.IOException("还没填高德 Key")
            amap(lat, lon, amapKey)
        })

    /** 高德周边搜索（v5），每页 25 个，最多取三页。坐标先转成 GCJ-02。 */
    fun amap(lat: Double, lon: Double, key: String): List<Sight> {
        val (gLat, gLon) = Sights.wgsToGcj(lat, lon)
        val loc = String.format(java.util.Locale.US, "%.6f,%.6f", gLon, gLat)
        val all = ArrayList<Sight>()
        for (page in 1..3) {      // 子点合并后会少一些，多取一页
            val u = "https://restapi.amap.com/v5/place/around?key=" + URLEncoder.encode(key.trim(), "UTF-8") +
                "&location=$loc&radius=${Sights.RADIUS_M}&types=" + URLEncoder.encode(Sights.AMAP_TYPES, "UTF-8") +
                "&sortrule=distance&page_size=25&page_num=$page"
            val got = Sights.parseAmap(http(u), gLat, gLon)
            all.addAll(got)
            if (got.size < 20) break
        }
        return all.sortedBy { it.distance }.distinctBy { it.name }
    }

    /** Google Places API (New) 附近搜索；万一类型表里有这个 Key 不认的类型，退回最基本的几种再试一次。 */
    fun google(lat: Double, lon: Double, key: String): List<Sight> {
        val u = "https://places.googleapis.com/v1/places:searchNearby"
        val headers = mapOf("X-Goog-Api-Key" to key.trim(), "Content-Type" to "application/json",
            "X-Goog-FieldMask" to "places.id,places.displayName,places.location,places.primaryTypeDisplayName,places.editorialSummary,places.userRatingCount")
        val json = try { http(u, post = Sights.googleBody(lat, lon), headers = headers) }
            catch (e: java.io.IOException) {
                if (e.message?.startsWith("HTTP 400") != true) throw e
                http(u, post = Sights.googleBody(lat, lon, listOf("tourist_attraction", "museum", "park")), headers = headers)
            }
        return Sights.parseGoogle(json, lat, lon)
    }

    /** 百度百科的词条摘要（按名字查，查不到返回 null）。 */
    fun baike(name: String): String? = runCatching {
        val u = "https://baike.baidu.com/api/openapi/BaikeLemmaCardApi?scope=103&format=json&appid=379020&bk_length=600&bk_key=" +
            URLEncoder.encode(name, "UTF-8")
        Sights.parseBaike(http(u))
    }.getOrNull()

    /**
     * 维基百科的介绍（中文要简体：Accept-Language: zh-cn）。先取摘要（第一段，一定转成了简体）；
     * 太短填不满一屏时再取完整的导言（几段），开头和摘要一样（说明也转成了简体）才用。
     */
    fun summary(lang: String, title: String): String? {
        val al = if (lang == "zh") "zh-cn" else null
        val first = runCatching {
            val u = "https://$lang.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")
            Sights.parseSummary(http(u, lang = al))
        }.getOrNull()
        if (first != null && first.length >= Sights.SHORT_CHARS) return first
        val full = runCatching {
            val u = "https://$lang.wikipedia.org/w/api.php?action=query&format=json&prop=extracts&exintro=1&explaintext=1&redirects=1" +
                (if (lang == "zh") "&variant=zh-cn" else "") + "&titles=" + URLEncoder.encode(title, "UTF-8")
            Sights.parseExtracts(http(u, lang = al))
        }.getOrNull()
        return when {
            full == null -> first
            first == null -> full
            full.length > first.length && full.take(12) == first.take(12) -> full
            else -> first
        }
    }

    /**
     * 给一个景点配上介绍：按设置先查百度百科或维基百科，没有再查另一个；
     * 都没有时用 Google 的一句话描述，再没有就只说是什么类型。
     * 返回 (名字, 介绍, 来源)，来源显示在眼镜和手机上（维基百科的内容按 CC BY-SA 要注明出处）。
     */
    fun describe(s: Sight, intro: Int = INTRO_BAIKE): Triple<String, String, String> {
        val name = s.name
        val wiki = { summary("zh", name) }
        val baidu = {
            val c = Sights.cityShort(s.city)
            if (c.isEmpty()) baike(name)
            // 知道在哪个城市（高德）：摘要里提到这个城市才用，否则按"城市+名字"再查一次（"人民公园"默认是成都的）
            else baike(name)?.takeIf { c in it } ?: baike(c + name)?.takeIf { c in it }
        }
        var src = ""
        val w = { wiki()?.also { src = "维基百科" } }
        val b = { baidu()?.also { src = "百度百科" } }
        val extract = if (intro == INTRO_WIKI) w() ?: b() else b() ?: w()
        if (extract == null && s.summary.isNotBlank()) src = "Google 地图"
        return Triple(name, Sights.intro(extract, s.summary, s.kind), src)
    }

    private fun http(url: String, post: String? = null, lang: String? = null, headers: Map<String, String> = emptyMap()): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", UA)
        if (lang != null) c.setRequestProperty("Accept-Language", lang)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (post != null) {
            c.requestMethod = "POST"; c.doOutput = true
            if ("Content-Type" !in headers) c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(post.toByteArray()) }
        }
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}
