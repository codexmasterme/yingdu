package io.github.yingdu

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import java.io.File

/**
 * 外卖、打车这类「正在进行」的通知（ColorOS 的流体云里显示的通常就是它们）。
 * 现在是测试：把这些通知的所有字段记到 files/live_log.txt，用户发回来，按真实数据再做看板卡片和提醒。
 * 这一部分是纯 Kotlin（挑哪些要记、内容有没有变、怎么排版），有单元测试；读通知在 LiveNotifyLog。
 */
object LiveNotify {
    /** 外卖、打车、地图（打车也常在地图 app 里）。 */
    val APPS = mapOf(
        "com.sankuai.meituan" to "美团",
        "com.sankuai.meituan.takeoutnew" to "美团外卖",
        "me.ele" to "饿了么",
        "com.taobao.taobao" to "淘宝",
        "com.jingdong.app.mall" to "京东",
        "com.sdu.didi.psnger" to "滴滴出行",
        "com.huaxiaozhu.rider" to "花小猪",
        "cn.caocaokeji.user" to "曹操出行",
        "com.t3go.passenger" to "T3 出行",
        "com.jingyao.easybike" to "哈啰",
        "com.autonavi.minimap" to "高德地图",
        "com.baidu.BaiduMap" to "百度地图",
        "com.eg.android.AlipayGphone" to "支付宝",
    )

    /** 流体云、实况通知可能用的私有字段名里会带的词。 */
    private val HINTS = listOf("oplus", "oppo", "coloros", "fluid", "capsule", "island", "focus", "live", "promot", "seedling")

    /**
     * 要不要记：外卖打车 app 的所有通知；别的 app 只记进行中、而且带流体云/实况字样字段或者是"推广的进行中通知"的。
     * 别的 app 的前台服务通知（加速器、VPN 每秒刷新网速这种）不记：ColorOS 给所有通知都加 oplus_ 字段，会被误认成实况通知，
     * 一秒一条把记录挤满（10-02 的记录里淘宝、美团就是这样被挤掉的）。
     */
    fun wanted(pkg: String, ongoing: Boolean, promoted: Boolean, extraKeys: Collection<String>, foreground: Boolean = false): Boolean =
        pkg in APPS || (ongoing && (promoted || !foreground && extraKeys.any { k -> HINTS.any { k.contains(it, ignoreCase = true) } }))

    /** 别的 app 的同一条通知最多隔多久记一次（够看清它的字段就行）。 */
    const val OTHER_EVERY_MS = 10 * 60_000L
    private val otherAt = HashMap<String, Long>()

    /** 别的 app 的这条通知现在该不该记（外卖打车 app 每次变化都记）。 */
    @Synchronized
    fun due(pkg: String, key: String, now: Long): Boolean {
        if (pkg in APPS) return true
        val last = otherAt[key]
        if (last != null && now - last < OTHER_EVERY_MS) return false
        otherAt[key] = now
        if (otherAt.size > 200) otherAt.clear()
        return true
    }

    /** 一条记录（每个字段一行）。 */
    fun format(time: String, what: String, pkg: String, fields: List<Pair<String, String>>): String = buildString {
        append("=== ").append(time).append(' ').append(what).append(' ').append(APPS[pkg] ?: pkg).append(" (").append(pkg).append(")\n")
        for ((k, v) in fields) if (v.isNotEmpty()) append("  ").append(k).append(": ").append(v.replace("\n", "⏎").take(300)).append('\n')
    }

    /** 同一条通知内容没变（只是时间在走）就不重复记。 */
    private val last = HashMap<String, Int>()

    @Synchronized
    fun changed(key: String, content: String): Boolean {
        val h = content.hashCode()
        if (last[key] == h) return false
        last[key] = h
        if (last.size > 200) last.clear()
        return true
    }

    @Synchronized
    fun forget(key: String): Boolean = last.remove(key) != null
}

/** 读通知的字段、写日志（在通知服务里调用）。 */
object LiveNotifyLog {
    private const val FILE = "live_log.txt"
    private const val MAX_BYTES = 400_000L

    fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun enabled(ctx: Context) = ctx.getSharedPreferences("notify", Context.MODE_PRIVATE).getBoolean("liveLog", true)
    fun setEnabled(ctx: Context, v: Boolean) = ctx.getSharedPreferences("notify", Context.MODE_PRIVATE).edit().putBoolean("liveLog", v).apply()

    /** 记了多少条。 */
    var count = 0; private set

