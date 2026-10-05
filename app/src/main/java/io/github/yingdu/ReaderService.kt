package io.github.yingdu

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.OpenableColumns
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 前台服务：手机锁屏、放进口袋时也能保持连接、响应镜腿翻页。
 * 书、分页、进度、眼镜连接都放在这里，界面只负责展示和操作。
 */
class ReaderService : Service(), NimoListener, AppHost {

    inner class LocalBinder : Binder() { val service: ReaderService get() = this@ReaderService }

    interface UiListener { fun onReaderChanged() }

    /** 眼镜当前显示什么。APP = 一个全屏功能（小游戏、表盘……），见 activeApp。 */
    enum class AppMode { READER, DASHBOARD, APP }

    companion object {
        /** 自动亮度平滑过渡用多久（毫秒；官方 app 是 4 秒）。 */
        const val BRIGHTNESS_FADE_MS = 4000L
        const val HIDE_SCREEN_OFF = 1
        /** 关屏收起时来通知：从屏幕亮起算，亮多久再关回去。 */
        const val NOTIFY_LIT_MS = 5_000L
        const val OFFLINE_MAX = 5
        private const val DAY_MS = 24 * 3600_000L
        /** 关屏收起时，眼镜上待机的第一屏多久检查一次要不要更新。 */
        const val STANDBY_REFRESH_MS = 30_000L
        const val OFFLINE_MAX_AGE_MS = 120_000L
        /** 全天记忆：一直没转成文字的录音最多留多久（之后删掉，不在手机上长期留录音）。 */
        const val PENDING_KEEP_MS = 24 * 3600_000L
        const val HIDE_HOME = 2
        /** 萤读自己退回主界面后，这么久内眼镜报的「主界面被点亮」不算用户点亮。 */
        const val QUIT_HOME_IGNORE_MS = 3_000L
        /** 通知监听服务通过它把手机通知、导航信息交给我们。 */
        var instance: ReaderService? = null
        private const val CHANNEL_ID = "reader"
        private const val NOTIF_ID = 1
        private const val PREFS = "reader"
        private const val MAX_LOG = 60
        const val ACTION_PREV = "io.github.yingdu.PREV"
        const val ACTION_NEXT = "io.github.yingdu.NEXT"
        const val ACTION_AUTO = "io.github.yingdu.AUTO"
    }

    private val binder = LocalBinder()
    override val main = Handler(Looper.getMainLooper())
    override val io: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor()
    override val context: Context get() = this
    private lateinit var client: NimoClient
    var uiListener: UiListener? = null

    // ---------- 状态（只在主线程读写） ----------
    var linkState = LinkState.DISCONNECTED; private set
    var battery = -1; private set
    var firmware = ""; private set
    var book: Book? = null; private set
    var bookUri: String? = null; private set
    var loading = false; private set
    private var paginator: Paginator? = null
    /** 当前屏幕顶部是全书的第几行。 */
    var topLine = 0; private set
    var autoFlip = false; private set
    val logLines = ArrayDeque<String>()

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    /** 显示页面：提词器页面更大（提词器一屏 5 行、每行约 28 字），笔记页面较小。 */
    var displayPage: DisplayPage
        get() = if (prefs.getString("displayPage", "PROMPTER") == "NOTE") DisplayPage.NOTE else DisplayPage.PROMPTER
        private set(v) = prefs.edit().putString("displayPage", v.name).apply()

    // 两种页面各自保存一套排版参数
    private val layoutSuffix get() = if (displayPage == DisplayPage.NOTE) "" else "_prompter"
    /**
     * 阅读用图片显示（推荐）：和看板在同一个眼镜页面里，切换不闪屏，也没有提词器的边框和状态行。
     * 关掉则用提词器或笔记页显示文字。
     */
    var readerAsImage: Boolean
        get() = prefs.getBoolean("readerAsImage3", true)
        private set(v) = prefs.edit().putBoolean("readerAsImage3", v).apply()
    /** 眼镜字号（字高像素，12～32，每 1px 一档）；图片显示的阅读和看板都用它。v2.0 选了「大」的沿用为 24px。 */
    var glassesFontPx: Int
        get() = prefs.getInt("glassesFontPx", if (prefs.getInt("readerSize2", 0) == 1) 24 else GlassesFonts.DEFAULT_PX)
            .coerceIn(GlassesFonts.MIN_PX, GlassesFonts.MAX_PX)
        private set(v) = prefs.edit().putInt("glassesFontPx", v.coerceIn(GlassesFonts.MIN_PX, GlassesFonts.MAX_PX)).apply()

    var charsPerLine: Int
        get() = if (readerAsImage) ReaderImage.layout(glassesFontPx).charsPerLine
                else prefs.getInt("charsPerLine$layoutSuffix", if (displayPage == DisplayPage.NOTE) 16 else 27)
        private set(v) = prefs.edit().putInt("charsPerLine$layoutSuffix", v).apply()
    var linesPerPage: Int
        get() = if (readerAsImage) ReaderImage.layout(glassesFontPx).rows
                else prefs.getInt("linesPerPage$layoutSuffix", if (displayPage == DisplayPage.NOTE) 3 else 5)
        private set(v) = prefs.edit().putInt("linesPerPage$layoutSuffix", v).apply()

    fun toggleReaderImage() {
        readerAsImage = !readerAsImage
        log(if (readerAsImage) "阅读改用图片显示" else "阅读改用文字页面显示")
        repaginate(currentOffset())
    }

    fun adjustGlassesFont(delta: Int) = setGlassesFont(glassesFontPx + delta)

    fun setGlassesFont(px: Int) {
        val v = px.coerceIn(GlassesFonts.MIN_PX, GlassesFonts.MAX_PX)
        if (v == glassesFontPx) return
        glassesFontPx = v
        val lay = ReaderImage.layout(v)
        log("眼镜字号：${v}px（一屏 ${lay.rows} 行 × ${lay.charsPerLine} 字）")
        lastPushedDash = ""
        if (book != null) repaginate(currentOffset()) else pushPage()
    }
    var autoFlipSeconds: Int
        get() = prefs.getInt("autoFlipSeconds", 15)
        set(v) { prefs.edit().putInt("autoFlipSeconds", v.coerceIn(1, 600)).apply() }
    /** 自动滚动时每次前进几行；0 表示整页。 */
    var scrollLines: Int
        get() = prefs.getInt("scrollLines", 0)
        set(v) { prefs.edit().putInt("scrollLines", v.coerceIn(0, 20)).apply() }
    var showProgress: Boolean
        get() = prefs.getBoolean("showProgress", false)
        set(v) { prefs.edit().putBoolean("showProgress", v).apply(); pushPage() }

    // ---------- 仪表盘 ----------
    /** 阅读或看板（记在设置里）；打开全屏功能时 appMode 是 APP，关掉后回到这里。 */
    private var baseMode: AppMode
        get() = runCatching { AppMode.valueOf(prefs.getString("appMode", "READER")!!) }.getOrDefault(AppMode.READER).let { if (it == AppMode.APP) AppMode.READER else it }
        set(v) = prefs.edit().putString("appMode", v.name).apply()
    val appMode: AppMode get() = if (activeApp != null) AppMode.APP else baseMode

    // ---------- 全屏功能 ----------

    /** 正在眼镜上显示的全屏功能。 */
    var activeApp: GlassesApp? = null; private set
    /** 小游戏（按首页的顺序）。 */
    val games: List<GameApp> by lazy { Games.all(this) }
    val clock by lazy { ClockApp(this) }
    val pomodoro by lazy { PomodoroApp(this) }
    val party by lazy { PartyApp(this) }
    /** 实时字幕（用全天记忆里设置的转文字服务）。 */
    val captions by lazy { CaptionsApp(this, object : CaptionsApp.Env {
        override fun asr() = memGet("asrKey").takeIf { it.isNotEmpty() }?.let { Triple(asrBase(), it, asrModel()) }
        override fun language() = memGet("asrLang", "zh").let { if (it == "auto") "" else it }
        override fun setMic(on: Boolean) { captionsWantMic = on; glassesMic(on) }
        override fun isMe(v: Speakers.Voice?) = memIsMe(v)
        override fun denoise() = memDenoise
    }) }

    override fun popup(title: String, text: String) { if (linkState == LinkState.READY) client.sendNotification(title, text) }

    /** 在眼镜上打开一个全屏功能（阅读的自动滚动、看板的轮换和自动收起都先停下）。 */
    fun openApp(app: GlassesApp) {
        if (activeApp !== app) activeApp?.onClose()
        activeApp = app
        app.onOpen()
        stopAutoFlip()
        cancelDashTimers()
        if (screenOffByUs) { screenOffByUs = false; client.screenOff(false) }
        glassesPaused = false
        log("打开：${app.title}")
        pushPage()
    }

    /** 关掉全屏功能，眼镜回到阅读或看板。 */
    fun closeApp() {
        val app = activeApp ?: return
        activeApp = null
        app.onClose()
        lastPushedDash = ""
        if (baseMode == AppMode.DASHBOARD) { dashPage = 0; refreshDashboard() }
        pushPage()
    }

    override fun isShowing(app: GlassesApp) = activeApp === app && !glassesPaused

    override fun redraw(app: GlassesApp) { if (activeApp === app && !glassesPaused) pushPage() }

    override fun changed() { updateNotification(); notifyUi() }

    override fun frameIntervalMs(): Long? = client.imageIntervalMs()

    /** 眼镜画面的发送情况：一共发了几张、最近一次是什么时候。 */
    fun framesSent(): Pair<Int, Long> = client.imagesSent to client.lastImageAt

    var dashCity: String
        get() = prefs.getString("dashCity", "") ?: ""
        private set(v) = prefs.edit().putString("dashCity", v).apply()
    var dashStocks: String
        get() = prefs.getString("dashStocks", "") ?: ""
        private set(v) = prefs.edit().putString("dashStocks", v).apply()
    var dashTodos: String
        get() = prefs.getString("dashTodos", "") ?: ""
        private set(v) = prefs.edit().putString("dashTodos", v).apply()
    private var dashLat: Double
        get() = prefs.getString("dashLat", "")?.toDoubleOrNull() ?: Double.NaN
        set(v) = prefs.edit().putString("dashLat", v.toString()).apply()
    private var dashLon: Double
        get() = prefs.getString("dashLon", "")?.toDoubleOrNull() ?: Double.NaN
        set(v) = prefs.edit().putString("dashLon", v.toString()).apply()

    /** 看板用图片显示（自己画，没有提词器边框）还是用提词器文字显示。 */
    var dashAsImage: Boolean
        get() = prefs.getBoolean("dashAsImage", true)
        set(v) { prefs.edit().putBoolean("dashAsImage", v).apply(); lastPushedDash = ""; if (appMode == AppMode.DASHBOARD) pushDashboard(force = true) }
    /** 用手机定位确定城市。 */
    var autoLocate: Boolean
        get() = prefs.getBoolean("autoLocate", true)
        set(v) { prefs.edit().putBoolean("autoLocate", v).apply(); if (v) locate() }

    // ---------- 用不用看板 ----------

    /**
     * 用萤读的看板（默认开）。关掉时眼镜一直用官方主界面，萤读不打开看板页面（阅读、小游戏等照常能打开）。
     * 老版本选过「不用看板」的按关算；用过「右半屏」的连上眼镜时把右侧卡片换回原来的。
     */
    var dashEnabled: Boolean
        get() = if (prefs.contains("dashOn")) prefs.getBoolean("dashOn", true) else prefs.getString("dashStyle", null) != "OFF"
        set(v) {
            if (v == dashEnabled) return
            prefs.edit().putBoolean("dashOn", v).apply()
            log(if (v) "看板：开" else "看板：关，眼镜用官方主界面")
            if (appMode == AppMode.DASHBOARD) {
                if (v) {
                    // 没连着眼镜时打开：不能停在"收起"状态（那样连上后不显示、抬头也叫不回来），连上时自然会显示
                    if (linkState == LinkState.READY) resumeGlasses() else { glassesPaused = false; headHidden = false }
                }
                else if (!glassesPaused) pauseGlasses()
            }
            notifyUi()
        }

    /** 不用看板：眼镜留在官方主界面。 */
    private val dashAtHome get() = !dashEnabled

    /** 老版本「右半屏」把眼镜主界面右侧换成了「位置」卡片：换回原来的（只做一次）。 */
    private fun restoreRightCardOnce() {
        val wasHalf = prefs.getString("dashStyle", null) == "HALF" || prefs.getBoolean("nativeDashTest", false)
        if (!wasHalf) return
        val orig = prefs.getInt("nativeDashOrigRight2", -1)
        if (orig >= 0 && orig != NimoProtocol.DASH_RIGHT_LOCATION) { client.setDashRightLayout(orig); log("主界面右侧卡片换回 $orig") }
        prefs.edit().remove("dashStyle").remove("nativeDashTest").remove("nativeDashTestImage").remove("nativeDashOrigRight2")
            .remove("nativeFlipSec").remove("nativeStockSec").apply()
    }

    /** 用户在眼镜上长按退出了显示；自动刷新不再把页面重新打开，直到手机上有操作。 */
    var glassesPaused = false; private set

    /** 手机上点"收起显示"：眼镜回到官方主界面，直到再次操作。 */
    fun pauseGlasses() {
        stopAutoFlip()
        cancelDashTimers()
        glassesPaused = true
        headHidden = false
        if (screenOffByUs) { screenOffByUs = false; client.screenOff(false) }
        client.quitCurrent()
        client.lightHome()
        log("已收起眼镜上的显示")
        updateNotification()
        notifyUi()
    }

    fun resumeGlasses() {
        if (screenOffByUs) { screenOffByUs = false; client.screenOff(false) }
        glassesPaused = false; lastPushedDash = ""; dashPage = 0
        activeApp?.onResume()
        pushPage()
    }

    fun toggleShow() { if (glassesPaused) resumeGlasses() else pauseGlasses() }

    // ---------- 看板自动收起 ----------

