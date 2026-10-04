package io.github.yingdu

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 读取手机通知（需要在系统设置里授予"通知使用权"），把选中 app 的新消息转发到眼镜的通知弹窗。
 */
class PhoneNotificationService : NotificationListenerService() {

    companion object {

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, PhoneNotificationService::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }

        fun openSettings(ctx: Context) {
            ctx.startActivity(android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** 系统现在真的连着我们（授权了不等于连着：更新 app 后系统常常不再连上，设置里还显示已授权，但一条通知也收不到）。 */
        @Volatile var connected = false
            private set

        /**
         * 授权了但没连上：先请系统重新连接（requestRebind）；3 秒后还没连上，就把这个组件关掉再打开，
         * 系统会重新绑定（有的手机上 requestRebind 不管用）。
         */
        private var lastRebindAt = 0L

        fun rebindIfNeeded(ctx: Context, why: String, force: Boolean = false) {
            if (connected || !isEnabled(ctx)) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (!force && now - lastRebindAt < 60_000 && lastRebindAt != 0L) return
            lastRebindAt = now
            val cn = ComponentName(ctx, PhoneNotificationService::class.java)
            ReaderService.instance?.log("通知使用权已授权但没连上（$why），请系统重新连接")
            runCatching { requestRebind(cn) }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (connected || !isEnabled(ctx)) return@postDelayed
                val pm = ctx.packageManager
                runCatching {
                    pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                    pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                    ReaderService.instance?.log("通知服务：关掉再打开，强制系统重新连接")
                }
                runCatching { requestRebind(cn) }
            }, 3_000)
        }
    }


    /** 授权后系统连上来。 */
    override fun onListenerConnected() {
        connected = true
        ReaderService.instance?.let { it.log("通知使用权：已连接，开始转发通知") }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        runCatching { LiveNotifyLog.onRemoved(this, sbn) }
    }

    /** 系统断开了我们（比如系统回收）：马上请它重新连接。 */
    override fun onListenerDisconnected() {
        connected = false
        ReaderService.instance?.log("通知使用权：连接断开，请系统重新连接")
        runCatching { requestRebind(ComponentName(this, PhoneNotificationService::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName) return
        // 外卖、打车的实况通知：先全部记下来（测试），再按原来的规则决定转不转发
        runCatching { LiveNotifyLog.onPosted(this, sbn) }
        val n = sbn.notification ?: return
        val ex = n.extras
        // 标题原样显示（QQ 之类后面的「(3条新消息)」是未读数，要留着）；去掉未读数的版本只用来认是不是群聊
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        // 聊天类 app（MessagingStyle）把每条消息放在消息列表里，正文常常只是"2 条新消息"：取列表里最新的一条
        val msgs = runCatching { ex.getParcelableArray(Notification.EXTRA_MESSAGES) }.getOrNull()
        val lastMsg = msgs?.lastOrNull() as? android.os.Bundle
        val lastText = lastMsg?.getCharSequence("text")?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val lastSender = lastMsg?.let { m ->
            m.getCharSequence("sender")?.toString()
                ?: if (android.os.Build.VERSION.SDK_INT >= 28) runCatching { m.getParcelable<android.app.Person>("sender_person")?.name?.toString() }.getOrNull() else null
        }?.trim()
        val text = NotifyText.pick(
            ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: "",
            ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: "",
            lastText, lastSender, NotifyText.cleanTitle(title))
        val msgTime = lastMsg?.getLong("time") ?: 0L
        val svc = ReaderService.instance ?: return
        NotifyPrefs.rememberApp(this, pkg)

        // 来电（电话、微信语音/视频）：通话通知是"进行中"的，下面会被当成音乐之类跳过，这里单独处理
        if (n.category == Notification.CATEGORY_CALL) {
            if (NotifyPrefs.enabled(this) && NotifyPrefs.callAlert(this) &&
                NotifyCall.isIncoming(ex.getInt("android.callType", 0), n.fullScreenIntent != null) &&
                NotifyCall.firstAlert(sbn.key, android.os.SystemClock.elapsedRealtime())) {
                val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                svc.onPhoneNotification(label, title.ifEmpty { "未知号码" }, text.ifEmpty { "来电" }, pkg = pkg)
            }
            return
        }
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return          // 音乐、下载进度之类
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return          // 分组汇总，会和单条重复
        if (title.isEmpty() && text.isEmpty()) return
        val key = sbn.key
        val sig = "$title|$text"
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        if (NotifyDedup.sameAsLast(key, sig)) {                                 // 同一条通知的重复刷新
            if (NotifyPrefs.slotOf(this, pkg) != 0) svc.log("通知：$label · $title（和这个 app 上一条通知内容一样，跳过）")
            return
        }
        if (!NotifyPrefs.shouldForward(this, pkg)) {
            // 选了时段、只是现在不在时段里的，记一笔（排查"为什么没弹"）；没选的 app 不记，免得刷屏
            val mask = NotifyPrefs.slotOf(this, pkg)
            if (mask != 0 && NotifyPrefs.enabled(this)) { NotifyDedup.remember(key, sig); svc.log("通知：$label（只在「${NotifySlots.label(mask)}」时段转发，现在不转）") }
            return
        }
        NotifyDedup.remember(key, sig)
        // 同一条消息又送来一次（app 在后台更新通知、换了编号重发，或者系统同时连着两个通知服务）：
        // 用"消息的时间戳"认——同一条消息时间戳不变；别人又发了一句一样的话是新消息，时间戳不同，照常弹
        val stamp = if (msgTime > 0) msgTime else if (n.`when` > 0) n.`when` else sbn.postTime
        if (NotifyDedup.seenBefore(pkg, text.ifEmpty { title }, stamp, android.os.SystemClock.elapsedRealtime())) {
            svc.log("通知：$label · $title（同一条消息又送来一次，跳过）"); return
        }
        svc.onPhoneNotification(label, title, text, pkg = pkg)
    }

}

/** 来电通知：只提醒"正在响铃"的那一次（接通后变成"通话中"不提醒），同一通来电 60 秒内只提醒一次。 */
object NotifyCall {
    private val alerted = HashMap<String, Long>()

    /**
     * @param callType Android 12 起 CallStyle 通知的类型：1 来电、2 通话中、3 来电筛选；0 = 旧样式不知道
     * @param fullScreen 有全屏界面（旧样式的来电通知都有，通话中的没有）
     */
    fun isIncoming(callType: Int, fullScreen: Boolean) = when (callType) { 1 -> true; 2, 3 -> false; else -> fullScreen }

    fun firstAlert(key: String, now: Long): Boolean {
        alerted.entries.removeAll { now - it.value > 60_000 }
        if (key in alerted) return false
        alerted[key] = now
        return true
    }
}

/** 通知转发设置：总开关、转发哪些 app。 */
object NotifyPrefs {
    /** 默认转发的常用聊天软件。 */
    val DEFAULT_APPS = setOf(
        "com.tencent.mm", "com.tencent.mobileqq", "jp.naver.line.android", "com.whatsapp", "org.telegram.messenger",
        "com.google.android.apps.messaging", "com.android.mms", "com.google.android.gm", "com.slack", "com.discord",
        "com.tencent.wework", "com.alibaba.android.rimet")

    private fun p(ctx: Context) = ctx.getSharedPreferences("notify", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = p(ctx).getBoolean("on", true)
    fun setEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("on", v).apply()

    /** 来电提醒（电话和微信等的语音/视频来电），默认开。 */
    fun callAlert(ctx: Context) = p(ctx).getBoolean("callAlert", true)
    fun setCallAlert(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("callAlert", v).apply()

    /** 每个 app 在哪些时段转发（NotifySlots 的位）；没有记录的 = 不转发。旧版本勾选过的 app 迁移成全天。 */
    fun slots(ctx: Context): Map<String, Int> {
        val raw = p(ctx).getString("slots", null)
        if (raw == null) {
            val old = p(ctx).getStringSet("apps", null) ?: DEFAULT_APPS
            return old.associateWith { NotifySlots.ALL }
        }
        val o = org.json.JSONObject(raw)
        return o.keys().asSequence().associateWith { o.optInt(it) }.filterValues { it != 0 }
    }
    fun slotOf(ctx: Context, pkg: String) = slots(ctx)[pkg] ?: 0
    fun setSlot(ctx: Context, pkg: String, mask: Int) {
        val m = slots(ctx).toMutableMap()
        if (mask == 0) m.remove(pkg) else m[pkg] = mask
        p(ctx).edit().putString("slots", org.json.JSONObject(m as Map<*, *>).toString()).apply()
    }
    /** 有时段的 app（设置页排在前面）。 */
    fun allowed(ctx: Context): Set<String> = slots(ctx).keys

    fun shouldForward(ctx: Context, pkg: String, cal: java.util.Calendar = java.util.Calendar.getInstance()) =
        enabled(ctx) && NotifySlots.allows(slotOf(ctx, pkg), NotifySlots.current(cal))

    /** 发过通知的 app，设置页里列出来让用户勾选。 */
    fun rememberApp(ctx: Context, pkg: String) {
        val s = seenApps(ctx)
        if (pkg !in s) p(ctx).edit().putStringSet("seen", s + pkg).apply()
    }
    fun seenApps(ctx: Context): Set<String> = p(ctx).getStringSet("seen", emptySet()) ?: emptySet()
}

/**
 * 通知的时段：工作（周一到周五 9:00～18:00）、休息（其余时间，周末全天），两个都选 = 全天，都不选 = 不转发。
 * 纯 Kotlin，有单元测试。
 */
object NotifySlots {
    const val WORK = 1
    const val OFF = 2
    const val ALL = WORK or OFF
    const val WORK_START = 9
    const val WORK_END = 18

    fun current(hour: Int, weekend: Boolean = false) = if (!weekend && hour in WORK_START until WORK_END) WORK else OFF
    fun current(cal: java.util.Calendar): Int {
        val d = cal.get(java.util.Calendar.DAY_OF_WEEK)
        return current(cal.get(java.util.Calendar.HOUR_OF_DAY), d == java.util.Calendar.SATURDAY || d == java.util.Calendar.SUNDAY)
    }
    fun allows(mask: Int, slot: Int) = mask and slot != 0
    /** 点一个时段按钮：点已选中的 = 取消（不转发），点别的 = 换成它。 */
    fun pick(mask: Int, tapped: Int) = if (mask == tapped) 0 else tapped
    fun label(mask: Int) = when (mask) { ALL -> "全天"; WORK -> "工作"; OFF -> "休息"; else -> "不转发" }
}

/**
 * 通知去重（全局共享：系统偶尔会同时连着两个通知服务实例，各自的记录会各转一次）。纯 Kotlin，有单元测试。
 *  - 同一条通知（同一个 key）内容没变的刷新：不转；
 *  - 同一条消息又送来一次：同一个 app、同样的正文（去掉"[2条]"这类计数前缀）、同一个消息时间戳 → 不转。
 *    时间戳不同的就是新消息（哪怕话一模一样，比如两次"好的"），照常转。
 */
object NotifyDedup {
    /** 记录保留多久（只为控制内存，判断重复靠时间戳）。 */
    const val KEEP_MS = 10 * 60_000L
    private val lastByKey = LinkedHashMap<String, String>()
    private val seen = LinkedHashMap<String, Long>()

    @Synchronized fun sameAsLast(key: String, sig: String) = lastByKey[key] == sig
    @Synchronized fun remember(key: String, sig: String) {
        lastByKey.remove(key); lastByKey[key] = sig
        while (lastByKey.size > 200) lastByKey.remove(lastByKey.keys.first())
    }

    /** 一条消息的身份：app + 正文（去掉开头的"[3条]"）+ 消息时间戳。 */
    fun messageKey(pkg: String, body: String, stamp: Long): String {
        val b = body.replace(Regex("^\\[\\d+条]\\s*"), "").trim()
        return "$pkg|$stamp|$b"
    }

    /** 这条消息转过没有；没转过就记下（返回 false）。 */
    @Synchronized fun seenBefore(pkg: String, body: String, stamp: Long, now: Long): Boolean {
        val it = seen.entries.iterator()
        while (it.hasNext()) if (now - it.next().value > KEEP_MS) it.remove()
        val k = messageKey(pkg, body, stamp)
        if (seen.containsKey(k)) return true
        seen[k] = now
        while (seen.size > 500) seen.remove(seen.keys.first())
        return false
    }

    @Synchronized fun clear() { lastByKey.clear(); seen.clear() }
}

/** 从通知里挑出"这条消息"的正文。纯 Kotlin，有单元测试。 */
object NotifyText {
    private val COUNT_SUFFIX = Regex("\\s*[(（]\\s*\\d+\\s*条新消息\\s*[)）]\\s*$")

    /** 标题去掉 QQ 之类加在后面的未读数「(3条新消息)」，只用来和发送人比较（判断是不是群聊），显示时不去掉。 */
    fun cleanTitle(title: String): String = title.replace(COUNT_SUFFIX, "").trim().ifEmpty { title }

    /**
     * @param text EXTRA_TEXT（一般是最新一条）；@param big EXTRA_BIG_TEXT（展开后的长文，有的 app 放的是好几条累积的内容）
     * @param lastMsg 消息列表里最新一条（MessagingStyle，最可靠）；@param sender 它的发送人（群聊时标题是群名，要带上是谁说的）
     */
    fun pick(text: String, big: String, lastMsg: String?, sender: String?, title: String): String {
        if (!lastMsg.isNullOrEmpty()) {
            return if (!sender.isNullOrEmpty() && sender != title && !lastMsg.startsWith(sender)) "$sender：$lastMsg" else lastMsg
        }
        // 长文是这一条的完整版（正文被截成"……"）才用长文；否则长文可能是好几条累积起来的，用正文
        val head = text.trimEnd('…', '.', ' ')
        return if (text.isEmpty() || (big.isNotEmpty() && head.isNotEmpty() && big.startsWith(head))) big.ifEmpty { text } else text
    }
}

/** 通知弹窗第一个字节（app 编号）：眼镜按它显示 app 的图标和名字。表里没有的 app 发 OTHER，标题里带上 app 名。 */
object NotifyIcons {
    // 弹窗指令第一个字节（notificationAppId）：眼镜按它在第一行显示 app 的图标和名字。
    // 编号是用「找图标编号（测试）」逐个试出来的（眼镜上显示成哪个 app），不在表里的发 OTHER。
    const val SMS = 0
    const val PHONE = 1
    const val EMAIL = 2
    const val CALENDAR = 3
    const val REMINDER = 4
    const val WECHAT = 16
    const val LARK = 17
    const val DINGTALK = 18
    const val WEWORK = 19
    const val DOUYIN = 20
    const val OTHER = 31

    val BY_PACKAGE: Map<String, Int> = HashMap<String, Int>().apply {
        for (p in listOf("com.android.mms", "com.android.mms.service", "com.hihonor.mms", "com.samsung.android.messaging",
                "com.google.android.apps.messaging", "com.android.messaging")) put(p, SMS)
        for (p in listOf("com.android.contacts", "com.hihonor.contacts", "com.huawei.contacts", "com.samsung.android.app.contacts",
                "com.android.dialer", "com.google.android.dialer", "com.android.incallui", "com.android.server.telecom")) put(p, PHONE)
        for (p in listOf("com.android.email", "com.hihonor.email", "com.vivo.email", "com.huawei.email", "com.google.android.gm")) put(p, EMAIL)
        for (p in listOf("com.android.calendar", "com.google.android.calendar", "com.hihonor.calendar", "com.coloros.calendar",
                "com.bbk.calendar", "com.huawei.calendar")) put(p, CALENDAR)
        put("com.android.deskclock", REMINDER)
        put("com.tencent.mm", WECHAT)
        for (p in listOf("com.ss.android.lark", "com.larksuite", "com.bytedance.ee.lark")) put(p, LARK)
        put("com.alibaba.android.rimet", DINGTALK)
        put("com.tencent.wework", WEWORK)
        for (p in listOf("com.ss.android.ugc.aweme", "com.ss.android.ugc.trill")) put(p, DOUYIN)
    }

    fun appIdOf(pkg: String?): Int? = pkg?.let { BY_PACKAGE[it] }

    /** 弹窗标题：眼镜认识的 app 会自己显示 app 名，标题只写人名；其他 app 写"QQ · 人名"。 */
    fun title(app: String, title: String, known: Boolean): String = when {
        title.isEmpty() || title == app -> app
        known -> title
        else -> "$app · $title"
    }
}