    private fun now() = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())

    fun onPosted(ctx: Context, sbn: StatusBarNotification) {
        if (!enabled(ctx)) return
        val n = sbn.notification ?: return
        val ex = n.extras
        val keys = ex?.keySet()?.toList() ?: emptyList()
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val promoted = n.flags and 0x00040000 != 0        // FLAG_PROMOTED_ONGOING（安卓 16 的实况通知）
        val foreground = n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        if (!LiveNotify.wanted(sbn.packageName, ongoing, promoted, keys, foreground)) return
        val fields = ArrayList<Pair<String, String>>()
        fields.add("id/tag" to "${sbn.id} ${sbn.tag ?: ""}".trim())
        fields.add("flags" to listOfNotNull(
            if (ongoing) "进行中" else null, if (promoted) "实况" else null,
            if (n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0) "只提醒一次" else null,
            if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) "前台服务" else null,
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) "分组汇总" else null,
        ).joinToString(" ") + " (0x" + Integer.toHexString(n.flags) + ")")
        fields.add("category" to (n.category ?: ""))
        fields.add("channel" to (if (android.os.Build.VERSION.SDK_INT >= 26) n.channelId ?: "" else ""))
        fields.add("when" to if (n.`when` > 0) java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(n.`when`)) else "")
        // extras 里的每个字段（图片之类只记类型）
        for (k in keys.sorted()) {
            val v = runCatching { @Suppress("DEPRECATION") ex.get(k) }.getOrNull() ?: continue
            val s = when (v) {
                is CharSequence, is Number, is Boolean -> v.toString()
                is Array<*> -> v.joinToString(" | ") { (it as? android.os.Bundle)?.let { b -> b.getCharSequence("text")?.toString() ?: b.toString() } ?: it.toString() }
                is android.os.Bundle -> v.keySet().joinToString(", ") { kk -> kk + "=" + runCatching { @Suppress("DEPRECATION") v.get(kk) }.getOrNull() }
                is android.graphics.Bitmap -> "[图片 ${v.width}×${v.height}]"
                is android.graphics.drawable.Icon -> "[图标]"
                else -> "[${v.javaClass.simpleName}] " + v.toString().take(120)
            }
            fields.add("extras." + k to s)
        }
        // 自定义布局（外卖、打车常用自己画的通知）：把里面的文字读出来
        @Suppress("DEPRECATION")
        listOf("布局" to n.contentView, "大布局" to n.bigContentView, "横幅布局" to n.headsUpContentView).forEach { (name, rv) ->
            val texts = viewTexts(ctx, rv)
            if (texts.isNotEmpty()) fields.add(name to texts.joinToString(" | "))
        }
        val content = fields.filter { it.first != "when" }.joinToString("\n") { it.first + it.second }
        if (!LiveNotify.changed(sbn.key, content)) return
        if (!LiveNotify.due(sbn.packageName, sbn.key, System.currentTimeMillis())) return
        write(ctx, LiveNotify.format(now(), if (ongoing) "更新" else "通知", sbn.packageName, fields))
    }

    fun onRemoved(ctx: Context, sbn: StatusBarNotification) {
        if (!enabled(ctx) || !LiveNotify.forget(sbn.key)) return
        write(ctx, LiveNotify.format(now(), "结束", sbn.packageName, listOf("id/tag" to "${sbn.id} ${sbn.tag ?: ""}".trim())))
    }

    /** 把 RemoteViews 在萤读里展开一次，收集所有 TextView 的文字。 */
    private fun viewTexts(ctx: Context, rv: android.widget.RemoteViews?): List<String> {
        rv ?: return emptyList()
        return runCatching {
            val v = rv.apply(ctx, android.widget.FrameLayout(ctx))
            val out = ArrayList<String>()
            fun walk(x: android.view.View) {
                if (x is android.widget.TextView) x.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
                if (x is android.view.ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
            }
            walk(v)
            out
        }.getOrElse { listOf("（读不出：${it.javaClass.simpleName}）") }
    }

    @Synchronized
    private fun write(ctx: Context, text: String) {
        runCatching {
            val f = file(ctx)
            if (f.length() > MAX_BYTES) {
                // 太大了只留后一半
                val keep = f.readText().takeLast((MAX_BYTES / 2).toInt())
                f.writeText(keep.substring(keep.indexOf("===").coerceAtLeast(0)))
            }
            f.appendText(text)
            count++
        }
    }

    fun clear(ctx: Context) { file(ctx).delete(); count = 0 }

    /** 最近的记录（分享用，太长只给最后一段）。 */
    fun tail(ctx: Context, maxChars: Int = 90_000): String {
        val f = file(ctx)
        if (!f.exists()) return ""
        val t = f.readText()
        return if (t.length <= maxChars) t else t.takeLast(maxChars).let { it.substring(it.indexOf("===").coerceAtLeast(0)) }
    }
}