    /** 看板显示多少秒后自动收起；0 = 一直显示。 */
    var dashAutoHideSec: Int
        get() = prefs.getInt("dashAutoHide", 10)
        set(v) { prefs.edit().putInt("dashAutoHide", v).apply(); notifyUi() }
    /**
     * 收起方式（v3.6，根据 v3.4/v3.5 两份日志）：
     *  - HIDE_SCREEN_OFF 关屏（默认）：页面不退出，只关屏幕，完全不亮。关屏时眼镜照样上报抬头/低头
     *    （日志里暗屏等看板画好的那一两秒都收到过），所以抬头就开屏显示看板，低头再关；
     *  - HIDE_HOME 回主界面：退出页面。我们发了"退出页面"之后眼镜就不再上报抬头，
     *    只能点一下镜腿（主界面被点亮，key 6）或在手机上叫回。
     * v3.5 的「黑屏待命」去掉了：黑图在眼镜上有漏光的绿点，顶部时间电量也关不掉。
     * 换个键存，大家都从「关屏」开始。
     */
    var dashHideMode: Int
        get() = if (prefs.getInt("dashHide36", HIDE_SCREEN_OFF) == HIDE_HOME) HIDE_HOME else HIDE_SCREEN_OFF
        set(v) { prefs.edit().putInt("dashHide36", v).apply(); notifyUi() }
    /** 关屏收起时：抬头就开屏显示看板（从第一屏开始），低头再关。 */
    var dashHeadWake: Boolean
        get() = prefs.getBoolean("dashHeadWake", true)
        set(v) { prefs.edit().putBoolean("dashHeadWake", v).apply(); notifyUi() }
    private val headWakeOn get() = dashHeadWake && dashHideMode == HIDE_SCREEN_OFF
    /** 是萤读把屏幕关掉的（恢复显示时要打开）。 */
    private var screenOffByUs = false
    /** 看板是自动收起的，或者在眼镜上被退出了：可以从眼镜上叫回来（抬头、点亮主界面）；手机上主动收起的不算。 */
    private var headHidden = false
    /** 这次看板是什么时候显示出来的（低头时至少让它显示 2 秒）。 */
    private var shownAt = 0L

    private val dashHide: Runnable = object : Runnable {
        override fun run() {
            // 通知正盖在看板上：等它显示完再收起
            if (notifyOver) { main.postDelayed(this, 1_000); return }
            hideDashboard("看板已自动收起")
        }
    }
    private val headDownHide: Runnable = object : Runnable {
        override fun run() {
            // 通知正盖在看板上：等它显示完再收起（不然弹出来一低头就没了）
            if (notifyOver) { main.postDelayed(this, 1_000); return }
            hideDashboard("低头：收起看板", headDown = true)
        }
    }

    /** 正在显示萤读画的通知（盖在看板上、关屏时开屏显示、主界面上打开页面显示）。 */
    private val showingPopup get() = notifyOver || notifyLit

    /** 眼镜最近报的是低头（看板页面开着时才报）。 */
    private var headIsDown = false

    /** 测试：回主界面方式下，低着头收起时直接关屏（主界面不亮，抬头再叫回看板）。默认关，关着时和以前完全一样。 */
    var dashHomeDark: Boolean
        get() = prefs.getBoolean("dashHomeDarkTest", false)
        set(v) { prefs.edit().putBoolean("dashHomeDarkTest", v).apply(); if (!v) client.lightHome(); notifyUi() }

    private fun hideDashboard(why: String, headDown: Boolean = headIsDown) {
        if (appMode != AppMode.DASHBOARD || glassesPaused || linkState != LinkState.READY) return
        cancelDashTimers()
        glassesPaused = true
        headHidden = true
        notifyLit = false; main.removeCallbacks(reOffAfterNotify); restoreHeadUp()
        notifyOver = false; main.removeCallbacks(endNotifyOver)
        if (dashHideMode == HIDE_HOME) {
            // 退回主界面后就交给眼镜，萤读不再做任何事。
            // 测试开关打开时：低着头就先关屏再退出（眼镜自己只在抬头→低头那一下变暗，这时已经低着头了，主界面会一直亮着）
            quitHomeAt = android.os.SystemClock.uptimeMillis()
            if (headDown && dashHomeDark) { client.quitToDarkHome(); log("$why（回主界面，低着头所以关屏；抬头再看）【测试】") }
            else { client.quitCurrent(); log("$why（回主界面；点一下镜腿再看）") }
        } else {
            screenOffByUs = true; client.screenOff(true)
            main.postDelayed(standbyRefresh, 300)
            log("$why（关屏；" + (if (dashHeadWake) "抬头再看" else "手机上点「显示看板」再看") + "）")
        }
        updateNotification(); notifyUi()
    }

    /** 萤读自己退回官方主界面的时间（uptime）：退出后眼镜会顺手报一次「主界面被点亮」，那不是用户点的。 */
    private var quitHomeAt = 0L


    /**
     * 官方主界面刚被点亮时叫回看板：眼镜正忙着画主界面，这时发的"打开页面"常被丢掉，
     * 所以马上发，之后每隔一小会儿再发一次，直到眼镜确认打开（见 NimoClient.retryNextEnter）。
     */
    private fun wakeFromHome(why: String) {
        // 手机上手动收起的也叫回（以前只叫回自动收起的，手动收起后抬头就再也叫不回来，容易误以为坏了）
        val skip = when {
            linkState != LinkState.READY -> "没连着"
            dashAtHome -> "没用看板"
            appMode != AppMode.DASHBOARD -> "现在不是看板（${activeApp?.title ?: "阅读"}）"
            !glassesPaused || client.enteredAppId() != null -> null      // 看板已经开着
            notifyLit -> "正在显示通知"
            else -> ""
        }
        if (skip == null) return
        if (skip.isNotEmpty()) { log("$why：不叫回（$skip）"); client.lightHome(); return }
        log(why)
        client.retryNextEnter()
        wakeDashboard()
    }

    /** 从收起回到看板：从第一屏开始，重新计时；屏幕等看板画好再亮，不先露出上一次的画面。 */
    private fun wakeDashboard() {
        headHidden = false
        val lit = notifyLit
        if (lit) log("叫回看板：正在显示的通知被看板替换")
        notifyLit = false; main.removeCallbacks(reOffAfterNotify); restoreHeadUp()
        main.removeCallbacks(standbyRefresh)
        dashPage = 0
        if ((screenOffByUs || lit) && standbyKey != null && standbyKey == lastPushedDash &&
            client.enteredAppId() == NimoProtocol.APP_ID_NAV && dashAsImage) {
            // 关屏时第一屏已经放在眼镜上了：只发一条"开屏"，马上就能看到；内容有变化再紧接着刷新
            standbyKey = null
            screenOffByUs = false; client.screenOnUrgent()
            glassesPaused = false
            cancelDashTimers(); armDashTimers()
            log("第一屏已在眼镜上，直接开屏")
            // 保险：紧接着再发一次当前画面（内容一样时眼镜上看不出变化；万一待机那张没收到，这里补上）
            lastPushedDash = ""
            pushDashboard(force = false, restartTimers = false)
            scheduleDashLive()
            updateNotification(); notifyUi()
            return
        }
        standbyKey = null
        lastPushedDash = ""
        if (screenOffByUs) { screenOffByUs = false; client.unblankAfterNextContent() }
        // 从官方主界面叫回时不先关屏：关屏再开要 1～2 秒，比露一下主界面更慢
        else if (dashHideMode != HIDE_HOME) client.blankUntilShown()
        pushDashboard(force = true)
    }

    /** 关屏收起时眼镜上放着的第一屏（抬头时直接开屏就能看到）；null = 没有放好。 */
    private var standbyKey: String? = null

    /**
     * 关屏收起期间，把看板第一屏放在眼镜上（屏幕关着看不到），每 30 秒看一次内容有没有变，变了才重发（压缩后约 2～3 KB）。
     * 抬头时只要发一条"开屏"，不用再等整张图传完。弹通知时开屏，底下看到的也是看板。
     */
    private val standbyRefresh: Runnable = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            if (appMode != AppMode.DASHBOARD || !glassesPaused || !screenOffByUs || linkState != LinkState.READY || !dashAsImage) return
            // 页面要重开（两条镜腿刚重新连上）：重开时会开屏，关屏期间不做，留到抬头时一起做
            if (client.reopenPending) { standbyKey = null; main.postDelayed(this, STANDBY_REFRESH_MS); return }
            dashPage = 0
            val key = dashKey()
            if (key != lastPushedDash) { lastPushedDash = key; client.showImage(renderDash(), DashboardImage.W, DashboardImage.H) }
            standbyKey = key
            main.postDelayed(this, STANDBY_REFRESH_MS)
        }
    }

    /** 看板多于一屏（卡片多于两张，或股票多于一屏）时，在显示期间自动轮换。 */
    private val dashCycle = object : Runnable {
        override fun run() {
            if (appMode != AppMode.DASHBOARD || glassesPaused) return
            if (notifyOver) { main.postDelayed(this, 1_000); return }    // 通知盖着：显示完再翻屏
            val pages = dashScreens().size
            if (pages <= 1) return
            // 会自动收起时，每屏只轮一遍，停在最后一屏直到收起；设成"不收起"才循环轮换
            if (dashAutoHideSec > 0 && dashPage >= pages - 1) return
            dashPage = (dashPage + 1) % pages
            val last = dashPage >= pages - 1
            // 下一屏也从真正显示出来时算起：显示满 5 秒再翻；最后一屏至少显示 5 秒才收起
            afterShown {
                val now = android.os.SystemClock.uptimeMillis()
                if (dashAutoHideSec > 0 && last && hideAt - now < 5_000) {
                    main.removeCallbacks(dashHide); hideAt = now + 5_000; main.postDelayed(dashHide, 5_000)
                }
                main.postDelayed(this, 5_000)
            }
            pushDashboard(force = true, restartTimers = false)
        }
    }

    private fun cancelDashTimers() {
        timerToken++
        main.removeCallbacks(dashHide); main.removeCallbacks(dashCycle); main.removeCallbacks(dashLive); main.removeCallbacks(stockLive)
        main.removeCallbacks(headDownHide)
    }

    /** 看板上有会动的卡片（表盘、番茄钟）时，显示期间及时刷新；内容没变不重发。 */
    private val dashLive = Runnable { pushDashboard(force = false, restartTimers = false); scheduleDashLive() }

    private fun scheduleDashLive() {
        main.removeCallbacks(dashLive)
        if (appMode != AppMode.DASHBOARD || glassesPaused) return
        val on = cards.items.filter { it.enabled }.map { it.type }.toSet()
        val now = System.currentTimeMillis()
        val delay = when {
            CardType.POMODORO in on && pomodoro.state.running -> 1000 - now % 1000 + 20
            CardType.FACE in on -> 60_000 - now % 60_000 + 50
            else -> return
        }
        main.postDelayed(dashLive, maxOf(delay, client.imageIntervalMs() ?: 0))
    }

    /** 计时的批次：取消计时后，还在等"画面显示出来"的旧回调就作废。 */
    private var timerToken = 0
    /** 自动收起预定在什么时候（uptime）。 */
    private var hideAt = 0L

    /** 每次把看板重新显示出来时调用：等画面真正显示在眼镜上，再开始计时。 */
    private fun startDashTimers() {
        cancelDashTimers()
        if (appMode != AppMode.DASHBOARD) return
        shownAt = android.os.SystemClock.uptimeMillis()
        afterShown { armDashTimers() }
        main.post(stockLive)
    }

    /** 看板显示着、开了股票卡片时，每秒取一次行情；价格变了才重发画面（上一轮没取完不叠加）。 */
    private var stockFetching = false
    private val stockLive: Runnable = object : Runnable {
        override fun run() {
            if (appMode != AppMode.DASHBOARD || glassesPaused || linkState != LinkState.READY) return
            if (cards.items.none { it.enabled && it.type == CardType.STOCKS }) return
            if (stockFetching) return
            val symbols = stockList()
            if (symbols.isEmpty()) return
            stockFetching = true
            val started = android.os.SystemClock.uptimeMillis()
            stockIo.execute {
                val fetched = symbols.map { sym -> stockPool.submit(java.util.concurrent.Callable { DashboardData.quote(sym) }) }
                    .map { runCatching { it.get(8, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull() }
                main.post {
                    stockFetching = false
                    // 这一轮没取到的保留上一次的价格
                    val old = quotes.associateBy { it.symbol }
                    quotes = symbols.mapIndexed { i, sym -> fetched[i]?.takeIf { it.price != null } ?: old[sym] ?: Quote(sym, null, null) }
                    dashUpdated = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())
                    if (appMode == AppMode.DASHBOARD && !glassesPaused) {
                        pushDashboard(force = false, restartTimers = false)
                        main.removeCallbacks(this)
                        main.postDelayed(this, maxOf(0L, started + 1_000 - android.os.SystemClock.uptimeMillis()))
                    }
                    notifyUi()
                }
            }
        }
    }
    private val stockPool by lazy { java.util.concurrent.Executors.newFixedThreadPool(4) }
    private val stockIo by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }

    /** 接下来那份内容显示到眼镜上之后执行（最多等 6 秒，发送出问题也照样继续）。 */
    private fun afterShown(action: () -> Unit) {
        val token = timerToken
        var done = false
        val run = Runnable { if (!done && token == timerToken) { done = true; action() } }
        client.whenNextContentShown { run.run() }
        main.postDelayed(run, 6_000)
    }

    /** 从现在（画面刚显示出来）开始计时：每屏显示满 5 秒再翻，到时间自动收起。 */
    private fun armDashTimers() {
        main.removeCallbacks(dashHide); main.removeCallbacks(dashCycle)
        if (appMode != AppMode.DASHBOARD || glassesPaused) return
        shownAt = android.os.SystemClock.uptimeMillis()
        val pages = dashScreens().size
        if (dashAutoHideSec > 0) {
            val delay = maxOf(dashAutoHideSec * 1000L, pages * 5_000L)
            hideAt = shownAt + delay
            main.postDelayed(dashHide, delay)
        }
        if (pages > 1) main.postDelayed(dashCycle, 5_000)
        if (!stockFetching) main.post(stockLive)
    }

    var weather: Weather? = null; private set
    var quotes: List<Quote> = emptyList(); private set
    var dashUpdated = ""; private set
    private var dashPage = 0
    private var lastWeatherAt = 0L
    private var lastPushedDash = ""
    private var refreshing = false

    val stocks by lazy { StockStore(this).also { it.migrateFrom(dashStocks) } }
    val todos by lazy { TodoStore(this).also { it.migrateFrom(dashTodos) } }
    lateinit var steps: StepsProvider
    var calendarEvents: List<CalEvent> = emptyList(); private set

    val cards by lazy { DashCardStore(this) }
    /** 网络卡片最近一次取到的内容和时间（按卡片 id）。 */
    private val webRows = HashMap<String, List<CardRow>>()
    private val webFetchedAt = HashMap<String, Long>()
    private val webLoading = HashSet<String>()

    /** 看板上显示的股票（勾选了的）；只取这些的行情。 */
    private fun stockList() = stocks.shown()

    /** 一张卡片现在要显示的行。 */
    fun cardRows(c: CardConfig, agendaLimit: Int = DashboardImage.ROWS): List<CardRow> {
        val now = System.currentTimeMillis()
        return when (c.type) {
            CardType.STOCKS -> {
                val bySym = quotes.associateBy { it.symbol }
                CardContent.stocks(stockList().map { bySym[it] ?: Quote(it, null, null) })
            }
            CardType.AGENDA -> CardContent.agenda(calendarEvents, todos.items, now, agendaLimit)
            CardType.FORECAST -> weather?.let { CardContent.forecast(it.hours, it.days) } ?: emptyList()
            CardType.COUNTDOWN -> CardContent.countdown(c.text, now)
            CardType.CLOCKS -> CardContent.clocks(c.text, now)
            CardType.FACE -> java.util.Calendar.getInstance().let {
                CardContent.face(PixelClock.styleOf(c.text), it.get(java.util.Calendar.HOUR_OF_DAY), it.get(java.util.Calendar.MINUTE))
            }
            CardType.POMODORO -> pomodoro.state.let { s ->
                if (!PomoLogic.started(s)) emptyList()
                else CardContent.pomodoro(s.phase.zh, s.running, pomodoro.remaining(), PomoLogic.progress(s, pomodoro.settings, now), s.done)
            }
            CardType.MEMORY -> memorySummaryForCard()?.let { CardContent.memory(it.todos, it.topics) } ?: emptyList()
            CardType.WEB -> webRows[c.id] ?: if (c.url.isBlank()) emptyList() else listOf(CardRow(c.name, right = "加载中…"))
            CardType.SIGHTS -> sightsCardRows()
        }
    }

    /** 看板的各屏：打开的卡片按顺序两两并排。 */
    fun dashScreens(): List<DashScreen> {
        val shown = cards.items.filter { it.enabled }.map { it to cardRows(it) }
        // 只有股票一张卡片、多于一屏时，左右两栏都放股票（一屏 8 只）
        val stocksOnly = shown.filter { it.second.isNotEmpty() }.let { it.size == 1 && it[0].first.type == CardType.STOCKS }
        return DashLayout.screens(shown.map { it.second }, dashRows(), pairSingle = stocksOnly,
            heads = shown.map { (c, rows) -> cardHead(c, rows, stocksOnly) })
    }

    /** 卡片框上的图标、名称和右上角小字。 */
    private fun cardHead(c: CardConfig, rows: List<CardRow>, stocksOnly: Boolean): CardHead = when (c.type) {
        // 不显示更新时间：眼镜顶部的状态栏已经有时间了
        CardType.STOCKS -> CardHead("stock", c.name, if (stocksOnly && rows.size > DashboardImage.ROWS) "${rows.size} 只" else "")
        CardType.AGENDA -> CardHead("cal", c.name, if (rows.isEmpty()) "" else "${rows.size} 项")
        CardType.FORECAST -> CardHead(DashboardImage.weatherIcon(weather?.desc), c.name, dashCity)
        CardType.COUNTDOWN -> CardHead("flag", c.name)
        CardType.CLOCKS -> CardHead("globe", c.name)
        CardType.FACE -> CardHead("clock", c.name)
        CardType.POMODORO -> CardHead("tomato", c.name, pomodoro.state.done.let { if (it > 0) "今天 $it 个" else "" })
        CardType.MEMORY -> CardHead("memo", c.name)
        CardType.WEB -> CardHead("web", c.name)
        CardType.SIGHTS -> CardHead("pin", c.name, if (nearSights.isEmpty()) "" else "${nearSights.size} 个")
    }

    /** 卡片的开关、顺序、设置改了：回到第一屏，网络卡片立即重新获取。 */
    fun cardsChanged(refetch: String? = null) {
        if (refetch != null) { webFetchedAt.remove(refetch); webRows.remove(refetch) }
        dashPage = 0; lastPushedDash = ""
        refreshDashboard()
        if (appMode == AppMode.DASHBOARD) pushDashboard(force = false)
        notifyUi()
    }

    /** 到时间的网络卡片在后台重新获取（每张卡片按自己设的分钟数）。 */
    private fun fetchWebCards(force: Boolean) {
        val now = System.currentTimeMillis()
        for (c in cards.items) {
            if (!c.enabled || c.type != CardType.WEB || c.url.isBlank() || c.id in webLoading) continue
            val last = webFetchedAt[c.id] ?: 0L
            if (!force && now - last < c.minutes.coerceAtLeast(1) * 60_000L) continue
            webLoading.add(c.id)
            io.execute {
                val rows = try {
                    CardContent.parseWeb(DashboardData.fetchCard(c)).ifEmpty { listOf(CardRow(c.name, right = "没有内容")) }
                } catch (e: Exception) {
                    listOf(CardRow(c.name, right = "获取失败"), CardRow(e.message ?: e.javaClass.simpleName))
                }
                main.post {
                    webLoading.remove(c.id)
                    webFetchedAt[c.id] = System.currentTimeMillis()
                    if (rows != webRows[c.id]) {
                        webRows[c.id] = rows
                        if (appMode == AppMode.DASHBOARD) pushDashboard(force = false)
                        notifyUi()
                    }
                }
            }
        }
    }

    fun stocksChanged() { dashPage = 0; lastPushedDash = ""; refreshDashboard(); notifyUi() }
    fun todosChanged() { lastPushedDash = ""; if (appMode == AppMode.DASHBOARD) pushDashboard(force = false); notifyUi() }

    fun toggleAppMode() = switchAppMode(if (appMode == AppMode.READER) AppMode.DASHBOARD else AppMode.READER)

    private fun setMode(mode: AppMode) {
        activeApp?.let { activeApp = null; it.onClose() }
        baseMode = mode
        cancelDashTimers()
        if (screenOffByUs) { screenOffByUs = false; client.screenOff(false) }
        headHidden = false
        stopAutoFlip()
        log(if (mode == AppMode.DASHBOARD) "切换到看板" else "切换到阅读")
        if (appMode == AppMode.DASHBOARD) { dashPage = 0; refreshDashboard(forceWeather = weather == null) }
        restartDashTimer()
        pushPage()
    }

    fun saveDashboard(stocks: String, todos: String) {
        dashStocks = stocks
        dashTodos = todos
        dashPage = 0
        log("仪表盘设置已保存")
        refreshDashboard(forceWeather = false)
        if (appMode == AppMode.DASHBOARD) pushDashboard(force = true)
    }

    fun setCity(name: String) {
        if (name.isBlank()) return
        prefs.edit().putBoolean("autoLocate", false).apply()   // 手动指定城市就不再自动定位
        log("查找城市：$name")
        io.execute {
            val place = runCatching { DashboardData.geocode(name.trim()) }.getOrNull()
            main.post {
                if (place == null) { log("没找到这个城市，换个写法试试（中文、日文或英文都可以）"); return@post }
                dashCity = place.name
                dashLat = place.lat
                dashLon = place.lon
                log("城市已设为 ${place.name}")
                refreshDashboard(forceWeather = true)
            }
        }
    }

    /** 后台刷新行情（每次）和天气（最多 30 分钟一次）。 */
    /** 后台刷新行情（每次）和天气（最多 30 分钟一次；开了自动定位会先重新定位）。 */
    fun refreshDashboard(forceWeather: Boolean = false) {
        steps.refresh()
        sightsCardRefresh(force = forceWeather)
        fetchWebCards(force = forceWeather)
        io.execute {
            val ev = runCatching { CalendarReader.upcoming(this) }.getOrDefault(emptyList())
            main.post { if (ev != calendarEvents) { calendarEvents = ev; if (appMode == AppMode.DASHBOARD) pushDashboard(force = false) } }
        }
        val weatherDue = forceWeather || System.currentTimeMillis() - lastWeatherAt > 30 * 60_000L
        if (weatherDue) {
            if (autoLocate) locate() else if (!dashLat.isNaN()) fetchWeather(dashLat, dashLon)
        }
        if (refreshing) return
        refreshing = true
        val symbols = stockList()
        io.execute {
            val qs = symbols.map { DashboardData.quote(it) }
            main.post {
                refreshing = false
                quotes = qs
                dashUpdated = java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date())
                val failed = qs.count { it.price == null }
                if (failed > 0) log("有 $failed 只股票没取到行情，检查代码是否正确")
                if (appMode == AppMode.DASHBOARD) pushDashboard(force = false)
                notifyUi()
            }
        }
    }

    private fun fetchWeather(lat: Double, lon: Double) {
        io.execute {
            val w = runCatching { DashboardData.weather(lat, lon) }.getOrNull()
            main.post {
                if (w == null) { log("天气获取失败"); return@post }
                weather = w
                lastWeatherAt = System.currentTimeMillis()
                if (appMode == AppMode.DASHBOARD) pushDashboard(force = false)
                notifyUi()
            }
        }
    }

    private var locating = false

    /** 用手机定位确定城市和天气坐标（有精确定位权限就用精确位置）。失败时沿用上次的位置。 */
    @SuppressLint("MissingPermission")
    fun locate() {
        if (locating) return
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            log("没有定位权限，请在看板页打开「自动定位」并允许")
            if (!dashLat.isNaN()) fetchWeather(dashLat, dashLon)
            return
        }
        if (hasFineLocation()) {
            locating = true
            preciseLocation { l ->
                locating = false
                if (l != null) useLocation(l) else { log("定位失败，沿用上次的位置"); if (!dashLat.isNaN()) fetchWeather(dashLat, dashLon) }
            }
            return
        }
        val lm = getSystemService(android.location.LocationManager::class.java) ?: return
        val enabled = lm.getProviders(true)
        val last = enabled.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        val fresh = last != null && System.currentTimeMillis() - last.time < 30 * 60_000L
        if (fresh) { useLocation(last!!); return }
        val provider = listOf("fused", android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.GPS_PROVIDER)
            .firstOrNull { it in enabled }
        if (provider == null) {
            if (last != null) useLocation(last) else { log("手机定位没有打开"); if (!dashLat.isNaN()) fetchWeather(dashLat, dashLon) }
            return
        }
        locating = true
        val done = { loc: android.location.Location? ->
            locating = false
            val l = loc ?: last
            if (l != null) useLocation(l) else { log("定位失败，沿用上次的位置"); if (!dashLat.isNaN()) fetchWeather(dashLat, dashLon) }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            lm.getCurrentLocation(provider, null, mainExecutor) { done(it) }
        } else {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, object : android.location.LocationListener {
                override fun onLocationChanged(l: android.location.Location) { done(l) }
                @Deprecated("旧接口") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }, Looper.getMainLooper())
        }
        main.postDelayed({ if (locating) done(null) }, 20_000)
    }

    private fun useLocation(l: android.location.Location) {
        val lat = l.latitude
        val lon = l.longitude
        dashLat = lat
        dashLon = lon
        fetchWeather(lat, lon)
        io.execute {
            val name = reverseGeocode(lat, lon)
            main.post {
                if (name != null && name != dashCity) { dashCity = name; log("定位：$name") }
                lastPushedDash = ""
                if (appMode == AppMode.DASHBOARD) pushDashboard(force = false)
                notifyUi()
            }
        }
    }

    /** 坐标 → 城市名。先用系统的地理编码，不可用时用免费的 BigDataCloud 接口。 */
    private fun reverseGeocode(lat: Double, lon: Double): String? {
        runCatching {
            @Suppress("DEPRECATION")
            val a = android.location.Geocoder(this, Locale.SIMPLIFIED_CHINESE).getFromLocation(lat, lon, 1)?.firstOrNull()
            val n = a?.locality ?: a?.subAdminArea ?: a?.adminArea
            if (!n.isNullOrBlank()) return n
        }
        return runCatching { DashboardData.reverseGeocode(lat, lon) }.getOrNull()
    }

    private val dashTick = object : Runnable {
        override fun run() {
            if (appMode != AppMode.DASHBOARD) return
            refreshDashboard()
            main.postDelayed(this, 60_000L)
        }
    }

    private fun restartDashTimer() {
        main.removeCallbacks(dashTick)
        if (appMode == AppMode.DASHBOARD) main.postDelayed(dashTick, 60_000L)
    }

    fun dashboardPreview(): Pair<String, String> {
        val screens = dashScreens()
        if (dashPage >= screens.size) dashPage = 0
        val head = DashboardImage.header(java.util.Calendar.getInstance(), steps.steps, dashCity, weather,
            GlassesFonts.text(glassesFontPx), 10_000)
        val status = head + if (screens.size > 1) "  ${dashPage + 1}/${screens.size}" else ""
        return status to DashboardFormatter.body(screens[dashPage], charsPerLine)
    }

    private fun dashRows() = if (dashAsImage) DashboardImage.ROWS else linesPerPage

    /** 看板现在是第几屏、一共几屏（从 0 数）。 */
    fun dashPageInfo(): Pair<Int, Int> { val n = dashScreens().size; return dashPage.coerceIn(0, n - 1) to n }

    /** 眼镜上看板当前这一屏的样子（手机上预览）。 */
    fun dashboardBitmap(): android.graphics.Bitmap {
        val screens = dashScreens()
        val p = dashPage.coerceIn(0, screens.size - 1)
        return DashboardImage.renderBitmap(java.util.Calendar.getInstance(), steps.steps, dashCity, weather, screens[p], p, screens.size, glassesFontPx)
    }

    private fun pushDashboard(force: Boolean, restartTimers: Boolean = true) {
        if (dashAtHome) {
            // 不用看板：萤读自己的看板页不在眼镜上打开，眼镜留在主界面
            if (client.enteredAppId() != null) client.quitCurrent()
            glassesPaused = true
            notifyUi()
            return
        }
        // 通知正盖在看板上：自动刷新（股价每秒、时钟……）先不发，免得把通知冲掉；用户主动翻屏、叫回看板就结束通知
        if (notifyOver) {
            if (!force) { notifyUi(); return }
            notifyOver = false; main.removeCallbacks(endNotifyOver)
        }
        val (status, body) = dashboardPreview()
        val key = dashKey(status, body)
        if (glassesPaused && !force) { notifyUi(); return }   // 自动刷新不打扰已收起的眼镜
        if (force && screenOffByUs) { screenOffByUs = false; client.screenOff(false) }
        glassesPaused = false
        headHidden = false
        if (force && restartTimers) { startDashTimers(); shownAt = android.os.SystemClock.uptimeMillis() }
        if (!force && key == lastPushedDash) { notifyUi(); return }
        lastPushedDash = key
        if (dashAsImage) client.showImage(renderDash(), DashboardImage.W, DashboardImage.H)
        else if (displayPage == DisplayPage.PROMPTER) client.showText(body, status)
        else client.showText(status + "\n" + body)
        scheduleDashLive()
        notifyUi()
    }

    private fun dashKey(): String { val (status, body) = dashboardPreview(); return dashKey(status, body) }
    private fun dashKey(status: String, body: String) = (if (dashAsImage) "I" else "T") + status + "\u0000" + body

    private fun renderDash(): ByteArray {
        val screens = dashScreens()
        val sc = screens[dashPage.coerceIn(0, screens.size - 1)]
        return DashboardImage.render(java.util.Calendar.getInstance(), steps.steps, dashCity, weather,
            sc, dashPage, screens.size, glassesFontPx)
    }

    fun flipDashboard(delta: Int) { if (glassesPaused) resumeGlasses(); dashFlip(delta) }

    private fun dashFlip(delta: Int) {
        val pages = dashScreens().size
        dashPage = ((dashPage + delta) % pages + pages) % pages
        pushDashboard(force = true)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        GlassesFonts.init(this)
        steps = StepsProvider(this) { if (appMode == AppMode.DASHBOARD) pushDashboard(force = false); notifyUi() }
        client = NimoClient(this, this)
        client.page = displayPage
        if (appMode == AppMode.DASHBOARD) { refreshDashboard(forceWeather = true); restartDashTimer() }
        prefs.getString("lastBook", null)?.let { openBook(Uri.parse(it), fromRestore = true) }
        pomodoro.restore()
        // 全天记忆：定好今晚的总结，把上次没转完的文字接着转
        scheduleSummary()
        main.postDelayed({ memKickTranscribe(); memPurge() }, 10_000)
        // 景点介绍：开着的话 1 分钟后查第一次
        scheduleSights(60_000L)
        // 通知使用权授权了但系统没连上（更新萤读后常见）：8 秒后还没连上就请系统重连
        main.postDelayed({ PhoneNotificationService.rebindIfNeeded(this, "萤读启动") }, 8_000)
        // 手机改了时间或换了时区：重新给眼镜校时；另外每天校一次
        registerReceiver(timeReceiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        main.postDelayed(dailyTimeSync, DAY_MS)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_PREV -> if (appMode == AppMode.READER) prevPage() else dashFlip(-1)
            ACTION_NEXT -> if (appMode == AppMode.READER) nextPage() else dashFlip(1)
            ACTION_AUTO -> if (appMode == AppMode.READER) { if (glassesPaused) pushPage(); toggleAutoFlip() } else toggleShow()
        }
        return START_NOT_STICKY
    }

    private val timeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            client.resyncTime(if (i.action == Intent.ACTION_TIMEZONE_CHANGED) "手机换了时区" else "手机改了时间")
        }
    }
    private val dailyTimeSync: Runnable = object : Runnable {
        override fun run() { client.resyncTime("每天一次"); main.postDelayed(this, DAY_MS) }
    }

    /** 打开萤读时调用：眼镜很久没连上、正在慢慢重试的话马上试一次。 */
    fun onUiShown() = client.reconnectNow()

    /** 系统有没有把萤读从"电池优化"里放出来（没放出来时后台容易被杀，通知和重连会断）。 */
    fun ignoringBatteryOptimizations(): Boolean =
        getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    override fun onDestroy() {
        runCatching { unregisterReceiver(timeReceiver) }
        main.removeCallbacks(dailyTimeSync)
        instance = null
        activeApp?.onClose()
        volSession?.let { runCatching { it.release() } }; volSession = null
        steps.stop()
        main.removeCallbacks(dashLive); main.removeCallbacks(stockLive)
        memEngine.stop()
        main.removeCallbacks(summaryTimer)
        main.removeCallbacks(sightsTimer); main.removeCallbacks(sightNext)
        stopAutoFlip()
        main.removeCallbacks(dashTick)
        client.disconnect()
        io.shutdown()
        super.onDestroy()
    }

    // ---------- 连接 ----------

    var deviceName = ""; private set

    fun switchAppMode(mode: AppMode) {
        if (mode == AppMode.APP || appMode == mode) return
        setMode(mode)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        deviceName = runCatching { device.name }.getOrNull() ?: device.address
        showDashboardOnReady = true
        startForegroundService(Intent(this, ReaderService::class.java))
        client.connect(device)
    }

    fun disconnect() = client.disconnect()

    /** 把眼镜交给官方 app：停掉翻译、收起画面、断开连接（不自动重连）。 */
    fun handOffToOfficial() {
        stopAutoFlip()
        client.quitCurrent()
        main.postDelayed({ client.disconnect(); log("已断开，眼镜交给官方 app 使用") }, 300)
    }

    /** 完全退出：断开眼镜并结束前台服务。 */
    fun shutdown() {
        client.disconnect()
        stopAutoFlip()
        inForeground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 眼镜现在的亮度（0..100）：连接时从眼镜读，自动亮度过渡、手动调时跟着更新（和官方 app 一样，用来显示和算偏移）。
     */
    var brightnessPct: Int
        get() = prefs.getInt("brightnessPct", 6)
        private set(v) = prefs.edit().putInt("brightnessPct", v.coerceIn(0, 100)).apply()

    private fun pctOf(level: Int) = Math.round(level * 100.0 / NimoProtocol.MAX_BRIGHTNESS_LEVEL).toInt()

    /** 调亮度：和官方一样，开着自动亮度时不关它，只发偏移。 */
    fun setBrightness(percent: Int) {
        if (linkState != LinkState.READY) return
        main.removeCallbacks(brightnessStep); brightnessAnim = null
        client.setBrightness(percent, brightnessPct, autoBrightness == true)
        brightnessPct = percent
        notifyUi()
    }

    override fun onBrightnessLevel(level: Int) {
        brightnessPct = pctOf(level)
        notifyUi()
    }

    /** 自动亮度平滑过渡：(开始的百分比, 目标百分比, 开始时间)。和官方一样 4 秒、50 毫秒一步、先快后慢。 */
    private var brightnessAnim: Triple<Double, Double, Long>? = null
    private var brightnessSentLevel = -1
    private val brightnessStep: Runnable = object : Runnable {
        override fun run() {
            val (from, to, start) = brightnessAnim ?: return
            val t = ((android.os.SystemClock.uptimeMillis() - start) / BRIGHTNESS_FADE_MS.toDouble()).coerceIn(0.0, 1.0)
            val e = 1 - (1 - t) * (1 - t)
            val pct = from + (to - from) * e
            val lvl = (pct / 100.0 * NimoProtocol.MAX_BRIGHTNESS_LEVEL).toInt()
            if (lvl != brightnessSentLevel) { brightnessSentLevel = lvl; client.setBrightnessLevel(lvl) }
            brightnessPct = Math.round(pct).toInt()
            if (t >= 1.0) { brightnessAnim = null; notifyUi(); return }
            main.postDelayed(this, 50)
        }
    }

    /** 眼镜按环境光算出了新的亮度：开着自动亮度时平滑地设过去；关着就不管（官方也是忽略）。 */
    override fun onAutoBrightnessLevel(level: Int) {
        if (autoBrightness != true || linkState != LinkState.READY) return
        val target = level * 100.0 / NimoProtocol.MAX_BRIGHTNESS_LEVEL
        val cur = brightnessPct.toDouble()
        if (Math.abs(target - cur) < 0.5) return
        log("自动亮度：${cur.toInt()}% → ${target.toInt()}%（$level 档）")
        main.removeCallbacks(brightnessStep)
        brightnessSentLevel = NimoClient.brightnessLevel(brightnessPct)
        brightnessAnim = Triple(cur, target, android.os.SystemClock.uptimeMillis())
        main.post(brightnessStep)
    }

    fun toggleDisplayPage() {
        displayPage = if (displayPage == DisplayPage.PROMPTER) DisplayPage.NOTE else DisplayPage.PROMPTER
        client.page = displayPage
        log(if (displayPage == DisplayPage.PROMPTER) "切换到提词器页面" else "切换到笔记页面")
        repaginate(currentOffset())
    }

    // ---------- 书 ----------

    @SuppressLint("Range")
    fun openBook(uri: Uri, fromRestore: Boolean = false) {
        loading = true
        notifyUi()
        io.execute {
            val result = runCatching {
                val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(c.getColumnIndex(OpenableColumns.DISPLAY_NAME)) else null
                } ?: "未命名"
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                Book.load(name, bytes)
            }
            main.post {
                loading = false
                result.onSuccess { b ->
                    book = b
                    bookUri = uri.toString()
                    prefs.edit().putString("lastBook", bookUri).apply()
                    log("已打开《${b.title}》，${b.text.length} 字，${b.chapters.size} 章")
                    repaginate(savedOffset())
                }.onFailure { e ->
                    if (!fromRestore) log("打开失败：${e.message}")
                    else prefs.edit().remove("lastBook").apply()
                    notifyUi()
                }
            }
        }
    }

    fun applyLayout(perLine: Int, perPage: Int) {
        charsPerLine = perLine.coerceIn(4, 60)
        linesPerPage = perPage.coerceIn(1, 20)
        repaginate(currentOffset())
    }

    private fun repaginate(offset: Int) {
        val b = book ?: return
        val p = Paginator(b, charsPerLine, linesPerPage)
        paginator = p
        topLine = p.lineIndexForOffset(offset)
        log("排版完成：共 ${p.lineCount} 行，约 ${p.pageCount} 屏")
        pushPage()
    }

    val pageCount: Int get() = paginator?.pageCount ?: 0

    /** 当前大约是第几屏（从 1 开始），用于界面显示。 */
    val pageNumber: Int get() = paginator?.let { topLine / it.rows + 1 } ?: 0

    fun currentPage(): Page? = paginator?.view(topLine)

    fun currentOffset(): Int = paginator?.lineStartOf(topLine) ?: savedOffset()

    fun progressPercent(): Double {
        val b = book ?: return 0.0
        if (b.text.isEmpty()) return 0.0
        val end = currentPage()?.end ?: 0
        return end * 100.0 / b.text.length
    }

    fun currentChapterTitle(): String {
        val b = book ?: return ""
        return b.chapters.getOrNull(b.chapterIndexAt(currentOffset()))?.title ?: ""
    }

    // ---------- 音量键翻页（阅读） ----------

    /** 音量键翻页：0 关，1 上一页 / 下一页，2 上一行 / 下一行（音量 + 往前，音量 − 往后；按住连续翻，和官方提词器一样）。 */
    var volumeKeys: Int
        get() = prefs.getInt("volumeKeys", 0)
        set(v) { prefs.edit().putInt("volumeKeys", v).apply(); updateVolumeSession(); notifyUi() }

    /** 现在音量键用来翻页吗（在阅读、开了这个设置、眼镜连着）。 */
    val volumeKeysActive: Boolean get() = volumeKeys != 0 && appMode == AppMode.READER && paginator != null && linkState == LinkState.READY

    private var lastVolumeKeyAt = 0L

    /** 按了音量键（up = 音量 +）：在翻页就翻、返回 true；不翻页返回 false（照常调音量）。 */
    fun volumeKey(up: Boolean, repeat: Boolean = false): Boolean {
        if (!volumeKeysActive) return false
        val now = android.os.SystemClock.uptimeMillis()
        // 按住时系统连发很快：翻页 0.35 秒一次，翻行 0.15 秒一次
        if (repeat && now - lastVolumeKeyAt < (if (volumeKeys == 1) 350 else 150)) return true
        lastVolumeKeyAt = now
        val rows = paginator?.rows ?: return true
        val step = if (volumeKeys == 1) rows else 1
        if (glassesPaused) resumeGlasses()
        moveBy(if (up) -step else step)
        return true
    }

    /**
     * 手机锁屏、萤读在后台时也能用音量键：阅读时开一个「远程音量」的媒体会话，系统把音量键交给它（不改手机音量）。
     * 不在阅读、关了设置时马上关掉，音量键恢复正常。
     */
    private var volSession: android.media.session.MediaSession? = null

    private fun updateVolumeSession() {
        val want = volumeKeysActive
        if (want == (volSession != null)) return
        if (!want) { volSession?.let { runCatching { it.isActive = false; it.release() } }; volSession = null; log("音量键翻页：停"); return }
        volSession = runCatching {
            android.media.session.MediaSession(this, "yingdu-volume-keys").apply {
                setPlaybackToRemote(object : android.media.VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
                    override fun onAdjustVolume(direction: Int) { if (direction != 0) main.post { volumeKey(direction > 0, repeat = true) } }
                })
                setPlaybackState(android.media.session.PlaybackState.Builder()
                    .setState(android.media.session.PlaybackState.STATE_PLAYING, 0, 1f).build())
                isActive = true
            }
        }.onFailure { log("音量键翻页开不了：${it.message}") }.getOrNull()
        if (volSession != null) log("音量键翻页：开（" + (if (volumeKeys == 1) "翻页" else "翻行") + "）")
    }

    fun nextPage() { moveBy(paginator?.rows ?: return) }
    fun prevPage() { moveBy(-(paginator?.rows ?: return)) }

    fun nextChapter() {
        val b = book ?: return
        val next = b.chapters.getOrNull(b.chapterIndexAt(currentOffset()) + 1) ?: return
        goToOffset(next.offset)
    }

    fun prevChapter() {
        val b = book ?: return
        val idx = b.chapterIndexAt(currentOffset())
        val cur = b.chapters.getOrNull(idx) ?: return
        // 已在本章开头就去上一章，否则回到本章开头
        val target = if (currentOffset() > cur.offset) cur else b.chapters.getOrNull(idx - 1) ?: return
        goToOffset(target.offset)
    }

    /** 跳到第 n 屏（从 1 开始，超出范围就停在第一屏或最后一屏）。 */
    fun goToPage(n: Int) {
        val p = paginator ?: return
        setTop(((n - 1).coerceAtLeast(0) * p.rows).coerceAtMost(p.maxTop))
    }

    /** 第 n 屏的内容（手机上拖进度条时预览，不发到眼镜）。 */
    fun pageAt(n: Int): Page? {
        val p = paginator ?: return null
        return p.view(((n - 1).coerceAtLeast(0) * p.rows).coerceAtMost(p.maxTop))
    }

    /** 某一屏读到全书的百分之几（按这一屏最后一个字算）。 */
    fun progressOf(page: Page): Double {
        val len = book?.text?.length ?: 0
        return if (len == 0) 0.0 else page.end * 100.0 / len
    }

    /** 某个位置所在的章节名。 */
    fun chapterAt(offset: Int): String {
        val b = book ?: return ""
        return b.chapters.getOrNull(b.chapterIndexAt(offset))?.title ?: ""
    }

    fun goToOffset(offset: Int) {
        val p = paginator ?: return
        setTop(p.lineIndexForOffset(offset))
    }

    /** 向前/向后移动若干行。到结尾时停在最后一屏，并关闭自动滚动。 */
    private fun moveBy(delta: Int) {
        val p = paginator ?: return
        if (delta > 0 && topLine >= p.maxTop) { stopAutoFlip(); notifyUi(); return }
        if (delta < 0 && topLine == 0) return
        setTop((topLine + delta).coerceIn(0, p.maxTop))
    }

    private fun setTop(line: Int) {
        val p = paginator ?: return
        topLine = line.coerceIn(0, p.lineCount - 1)
        pushPage()
    }

    private fun savedOffset(): Int = bookUri?.let { prefs.getInt("pos:$it", 0) } ?: 0

    /** 把当前页推到眼镜上，同时保存进度。 */
    private fun pushPage() {
        headHidden = false
        when (appMode) {
            AppMode.DASHBOARD -> { pushDashboard(force = true); return }
            AppMode.APP -> {
                val app = activeApp ?: return
                glassesPaused = false
                client.showImage(app.render(glassesFontPx), ReaderImage.W, ReaderImage.H)
                notifyUi()
                return
            }
            AppMode.READER -> {}
        }
        val page = currentPage() ?: run { notifyUi(); return }
        glassesPaused = false
        bookUri?.let { prefs.edit().putInt("pos:$it", page.start).apply() }
        if (readerAsImage) {
            client.showImage(ReaderImage.render(page.text, glassesFontPx, if (showProgress) progressPercent() else null),
                ReaderImage.W, ReaderImage.H)
        } else if (displayPage == DisplayPage.PROMPTER) {
            // 提词器有独立的顶部状态行，章节和进度放在那里，不占正文行数
            val ch = currentChapterTitle()
            val status = if (showProgress) listOf(ch, "%.1f%%".format(progressPercent())).filter { it.isNotEmpty() }.joinToString("  ") else ch
            client.showText(page.text, status)
        } else {
            val text = if (showProgress) page.text + "\n— %.1f%% —".format(progressPercent()) else page.text
            client.showText(text)
        }
        restartAutoFlipTimer()
        notifyUi()
    }

    // ---------- 手机通知 → 眼镜弹窗 ----------

    /**
     * 手机通知 → 眼镜：用眼镜自带的通知弹窗（一条 319 字节的文字指令，眼镜用自己的字画，零点几秒就到；
     * 画成图要传一整张 19 KB 的图，关屏时还要重开页面，慢 2～4 秒）。
     *  - 看板关屏收起时：回官方主界面、临时关掉抬头显示、开屏，等眼镜确认开屏后弹；
     *  - 上一条弹窗还亮着：什么都不动（不再开屏、不退页面），新的直接弹，从这条起再亮 litMs；
     *    第一条还在等开屏确认时来的，排队，开屏后按顺序弹（新的在最上面）；
     *  - 看板亮着：直接弹，看板暂停自动刷新 litMs（每秒的股价刷新会把弹窗冲掉）；
     *  - 其他（阅读、官方主界面）：直接弹。
     * fit = true（景点介绍）要写满一屏，画成图（showDrawnNotification）。
     */
    fun onPhoneNotification(app: String, title: String, text: String, litMs: Long = NOTIFY_LIT_MS, fit: Boolean = false, pkg: String? = null) {
        if (linkState != LinkState.READY) {
            // 眼镜重连期间来的通知先存着，连上后再弹（只留最近 5 条、2 分钟内的）
            if (!fit) {
                offlineQueue.addLast(OfflineNotice(app, title, text, pkg, System.currentTimeMillis()))
                while (offlineQueue.size > OFFLINE_MAX) offlineQueue.removeFirst()
                log("眼镜没连着，通知先存着（${offlineQueue.size} 条），连上后再弹")
            }
            return
        }
        if (fit) { showDrawnNotification(app, title, text, litMs, fit); return }
        // 关屏收起时（或屏幕正为通知亮着）：眼镜的"已开屏"确认几十毫秒就回来，但屏幕真正亮起要 1～2 秒，
        // 这时发眼镜自带弹窗会被丢掉（v5.7.7 日志：开屏确认后立刻弹，屏幕亮了却只有看板）。
        // 所以画成图先放到页面上，再开屏，一亮就是通知
        if (screenOffByUs || notifyLit) { showDrawnNotification(app, title, text, litMs, false); return }
        val appId = NotifyIcons.appIdOf(pkg)
        val icon = appId ?: NotifyIcons.OTHER
        val t = NotifyIcons.title(app, title, appId != null)
        val how: String
        when {
            appMode == AppMode.DASHBOARD && !glassesPaused && activeApp == null -> {
                notifyOver = true
                main.removeCallbacks(endNotifyOver)
                client.sendNotification(t, text, icon)
                main.postDelayed(endNotifyOver, litMs)
                how = "看板上 · 看板暂停刷新 ${litMs / 1000} 秒"
            }
            else -> { client.sendNotification(t, text, icon); how = "直接弹" }
        }
        log("通知：$t（$how）")
    }

    private class OfflineNotice(val app: String, val title: String, val text: String, val pkg: String?, val at: Long)
    /** 眼镜没连着时来的通知（连上后重放）。 */
    private val offlineQueue = ArrayDeque<OfflineNotice>()

    /** 连上后重放离线期间的通知：丢掉超过 2 分钟的，其余每 2 秒弹一条。 */
    private fun replayOffline() {
        val now = System.currentTimeMillis()
        val list = offlineQueue.filter { now - it.at <= OFFLINE_MAX_AGE_MS }
        offlineQueue.clear()
        if (list.isEmpty()) return
        log("眼镜已连上，重放离线期间的 ${list.size} 条通知")
        list.forEachIndexed { i, n ->
            main.postDelayed({ if (linkState == LinkState.READY) onPhoneNotification(n.app, n.title, n.text, pkg = n.pkg) }, i * 2_000L)
        }
    }

    /** 画成图显示（景点介绍：要写满一屏，眼镜自带弹窗只放得下约 85 字）。 */
    private fun showDrawnNotification(app: String, title: String, text: String, litMs: Long, fit: Boolean,
                                      custom: android.graphics.Bitmap? = null) {
        val popupText = if (fit) Sights.cut(text, Sights.POPUP_CHARS) else text
        val t = if (title.isEmpty() || title == app) app else "$app · $title"
        val overDash = !(screenOffByUs || notifyLit) && appMode == AppMode.DASHBOARD && !glassesPaused && dashAsImage && activeApp == null
        val img = custom ?: DashboardImage.renderNotification(app, title, if (fit) DashboardImage.fitNotificationText(text, glassesFontPx) else text,
            java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date()), glassesFontPx)
        val how: String
        if (overDash) {
            // 看板亮着：画成图盖在看板上，这期间看板不自动刷新，从显示出来算起 litMs 后恢复
            val id = ++notifySerial
            notifyOver = true
            main.removeCallbacks(endNotifyOver)
            client.whenNextContentShown {
                if (notifyOver && id == notifySerial) { main.removeCallbacks(endNotifyOver); main.postDelayed(endNotifyOver, litMs) }
            }
            main.postDelayed(endNotifyOver, litMs + 6_000)
            client.showImage(DashboardImage.toGlasses(img), DashboardImage.W, DashboardImage.H)
            lastPushedDash = ""
            how = "看板上 · 画成图"
        } else if (screenOffByUs && client.enteredAppId() == NimoProtocol.APP_ID_NAV && !client.reopenPending) {
            // 看板关屏收起、页面还开着：和抬头叫回看板走同一条路——不重开页面、不改抬头显示，
            // 直接把图发进开着的页面，发完用带确认的开屏（插到发送队列最前面）。litMs 后关回去，再把看板第一屏放回去
            screenOffByUs = false; notifyLit = true
            val id = ++notifySerial
            main.removeCallbacks(reOffAfterNotify)
            main.removeCallbacks(standbyRefresh)
            standbyKey = null
            client.whenNextContentShown {
                if (notifyLit && id == notifySerial) {
                    client.screenOnUrgent()
                    main.removeCallbacks(reOffAfterNotify); main.postDelayed(reOffAfterNotify, litMs)
                    main.postDelayed({ if (notifyLit && id == notifySerial) client.screenOnUrgent() }, 1_500)   // 保险：再开一次
                }
            }
            main.postDelayed(reOffAfterNotify, litMs + 6_000)     // 保险：图没发出去也不会一直亮着
            client.showImage(DashboardImage.toGlasses(img), DashboardImage.W, DashboardImage.H)
            lastPushedDash = ""
            how = "关屏时 · 发进开着的页面再开屏"
        } else if (screenOffByUs || notifyLit) {
            // 看板关屏收起时（或者屏幕正为通知亮着）：先把页面重开一次（关屏久了页面可能已被眼镜收起），发图、画好再开屏，
            // 从亮起算 litMs 后关回去；看板本身不唤醒。亮屏期间临时关掉抬头显示（开着时低头会把屏幕压暗）
            val fromOff = screenOffByUs
            if (screenOffByUs) { screenOffByUs = false; notifyLit = true }
            // 不再临时关掉抬头显示：改这项设置时眼镜会关屏，图就看不到了
            val id = ++notifySerial
            main.removeCallbacks(reOffAfterNotify)
            client.unblankAfterNextContent()
            if (fromOff || client.enteredAppId() == null) client.reopenBeforeNextContent()
            client.whenNextContentShown {
                if (notifyLit && id == notifySerial) {
                    main.removeCallbacks(reOffAfterNotify); main.postDelayed(reOffAfterNotify, litMs)
                    main.postDelayed({ if (notifyLit && id == notifySerial) client.screenOff(false) }, 800)   // 保险：再发一次开屏
                }
            }
            main.postDelayed(reOffAfterNotify, litMs + 6_000)     // 保险：图没发出去也不会一直亮着
            client.showImage(DashboardImage.toGlasses(img), DashboardImage.W, DashboardImage.H)
            lastPushedDash = ""
            how = "关屏时 · 画成图"
        } else { client.sendNotification(t, popupText); how = "直接弹" }
        log("通知：$t（$how）")
    }

    private var notifySerial = 0

    /** 通知正画在看板上（看板亮着时来的通知）：这几秒里看板不自动刷新，显示完恢复。 */
    private var notifyOver = false
    private val endNotifyOver = Runnable {
        if (!notifyOver) return@Runnable
        notifyOver = false
        log("通知显示完，回到看板")
        if (appMode == AppMode.DASHBOARD && !glassesPaused && linkState == LinkState.READY) pushDashboard(force = false, restartTimers = false)
    }

    /** 为了弹通知临时开着屏（看板仍是收起状态）。 */
    private var notifyLit = false
    private val reOffAfterNotify = Runnable {
        if (!notifyLit) return@Runnable
        notifyLit = false
        log("通知显示完")
        if (appMode == AppMode.DASHBOARD && glassesPaused && headHidden && dashHideMode == HIDE_SCREEN_OFF && linkState == LinkState.READY) {
            screenOffByUs = true; client.screenOff(true)
            main.postDelayed(standbyRefresh, 300)
        }
        restoreHeadUp()
    }

    /** 弹通知期间临时关掉了眼镜的抬头显示（要恢复）。记在设置里，万一中途被杀掉，下次连上也会恢复。 */
    private var headUpSuspended = false
    private fun suspendHeadUp() {
        if (headUpDisplay != true || headUpSuspended) return
        headUpSuspended = true
        prefs.edit().putBoolean("headUpRestore", true).apply()
        client.headUpDisplayTemp(false)
    }
    private fun restoreHeadUp() {
        if (!headUpSuspended && !prefs.getBoolean("headUpRestore", false)) return
        if (linkState != LinkState.READY) return      // 没连着：留着标记，下次连上再恢复
        headUpSuspended = false
        prefs.edit().remove("headUpRestore").apply()
        client.setGlassesSetting(NimoProtocol.SET_HEADUP_DISPLAY, true)   // 走正常设置，界面上的开关也跟着对上
        log("恢复抬头显示")
    }

    // ---------- 景点介绍 ----------

    private val sightPrefs by lazy { getSharedPreferences("sights", MODE_PRIVATE) }
    var sightsOn: Boolean
        get() = sightPrefs.getBoolean("on", false)
        set(v) {
            sightPrefs.edit().putBoolean("on", v).apply()
            if (inForeground) runCatching { startInForeground() }
            scheduleSights(if (v) 3_000L else -1L)
            if (!v) { sightQueue.clear(); main.removeCallbacks(sightNext); sightPlaying = false }
            notifyUi()
        }
    /** 每隔几分钟查一次（15 / 30）。 */
    /** 找景点用哪家地图（SightsNet.SRC_*）、介绍先查哪个百科（SightsNet.INTRO_*）、高德和 Google 的 Key。 */
    var sightsSource: Int
        get() = sightPrefs.getInt("source", SightsNet.SRC_AMAP).let { if (it == SightsNet.SRC_GOOGLE) it else SightsNet.SRC_AMAP }
        set(v) { sightPrefs.edit().putInt("source", v).apply(); notifyUi() }
    var sightsIntro: Int
        get() = sightPrefs.getInt("intro", SightsNet.INTRO_BAIKE)
        set(v) { sightPrefs.edit().putInt("intro", v).apply(); notifyUi() }
    var amapKey: String
        get() = sightPrefs.getString("amapKey", "").orEmpty()
        set(v) { sightPrefs.edit().putString("amapKey", v.trim()).apply(); notifyUi() }
    var googleMapsKey: String
        get() = sightPrefs.getString("googleKey", "").orEmpty()
        set(v) { sightPrefs.edit().putString("googleKey", v.trim()).apply(); notifyUi() }

    var sightsEveryMin: Int
        get() = sightPrefs.getInt("everyMin", 15)
        set(v) { sightPrefs.edit().putInt("everyMin", v).apply(); scheduleSights(); notifyUi() }
    /** 页面上显示的状态。 */
    var sightsStatus = ""; private set
    var sightsBusy = false; private set

    class SightNote(val time: Long, val name: String, val dist: String, val text: String, val src: String = "")

    /** 最近介绍过的（新的在前，最多 50 条）。 */
    val sightsHistory: List<SightNote> get() = runCatching {
        val a = org.json.JSONArray(sightPrefs.getString("history", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> SightNote(o.getLong("t"), o.getString("n"), o.optString("d"), o.optString("x"), o.optString("s")) } }
    }.getOrDefault(emptyList())

    private fun addSightHistory(n: SightNote) {
        val a = org.json.JSONArray()
        (listOf(n) + sightsHistory).take(50).forEach { a.put(org.json.JSONObject().put("t", it.time).put("n", it.name).put("d", it.dist).put("x", it.text).put("s", it.src)) }
        sightPrefs.edit().putString("history", a.toString()).apply()
    }

    /** 介绍过的景点（Sights.key → 时间），FORGET_DAYS 天后可以再介绍。 */
    private fun seenSights(): Map<String, Long> = runCatching {
        val o = org.json.JSONObject(sightPrefs.getString("seen", "{}"))
        val cutoff = System.currentTimeMillis() - Sights.FORGET_DAYS * 24 * 3600_000L
        o.keys().asSequence().associateWith { o.getLong(it) }.filterValues { it > cutoff }
    }.getOrDefault(emptyMap())

    private fun markSightsSeen(keys: List<String>) {
        val m = seenSights().toMutableMap()
        val now = System.currentTimeMillis()
        keys.forEach { m[it] = now }
        sightPrefs.edit().putString("seen", org.json.JSONObject(m as Map<*, *>).toString()).apply()
    }

    fun clearSightsSeen() {
        sightPrefs.edit().remove("seen").remove("history").remove("lat").remove("lon").apply()
        sightsStatus = "已清空，下次会重新介绍附近的景点"; notifyUi()
    }

    fun hasFineLocation() = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    fun hasLocation() = hasFineLocation() ||
        checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** 权限刚给了：前台服务带上定位类型，马上查一次。 */
    fun onLocationPermission() {
        if (inForeground) runCatching { startInForeground() }
        if (sightsOn) scheduleSights(1_000L)
        notifyUi()
    }

    private val sightsTimer = Runnable { sightsCheck(manual = false); scheduleSights() }
    private fun scheduleSights(firstDelay: Long = -1L) {
        main.removeCallbacks(sightsTimer)
        if (!sightsOn) return
        main.postDelayed(sightsTimer, if (firstDelay >= 0) firstDelay else sightsEveryMin * 60_000L)
    }

    /**
     * 查一次附近的景点。自动查时：眼镜没连上就跳过；离上次查的地方不到 500 米也跳过（原地不动时不会一直推）。
     * 手动查（页面上的按钮）不管这些。
     */
    fun sightsCheck(manual: Boolean) {
        if (sightsBusy) return
        val hm = java.text.SimpleDateFormat("HH:mm", Locale.US)
        fun status(t: String) { sightsStatus = hm.format(java.util.Date()) + "  " + t; notifyUi() }
        if (!manual && linkState != LinkState.READY) { status("眼镜没连上，这次不查"); return }
        if (!hasFineLocation()) {
            status(if (hasLocation()) "只给了大致位置，景点介绍需要精确位置" else "没有定位权限")
            return
        }
        sightsBusy = true
        status("正在定位…")
        preciseLocation { loc ->
            if (loc == null) { sightsBusy = false; status("定位失败（手机定位打开了吗？）"); return@preciseLocation }
            val lat = loc.latitude; val lon = loc.longitude
            val lastLat = sightPrefs.getFloat("lat", Float.NaN).toDouble(); val lastLon = sightPrefs.getFloat("lon", Float.NaN).toDouble()
            if (!manual && !lastLat.isNaN() && Sights.distance(lat, lon, lastLat, lastLon) < Sights.MOVE_M) {
                sightsBusy = false; status("离上次查的地方不到 ${Sights.MOVE_M} 米，走动了再介绍新的"); return@preciseLocation
            }
            if (autoLocate) useLocation(loc)    // 顺便更新看板的城市、天气
            status("正在找附近 3 公里的景点…（定位精度约 ${loc.accuracy.toInt()} 米）")
            io.execute {
                val src = sightsSource; val intro = sightsIntro; val ak = amapKey; val gk = googleMapsKey
                val r = runCatching {
                    val all = SightsNet.nearby(lat, lon, src, ak, gk)
                    val picks = Sights.pick(all, seenSights().keys)
                    main.post { saveNearSights(all, lat, lon) }
                    all.size to picks.map { sg -> sg to SightsNet.describe(sg, intro) }
                }
                main.post {
                    sightsBusy = false
                    r.onFailure { status("查询失败：" + sightsError(it)); log("景点介绍：查询失败 $it") }
                    r.onSuccess { (total, list) ->
                        sightPrefs.edit().putFloat("lat", lat.toFloat()).putFloat("lon", lon.toFloat()).apply()
                        markSightsSeen(list.map { Sights.key(it.first) })
                        status((if (list.isEmpty()) "附近 3 公里有 $total 个景点，都介绍过了" else "附近 3 公里有 $total 个景点，这次介绍 ${list.size} 个"))
                        val now = System.currentTimeMillis()
                        list.forEach { (sg, d) -> sightQueue.addLast(SightNote(now, d.first, Sights.distanceText(sg.distance), d.second, d.third)) }
                        if (!sightPlaying) playNextSight()
                    }
                }
            }
        }
    }

    /** 查询失败的原因，说人话（完整的异常写进日志）。 */
    private fun sightsError(e: Throwable): String = when {
        e.message?.startsWith("还没填") == true -> e.message + "（在下面「数据来源」里填）"
        e is java.net.UnknownHostException -> "连不上网络"
        e is java.net.SocketTimeoutException -> "网络超时，稍后再试"
        e.message?.startsWith("HTTP 429") == true -> "查询服务太忙（HTTP 429），稍后再试"
        e.message?.startsWith("HTTP ") == true -> "查询服务出错（${e.message}），稍后再试"
        e is java.io.IOException -> "网络出错：" + (e.message ?: e.javaClass.simpleName).take(60)
        else -> (e.javaClass.simpleName + " " + (e.message ?: "")).trim().take(60)
    }

    // ---- 看板卡片「附近景点」 ----

    /** 最近一次查到的附近景点（3 公里内），和查的位置、时间。 */
    private var nearSights: List<Sight> = emptyList()
    private var nearLoaded = false
    private var nearAt = 0L
    private var nearLat = Double.NaN; private var nearLon = Double.NaN
    /** 现在的位置（卡片按它重新算距离）和定位时间。 */
    private var curLat = Double.NaN; private var curLon = Double.NaN
    private var curAt = 0L
    private var nearBusy = false
    var nearStatus = ""; private set

    private fun loadNearSights() {
        if (nearLoaded) return
        nearLoaded = true
        nearSights = Sights.fromJson(sightPrefs.getString("near", null))
        nearAt = sightPrefs.getLong("nearAt", 0)
        nearLat = sightPrefs.getFloat("nearLat", Float.NaN).toDouble(); nearLon = sightPrefs.getFloat("nearLon", Float.NaN).toDouble()
        curLat = nearLat; curLon = nearLon
    }

    private fun saveNearSights(all: List<Sight>, lat: Double, lon: Double) {
        loadNearSights()
        nearSights = all; nearAt = System.currentTimeMillis(); nearLat = lat; nearLon = lon
        curLat = lat; curLon = lon; curAt = nearAt
        sightPrefs.edit().putString("near", Sights.toJson(all)).putLong("nearAt", nearAt)
            .putFloat("nearLat", lat.toFloat()).putFloat("nearLon", lon.toFloat()).apply()
        if (appMode == AppMode.DASHBOARD) pushDashboard(force = false)
        notifyUi()
    }

    private fun sightsCardOn() = cards.items.any { it.enabled && it.type == CardType.SIGHTS }

    fun sightsCardRows(): List<CardRow> {
        loadNearSights()
        if (nearSights.isEmpty()) return listOf(CardRow(nearStatus.ifEmpty { if (nearAt == 0L) "正在找附近的景点…" else "附近 3 公里没有景点" }))
        return Sights.cardRows(nearSights, curLat, curLon)
    }

    /**
     * 卡片开着时（看板每分钟刷新一次时调用）：每 3 分钟定一次位，按现在的位置重新排远近；
     * 走出 500 米、或者 6 小时没查过，再联网查一次附近的景点（只查名单，不查介绍）。
     */
    private fun sightsCardRefresh(force: Boolean) {
        if (!sightsCardOn() || nearBusy) return
        loadNearSights()
        if (!force && System.currentTimeMillis() - curAt < 3 * 60_000L) return
        if (!hasFineLocation()) { nearStatus = if (hasLocation()) "要给萤读精确位置权限" else "没有定位权限"; return }
        nearBusy = true
        preciseLocation { loc ->
            if (loc == null) { nearBusy = false; nearStatus = "定位失败（手机定位打开了吗？）"; return@preciseLocation }
            val lat = loc.latitude; val lon = loc.longitude
            curLat = lat; curLon = lon; curAt = System.currentTimeMillis()
            val stale = nearLat.isNaN() || Sights.distance(lat, lon, nearLat, nearLon) >= Sights.MOVE_M ||
                System.currentTimeMillis() - nearAt > 6 * 3600_000L
            if (!stale) {
                nearBusy = false
                if (appMode == AppMode.DASHBOARD) pushDashboard(force = false)
                return@preciseLocation
            }
            val src = sightsSource; val ak = amapKey; val gk = googleMapsKey
            io.execute {
                val r = runCatching { SightsNet.nearby(lat, lon, src, ak, gk) }
                main.post {
                    nearBusy = false
                    r.onSuccess { nearStatus = ""; saveNearSights(it, lat, lon) }
                        .onFailure { nearStatus = "查询失败：" + sightsError(it); log("附近景点卡片：$nearStatus"); notifyUi() }
                }
            }
        }
    }

    private val sightQueue = ArrayDeque<SightNote>()
    private var sightPlaying = false
    private val sightNext = Runnable { playNextSight() }

    /** 一个接一个地弹：每个亮 15 秒，下一个接着换上。 */
    private fun playNextSight() {
        val n = sightQueue.removeFirstOrNull() ?: run { sightPlaying = false; return }
        sightPlaying = true
        addSightHistory(n)
        // 来源写在第一行（维基百科的内容要注明出处）
        if (linkState == LinkState.READY) onPhoneNotification("景点" + if (n.src.isNotEmpty()) " · ${n.src}" else "", "${n.name} · ${n.dist}", n.text,
            litMs = Sights.GAP_MS, fit = true)
        else log("景点：${n.name}（眼镜没连上，只记在手机上）")
        notifyUi()
        main.postDelayed(sightNext, Sights.GAP_MS)
    }

    /** 精确定位：2 分钟内的高精度定位直接用，否则用融合定位（高精度）/ GPS 现定位，最多等 35 秒。 */
    @SuppressLint("MissingPermission")
    private fun preciseLocation(cb: (android.location.Location?) -> Unit) {
        val lm = getSystemService(android.location.LocationManager::class.java) ?: return cb(null)
        val enabled = lm.getProviders(true)
        val known = enabled.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        val now = System.currentTimeMillis()
        known.filter { now - it.time < 120_000 && it.hasAccuracy() && it.accuracy <= 100f }.minByOrNull { it.accuracy }?.let { return cb(it) }
        val fallback = known.filter { now - it.time < 30 * 60_000L }.minByOrNull { if (it.hasAccuracy()) it.accuracy else 9999f }
        var done = false
        val finish = { l: android.location.Location? -> if (!done) { done = true; cb(l ?: fallback) } }
        val gps = android.location.LocationManager.GPS_PROVIDER; val net = android.location.LocationManager.NETWORK_PROVIDER
        try {
            if (Build.VERSION.SDK_INT >= 31 && "fused" in enabled) {
                val req = android.location.LocationRequest.Builder(0L)
                    .setQuality(android.location.LocationRequest.QUALITY_HIGH_ACCURACY).setDurationMillis(30_000L).build()
                lm.getCurrentLocation("fused", req, null, mainExecutor) { finish(it) }
            } else {
                val p = if (gps in enabled) gps else if (net in enabled) net else null
                if (p == null) { finish(null); return }
                if (Build.VERSION.SDK_INT >= 30) lm.getCurrentLocation(p, null, mainExecutor) { finish(it) }
                else {
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(p, object : android.location.LocationListener {
                        override fun onLocationChanged(l: android.location.Location) { finish(l) }
                        @Deprecated("旧接口") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                        override fun onProviderEnabled(p: String) {}
                        override fun onProviderDisabled(p: String) {}
                    }, Looper.getMainLooper())
                }
            }
        } catch (e: Exception) { log("定位出错：${e.message}"); finish(null); return }
        main.postDelayed({ finish(null) }, 35_000)
    }

    // ---------- 自动翻页 ----------

    private val autoFlipRunnable = Runnable {
        if (!autoFlip) return@Runnable
        val rows = paginator?.rows ?: return@Runnable
        moveBy(if (scrollLines in 1 until rows) scrollLines else rows)
    }

    fun toggleAutoFlip() {
        autoFlip = !autoFlip
        val step = if (scrollLines in 1 until (paginator?.rows ?: 0)) " $scrollLines 行" else "一整页"
        log(if (autoFlip) "自动滚动：开（每 $autoFlipSeconds 秒前进$step）" else "自动滚动：关")
        restartAutoFlipTimer()
        updateNotification()
        notifyUi()
    }

    private fun stopAutoFlip() {
        autoFlip = false
        main.removeCallbacks(autoFlipRunnable)
    }

    private fun restartAutoFlipTimer() {
        main.removeCallbacks(autoFlipRunnable)
        if (autoFlip) main.postDelayed(autoFlipRunnable, autoFlipSeconds * 1000L)
    }

    // ---------- 校准 ----------

    /** 一长串全角数字，看眼镜在第几个字处自动换行，就能知道每行放得下几个字。 */
    fun sendWidthTest() {
        val digits = "１２３４５６７８９０"
        client.showText(digits.repeat(5), "宽度测试")
        log("已发送宽度测试：数一下第一行有几个字，填到每行字数（建议再减 1）")
    }

    /** 逐行编号，看眼镜上最多能看到第几行。 */
    fun sendHeightTest() {
        client.showText((1..12).joinToString("\n") { "第${it}行" }, "行数测试")
        log("已发送行数测试：看最多显示到第几行，填到每页行数")
    }

    fun sendCurrentPageAgain() = pushPage()

    // ---------- 眼镜回调 ----------

    /** 手动点"连接"后第一次连上：默认显示看板（断线重连不改变当前画面）。 */
    private var showDashboardOnReady = false

    override fun onLinkState(state: LinkState) {
        linkState = state
        if (state == LinkState.DISCONNECTED) { memEngine.onGlassesLost(); headUpSuspended = false; notifyLit = false }
        if (state == LinkState.READY && memEnabled && !memEngine.running) main.postDelayed({ memStartIfEnabled() }, 3_000)
        if (state == LinkState.READY) {
            // 刚连上时眼镜还在初始化，立刻打开页面容易被忽略：
            // 等 1.2 秒再推送，4 秒后再强制补推一次，保证画面一定出来，不用手动刷新
            val toDash = showDashboardOnReady
            showDashboardOnReady = false
            main.postDelayed({
                if (linkState != LinkState.READY) return@postDelayed
                restoreRightCardOnce()
                if (dashAtHome && (appMode == AppMode.DASHBOARD || toDash)) { if (!glassesPaused) pauseGlasses(); return@postDelayed }
                if (toDash && appMode != AppMode.DASHBOARD) switchAppMode(AppMode.DASHBOARD) else { lastPushedDash = ""; pushPage() }
            }, 1200)
            main.postDelayed({ if (linkState == LinkState.READY && !notifyLit) restoreHeadUp() }, 2500)
            main.postDelayed({ if (linkState == LinkState.READY) replayOffline() }, 4500)
            main.postDelayed(standbyRefresh, 5000)
            main.postDelayed({
                if (linkState != LinkState.READY || glassesPaused) return@postDelayed
                lastPushedDash = ""
                if (appMode == AppMode.DASHBOARD) pushDashboard(force = true, restartTimers = false) else pushPage()
            }, 4000)
        }
        updateNotification()
        notifyUi()
    }

    /**
     * 两条镜腿重新连上（NimoClient 已经安排好下一份内容先重开页面）：把两边的状态对齐。
     *  - 萤读关着屏：再发一次关屏（刚连上的那一边可能是亮的），待机的第一屏作废，下次抬头走完整的"重开页面 + 发图 + 开屏"；
     *  - 画面显示着：马上补发当前画面（重开页面，两边都拿到）。
     */
    override fun onTwsReconnected() {
        log("两条镜腿重新连上：同步画面到两边")
        if (screenOffByUs) {
            client.screenOff(true)
            standbyKey = null; lastPushedDash = ""
            return
        }
        if (glassesPaused) return
        lastPushedDash = ""
        if (appMode == AppMode.DASHBOARD) pushDashboard(force = true, restartTimers = false) else pushPage()
    }

    override fun onInput(input: GlassesInput) {
        // 排查"抬头不显示看板"：收起状态下抬头时，把当时的状态记下来
        if (input == GlassesInput.HEAD_UP && glassesPaused)
            log("抬头时：" + (activeApp?.let { "全屏功能「${it.title}」开着，" } ?: "") + "模式 ${appMode.name}，" +
                (if (headHidden) "看板是自动收起的" else "看板是手动收起的") + "，" + (if (screenOffByUs) "萤读关了屏" else "屏幕不是萤读关的") +
                "，收起方式 ${if (dashHideMode == HIDE_HOME) "回主界面" else "关屏"}，抬头显示看板 ${if (dashHeadWake) "开" else "关"}，" +
                "使用看板 ${if (dashEnabled) "开" else "关"}，眼镜页面 ${client.enteredAppId() ?: "主界面"}")
        if (input == GlassesInput.EXITED) {
            // 眼镜退出了页面（长按右镜腿）：停止自动滚动，暂停显示
            stopAutoFlip()
            if (appMode == AppMode.DASHBOARD) cancelDashTimers()
            glassesPaused = true
            // 退到了官方主界面，屏幕是我们关的就要打开
            if (screenOffByUs) { screenOffByUs = false; client.screenOff(false) }
            // 看板被眼镜退出了：还能从眼镜上叫回来（点一下镜腿点亮主界面；关屏方式下也可以抬头）
            headHidden = appMode == AppMode.DASHBOARD
            log(if (headHidden) "眼镜上已退出看板，" + (if (headWakeOn) "抬头、" else "") + "点一下镜腿或在手机上点「显示看板」再看"
                else "眼镜上已退出显示，在手机上翻页、切换或点通知栏按钮即可恢复")
            updateNotification()
            notifyUi()
            return
        }
        activeApp?.let { it.onInput(input); return }
        if (appMode == AppMode.DASHBOARD) when (input) {
            GlassesInput.HOME_WAKE -> {
                headIsDown = false
                if (dashAtHome) { client.lightHome(); return }
                // 官方主界面被点亮了（点了一下镜腿）：看板是自动收起/被眼镜退出的，就叫回来
                // 弹通知时是我们自己在主界面上开的屏（眼镜自带弹窗方式），不算用户点亮
                if (notifyLit) { log("弹通知时主界面亮了，不叫回看板"); return }
                // 萤读刚把页面退回主界面：主界面亮起是退出引起的，不是用户点的，不叫回（v5.8：低头收起后看板又被打开，屏幕黑一下再亮）
                if (android.os.SystemClock.uptimeMillis() - quitHomeAt < QUIT_HOME_IGNORE_MS) { log("刚退回主界面，主界面亮起不算点亮，不叫回看板"); return }
                // 眼镜刚亮屏、正在处理自己的主界面时发"打开页面"会被忽略（v5.7.4 日志：只亮了官方主界面）：
                // 马上发，再隔一小会儿重发几次
                wakeFromHome("点亮主界面：显示看板")
                return
            }
            GlassesInput.HEAD_UP -> {
                headIsDown = false
                main.removeCallbacks(headDownHide)
                if (!dashHeadWake) return
                // 页面还开着、只是萤读关了屏（关屏方式收起的；收起后才改成「回主界面」也一样）：抬头就开屏
                val pageOpen = screenOffByUs && client.enteredAppId() != null
                // 通知正亮着：抬头是为了看它，不要拿看板把它顶掉（v5.16.3：战绩弹出来一抬头 1 秒就没了）
                if (glassesPaused && showingPopup) { log("抬头：正在显示通知，等它显示完"); return }
                if (glassesPaused && !headHidden) log("抬头：看板是在手机上收起的，不自动显示（点「显示看板」）")
                else if (glassesPaused && dashHideMode == HIDE_HOME && !pageOpen) log("抬头：收起方式是「回主界面」，眼镜点亮主界面时才叫回看板")
                if (!glassesPaused) { cancelDashTimers(); armDashTimers(); scheduleDashLive() }   // 还在看，重新计时
                else if (headHidden && (headWakeOn || pageOpen) && !dashAtHome) { log("抬头：显示看板"); wakeDashboard() }
                return
            }
            GlassesInput.HEAD_DOWN -> {
                headIsDown = true
                // 看板页面开着时眼镜会报低头：关屏方式下关屏，回主界面方式下退回主界面（主界面上眼镜自己低头灭屏）
                if (dashHeadWake && !glassesPaused) {
                    main.removeCallbacks(headDownHide)
                    main.postDelayed(headDownHide, maxOf(0L, shownAt + 2_000 - android.os.SystemClock.uptimeMillis()))
                }
                return
            }
            else -> if (glassesPaused) {
                // 看板收起、回到了官方主界面：主界面上不报抬头，眼镜亮屏时的拉取也不常来，
                // 但左击会报（官方 app 里左击设成「切换看板应用」时）——点一下就叫回看板
                val click = input == GlassesInput.CLICK_LEFT || input == GlassesInput.CLICK_RIGHT ||
                    input == GlassesInput.DOUBLE_LEFT || input == GlassesInput.DOUBLE_RIGHT
                if (click) wakeFromHome("主界面上点了镜腿：显示看板")
                return
            }
        }
        if (appMode == AppMode.DASHBOARD) {
            when (input) {
                GlassesInput.CLICK_RIGHT, GlassesInput.DOUBLE_RIGHT -> dashFlip(1)
                GlassesInput.CLICK_LEFT, GlassesInput.DOUBLE_LEFT -> dashFlip(-1)
                GlassesInput.LONG_PRESS -> { log("手动刷新"); refreshDashboard(forceWeather = true) }
                else -> {}
            }
            return
        }
        when (input) {
            GlassesInput.CLICK_RIGHT -> nextPage()
            GlassesInput.CLICK_LEFT -> prevPage()
            GlassesInput.DOUBLE_RIGHT -> nextChapter()
            GlassesInput.DOUBLE_LEFT -> prevChapter()
            GlassesInput.LONG_PRESS -> toggleAutoFlip()
            GlassesInput.HEAD_UP, GlassesInput.HEAD_DOWN, GlassesInput.EXITED, GlassesInput.HOME_WAKE -> {}
        }
    }

    // ---------- 眼镜开关设置 ----------

    /** 眼镜当前状态（null = 还没读到）。 */
    var headUpDisplay: Boolean? = null; private set
    var displayOff: Boolean? = null; private set
    var autoBrightness: Boolean? = null; private set

    override fun onGlassesSetting(key: Int, on: Boolean) {
        when (key) {
            NimoProtocol.SET_HEADUP_DISPLAY -> headUpDisplay = on
            NimoProtocol.SET_DISPLAY_OFF -> displayOff = on
            NimoProtocol.SET_AUTO_BRIGHTNESS -> { autoBrightness = on; if (!on) { main.removeCallbacks(brightnessStep); brightnessAnim = null } }
        }
        notifyUi()
    }

    fun toggleHeadUp() { client.setGlassesSetting(NimoProtocol.SET_HEADUP_DISPLAY, headUpDisplay != true); log(if (headUpDisplay != true) "抬头显示：开" else "抬头显示：关") }
    fun toggleDisplayOff() { client.setGlassesSetting(NimoProtocol.SET_DISPLAY_OFF, displayOff != true); log(if (displayOff != true) "息屏模式：开" else "息屏模式：关") }
    fun toggleAutoBrightness() { client.setGlassesSetting(NimoProtocol.SET_AUTO_BRIGHTNESS, autoBrightness != true) }

    override fun onBattery(level: Int, charging: Boolean) {
        battery = level
        notifyUi()
    }

    override fun onFirmware(version: String) {
        firmware = version
        log("眼镜固件 $version")
    }

    override fun onLog(msg: String) = log(msg)

    /** 全天记忆、实时字幕各自要不要眼镜麦克风：有一个要就开着，都不要了才关。 */
    private var memWantsMic = false
    private var captionsWantMic = false
    private fun glassesMic(on: Boolean) {
        if (on) client.micStart()
        else if (!memWantsMic && !captionsWantMic) client.micStop()
    }

    override fun onMicFrames(frames: List<NimoMic.Frame>) {
        if (memEngine.running) { memEngine.onGlassesFrames(frames); memUi() }
        if (captions.running) captions.onFrames(frames)
    }

    val micAvailable get() = ::client.isInitialized && client.micAvailable

    // ---------- 全天记忆 ----------

    private val memPrefs by lazy { getSharedPreferences("memory", MODE_PRIVATE) }
    val memStore by lazy { MemoryStore(java.io.File(filesDir, "memory")) }
    private val memIo = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** 全天记忆开着（连上眼镜后自动开始）。 */
    var memEnabled: Boolean
        get() = memPrefs.getBoolean("enabled", false)
        private set(v) = memPrefs.edit().putBoolean("enabled", v).apply()
    var memMode: ListenMode
        get() = runCatching { ListenMode.valueOf(memPrefs.getString("mode", "DUTY")!!) }.getOrDefault(ListenMode.DUTY)
        set(v) { memPrefs.edit().putString("mode", v.name).apply(); if (memEngine.running) { memEngine.stop(); memStartIfEnabled() }; notifyUi() }
    var memDutySec: Int
        get() = memPrefs.getInt("dutySec", 10)
        set(v) { memPrefs.edit().putInt("dutySec", v).apply(); memEngine.dutyIntervalMs = v * 1000L; notifyUi() }

    /** 前后两句隔多少秒以内算同一段对话（显示时合在一起）；0 = 不合并，默认 60 秒。 */
    var memMergeSec: Int
        get() = memPrefs.getInt("mergeSec", 60)
        set(v) { memPrefs.edit().putInt("mergeSec", v).apply(); notifyUi() }

    /** 转文字前先降噪（维纳滤波）。 */
    var memDenoise: Boolean
        get() = memPrefs.getString("denoiseMode", null)?.let { it != "off" } ?: memPrefs.getBoolean("denoise", true)
        set(v) { memPrefs.edit().putString("denoiseMode", if (v) "wiener" else "off").apply(); notifyUi() }

    /**
     * 清理录音（后台）：录音只是等着转文字的临时文件，转好就删；万一转好了没删掉的，这里补删；
     * 一直没转成的（比如没填转文字的 Key）超过 [PENDING_KEEP_MS] 也删掉。
     */
    fun memPurge() {
        memIo.execute {
            val n = memStore.purgeAudio(System.currentTimeMillis(), PENDING_KEEP_MS)
            if (n > 0) main.post { log("全天记忆：删掉 $n 段录音"); countPending() }
        }
    }

    /** 每天几点自动总结（分钟数，默认 22:00）。 */
    var memSummaryMinute: Int
        get() = memPrefs.getInt("sumMinute", 22 * 60)
        set(v) { memPrefs.edit().putInt("sumMinute", v).apply(); scheduleSummary(); notifyUi() }

    fun memGet(k: String, def: String = ""): String = memPrefs.getString(k, def) ?: def
    fun memSet(k: String, v: String) { memPrefs.edit().putString(k, v.trim()).apply(); if (k.startsWith("asr")) memKickTranscribe(); notifyUi() }

    val asrPreset get() = SpeechToText.PRESETS.firstOrNull { it.id == memGet("asrPreset", "siliconflow") } ?: SpeechToText.PRESETS[0]
    val sumPreset get() = DaySummarizer.PRESETS.firstOrNull { it.id == memGet("sumPreset", "claude") } ?: DaySummarizer.PRESETS[0]

    /**
     * 换服务（kind = "asr" 转文字 / "sum" 总结）：每个服务的 API Key 分开存，换过去时自动换成那个服务上次填的，
     * 不会把一家的 Key 发给另一家；接口地址、模型回到新服务的默认值。
     */
    fun memSwitchPreset(kind: String, oldId: String, newId: String) {
        if (oldId == newId) return
        val e = memPrefs.edit()
        e.putString("${kind}Key@$oldId", memGet("${kind}Key"))
        e.putString("${kind}Key", memGet("${kind}Key@$newId"))
        e.putString("${kind}Preset", newId).putString("${kind}Base", "").putString("${kind}Model", "")
        e.apply()
        if (kind == "asr") memKickTranscribe()
        notifyUi()
    }
    fun asrBase() = memGet("asrBase").ifEmpty { asrPreset.base }
    fun asrModel() = memGet("asrModel").ifEmpty { asrPreset.model }
    fun sumBase() = memGet("sumBase").ifEmpty { sumPreset.base }
    fun sumModel() = memGet("sumModel").ifEmpty { sumPreset.model }

    /** 界面状态：转文字中、总结中、最近的错误。 */
    var memTranscribing = false; private set
    var memSummarizing = false; private set
    var memLastError = ""; private set
    /** 等着转文字的段数（后台数好再更新，界面不用每次读文件）。 */
    @Volatile var memPending = 0; private set
    private fun countPending() { memIo.execute { memPending = memStore.pending().size; main.post { notifyUi() } } }
    private var memUiAt = 0L
    private fun memUi() { val now = System.currentTimeMillis(); if (now - memUiAt > 500) { memUiAt = now; notifyUi() } }

    val memEngine by lazy { MemoryEngine(object : MemoryEngine.Host {
        override fun post(delayMs: Long, r: Runnable) { main.postDelayed(r, delayMs) }
        override fun cancel(r: Runnable) { main.removeCallbacks(r) }
        override fun uptime() = android.os.SystemClock.uptimeMillis()
        override fun wallTime() = System.currentTimeMillis()
        override fun glassesReady() = linkState == LinkState.READY && client.micAvailable
        override fun setGlassesMic(on: Boolean) { memWantsMic = on; glassesMic(on) }
        override fun log(msg: String) = this@ReaderService.log(msg)
        override fun segmentSaved(seg: MemorySegment) { memPending++; memUi(); memKickTranscribe() }
        override fun segmentDir(dayStart: Long) = memStore.dirOf(memStore.dayOf(dayStart))
    }).also { it.dutyIntervalMs = memDutySec * 1000L } }

    fun phoneBattery(): Int = runCatching {
        (getSystemService(BATTERY_SERVICE) as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(-1)

    /** 打开 / 关闭全天记忆。 */
    fun memSetEnabled(on: Boolean) {
        memEnabled = on
        if (on) memStartIfEnabled() else memEngine.stop()
        notifyUi()
    }

    fun memStartIfEnabled() {
        if (!memEnabled || memEngine.running) return
        memEngine.dutyIntervalMs = memDutySec * 1000L
        memEngine.start(memMode, battery, phoneBattery())
        scheduleSummary()
        memKickTranscribe()
        notifyUi()
    }

    // ---- 转文字（后台一段一段来，失败了过一会儿再试） ----
    private val memRetry = Runnable { memKickTranscribe() }

    fun memKickTranscribe() {
        if (memTranscribing) return
        val key = memGet("asrKey")
        if (key.isEmpty()) { countPending(); return }
        memTranscribing = true
        val base = asrBase(); val model = asrModel(); val lang = memGet("asrLang", "zh")
        val denoise = memDenoise
        memIo.execute {
            var err: String? = null
            try {
                for (f in memStore.pending()) {
                    val (start, dur) = memStore.segmentInfo(f)
                    memStore.addEntry(f.parentFile!!.name, memTranscribeSegment(f, start, dur, base, key, model, lang, denoise))
                    f.delete()   // 转好文字就删录音，手机上只留文字
                    memPending = maxOf(0, memPending - 1)
                    main.post { memUi() }
                }
            } catch (e: Exception) { err = e.message ?: e.toString() }
            main.post {
                memTranscribing = false
                if (err != null) {
                    if (err != memLastError) log("转文字失败：$err（5 分钟后再试）")
                    memLastError = "转文字：$err"
                    main.removeCallbacks(memRetry); main.postDelayed(memRetry, 5 * 60_000L)
                } else if (memLastError.startsWith("转文字")) memLastError = ""
                notifyUi()
            }
        }
    }

    /** 说话人：按音量、音高、低频判断是不是自己（记住用户纠正过的例子）。 */
    val speakerModel by lazy { SpeakerModel(java.io.File(filesDir, "memory/speaker.json")) }
    /** 分辨自己和别人（开着时一段录音按说话人切开、每轮单独转文字）。 */
    var memSpeakers: Boolean
        get() = memPrefs.getBoolean("speakers", true)
        set(v) { memPrefs.edit().putBoolean("speakers", v).apply(); notifyUi() }
    /** 界面、总结用的"是不是我"；关了分辨就一律不标。 */
    fun memIsMe(v: Speakers.Voice?): Boolean? = if (memSpeakers) speakerModel.isMe(v) else null

    /** 同时转好几截录音（每轮话）。 */
    private val asrPool = java.util.concurrent.Executors.newFixedThreadPool(4)

    /**
     * 转一段录音（后台线程）：先用原始声音按说话人切成几轮（降噪、放大会改变音量，不能先做），每轮单独转文字。
     */
    private fun memTranscribeSegment(f: java.io.File, start: Long, dur: Long, base: String, key: String, model: String,
                                     lang: String, denoise: Boolean): MemoryEntry {
        val raw = OggOpus.toPcm(f.readBytes())
        val turns = (if (memSpeakers) Speakers.turns(raw) else emptyList())
        val spans = if (turns.size <= 1) listOf(0 to raw.size) else turns.map { it.from to it.to.coerceAtMost(raw.size) }
        // 降噪整段做（底噪估得准），放大每截单独做
        val clean = if (denoise) AudioClean.process(raw, AudioClean.Mode.WIENER, floor = 0.2f, boost = false) else raw
        val language = if (lang == "auto") "" else lang
        val jobs = spans.map { (from, to) ->
            asrPool.submit(java.util.concurrent.Callable {
                SpeechToText.transcribe(base, key, model, language, f.nameWithoutExtension + ".wav", "audio/wav",
                    OggOpus.wav(AudioClean.process(clean.copyOfRange(from, to), AudioClean.Mode.NONE, floor = 0.2f, boost = true)))
            })
        }
        val texts = jobs.map { it.get() }
        if (turns.size <= 1) {
            val text = texts[0]
            val v = turns.firstOrNull()?.voice
            v?.let { speakerModel.observe(it) }
            return MemoryEntry(start, dur, f.name, text, if (v != null && text.isNotBlank()) listOf(MemoryTurn(0, text, v)) else emptyList())
        }
        val out = turns.mapIndexed { i, t ->
            speakerModel.observe(t.voice)
            MemoryTurn(t.from * 1000L / NimoMic.SAMPLE_RATE, texts[i], t.voice)
        }
        val text = out.fold("") { acc, t -> MemoryGroup.join(acc, t.text.trim()) }
        return MemoryEntry(start, dur, f.name, text, out)
    }

    // ---- 每天总结 ----
    private val summaryTimer = Runnable { memSummarize(memStore.dayOf(System.currentTimeMillis()), auto = true); memPurge(); scheduleSummary() }

    private fun scheduleSummary() {
        main.removeCallbacks(summaryTimer)
        if (!memEnabled) return
        val cal = java.util.Calendar.getInstance()
        val now = cal.timeInMillis
        cal.set(java.util.Calendar.HOUR_OF_DAY, memSummaryMinute / 60); cal.set(java.util.Calendar.MINUTE, memSummaryMinute % 60)
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        main.postDelayed(summaryTimer, cal.timeInMillis - now)
    }

    fun memSummarize(day: String, auto: Boolean = false) {
        if (memSummarizing) return
        val key = memGet("sumKey")
        if (key.isEmpty()) { if (!auto) log("先在全天记忆设置里填总结用的 API Key"); return }
        val entries = memStore.entries(day).filter { it.text.isNotBlank() }
        if (entries.isEmpty()) { if (!auto) log("$day 还没有转好的文字"); return }
        memSummarizing = true; notifyUi()
        val p = sumPreset; val base = sumBase(); val model = sumModel()
        memIo.execute {
            val r = runCatching { DaySummarizer.summarize(p.id, base, key, model, day, entries, ::memIsMe) }
            main.post {
                memSummarizing = false
                r.onSuccess { memStore.saveSummary(it); log("全天记忆：$day 的总结好了"); if (memLastError.startsWith("总结")) memLastError = ""; lastPushedDash = "" }
                    .onFailure { memLastError = "总结：${it.message}"; log("总结失败：${it.message}") }
                if (appMode == AppMode.DASHBOARD) pushDashboard(force = false)
                notifyUi()
            }
        }
    }

    /** 记忆检索：在所有天的文字和回顾里搜（后台线程调用，读文件）。 */
    fun memSearch(query: String): MemorySearch.Result =
        MemorySearch.search(memStore.days(), memStore::entries, memStore::summary, query, memMergeSec * 1000L)

    /**
     * 问问记忆：用总结的服务和 Key，把问题和相关材料交给大模型。在自己的线程里跑（不排在转文字后面），结果回主线程。
     */
    fun memAsk(question: String, done: (Result<String>) -> Unit) {
        val key = memGet("sumKey")
        if (key.isEmpty()) { done(Result.failure(java.io.IOException("先在全天记忆设置里填总结用的 API Key"))); return }
        val p = sumPreset; val base = sumBase(); val model = sumModel()
        val today = memStore.dayOf(System.currentTimeMillis())
        Thread {
            val r = runCatching {
                val ctx = MemorySearch.askContext(memStore.days(), memStore::entries, memStore::summary, question, memMergeSec * 1000L)
                MemoryAsk.ask(p.id, base, key, model, today, question, ctx)
            }
            main.post { done(r) }
        }.apply { name = "mem-ask" }.start()
    }

    /** 看板卡片用：今天的总结，没有就用昨天的（文件没变就不重读）。 */
    private var memCardCache: Pair<String, MemorySummary?>? = null
    private fun memorySummaryForCard(): MemorySummary? {
        val now = System.currentTimeMillis()
        val days = listOf(memStore.dayOf(now), memStore.dayOf(now - 24 * 3600_000L))
        val sig = days.joinToString { java.io.File(memStore.dirOf(it), "summary.json").lastModified().toString() } + days[0]
        memCardCache?.let { if (it.first == sig) return it.second }
        val s = days.firstNotNullOfOrNull { memStore.summary(it) }
        memCardCache = sig to s
        return s
    }

    /** 把总结里的待办加到萤读的待办。 */
    fun memAddTodos(s: MemorySummary): Int {
        val existing = todos.items.map { it.text }.toSet()
        val add = s.todos.map { if (it.second.isBlank()) it.first else "${it.first}（${it.second}）" }.filter { it !in existing }
        add.forEach { todos.add(it) }
        todosChanged()
        return add.size
    }

    /** 最上面一行是不是"眼镜麦克风开关"的汇总行（连续的开关合成一行，不刷屏）。 */
    private var micLogRun = false
    private var micLogOpens = 0
    private var micLogSince = ""

    override fun log(msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val mic = msg == "眼镜麦克风：开" || msg == "眼镜麦克风：关"
        if (mic) {
            val on = msg.endsWith("开")
            if (micLogRun && logLines.isNotEmpty()) {
                // 连续的开关：只更新最上面那一行
                if (on) micLogOpens++
                logLines.removeFirst()
                logLines.addFirst("$t  眼镜麦克风：开关 $micLogOpens 次（$micLogSince 起），现在" + if (on) "开着" else "关着")
                notifyUi(); return
            }
            micLogRun = true; micLogOpens = if (on) 1 else 0; micLogSince = t
        } else micLogRun = false
        logLines.addFirst("$t  $msg")
        while (logLines.size > MAX_LOG) logLines.removeLast()
        notifyUi()
    }

    private var lastNotifText = ""
    private fun notifyUi() {
        updateVolumeSession()
        uiListener?.onReaderChanged()
        // 通知里显示页码；只在内容变化时更新，避免频繁刷新
        val t = "$pageNumber/$autoFlip/$glassesPaused/$appMode/$linkState/${activeApp?.status()}"
        if (t != lastNotifText) { lastNotifText = t; updateNotification() }
    }

    // ---------- 前台通知 ----------

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "阅读器连接", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val text = when {
            linkState == LinkState.DISCONNECTED -> "眼镜未连接"
            linkState != LinkState.READY -> "正在连接眼镜…"
            activeApp != null && glassesPaused -> (activeApp?.title ?: "") + "（眼镜上已收起）"
            activeApp != null -> activeApp?.status() ?: ""
            glassesPaused && appMode == AppMode.DASHBOARD -> "看板已收起"
            glassesPaused -> "眼镜上已收起显示"
            appMode == AppMode.DASHBOARD -> "看板显示中"
            autoFlip -> "自动滚动中 · 第 $pageNumber / $pageCount 屏"
            else -> "阅读中 · 第 $pageNumber / $pageCount 屏"
        }
        val b = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("萤读")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(open)
            .setOngoing(true)
        return b.build()
    }

    private var inForeground = false

    private fun startInForeground() {
        inForeground = true
        val n = buildNotification()
        if (Build.VERSION.SDK_INT < 29) { startForeground(NOTIF_ID, n); return }
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        // 景点介绍：带上"定位"类型，萤读在后台时也能按时定位
        val loc = sightsOn && hasLocation()
        if (loc) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try { startForeground(NOTIF_ID, n, type) }
        catch (e: Exception) {
            if (!loc) throw e
            log("没能以定位模式在后台运行：${e.message}")
            startForeground(NOTIF_ID, n, type and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION.inv())
        }
    }

    private fun updateNotification() {
        if (!inForeground) return
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) }
    }
}
