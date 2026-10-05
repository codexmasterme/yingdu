package io.github.yingdu

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * 界面：底部导航 首页 / 看板 / 我的，功能和设置都在子页面里（左上角返回，可以一层层进去）。
 * 每页的主要内容尽量一屏放下；不常用的设置、说明放到右上角或单独一行点进去。
 * 全部用代码搭建，没有 XML 布局。
 */
class MainActivity : Activity(), ReaderService.UiListener {


    companion object {
        /** 系统安装器装完（或要用户确认）时回到这里。 */
        const val ACTION_INSTALL_STATUS = "io.github.yingdu.INSTALL_STATUS"
        private const val REQ_OPEN = 1
        private const val REQ_PERMS = 2

        // 配色
        private const val BG = 0xFFF6F0E9.toInt()
        private const val CARD = 0xFFFFFFFF.toInt()
        private const val CARD_STROKE = 0xFFEFE4D8.toInt()
        private const val DIVIDER = 0xFFF3ECE3.toInt()
        private const val TAN = 0xFFBF9F7E.toInt()
        private const val TAN_SOFT = 0xFFFFFBF7.toInt()
        /** 次要文字：比原来的驼色深一点，看得清。 */
        private const val SUB = 0xFF8C7560.toInt()
        private const val FAINT = 0xFFB8A792.toInt()
        private const val INK = 0xFF333333.toInt()
        private const val DARK = 0xFF2E2E2E.toInt()
        private const val FIELD = 0xFFF8F3ED.toInt()
        private const val TRACK = 0xFFEFE6DB.toInt()
        private const val TRACK_OFF = 0xFFE6DCD0.toInt()
        private const val DANGER = 0xFFB5533C.toInt()
        private const val SCREEN = 0xFF0B1510.toInt()
        private const val SCREEN_TEXT = 0xFF9EF59E.toInt()
        private const val BATTERY = 0xFFEBD77A.toInt()

        /** 页面切换动画的时长（毫秒；系统设置里关掉动画时自动跳过）。 */
        private const val ANIM_MS = 300L

        private val GAME_ICONS = mapOf("2048" to "▦", "贪吃蛇" to "∽", "俄罗斯方块" to "▙", "扫雷" to "✹", "数独" to "⊞",
            "数字华容道" to "▤", "推箱子" to "□", "五子棋" to "●", "记忆翻牌" to "◇")
    }

    private enum class Tab { HOME, DASH, ME }
    private enum class Sub {
        READ, STOCKS, AGENDA, NOTIFY, GAMES, GAME, CLOCK, POMO, POMO_SET, PARTY,
        DASH_SET, FONT, READ_SET, OFFICIAL, LOG, ABOUT, LICENSES, MEMORY, MEMORY_SET, MEMORY_LISTEN, MEMORY_API, CAPTIONS, SIGHTS, MANUAL, MANUAL_TOPIC
    }

    private var service: ReaderService? = null
    private var tab = Tab.DASH   // 默认页：看板
    /** 打开的子页面（可以一层层进去，返回时一层层退出）。 */
    private val stack = ArrayList<Sub>()
    private val sub: Sub? get() = stack.lastOrNull()
    /** 每层子页面是从哪里点进来的（缩放动画的中心）、进来前上一层滚到了哪里（返回时恢复）。 */
    private class Origin(val x: Float, val y: Float, val scrollY: Int)
    private val origins = ArrayList<Origin>()

    /** 整个界面放在 stage 里，页面切换时对它做缩放；root 下面 / 上面临时放一张旧界面的截图。 */
    private lateinit var root: FrameLayout
    private lateinit var stage: FrameLayout
    private var shot: ImageView? = null
    private var shotBitmap: Bitmap? = null
    private var touchX = 0f
    private var touchY = 0f

    private lateinit var scroll: ScrollView
    private lateinit var navBar: View
    private lateinit var subHeader: LinearLayout
    private lateinit var subTitle: TextView
    private lateinit var subAction: TextView
    private lateinit var sticky: View
    private lateinit var stickyButton: TextView
    private val tabPages = HashMap<Tab, View>()
    private val subPages = HashMap<Sub, View>()
    private val navItems = HashMap<Tab, Pair<TextView, TextView>>()

    // 刷新时调用的更新函数（按页面分组，只刷新当前看得到的页面）
    private val updaters = HashMap<Any, ArrayList<(ReaderService) -> Unit>>()
    private fun onUpdate(key: Any, fn: (ReaderService) -> Unit) { updaters.getOrPut(key) { ArrayList() }.add(fn) }
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var refreshQueued = false
    private val loaders = ArrayList<(ReaderService) -> Unit>()
    private var loaded = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as ReaderService.LocalBinder).service
            service = s
            s.uiListener = this@MainActivity
            s.onUiShown()
            if (!loaded) { loaded = true; loaders.forEach { it(s) } }
            refreshNow()
            uiHandler.removeCallbacks(pageTicker); uiHandler.postDelayed(pageTicker, 1000)
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(buildUi())
        if (legalAgreed()) requestPermissionsIfNeeded() else showLegalFirstRun()
    }

    // ---- 免责说明、隐私说明：第一次打开时要同意才能用 ----
    private val legalPrefs by lazy { getSharedPreferences("legal", Context.MODE_PRIVATE) }
    private fun legalAgreed() = legalPrefs.getInt("agreed", 0) >= Legal.VERSION

    private fun showLegalFirstRun() {
        val body = vbox().apply {
            setPadding(dp(22), dp(6), dp(22), dp(4))
            addView(text("免责说明", 15f, INK, true))
            addView(text(Legal.DISCLAIMER, 13f).apply { setLineSpacing(0f, 1.35f) }, lp(top = 6))
            addView(text("隐私说明", 15f, INK, true), lp(top = 16))
            addView(text(Legal.PRIVACY, 13f).apply { setLineSpacing(0f, 1.35f) }, lp(top = 6))
        }
        AlertDialog.Builder(this)
            .setTitle("使用萤读前请先看一下")
            .setView(ScrollView(this).apply { addView(body) })
            .setCancelable(false)
            .setPositiveButton("同意并继续") { _, _ ->
                legalPrefs.edit().putInt("agreed", Legal.VERSION).apply()
                requestPermissionsIfNeeded()
            }
            .setNegativeButton("不同意，退出") { _, _ -> finish() }
            .show()
    }

    private fun showLegal(title: String, body: String) {
        AlertDialog.Builder(this).setTitle(title)
            .setView(ScrollView(this).apply {
                addView(text(body, 13.5f).apply { setLineSpacing(0f, 1.35f); setPadding(dp(22), dp(6), dp(22), dp(4)) })
            })
            .setPositiveButton("知道了", null).show()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, ReaderService::class.java), conn, Context.BIND_AUTO_CREATE)
        // 每天看一次有没有新版（同意了免责说明之后）
        uiHandler.postDelayed({ if (legalAgreed()) checkUpdate(manual = false) }, 3_000)
        // 通知使用权授权了但系统没连上（更新萤读后常见）：给系统 5 秒自己连，还没连上就请它重连
        uiHandler.postDelayed({ PhoneNotificationService.rebindIfNeeded(this, "打开萤读") }, 5_000)
    }

    override fun onStop() {
        uiHandler.removeCallbacks(pageTicker)
        service?.uiListener = null
        unbindService(conn)
        service = null
        super.onStop()
    }

    /** 音量键翻页：只在萤读的阅读页面开着时（和官方提词器一样）；别的页面、锁屏、后台照常调音量。 */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val code = event.keyCode
        if (code == android.view.KeyEvent.KEYCODE_VOLUME_UP || code == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            val s = service
            if (s != null && sub == Sub.READ && s.volumeKeysActive) {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) s.volumeKey(code == android.view.KeyEvent.KEYCODE_VOLUME_UP, repeat = event.repeatCount > 0)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 记下手指按下的位置：点进子页面时，新页面从这里放大出来。 */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) { touchX = ev.rawX; touchY = ev.rawY }
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Activity 基类的旧 API")
    override fun onBackPressed() {
        if (sub != null) closeSub() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // =====================================================================
    // 样式小工具
    // =====================================================================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    private fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dpf(radius)
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

    private fun text(t: String, size: Float, color: Int = INK, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        includeFontPadding = false
    }

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
                   top: Int = 0, bottom: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun weighted(left: Int = 0, right: Int = 0) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(left); rightMargin = dp(right) }

    private fun vbox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    /** 白色圆角卡片。 */
    private fun card(pad: Int = 14) = vbox().apply {
        background = rounded(CARD, 16f, 1, CARD_STROKE)
        setPadding(dp(pad), dp(12), dp(pad), dp(12))
        layoutParams = lp(bottom = 10)
    }

    /** 分组的小标题（灰色小字），右边可以放几个文字按钮。 */
    private fun section(label: String, vararg actions: Pair<String, () -> Unit>) = hbox().apply {
        setPadding(dp(4), dp(8), dp(4), dp(6))
        addView(text(label, 12f, SUB), weighted())
        for ((t, f) in actions) addView(text(t, 12f, TAN, true).apply {
            setPadding(dp(10), dp(4), 0, dp(4)); setOnClickListener { f() }
        })
    }

    /** 列表框：白色圆角，里面一行一行，行之间有细分隔线。 */
    private fun listBox() = vbox().apply {
        background = rounded(CARD, 16f, 1, CARD_STROKE)
        layoutParams = lp(bottom = 10)
        clipToOutline = true
    }

    /**
     * 会越来越长的列表：放进最高 maxDp 的框里，内容少就跟着变矮，多了停在这个高度、在框里上下滑
     * （滑到头继续拖就滑整页）。把返回的框加到页面上，往返回的 list 里 addRow。
     */
    private fun scrollListBox(maxDp: Int): Pair<View, LinearLayout> {
        val list = listBox().apply { background = null; layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        val box = InnerScroll(this, dp(maxDp)).apply {
            addView(list); isVerticalScrollBarEnabled = true
            background = rounded(CARD, 16f, 1, CARD_STROKE); clipToOutline = true
            layoutParams = lp(bottom = 10)
        }
        return box to list
    }

    private fun LinearLayout.addRow(v: View) {
        if (childCount > 0) addView(View(this@MainActivity).apply { setBackgroundColor(DIVIDER) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { leftMargin = dp(14) })
        addView(v)
    }

    /** 说明文字（小号灰字）。 */
    private fun hint(t: String) = text(t, 11.5f, SUB).apply { setLineSpacing(0f, 1.35f) }

    private fun note(t: String) = hint(t).apply { setPadding(dp(4), dp(2), dp(4), dp(8)) }

    /** 列表里的一行：标题、一行说明，右边是值、附件（开关、步进器）和箭头。 */
    private inner class Row(title: String, desc: String? = null, chevron: Boolean = false, onClick: (() -> Unit)? = null) {
        val titleV = text(title, 14f)
        val descV = text(desc ?: "", 11.5f, SUB).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; visibility = if (desc == null) View.GONE else View.VISIBLE
        }
        val valueV = text("", 13f, SUB).apply { visibility = View.GONE; maxLines = 1 }
        private val slot = FrameLayout(this@MainActivity)
        val root = hbox().apply {
            minimumHeight = dp(46)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            val col = vbox()
            col.addView(titleV)
            col.addView(descV, lp(top = 3))
            addView(col, weighted())
            addView(valueV, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            addView(slot)
            if (chevron) addView(text("›", 17f, FAINT).apply { setPadding(dp(6), 0, 0, dp(2)) })
            if (onClick != null) setOnClickListener { onClick() }
        }
        fun value(t: String) { valueV.text = t; valueV.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE }
        fun desc(t: String?) { descV.text = t ?: ""; descV.visibility = if (t.isNullOrEmpty()) View.GONE else View.VISIBLE }
        fun accessory(v: View) { slot.addView(v) }
    }

    /** 拨动开关。 */
    private inner class Toggle(onClick: () -> Unit) {
        private val thumb = View(this@MainActivity).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            elevation = dpf(1f)
        }
        private var enabled = true
        val root = FrameLayout(this@MainActivity).apply {
            layoutParams = FrameLayout.LayoutParams(dp(38), dp(22)).apply { leftMargin = dp(10) }
            addView(thumb, FrameLayout.LayoutParams(dp(18), dp(18)).apply { topMargin = dp(2); leftMargin = dp(2) })
            setOnClickListener { if (enabled) onClick() }
            background = rounded(TRACK_OFF, 11f)
        }
        private var shown: Boolean? = null
        fun set(on: Boolean, enabled: Boolean = true) {
            this.enabled = enabled
            root.alpha = if (enabled) 1f else 0.4f
            if (shown == on) return
            shown = on
            root.background = rounded(if (on) TAN else TRACK_OFF, 11f)
            (thumb.layoutParams as FrameLayout.LayoutParams).leftMargin = dp(if (on) 18 else 2)
            thumb.requestLayout()
        }
    }

    /** 分段按钮（几个选项选一个）。 */
    private inner class Seg(items: List<String>, onPick: (Int) -> Unit) {
        private val views = items.mapIndexed { i, t ->
            text(t, 13f, SUB).apply { gravity = Gravity.CENTER; maxLines = 1; setOnClickListener { onPick(i) } }
        }
        val root = hbox().apply {
            background = rounded(FIELD, 10f)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            views.forEach { addView(it, LinearLayout.LayoutParams(0, dp(28), 1f)) }
        }
        fun select(i: Int) {
            views.forEachIndexed { k, v ->
                val on = k == i
                v.background = if (on) rounded(CARD, 8f) else null
                v.setTextColor(if (on) INK else SUB)
                v.typeface = if (on) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.DEFAULT
            }
        }
    }

    /** 步进器：− 数值 +。 */
    private inner class Stepper(onMinus: () -> Unit, onPlus: () -> Unit) {
        val value = text("", 13f).apply { gravity = Gravity.CENTER; minWidth = dp(58) }
        private fun key(t: String, f: () -> Unit) = text(t, 16f, TAN).apply {
            gravity = Gravity.CENTER; background = rounded(TAN_SOFT, 0f); setOnClickListener { f() }
        }
        val root = hbox().apply {
            background = rounded(TAN_SOFT, 10f, 1, CARD_STROKE)
            setPadding(dp(1), dp(1), dp(1), dp(1))
            clipToOutline = true
            addView(key("−", onMinus), LinearLayout.LayoutParams(dp(34), dp(30)))
            addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)))
            addView(key("+", onPlus), LinearLayout.LayoutParams(dp(34), dp(30)))
        }
    }

    /** 细进度条。 */
    private inner class Bar(h: Int = 4) {
        private val fill = View(this@MainActivity).apply { background = rounded(TAN, h / 2f) }
        private val rest = View(this@MainActivity)
        val root = LinearLayout(this@MainActivity).apply {
            background = rounded(TRACK, h / 2f)
            addView(fill, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0f))
            addView(rest, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        val height = h
        fun set(f: Float) {
            val v = f.coerceIn(0f, 1f)
            (fill.layoutParams as LinearLayout.LayoutParams).weight = v
            (rest.layoutParams as LinearLayout.LayoutParams).weight = 1f - v
            root.requestLayout()
        }
    }

    private fun barLp(b: Bar) = LinearLayout.LayoutParams(0, dp(b.height), 1f)

    /** 方形（或圆形）图标按钮 + 下方小字；选中时驼色填充。 */
    private inner class Tile(icon: String, label: String, round: Boolean = false, onClick: () -> Unit) {
        private val radius = if (round) 22f else 14f
        val box = FrameLayout(this@MainActivity)
        val iconView = text(icon, 17f, TAN).apply { gravity = Gravity.CENTER }
        val labelView = text(label, 11f, SUB).apply { gravity = Gravity.CENTER; maxLines = 1 }
        val root = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            box.addView(iconView, FrameLayout.LayoutParams(dp(44), dp(44)))
            addView(box, LinearLayout.LayoutParams(dp(44), dp(44)))
            if (label.isNotEmpty()) addView(labelView, lp(top = 5))
            setOnClickListener { onClick() }
        }
        fun setActive(on: Boolean) {
            box.background = if (on) rounded(TAN, radius) else rounded(TAN_SOFT, radius, 1, CARD_STROKE)
            iconView.setTextColor(if (on) Color.WHITE else TAN)
            labelView.setTextColor(if (on) INK else SUB)
        }
        fun setLabel(t: String) { labelView.text = t }
        init { setActive(false) }
    }

    private fun tileRow(vararg tiles: Tile) = hbox().apply {
        for (t in tiles) addView(t.root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** 胶囊按钮：dark 为黑底主按钮，否则浅色次按钮；small 是矮一号的。 */
    private fun pill(t: String, dark: Boolean = true, small: Boolean = false, onClick: () -> Unit) =
        text(t, if (small) 13f else 14f, if (dark) Color.WHITE else INK, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            background = if (dark) rounded(DARK, 21f) else rounded(TAN_SOFT, 21f, 1, CARD_STROKE)
            setPadding(dp(14), dp(if (small) 8 else 12), dp(14), dp(if (small) 8 else 12))
            setOnClickListener { onClick() }
        }

    private fun pillRow(vararg pills: View) = hbox().apply {
        pills.forEachIndexed { i, p -> addView(p, weighted(left = if (i == 0) 0 else 4, right = if (i == pills.size - 1) 0 else 4)) }
    }

    /** 小的选择按钮（选中时深色）。 */
    private fun chip(t: String, on: Boolean, onClick: () -> Unit) = text(t, 12f, if (on) Color.WHITE else SUB, on).apply {
        gravity = Gravity.CENTER
        background = if (on) rounded(TAN, 13f) else rounded(TAN_SOFT, 13f, 1, CARD_STROKE)
        setPadding(dp(9), dp(4), dp(9), dp(4))
        setOnClickListener { onClick() }
    }

    /** 小圆勾（待办）。 */
    private fun check(on: Boolean, onClick: () -> Unit) = text(if (on) "✓" else "", 13f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        background = if (on) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TAN) }
                     else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(1), FAINT) }
        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        setOnClickListener { onClick() }
    }

    private fun smallButton(t: String, onClick: () -> Unit) = text(t, 15f, TAN, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { onClick() }
    }

    private fun field(hint: String, number: Boolean = false, multi: Boolean = false) = EditText(this).apply {
        this.hint = hint; textSize = 14f; setTextColor(INK); setHintTextColor(0xFFC9B8A6.toInt())
        background = rounded(FIELD, 10f)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        when {
            number -> { inputType = InputType.TYPE_CLASS_NUMBER; gravity = Gravity.CENTER }
            multi -> { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 2; gravity = Gravity.TOP }
            else -> setSingleLine()
        }
    }

    /** 行内的小数字框（自动滚动、排版、番茄钟时长）。 */
    private fun smallField(w: Int = 52) = field("", number = true).apply {
        setPadding(dp(4), dp(4), dp(4), dp(4))
        layoutParams = LinearLayout.LayoutParams(dp(w), dp(30)).apply { leftMargin = dp(6); rightMargin = dp(6) }
    }

    /** 预览框：和 app 一样的浅色底，外面一圈圆角细框。 */
    private fun previewFrame() = rounded(TAN_SOFT, 12f, 1, 0xFFE3D5C5.toInt())

    /** 文字预览（提词器文字）：浅色底、正文颜色。 */
    private fun screen() = text("", 12.5f, INK).apply {
        background = previewFrame()
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setLineSpacing(0f, 1.3f); minLines = 4; includeFontPadding = true
    }

    /** 眼镜画面预览：灰度图按 app 的浅色底、正文颜色上色（半亮的部分是两者之间），放大 2 倍（最近邻，像素清楚）。 */
    private fun glassesTint(src: Bitmap, scale: Int = 2, bg: Int = TAN_SOFT, fg: Int = INK): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val r0 = Color.red(bg); val g0 = Color.green(bg); val b0 = Color.blue(bg)
        val r1 = Color.red(fg); val g1 = Color.green(fg); val b1 = Color.blue(fg)
        for (i in px.indices) {
            val l = (Color.red(px[i]) * 299 + Color.green(px[i]) * 587 + Color.blue(px[i]) * 114) / 1000
            px[i] = Color.rgb(r0 + (r1 - r0) * l / 255, g0 + (g1 - g0) * l / 255, b0 + (b1 - b0) * l / 255)
        }
        val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        return if (scale == 1) out else Bitmap.createScaledBitmap(out, w * scale, h * scale, false)
    }

    private fun glassesImage() = ImageView(this).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = previewFrame()
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    private fun onEdit(block: (String) -> Unit) = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(e: android.text.Editable?) { block(e?.toString()?.trim() ?: "") }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun choose(title: String, items: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setItems(items.toTypedArray()) { _, i -> onPick(i) }.show()
    }

    private fun message(title: String, msg: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("知道了", null).show()
    }

    private fun need(vararg perms: String): Boolean {
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return true
        requestPermissions(missing.toTypedArray(), REQ_PERMS)
        return false
    }

    // =====================================================================
    // 页面框架
    // =====================================================================

    private fun buildUi(): View {
        val frame = FrameLayout(this).apply { setBackgroundColor(BG) }
        stage = frame
        root = FrameLayout(this).apply { setBackgroundColor(BG); addView(frame) }
        val column = vbox()
        subHeader = hbox().apply {
            minimumHeight = dp(52)
            setPadding(dp(6), dp(4), dp(14), dp(4))
            addView(text("‹", 28f, INK).apply { setPadding(dp(10), 0, dp(10), dp(4)); setOnClickListener { closeSub() } })
            subTitle = text("", 17f, INK, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            addView(subTitle, weighted())
            subAction = text("", 13f, TAN, true).apply { setPadding(dp(10), dp(8), 0, dp(8)) }
            addView(subAction)
            visibility = View.GONE
        }
        column.addView(subHeader)
        val content = vbox().apply { setPadding(dp(16), dp(4), dp(16), dp(100)) }
        tabPages[Tab.HOME] = buildHome()
        tabPages[Tab.DASH] = buildDash()
        tabPages[Tab.ME] = buildMe()
        subPages[Sub.READ] = buildRead()
        subPages[Sub.STOCKS] = buildStocks()
        subPages[Sub.AGENDA] = buildAgenda()
        subPages[Sub.NOTIFY] = buildNotify()
        subPages[Sub.GAMES] = buildGames()
        subPages[Sub.GAME] = buildGame()
        subPages[Sub.CLOCK] = buildClock()
        subPages[Sub.POMO] = buildPomo()
        subPages[Sub.POMO_SET] = buildPomoSettings()
        subPages[Sub.PARTY] = buildParty()
        subPages[Sub.DASH_SET] = buildDashSettings()
        subPages[Sub.FONT] = buildFont()
        subPages[Sub.READ_SET] = buildReadSettings()
        subPages[Sub.OFFICIAL] = buildOfficial()
        subPages[Sub.LOG] = buildLog()
        subPages[Sub.MEMORY] = buildMemory()
        subPages[Sub.CAPTIONS] = buildCaptions()
        subPages[Sub.LICENSES] = buildLicenses()
        subPages[Sub.MEMORY_LISTEN] = buildMemoryListen()
        subPages[Sub.MEMORY_API] = buildMemoryApi()
        subPages[Sub.MEMORY_SET] = buildMemorySettings()
        subPages[Sub.SIGHTS] = buildSights()
        subPages[Sub.ABOUT] = buildAbout()
        subPages[Sub.MANUAL] = buildManual()
        subPages[Sub.MANUAL_TOPIC] = buildManualTopic()
        (tabPages.values + subPages.values).forEach { content.addView(it) }
        scroll = ScrollView(this).apply { addView(content); isVerticalScrollBarEnabled = false }
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        frame.addView(column)
        navBar = buildNavBar()
        frame.addView(navBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58), Gravity.BOTTOM).apply {
            leftMargin = dp(14); rightMargin = dp(14); bottomMargin = dp(12)
        })
        // 子页面底部固定的主按钮（在眼镜上显示 / 收回）
        stickyButton = pill("") { stickyClick() }
        sticky = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG and 0x00FFFFFF, BG, BG))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(stickyButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            visibility = View.GONE
        }
        frame.addView(sticky, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        render()
        return root
    }

    private fun buildNavBar(): View {
        val bar = hbox().apply { background = rounded(DARK, 29f); gravity = Gravity.CENTER }
        fun item(t: Tab, icon: String, label: String) {
            val iconV = text(icon, 17f, Color.WHITE).apply { gravity = Gravity.CENTER }
            val labelV = text(label, 11f, Color.WHITE).apply { gravity = Gravity.CENTER }
            bar.addView(vbox().apply {
                gravity = Gravity.CENTER
                addView(iconV); addView(labelV, lp(top = 3))
                setOnClickListener { selectTab(t) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            navItems[t] = iconV to labelV
        }
        item(Tab.HOME, "⌂", "首页")
        item(Tab.DASH, "▦", "看板")
        item(Tab.ME, "◎", "我的")
        return bar
    }

    /** 大标题行（首页、看板、我的），右边可以放一个文字按钮。 */
    private fun pageTitle(t: String, action: Pair<String, () -> Unit>? = null) = hbox().apply {
        minimumHeight = dp(44)
        setPadding(0, dp(10), 0, dp(6))
        addView(text(t, 22f, INK, true), weighted())
        if (action != null) addView(text(action.first, 13f, TAN, true).apply { setPadding(dp(10), dp(6), dp(2), dp(6)); setOnClickListener { action.second() } })
    }

    private fun selectTab(t: Tab) {
        endTransition()
        val changed = t != tab || stack.isNotEmpty()
        stack.forEach { leaveHooks[it]?.invoke() }
        tab = t; stack.clear(); origins.clear()
        if (t == Tab.DASH) {
            service?.switchAppMode(ReaderService.AppMode.DASHBOARD)
            if (service?.autoLocate == true && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_PERMS)
        }
        render()
        // 切换底部标签：轻轻淡入，不缩放
        if (changed) { stage.alpha = 0.55f; stage.animate().alpha(1f).setDuration(160).start() }
    }

    /** 进入子页面。animate：带缩放动画（连续进两层时只让最后一层动）。 */
    private fun openSub(s: Sub, animate: Boolean = true) {
        if (sub == s) return
        endTransition()
        val before = if (animate) snapshot() else null
        val (px, py) = stagePoint()
        origins.add(Origin(px, py, scroll.scrollY))
        stack.add(s)
        when (s) {
            Sub.READ -> service?.switchAppMode(ReaderService.AppMode.READER)
            Sub.AGENDA -> if (!CalendarReader.hasPermission(this)) need(Manifest.permission.READ_CALENDAR)
            else -> {}
        }
        render()
        uiHandler.removeCallbacks(pageTicker); uiHandler.postDelayed(pageTicker, 1000)
        if (before != null) zoomIn(before, px, py)
    }

    /** 这个子页面对应的眼镜全屏功能正在显示（离开这个页面时要收回）。 */
    private fun ownsApp(p: Sub, s: ReaderService) = when (p) {
        Sub.GAME -> s.activeApp is GameApp
        Sub.CLOCK -> s.activeApp === s.clock
        Sub.POMO -> s.activeApp === s.pomodoro
        Sub.PARTY -> s.activeApp === s.party
        else -> false
    }

    /** 离开某一页（返回上一层，或切到底部别的标签）时要做的事，比如停掉正在放的录音。 */
    private val leaveHooks = HashMap<Sub, () -> Unit>()
    private fun onLeave(p: Sub, f: () -> Unit) { leaveHooks[p] = f }

    /** 返回上一层；离开小游戏、表盘这些全屏功能的页面时，眼镜回到阅读或看板。 */
    private fun closeSub() {
        if (stack.isEmpty()) return
        endTransition()
        val before = snapshot()
        val p = stack.removeAt(stack.size - 1)
        leaveHooks[p]?.invoke()
        val o = origins.removeLastOrNull()
        service?.let { s -> if (ownsApp(p, s)) s.closeApp() }
        render(o?.scrollY ?: 0)
        if (before != null && o != null) zoomOut(before, o.x, o.y)
    }

    // ---------- 页面切换的缩放动画 ----------

    /** 手指最后按下的位置，换算成 stage 里的坐标（没有按过就用屏幕中间）。 */
    private fun stagePoint(): Pair<Float, Float> {
        val loc = IntArray(2)
        stage.getLocationOnScreen(loc)
        val x = (touchX - loc[0]).takeIf { touchX > 0 && it in 0f..stage.width.toFloat() } ?: stage.width / 2f
        val y = (touchY - loc[1]).takeIf { touchY > 0 && it in 0f..stage.height.toFloat() } ?: stage.height / 2f
        return x to y
    }

    /** 当前界面截一张图（界面还没排好版时返回 null，不做动画）。 */
    private fun snapshot(): Bitmap? {
        if (stage.width <= 0 || stage.height <= 0) return null
        return runCatching {
            Bitmap.createBitmap(stage.width, stage.height, Bitmap.Config.ARGB_8888).also { stage.draw(android.graphics.Canvas(it)) }
        }.getOrNull()
    }

    private fun showShot(bmp: Bitmap, onTop: Boolean): ImageView {
        val v = ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY }
        if (onTop) root.addView(v) else root.addView(v, 0)
        shot = v; shotBitmap = bmp
        return v
    }

    private var transition: android.animation.AnimatorSet? = null

    private fun pvh(prop: android.util.Property<View, Float>, from: Float, to: Float) =
        android.animation.PropertyValuesHolder.ofFloat(prop, from, to)

    /** 缩放时切成圆角卡片的样子（结束后恢复直角）。 */
    private fun roundCorners(v: View, on: Boolean) {
        v.clipToOutline = on
        v.outlineProvider = if (!on) android.view.ViewOutlineProvider.BACKGROUND else object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dpf(26f))
            }
        }
    }

    private fun play(vararg anims: android.animation.Animator) {
        val set = android.animation.AnimatorSet()
        set.playTogether(*anims)
        set.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { if (transition === set) endTransition() }
        })
        transition = set
        set.start()
    }

    /**
     * 点进去：新页面从点的位置放大出来（0.86 → 1），很快变得不透明；
     * 旧页面在下面朝同一个点稍微放大、变淡，像镜头推进去。
     */
    private fun zoomIn(before: Bitmap, px: Float, py: Float) {
        val old = showShot(before, onTop = false)
        old.pivotX = px; old.pivotY = py
        stage.pivotX = px; stage.pivotY = py
        stage.scaleX = 0.86f; stage.scaleY = 0.86f; stage.alpha = 0f
        roundCorners(stage, true)
        stage.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val ease = android.view.animation.DecelerateInterpolator(2.2f)
        play(
            android.animation.ObjectAnimator.ofPropertyValuesHolder(stage, pvh(View.SCALE_X, 0.86f, 1f), pvh(View.SCALE_Y, 0.86f, 1f))
                .setDuration(ANIM_MS).apply { interpolator = ease },
            android.animation.ObjectAnimator.ofFloat(stage, View.ALPHA, 0f, 1f).setDuration(ANIM_MS * 45 / 100)
                .apply { interpolator = android.view.animation.DecelerateInterpolator(1.5f) },
            android.animation.ObjectAnimator.ofPropertyValuesHolder(old, pvh(View.SCALE_X, 1f, 1.06f), pvh(View.SCALE_Y, 1f, 1.06f), pvh(View.ALPHA, 1f, 0.4f))
                .setDuration(ANIM_MS).apply { interpolator = ease })
    }

    /**
     * 返回：当前页面缩回它当初被点开的位置（1 → 0.86），缩到后半程才淡出；
     * 上一层页面从稍微放大的状态退回原样、变清楚。
     */
    private fun zoomOut(before: Bitmap, px: Float, py: Float) {
        val cur = showShot(before, onTop = true)
        cur.pivotX = px; cur.pivotY = py
        roundCorners(cur, true)
        stage.pivotX = px; stage.pivotY = py
        stage.scaleX = 1.06f; stage.scaleY = 1.06f; stage.alpha = 0.4f
        stage.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val ease = android.view.animation.DecelerateInterpolator(2f)
        play(
            android.animation.ObjectAnimator.ofPropertyValuesHolder(cur, pvh(View.SCALE_X, 1f, 0.86f), pvh(View.SCALE_Y, 1f, 0.86f))
                .setDuration(ANIM_MS).apply { interpolator = ease },
            android.animation.ObjectAnimator.ofFloat(cur, View.ALPHA, 1f, 0f).setDuration(ANIM_MS * 60 / 100)
                .apply { startDelay = ANIM_MS * 35 / 100 },
            android.animation.ObjectAnimator.ofPropertyValuesHolder(stage, pvh(View.SCALE_X, 1.06f, 1f), pvh(View.SCALE_Y, 1.06f, 1f), pvh(View.ALPHA, 0.4f, 1f))
                .setDuration(ANIM_MS).apply { interpolator = ease })
    }

    /** 结束（或打断）正在进行的切换动画：去掉截图，界面回到原样。 */
    private fun endTransition() {
        if (!::stage.isInitialized) return
        val t = transition
        transition = null
        t?.cancel()
        stage.animate().cancel()
        stage.scaleX = 1f; stage.scaleY = 1f; stage.alpha = 1f
        roundCorners(stage, false)
        stage.setLayerType(View.LAYER_TYPE_NONE, null)
        shot?.let { root.removeView(it) }
        shot = null
        shotBitmap?.recycle(); shotBitmap = null
    }

    private fun titleOf(p: Sub): String = when (p) {
        Sub.READ -> "阅读"; Sub.STOCKS -> "股票"; Sub.AGENDA -> "日程待办"; Sub.NOTIFY -> "通知转发"
        Sub.GAMES -> "小游戏"; Sub.GAME -> (service?.activeApp as? GameApp)?.title ?: "小游戏"
        Sub.CLOCK -> "像素表盘"
        Sub.POMO -> "番茄钟"; Sub.POMO_SET -> "番茄钟设置"; Sub.PARTY -> "聚会工具"; Sub.DASH_SET -> "看板设置"
        Sub.FONT -> "眼镜字号"; Sub.READ_SET -> "阅读显示"; Sub.OFFICIAL -> "官方 app"; Sub.LOG -> "日志"; Sub.ABOUT -> "关于"; Sub.LICENSES -> "开源许可"
        Sub.MEMORY -> "全天记忆"; Sub.CAPTIONS -> "实时字幕"; Sub.MEMORY_LISTEN -> "收音方式和耗电"; Sub.MEMORY_API -> "转文字和总结"; Sub.MEMORY_SET -> "全天记忆设置"; Sub.SIGHTS -> "景点介绍"
        Sub.MANUAL -> "使用手册"; Sub.MANUAL_TOPIC -> manualTopic.title
    }

    /** 子页面右上角的文字按钮。 */
    private fun actionOf(p: Sub): Pair<String, () -> Unit>? = when (p) {
        Sub.READ -> "☰ 目录" to { showChapters() }
        Sub.POMO -> "⚙ 设置" to { openSub(Sub.POMO_SET) }
        Sub.MEMORY -> "⚙ 设置" to { openSub(Sub.MEMORY_SET) }
        Sub.GAME -> "说明" to { (service?.activeApp as? GameApp)?.let { message(it.title, it.help) }; Unit }
        // 设置页只留一行字，详细说明在使用手册里：右上角「说明」直接跳到对应的一页
        Sub.NOTIFY -> "说明" to { openManual("notify") }
        Sub.SIGHTS -> "说明" to { openManual("sights") }
        Sub.DASH_SET -> "说明" to { openManual("dash") }
        Sub.MEMORY_SET, Sub.MEMORY_API, Sub.MEMORY_LISTEN -> "说明" to { openManual("memory") }
        Sub.CAPTIONS -> "说明" to { openManual("captions") }
        Sub.READ_SET -> "说明" to { openManual("read") }
        else -> null
    }

    /** 子页面底部固定的按钮：在眼镜上显示这个功能 / 收回。 */
    private fun stickyLabel(p: Sub, s: ReaderService): String? = when (p) {
        Sub.READ -> if (s.appMode != ReaderService.AppMode.READER || s.glassesPaused) "在眼镜上显示阅读" else "收起眼镜显示"
        Sub.CLOCK -> glassesButton(s, s.clock, "在眼镜上显示表盘")
        Sub.POMO -> glassesButton(s, s.pomodoro, "在眼镜上看进度环")
        Sub.PARTY -> glassesButton(s, s.party, "在眼镜上打开")
        Sub.CAPTIONS -> if (s.activeApp === s.captions && !s.glassesPaused) "关掉字幕" else glassesButton(s, s.captions, "在眼镜上开字幕")
        else -> null
    }

    private fun stickyClick() {
        val s = service ?: return
        when (sub) {
            Sub.READ -> when {
                s.appMode != ReaderService.AppMode.READER -> s.switchAppMode(ReaderService.AppMode.READER)
                s.glassesPaused -> s.resumeGlasses()
                else -> s.pauseGlasses()
            }
            Sub.CLOCK -> toggleOnGlasses { it.clock }
            Sub.POMO -> toggleOnGlasses { it.pomodoro }
            Sub.PARTY -> toggleOnGlasses { it.party }
            Sub.CAPTIONS -> {
                if (s.activeApp !== s.captions && s.memGet("asrKey").isEmpty()) { toast("先在「全天记忆 › 设置」里填转文字的 API Key"); return }
                if (s.activeApp !== s.captions && s.linkState != LinkState.READY) { toast("先连上眼镜"); return }
                toggleOnGlasses { it.captions }
            }
            else -> {}
        }
        refreshNow()
    }

    /** 按当前页面显示；scrollTo：排版好之后滚到哪里（返回上一层时恢复原来的位置）。 */
    private fun render(scrollTo: Int = 0) {
        val s = sub
        tabPages.forEach { (k, v) -> v.visibility = if (s == null && k == tab) View.VISIBLE else View.GONE }
        subPages.forEach { (k, v) -> v.visibility = if (k == s) View.VISIBLE else View.GONE }
        subHeader.visibility = if (s != null) View.VISIBLE else View.GONE
        navBar.visibility = if (s != null) View.GONE else View.VISIBLE
        if (s != null) {
            subTitle.text = titleOf(s)
            val a = actionOf(s)
            subAction.text = a?.first ?: ""
            subAction.visibility = if (a == null) View.GONE else View.VISIBLE
            subAction.setOnClickListener { a?.second?.invoke() }
        }
        sticky.visibility = View.GONE
        navItems.forEach { (k, v) -> val c = if (k == tab) TAN else Color.WHITE; v.first.setTextColor(c); v.second.setTextColor(c) }
        if (::scroll.isInitialized) {
            scroll.scrollTo(0, 0)
            if (scrollTo > 0) scroll.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    scroll.viewTreeObserver.removeOnPreDrawListener(this)
                    scroll.scrollTo(0, scrollTo)
                    return true
                }
            })
        }
        refreshNow()
    }

    /** 服务通知状态变化：合并成每 150 毫秒最多刷新一次，避免日志频繁更新时界面卡顿。 */
    override fun onReaderChanged() {
        if (refreshQueued) return
        refreshQueued = true
        uiHandler.postDelayed({ refreshQueued = false; refreshNow() }, 150)
    }

    private fun refreshNow() {
        val s = service ?: return
        updaters[sub ?: tab]?.forEach { it(s) }
        val p = sub
        val label = p?.let { stickyLabel(it, s) }
        sticky.visibility = if (label == null) View.GONE else View.VISIBLE
        if (label != null) stickyButton.text = label
        if (p == Sub.GAME) subTitle.text = titleOf(p)
    }

    /** 这几页每秒刷新一次（进度、倒计时、表盘预览）。 */
    private val pageTicker = object : Runnable {
        override fun run() {
            if (sub != Sub.CLOCK && sub != Sub.POMO) return
            refreshNow()
            uiHandler.postDelayed(this, 1000)
        }
    }

    /** 在眼镜上打开 / 收回一个全屏功能；眼镜上长按退出过（显示暂停了）时是恢复显示。 */
    private fun toggleOnGlasses(app: (ReaderService) -> GlassesApp) {
        val s = service ?: return
        val a = app(s)
        when {
            s.activeApp !== a -> s.openApp(a)
            s.glassesPaused -> s.resumeGlasses()
            else -> s.closeApp()
        }
        refreshNow()
    }

    private fun glassesButton(s: ReaderService, a: GlassesApp, open: String) = when {
        s.activeApp !== a -> open
        s.glassesPaused -> "恢复眼镜显示"
        else -> "从眼镜上收回"
    }

    // =====================================================================
    // 首页：连接状态、快捷开关、眼镜正在显示什么、功能入口
    // =====================================================================

    private fun buildHome(): View {
        val page = vbox()

        val title = pageTitle("萤读")
        val batteryClip = ClipDrawable(GradientDrawable().apply { setColor(BATTERY); cornerRadius = dpf(9f) }, Gravity.START, ClipDrawable.HORIZONTAL)
        val battery = text("--", 12f, INK, true).apply {
            gravity = Gravity.CENTER
            background = LayerDrawable(arrayOf(rounded(CARD, 11f, 1, CARD_STROKE), batteryClip)).apply { setLayerInset(1, dp(2), dp(2), dp(2), dp(2)) }
            setPadding(dp(10), dp(4), dp(10), dp(4)); minWidth = dp(52)
        }
        title.addView(battery)
        page.addView(title)

        // 眼镜
        val dev = card().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = vbox()
        val device = text("Nimo 眼镜", 15f, INK, true)
        val status = text("", 12f, SUB)
        col.addView(device); col.addView(status, lp(top = 3))
        dev.addView(col, weighted())
        val connect = pill("连接", dark = false, small = true) {
            val s = service ?: return@pill
            if (s.linkState == LinkState.DISCONNECTED) pickDevice() else s.disconnect()
        }
        dev.addView(connect)
        page.addView(dev)

        // 快捷开关 + 亮度
        val quick = card()
        val dispOff = Tile("⊘", "息屏") { service?.toggleDisplayOff() }
        val headUp = Tile("↥", "抬头显示") { service?.toggleHeadUp() }
        val autoBr = Tile("☼", "自动亮度") { service?.toggleAutoBrightness() }
        val smaller = Tile("A−", "字小") { service?.adjustGlassesFont(-1) }
        val larger = Tile("A+", "字大") { service?.adjustGlassesFont(+1) }
        quick.addView(tileRow(dispOff, headUp, autoBr, smaller, larger))
        val br = hbox().apply { layoutParams = lp(top = 8) }
        br.addView(text("☼", 12f, SUB))
        val brBar = SeekBar(this)
        br.addView(brBar.apply {
            max = 100; progress = service?.brightnessPct ?: 60
            progressTintList = ColorStateList.valueOf(TAN); thumbTintList = ColorStateList.valueOf(TAN)
            progressBackgroundTintList = ColorStateList.valueOf(TRACK)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { service?.setBrightness(progress) }
            })
        }, weighted())
        br.addView(text("☼", 17f, TAN))
        quick.addView(br)
        page.addView(quick)

        // 眼镜正在显示：按内容给出最常用的操作
        val now = card()
        val nowHead = hbox()
        val nowCol = vbox()
        nowCol.addView(text("眼镜正在显示", 12f, SUB))
        val nowWhat = text("", 15f, INK, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        nowCol.addView(nowWhat, lp(top = 3))
        nowHead.addView(nowCol, weighted())
        val nowAction = text("", 13f, TAN, true).apply { setPadding(dp(10), dp(6), 0, dp(6)) }
        nowHead.addView(nowAction)
        now.addView(nowHead)
        val nowProg = hbox().apply { layoutParams = lp(top = 10) }
        val nowBar = Bar()
        nowProg.addView(nowBar.root, barLp(nowBar))
        val nowPct = text("", 12f, SUB).apply { setPadding(dp(8), 0, 0, 0) }
        nowProg.addView(nowPct)
        now.addView(nowProg)
        var leftAct: () -> Unit = {}
        var rightAct: () -> Unit = {}
        val leftBtn = pill("", dark = false, small = true) { leftAct() }
        val rightBtn = pill("", small = true) { rightAct() }
        val nowBtns = pillRow(leftBtn, rightBtn).apply { layoutParams = lp(top = 10) }
        now.addView(nowBtns)
        page.addView(now)

        // 功能入口
        page.addView(section("功能"))
        val grid = card(pad = 4).apply { setPadding(dp(4), dp(12), dp(4), dp(4)) }
        fun app(icon: View, label: String, onClick: () -> Unit) = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            val box = FrameLayout(this@MainActivity).apply { background = rounded(TAN_SOFT, 15f, 1, CARD_STROKE) }
            box.addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(box, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(text(label, 12f), lp(w = ViewGroup.LayoutParams.WRAP_CONTENT, top = 6, bottom = 10))
            setOnClickListener { onClick() }
        }
        // 图标都是像素画（每格 2dp），见 PhoneIcons.kt
        fun px(icon: PixelIcon) = ImageView(this).apply { setImageBitmap(icon.bitmap(dp(2))) }
        val apps = listOf(
            app(px(PhoneIcons.READ), "阅读") { openSub(Sub.READ) },
            app(px(PhoneIcons.POMO), "番茄钟") { openSub(Sub.POMO) }, app(px(PhoneIcons.CLOCK), "像素表盘") { openSub(Sub.CLOCK) },
            app(px(PhoneIcons.GAMES), "小游戏") { openSub(Sub.GAMES) },
            app(px(PhoneIcons.PARTY), "聚会工具") { openSub(Sub.PARTY) }, app(px(PhoneIcons.OFFICIAL), "官方 app") { openSub(Sub.OFFICIAL) },
            app(px(PhoneIcons.MEMORY), "全天记忆") { openSub(Sub.MEMORY) },
            app(px(PhoneIcons.CAPTIONS), "实时字幕") { openSub(Sub.CAPTIONS) },
            app(px(PhoneIcons.SIGHTS), "景点介绍") { openSub(Sub.SIGHTS) }, app(px(PhoneIcons.MANUAL), "使用手册") { openSub(Sub.MANUAL) })
        apps.chunked(4).forEach { r -> grid.addView(hbox().apply {
            (r + List(4 - r.size) { View(this@MainActivity) }).forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }) }
        page.addView(grid)

        onUpdate(Tab.HOME) { s ->
            val link = when (s.linkState) {
                LinkState.READY -> "已连接"; LinkState.HANDSHAKING -> "握手中…"
                LinkState.CONNECTING -> "连接中…"; LinkState.DISCONNECTED -> "未连接"
            }
            device.text = s.deviceName.ifEmpty { "Nimo 眼镜" }
            status.text = listOf(link, if (s.firmware.isNotEmpty()) "固件 ${s.firmware}" else "").filter { it.isNotEmpty() }.joinToString(" · ")
            connect.text = if (s.linkState == LinkState.DISCONNECTED) "连接" else "断开"
            battery.text = if (s.battery >= 0) "${s.battery}%" else "--"
            batteryClip.level = if (s.battery >= 0) s.battery * 100 else 0
            dispOff.setActive(s.displayOff == true)
            headUp.setActive(s.headUpDisplay == true)
            autoBr.setActive(s.autoBrightness == true)
            if (!brBar.isPressed && brBar.progress != s.brightnessPct) brBar.progress = s.brightnessPct
            larger.setLabel("字大 ${s.glassesFontPx}")

            val ready = s.linkState == LinkState.READY
            nowAction.text = if (s.glassesPaused) "恢复显示" else "收起显示"
            nowAction.visibility = if (ready) View.VISIBLE else View.GONE
            nowAction.setOnClickListener { if (s.glassesPaused) s.resumeGlasses() else s.pauseGlasses() }
            nowProg.visibility = View.GONE
            nowBtns.visibility = View.VISIBLE
            val paused = if (s.glassesPaused) "（已收起）" else ""
            when {
                !ready -> { nowWhat.text = "眼镜没有连接"; nowBtns.visibility = View.GONE }
                s.appMode == ReaderService.AppMode.READER -> {
                    val b = s.book
                    val ch = s.currentChapterTitle()
                    nowWhat.text = if (b == null) "阅读 · 还没有打开书$paused" else "${b.title}${if (ch.isNotEmpty()) " · $ch" else ""}$paused"
                    if (b != null && s.pageCount > 0) {
                        nowProg.visibility = View.VISIBLE
                        nowBar.set((s.progressPercent() / 100).toFloat()); nowPct.text = "%.0f%%".format(s.progressPercent())
                    }
                    leftBtn.text = "‹ 上一页"; rightBtn.text = "下一页 ›"
                    leftAct = { s.prevPage() }; rightAct = { if (b == null) openSub(Sub.READ) else s.nextPage() }
                    if (b == null) { leftBtn.text = "打开书籍"; leftAct = { openFile() }; rightBtn.text = "阅读页 ›"; rightAct = { openSub(Sub.READ) } }
                }
                s.appMode == ReaderService.AppMode.DASHBOARD -> {
                    val (p, n) = s.dashPageInfo()
                    nowWhat.text = "看板 · 第 ${p + 1}/$n 屏$paused"
                    leftBtn.text = "‹ 上一屏"; rightBtn.text = "下一屏 ›"
                    leftAct = { s.flipDashboard(-1) }; rightAct = { s.flipDashboard(1) }
                }
                else -> {
                    val a = s.activeApp
                    nowWhat.text = (a?.status() ?: "") + paused
                    leftBtn.text = "收回"; rightBtn.text = "打开页面 ›"
                    leftAct = { s.closeApp() }
                    rightAct = {
                        when {
                            a is GameApp -> { openSub(Sub.GAMES, animate = false); openSub(Sub.GAME) }
                            a === s.clock -> openSub(Sub.CLOCK)
                            a === s.pomodoro -> openSub(Sub.POMO)
                            a === s.party -> openSub(Sub.PARTY)
                            a === s.captions -> openSub(Sub.CAPTIONS)
                        }
                    }
                }
            }
        }
        return page
    }

    // =====================================================================
    // 阅读
    // =====================================================================

    private fun buildRead(): View {
        val page = vbox()
        val now = card()
        val book = text("还没有打开书", 16f, INK, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val chapter = text("", 12f, SUB).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        now.addView(book); now.addView(chapter, lp(top = 3))
        // 眼镜上这一屏的样子：图片显示时是眼镜画面的预览，文字显示时是提词器里的文字
        // 和 app 一样的浅色底、深色字，外面一圈圆角细框
        val img = glassesImage()
        val txt = screen().apply { minLines = 0; textSize = 13f; setPadding(dp(12), dp(10), dp(12), dp(10)) }
        now.addView(img, lp(top = 10)); now.addView(txt, lp(top = 10))
        // 进度条：拖动时预览那一屏（不发到眼镜），松手后跳过去
        var dragging = -1
        val seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(TAN); thumbTintList = ColorStateList.valueOf(TAN)
            progressBackgroundTintList = ColorStateList.valueOf(TRACK)
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        now.addView(seek, lp(top = 6).apply { leftMargin = -dp(8); rightMargin = -dp(8) })
        val posRow = hbox()
        val pos = text("", 12f, SUB).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        posRow.addView(pos, weighted())
        posRow.addView(text("跳到第几屏 ›", 12.5f, TAN, true).apply { setPadding(dp(10), dp(4), 0, dp(4)); setOnClickListener { jumpDialog() } })
        now.addView(posRow)
        var shownPreview = ""
        fun preview(s: ReaderService, n: Int?) {
            val pg = (if (n != null) s.pageAt(n) else s.currentPage()) ?: return
            val key = "${pg.start}|${s.glassesFontPx}|${s.readerAsImage}|${s.showProgress}|${s.linesPerPage}|${s.charsPerLine}"
            if (key == shownPreview) return
            shownPreview = key
            if (s.readerAsImage) {
                img.setImageBitmap(glassesTint(ReaderImage.renderBitmap(pg.text, s.glassesFontPx, if (s.showProgress) s.progressOf(pg) else null)))
            } else {
                // 字号按「每行字数」算，让手机上的换行和眼镜上一模一样
                val avail = txt.width - txt.paddingLeft - txt.paddingRight
                if (avail > 0) txt.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (avail / (s.charsPerLine + 1.2f)).coerceAtMost(dpf(15f)))
                else txt.post { shownPreview = ""; refreshNow() }
                txt.setLines(s.linesPerPage)
                txt.text = pg.text
            }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val s = service ?: return
                dragging = p + 1
                val pg = s.pageAt(dragging) ?: return
                val ch = s.chapterAt(pg.start)
                pos.text = "第 $dragging / ${s.pageCount} 屏 · %.1f%%".format(s.progressOf(pg)) + if (ch.isNotEmpty()) " · $ch" else ""
                preview(s, dragging)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val n = dragging
                dragging = -1
                if (n > 0) service?.goToPage(n)
                refreshNow()
            }
        })
        val prevCh = Tile("«", "上一章") { service?.prevChapter() }
        val prev = Tile("‹", "上一页") { service?.prevPage() }
        val next = Tile("›", "下一页") { service?.nextPage() }.also { it.setActive(true) }
        val nextCh = Tile("»", "下一章") { service?.nextChapter() }
        now.addView(tileRow(prevCh, prev, next, nextCh), lp(top = 14))
        page.addView(now)

        val auto = listBox()
        val autoRow = Row("自动滚动") { service?.toggleAutoFlip() }
        val autoToggle = Toggle { service?.toggleAutoFlip() }
        autoRow.accessory(autoToggle.root)
        auto.addRow(autoRow.root)
        val interval = smallField()
        val lines = smallField().apply { hint = "整页" }
        interval.addTextChangedListener(onEdit { t -> t.toIntOrNull()?.let { service?.autoFlipSeconds = it } })
        lines.addTextChangedListener(onEdit { t -> service?.scrollLines = t.toIntOrNull() ?: 0 })
        auto.addRow(hbox().apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            addView(text("每", 13.5f)); addView(interval); addView(text("秒前进", 13.5f)); addView(lines); addView(text("行", 13.5f))
        })
        page.addView(auto)
        page.addView(pill("打开书籍（TXT · EPUB · MOBI）", dark = false) { openFile() })
        val autoHint = note("")
        page.addView(autoHint.apply { setPadding(dp(4), dp(10), dp(4), 0) })

        loaders.add { s ->
            interval.setText(s.autoFlipSeconds.toString())
            lines.setText(s.scrollLines.takeIf { it > 0 }?.toString() ?: "")
        }
        onUpdate(Sub.READ) { s ->
            val b = s.book
            book.text = when { s.loading -> "正在打开…"; b == null -> "还没有打开书"; else -> b.title }
            val has = b != null && s.pageCount > 0
            chapter.text = if (has) s.currentChapterTitle() else "支持 TXT、EPUB、MOBI / AZW3（无 DRM）"
            chapter.visibility = if (chapter.text.isEmpty()) View.GONE else View.VISIBLE
            img.visibility = if (has && s.readerAsImage) View.VISIBLE else View.GONE
            txt.visibility = if (has && !s.readerAsImage) View.VISIBLE else View.GONE
            seek.visibility = if (has) View.VISIBLE else View.GONE
            posRow.visibility = if (has) View.VISIBLE else View.GONE
            if (has && dragging < 0) {
                seek.max = (s.pageCount - 1).coerceAtLeast(1)
                seek.progress = s.pageNumber - 1
                pos.text = "第 ${s.pageNumber} / ${s.pageCount} 屏 · %.1f%%".format(s.progressPercent())
                preview(s, null)
            }
            autoToggle.set(s.autoFlip)
            autoHint.text = if (s.readerAsImage) "行数空着就是整页翻。图片显示时镜腿单击不可用，用上面的按钮翻页。"
                else "行数空着就是整页翻。镜腿：单击翻页 · 双击换章 · 长按开关自动滚动"
        }
        return page
    }

    /** 跳到第几屏：输入屏数，也可以输入百分比（例如 50%）。 */
    private fun jumpDialog() {
        val s = service ?: return
        val total = s.pageCount
        if (total <= 0) { toast("还没有打开书"); return }
        val edit = field("1～$total，或者百分比如 50%").apply { inputType = InputType.TYPE_CLASS_TEXT }
        val box = vbox().apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(hint("现在在第 ${s.pageNumber} 屏，一共 $total 屏"))
            addView(edit, lp(top = 10))
        }
        val dlg = AlertDialog.Builder(this).setTitle("跳到第几屏").setView(box)
            .setPositiveButton("跳转") { _, _ ->
                val t = edit.text.toString().trim().replace("％", "%")
                val n = when {
                    t.endsWith("%") -> t.dropLast(1).trim().toDoubleOrNull()?.let { (it.coerceIn(0.0, 100.0) / 100 * total).toInt().coerceIn(1, total) }
                    else -> t.toIntOrNull()?.coerceIn(1, total)
                }
                if (n == null) toast("请输入数字") else { s.goToPage(n); refreshNow() }
            }
            .setNegativeButton("取消", null)
            .create()
        dlg.setOnShowListener {
            edit.requestFocus()
            dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dlg.show()
    }

    // =====================================================================
    // 看板：预览 + 卡片开关；其余设置在「设置」里
    // =====================================================================

    private fun buildDash(): View {
        val page = vbox()
        page.addView(pageTitle("看板", "⚙ 设置" to { openSub(Sub.DASH_SET) }))

        // 用不用看板：关掉时眼镜就用官方主界面
        val onBox = listBox()
        val onToggle = Toggle { service?.let { it.dashEnabled = !it.dashEnabled }; refreshNow() }
        val onRow = Row("使用看板", "").apply { accessory(onToggle.root) }
        onBox.addRow(onRow.root)
        page.addView(onBox)

        val pv = card(pad = 10).apply { setPadding(dp(10), dp(10), dp(10), dp(10)) }
        val img = glassesImage()
        val textPreview = screen()
        pv.addView(img); pv.addView(textPreview)
        fun sq(t: String, f: () -> Unit) = pill(t, dark = false, small = true, onClick = f).apply { setPadding(0, dp(8), 0, dp(8)) }
        val show = pill("显示看板", small = true) {
            val s = service ?: return@pill
            if (!s.dashEnabled) { toast("先打开最上面的「使用看板」"); return@pill }
            s.switchAppMode(ReaderService.AppMode.DASHBOARD); if (s.glassesPaused) s.resumeGlasses() else s.pauseGlasses()
        }
        fun flip(d: Int) { service?.flipDashboard(d); refreshNow() }
        pv.addView(hbox().apply {
            layoutParams = lp(top = 10)
            addView(sq("‹") { flip(-1) }, LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(show, weighted(left = 8, right = 8))
            addView(sq("›") { flip(1) }, LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
            addView(sq("↻") { service?.refreshDashboard(forceWeather = true); toast("正在刷新") }, LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        val info = text("", 12f, SUB).apply { gravity = Gravity.CENTER }
        pv.addView(info, lp(top = 8))
        page.addView(pv)

        var sorting = false
        val cardList = listBox()
        var shownCards = ""
        val cardsSection = section("卡片（按顺序轮换）",
            "排序" to { sorting = !sorting; shownCards = ""; refreshNow() },
            "＋ 网络卡片" to { editWebCard(null) })
        page.addView(cardsSection)
        page.addView(cardList)
        val cardsNote = note("点卡片的名字进入它的设置。")
        page.addView(cardsNote)

        var shownImg = ""
        onUpdate(Tab.DASH) { s ->
            val on = s.dashEnabled
            onToggle.set(on)
            onRow.desc(if (on) "抬头显示萤读的看板" else "眼镜用官方主界面；阅读、小游戏等照常能打开")
            val items = s.cards.items
            val sig = items.joinToString("\u0000") + sorting
            if (sig != shownCards) {
                shownCards = sig
                cardList.removeAllViews()
                items.forEachIndexed { i, c -> cardList.addRow(cardRow(c, i, items.size, sorting)) }
            }
            val (stt, body) = s.dashboardPreview()
            val (p, n) = s.dashPageInfo()
            if (s.dashAsImage) {
                img.visibility = View.VISIBLE; textPreview.visibility = View.GONE
                val key = stt + body + p + s.glassesFontPx + (System.currentTimeMillis() / 60_000)
                if (key != shownImg) { shownImg = key; img.setImageBitmap(glassesTint(s.dashboardBitmap())) }
            } else {
                img.visibility = View.GONE; textPreview.visibility = View.VISIBLE
                textPreview.text = stt + "\n\n" + body
            }
            val showing = !s.glassesPaused && s.appMode == ReaderService.AppMode.DASHBOARD
            show.text = if (showing) "收起看板" else "显示看板"
            info.text = "第 ${p + 1}/$n 屏" + if (s.dashUpdated.isNotEmpty()) " · ${s.dashUpdated} 更新" else " · 正在获取数据…"
        }
        return page
    }

    /** 卡片列表的一行：名字和一句说明（点击进设置）、开关；排序时多出上移、下移。 */
    private fun cardRow(c: CardConfig, index: Int, count: Int, sorting: Boolean): View {
        val desc = when (c.type) {
            CardType.WEB -> c.url.ifBlank { "还没有填网址" }
            CardType.FACE -> PixelClock.STYLES[PixelClock.styleOf(c.text)]
            else -> c.type.desc
        }
        val row = Row(c.name, desc) { openCardSettings(c) }
        if (sorting) {
            fun small(t: String, enabled: Boolean, onClick: () -> Unit) = text(t, 16f, if (enabled) TAN else CARD_STROKE, true).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                if (enabled) setOnClickListener { onClick() }
            }
            row.accessory(hbox().apply {
                addView(small("↑", index > 0) { service?.let { it.cards.move(c.id, -1); it.cardsChanged() } })
                addView(small("↓", index < count - 1) { service?.let { it.cards.move(c.id, 1); it.cardsChanged() } })
            })
        } else {
            val t = Toggle {
                val s = service ?: return@Toggle
                s.cards.update(c.copy(enabled = !c.enabled))
                s.cardsChanged(refetch = c.id)
            }
            t.set(c.enabled)
            row.accessory(t.root)
        }
        return row.root
    }

    private fun openCardSettings(c: CardConfig) {
        when (c.type) {
            CardType.STOCKS -> openSub(Sub.STOCKS)
            CardType.AGENDA -> openSub(Sub.AGENDA)
            CardType.FORECAST -> toast("天气预报跟随「我的 › 天气和步数」里的城市")
            CardType.FACE -> choose("表盘样式", PixelClock.STYLES) { i ->
                val s = service ?: return@choose
                s.cards.update(c.copy(text = PixelClock.KEYS[i])); s.cardsChanged()
            }
            CardType.POMODORO -> openSub(Sub.POMO)
            CardType.COUNTDOWN -> editListCard(c, "每行一项：名称 日期\n2027-02-06 表示那一天；12-25 表示每年这一天。已经过去的不显示。")
            CardType.CLOCKS -> editListCard(c, "每行一项：名称 时区\n时区写英文名称，例如 America/New_York、Europe/Paris、Asia/Shanghai、Australia/Sydney。")
            CardType.MEMORY -> openSub(Sub.MEMORY)
            CardType.WEB -> editWebCard(c)
            CardType.SIGHTS -> openSub(Sub.SIGHTS)
        }
    }

    /** 倒数日、世界时钟：一个多行文本框编辑列表。 */
    private fun editListCard(c: CardConfig, help: String) {
        val box = vbox().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        box.addView(hint(help))
        val edit = field("", multi = true).apply { setText(c.text); minLines = 4 }
        box.addView(edit, lp(top = 10))
        AlertDialog.Builder(this).setTitle(c.name).setView(box)
            .setPositiveButton("保存") { _, _ ->
                val s = service ?: return@setPositiveButton
                s.cards.update(c.copy(text = edit.text.toString().trim()))
                s.cardsChanged()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 网络卡片：名称、网址、请求头、请求体（填了就用 POST）、刷新间隔。c 为 null 表示新建。 */
    private fun editWebCard(c: CardConfig?) {
        val box = vbox().apply { setPadding(dp(20), dp(8), dp(20), 0) }
        val name = field("名称，例如 家里").apply { setText(c?.name ?: "") }
        val url = field("网址 https://…").apply { setText(c?.url ?: "") }
        val headers = field("请求头（可选），每行一个，例如\nAuthorization: Bearer 你的令牌", multi = true).apply { setText(c?.headers ?: "") }
        val body = field("请求体（可选，填了就用 POST）", multi = true).apply { setText(c?.body ?: "") }
        val minutes = field("刷新间隔（分钟）", number = true).apply { setText((c?.minutes ?: 5).toString()) }
        box.addView(name)
        box.addView(url, lp(top = 8))
        box.addView(headers, lp(top = 8))
        box.addView(body, lp(top = 8))
        box.addView(hbox().apply {
            layoutParams = lp(top = 8)
            addView(text("每", 14f)); addView(minutes, weighted(left = 8, right = 8)); addView(text("分钟刷新", 14f))
        })
        box.addView(hint("返回纯文本或 JSON，格式见「使用手册 → 看板 → 网络卡片」。"), lp(top = 10))
        val scroll = ScrollView(this).apply { addView(box) }
        val dlg = AlertDialog.Builder(this).setTitle(if (c == null) "添加网络卡片" else c.name).setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val s = service ?: return@setPositiveButton
                val u = url.text.toString().trim()
                if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) { toast("网址要以 http:// 或 https:// 开头"); return@setPositiveButton }
                val cfg = (c ?: CardConfig("web-" + System.currentTimeMillis(), CardType.WEB)).copy(
                    name = name.text.toString().trim().ifEmpty { "网络卡片" }, url = u,
                    headers = headers.text.toString().trim(), body = body.text.toString().trim(),
                    minutes = minutes.text.toString().toIntOrNull()?.coerceIn(1, 1440) ?: 5)
                if (c == null) s.cards.add(cfg) else s.cards.update(cfg)
                s.cardsChanged(refetch = cfg.id)
            }
            .setNegativeButton("取消", null)
        if (c != null) dlg.setNeutralButton("删除") { _, _ -> service?.let { it.cards.remove(c.id); it.cardsChanged() } }
        dlg.show()
    }

    /** 看板设置：画面、自动收起和收起方式、抬头低头。 */
    private fun buildDashSettings(): View {
        val page = vbox()
        val offNote = note("现在没有使用看板（在「看板」页最上面打开）。")
        page.addView(offNote)
        val fc = card()
        fc.addView(text("画面", 13.5f))
        val lookSeg = Seg(listOf("图片", "提词器文字")) { i -> service?.dashAsImage = i == 0; refreshNow() }
        fc.addView(lookSeg.root, lp(top = 6))
        fc.addView(text("显示多久后收起", 13.5f), lp(top = 12))
        val secs = listOf(10, 20, 30, 0)
        val secSeg = Seg(listOf("10 秒", "20 秒", "30 秒", "不收起")) { i -> service?.dashAutoHideSec = secs[i]; refreshNow() }
        fc.addView(secSeg.root, lp(top = 6))
        fc.addView(text("收起方式", 13.5f), lp(top = 12))
        val hows = listOf(ReaderService.HIDE_SCREEN_OFF, ReaderService.HIDE_HOME)
        val howSeg = Seg(listOf("关屏", "回主界面")) { i -> service?.dashHideMode = hows[i]; refreshNow() }
        fc.addView(howSeg.root, lp(top = 6))
        val howHint = hint("")
        fc.addView(howHint, lp(top = 8))
        page.addView(fc, lp(top = 4, bottom = 10))
        val wakeBox = listBox()
        val headToggle = Toggle { service?.let { it.dashHeadWake = !it.dashHeadWake }; refreshNow() }
        val headRow = Row("抬头时显示看板", "低头再关屏").apply { accessory(headToggle.root) }
        wakeBox.addRow(headRow.root)
        val darkToggle = Toggle { service?.let { it.dashHomeDark = !it.dashHomeDark }; refreshNow() }
        val darkRow = Row("低着头收起时直接关屏（测试）", "低头退回主界面时先关屏，主界面不会一直亮着；12 秒后松开关屏，交回眼镜自己按抬头低头开关屏。抬头没反应就点左镜腿或关掉这个开关").apply { accessory(darkToggle.root) }
        wakeBox.addRow(darkRow.root)
        page.addView(wakeBox)

        onUpdate(Sub.DASH_SET) { s ->
            offNote.visibility = if (s.dashEnabled) View.GONE else View.VISIBLE
            lookSeg.select(if (s.dashAsImage) 0 else 1)
            secSeg.select(secs.indexOf(s.dashAutoHideSec).coerceAtLeast(0))
            howSeg.select(hows.indexOf(s.dashHideMode).coerceAtLeast(0))
            val home = s.dashHideMode == ReaderService.HIDE_HOME
            howHint.text = if (home) "回官方主界面；眼镜报告「主界面被点亮」时叫回看板。官方 app 连着时这个报告会发给官方 app，" +
                "萤读收不到，所以要先停止官方 app（「我的 › 和官方 app 切换」）。"
                else "只关屏幕：抬头亮、低头关。"
            headToggle.set(s.dashHeadWake)
            headRow.titleV.text = if (home) "低头时回主界面" else "抬头时显示看板"
            headRow.desc(if (home) "不用等自动收起" else "低头再关屏")
            darkToggle.set(s.dashHomeDark)
            darkRow.root.visibility = if (home) View.VISIBLE else View.GONE
        }
        return page
    }

    // =====================================================================
    // 使用手册：功能列表 → 每个功能一页说明
    // =====================================================================

    private var manualTopic: ManualTopic = Manual.TOPICS.first()
    private lateinit var manualBox: LinearLayout

    private fun buildManual(): View {
        val page = vbox()
        val list = listBox()
        Manual.TOPICS.forEach { t -> list.addRow(Row(t.title, t.summary, chevron = true) { openManual(t.id) }.root) }
        page.addView(list)
        return page
    }

    private fun buildManualTopic(): View {
        val page = vbox()
        manualBox = vbox()
        page.addView(manualBox)
        return page
    }

    private fun openManual(id: String) {
        manualTopic = Manual.topic(id)
        manualBox.removeAllViews()
        manualTopic.items.forEach { item ->
            manualBox.addView(card().apply {
                addView(text(item.title, 15f, INK, true))
                addView(text(item.body, 13.5f).apply { setLineSpacing(0f, 1.45f) }, lp(top = 6))
            }, lp(bottom = 10))
        }
        if (sub == Sub.MANUAL_TOPIC) render() else openSub(Sub.MANUAL_TOPIC)
    }

    // =====================================================================
    // 我的：设置列表
    // =====================================================================

    private fun buildMe(): View {
        val page = vbox()
        page.addView(pageTitle("我的"))

        page.addView(section("连接"))
        val c = listBox()
        val link = Row("连接眼镜", chevron = true) {
            val s = service ?: return@Row
            if (s.linkState == LinkState.DISCONNECTED) pickDevice()
            else AlertDialog.Builder(this).setTitle("断开眼镜？").setPositiveButton("断开") { _, _ -> s.disconnect() }.setNegativeButton("取消", null).show()
        }
        c.addRow(link.root)
        c.addRow(Row("和官方 app 切换", "眼镜同时只能连一个 app", chevron = true) { openSub(Sub.OFFICIAL) }.root)
        val bg = Row("后台运行", chevron = true) {
            val s = service ?: return@Row
            val i = if (s.ignoringBatteryOptimizations()) Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                else Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))
            runCatching { startActivity(i) }.onFailure {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) }
            }
        }
        c.addRow(bg.root)
        c.addRow(Row("退出萤读") {
            AlertDialog.Builder(this).setTitle("退出萤读？").setMessage("会断开眼镜，停止后台服务。")
                .setPositiveButton("退出") { _, _ -> service?.shutdown(); finish() }.setNegativeButton("取消", null).show()
        }.apply { titleV.setTextColor(DANGER) }.root)
        page.addView(c)

        page.addView(section("眼镜"))
        val g = listBox()
        val font = Row("眼镜字号", chevron = true) { openSub(Sub.FONT) }
        val read = Row("阅读显示", "图片 / 提词器文字、进度、排版", chevron = true) { openSub(Sub.READ_SET) }
        val notify = Row("通知转发", "", chevron = true) { openSub(Sub.NOTIFY) }
        g.addRow(font.root); g.addRow(read.root); g.addRow(notify.root)
        page.addView(g)

        // 看板顶部一行、天气卡片都用
        page.addView(section("天气和步数"))
        val data = listBox()
        val locToggle = Toggle {
            val s = service ?: return@Toggle
            if (!s.autoLocate) {
                if (!need(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) &&
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return@Toggle
                s.autoLocate = true
            } else s.autoLocate = false
            onReaderChanged()
        }
        data.addRow(Row("天气用手机定位").apply { accessory(locToggle.root) }.root)
        val cityRow = Row("城市", chevron = true) {
            val edit = field("例如 东京").apply { setText(service?.dashCity ?: "") }
            AlertDialog.Builder(this).setTitle("城市").setView(vbox().apply { setPadding(dp(20), dp(8), dp(20), 0); addView(edit) })
                .setPositiveButton("设置") { _, _ -> service?.setCity(edit.text.toString()) }.setNegativeButton("取消", null).show()
        }
        data.addRow(cityRow.root)
        val stepsRow = Row("步数来源", "", chevron = true) {
            val sp = service?.steps ?: return@Row
            val perms = sp.neededPermissions()
            if (perms.isEmpty()) { toast("已经授权"); sp.refresh() } else requestPermissions(perms.toTypedArray(), REQ_PERMS)
        }
        data.addRow(stepsRow.root)
        page.addView(data)

        page.addView(section("其他"))
        val o = listBox()
        o.addRow(Row("使用手册", chevron = true) { openSub(Sub.MANUAL) }.root)
        o.addRow(Row("日志", chevron = true) { openSub(Sub.LOG) }.root)
        val about = Row("关于", chevron = true) { openSub(Sub.ABOUT) }
        about.value(versionName().let { if (it.isEmpty()) "" else "v$it" })
        o.addRow(about.root)
        page.addView(o)

        onUpdate(Tab.ME) { s ->
            locToggle.set(s.autoLocate)
            cityRow.value(s.dashCity.ifEmpty { "未设置" })
            stepsRow.desc(s.steps.steps?.let { "今天 %,d 步".format(it) } ?: "还没有步数数据")
            stepsRow.value(if (s.steps.steps != null) s.steps.source else "去授权")
            font.value("${s.glassesFontPx}px")
            bg.value(if (s.ignoringBatteryOptimizations()) "已允许" else "去允许")
            read.value(if (s.readerAsImage) "图片" else "文字")
            val chosen = chosenApps()
            notify.desc(when {
                !PhoneNotificationService.isEnabled(this) -> "还没有通知使用权"
                chosen.isEmpty() -> "还没有选 app"
                chosen.size <= 3 -> chosen.joinToString("、")
                else -> chosen.take(3).joinToString("、") + " 等 ${chosen.size} 个"
            })
            notify.value(if (NotifyPrefs.enabled(this)) "已开" else "已关")
            link.value(when (s.linkState) {
                LinkState.READY -> s.deviceName.ifEmpty { "已连接" }; LinkState.DISCONNECTED -> "未连接"; else -> "连接中…"
            })
        }
        return page
    }

    private var chosenKey: Set<String>? = null
    private var chosenLabels: List<String> = emptyList()

    /**
     * 勾选了要转发的 app（名称）。只算手机上装了、能在列表里看到的：默认勾选的常用聊天软件
     * （WhatsApp、LINE……）没装的也在设置里，但列表里看不到，不能算进去。
     */
    private fun chosenApps(): List<String> {
        val allowed = NotifyPrefs.allowed(this)
        if (allowed != chosenKey) {
            chosenKey = allowed
            chosenLabels = allowed.filter { packageManager.getLaunchIntentForPackage(it) != null }
                .mapNotNull { pkg -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull() }
                .sorted()
        }
        return chosenLabels
    }

    private fun versionName(): String = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""

    /** 眼镜字号：12～32px 每 1px 一档，拖动时实时预览眼镜上的效果。 */
    private fun buildFont(): View {
        val page = vbox()
        val c = card()
        GlassesFonts.init(this)
        val sample = "12:45 灯下的长椅空无一人"
        val value = text("", 22f, INK, true)
        val info = text("", 12f, SUB)
        val preview = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START }
        var shownPx = -1
        fun show(px: Int) {
            val lay = ReaderImage.layout(px)
            value.text = "${lay.px}px"
            info.text = "阅读一屏 ${lay.rows} 行 × ${lay.charsPerLine} 字" + when (lay.px) {
                16 -> " · 和官方界面一样"
                24 -> " · 12 点阵的 2 倍"
                else -> ""
            }
            if (lay.px != shownPx) { shownPx = lay.px; preview.setImageBitmap(fontSample(GlassesFonts.text(lay.px), sample)) }
        }
        c.addView(value); c.addView(info, lp(top = 4)); c.addView(preview, lp(top = 10))
        val bar = SeekBar(this).apply {
            max = GlassesFonts.MAX_PX - GlassesFonts.MIN_PX
            progressTintList = ColorStateList.valueOf(TAN); thumbTintList = ColorStateList.valueOf(TAN)
            progressBackgroundTintList = ColorStateList.valueOf(TRACK)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) show(GlassesFonts.MIN_PX + p) }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { service?.setGlassesFont(GlassesFonts.MIN_PX + progress); refreshNow() }
            })
        }
        val row = hbox().apply { layoutParams = lp(top = 12) }
        row.addView(pill("A−", dark = false, small = true) { service?.adjustGlassesFont(-1); refreshNow() })
        row.addView(bar, weighted())
        row.addView(pill("A+", dark = false, small = true) { service?.adjustGlassesFont(+1); refreshNow() })
        c.addView(row)
        page.addView(c)
        page.addView(note("阅读和看板共用这个字号，16px、24px 最清晰。"))
        onUpdate(Sub.FONT) { s ->
            if (!bar.isPressed) bar.progress = s.glassesFontPx - GlassesFonts.MIN_PX
            show(s.glassesFontPx)
        }
        return page
    }

    /** 模拟眼镜效果的示例图：深色底、绿色点阵字，眼镜上的 1 个像素放大成手机上的整数个像素。 */
    private fun fontSample(font: PixelText, text: String): Bitmap {
        val k = kotlin.math.max(1, kotlin.math.round(resources.displayMetrics.density * 0.9f).toInt())
        val pad = 4
        val w = font.measure(text) + pad * 2
        val h = font.height + pad * 2
        val bmp = Bitmap.createBitmap(w * k, h * k, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        cv.drawColor(SCREEN)
        val p = android.graphics.Paint().apply { color = SCREEN_TEXT }
        font.draw(text, pad, pad) { x, y, rw, rh ->
            cv.drawRect((x * k).toFloat(), (y * k).toFloat(), ((x + rw) * k).toFloat(), ((y + rh) * k).toFloat(), p)
        }
        return bmp
    }

    /** 阅读显示：图片 / 提词器文字、进度；文字显示时的页面和排版。 */
    private fun buildReadSettings(): View {
        val page = vbox()
        page.addView(section("显示方式"))
        val c = card()
        val seg = Seg(listOf("图片", "提词器文字")) { i ->
            val s = service ?: return@Seg
            if ((i == 0) != s.readerAsImage) { s.toggleReaderImage(); reloadLayout() }
            refreshNow()
        }
        c.addView(seg.root)
        c.addView(hint("图片：切换不闪屏；文字：用提词器，镜腿翻页更灵敏。"), lp(top = 8))
        page.addView(c)
        page.addView(section("音量键翻页"))
        val vc = card()
        val volSeg = Seg(listOf("关", "上一页 / 下一页", "上一行 / 下一行")) { i -> service?.volumeKeys = i; refreshNow() }
        vc.addView(volSeg.root)
        vc.addView(hint("在萤读的阅读页面里：音量 + 往前，音量 − 往后，按住连续翻（和官方提词器一样，只在阅读页面开着时有效，锁屏或切到别处照常调音量）。"), lp(top = 8))
        page.addView(vc)
        val l = listBox()
        val progToggle = Toggle { service?.let { it.showProgress = !it.showProgress }; refreshNow() }
        l.addRow(Row("显示阅读进度") { service?.let { it.showProgress = !it.showProgress }; refreshNow() }.apply { accessory(progToggle.root) }.root)
        page.addView(l)

        val textPart = vbox()
        textPart.addView(section("文字显示"))
        val t = listBox()
        val pageRow = Row("眼镜页面", chevron = true) { service?.toggleDisplayPage(); reloadLayout(); refreshNow() }
        t.addRow(pageRow.root)
        t.addRow(Row("测宽度", "在眼镜上显示一行数字，看能放多少字", chevron = true) { service?.sendWidthTest() }.root)
        t.addRow(Row("测行数", "看一屏能放几行", chevron = true) { service?.sendHeightTest() }.root)
        perLine = smallField(); perPage = smallField()
        t.addRow(hbox().apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            addView(text("每行", 13.5f)); addView(perLine); addView(text("字  每页", 13.5f)); addView(perPage); addView(text("行", 13.5f))
            addView(View(this@MainActivity), weighted())
            addView(pill("应用", small = true) {
                val a = perLine.text.toString().toIntOrNull(); val b = perPage.text.toString().toIntOrNull()
                if (a == null || b == null) toast("请填入数字") else { service?.applyLayout(a, b); reloadLayout(); toast("已应用") }
            })
        })
        textPart.addView(t)
        textPart.addView(note("先测宽度、测行数，每行字数比实测少 1。"))
        page.addView(textPart)

        loaders.add { reloadLayout() }
        onUpdate(Sub.READ_SET) { s ->
            seg.select(if (s.readerAsImage) 0 else 1)
            volSeg.select(s.volumeKeys)
            progToggle.set(s.showProgress)
            textPart.visibility = if (s.readerAsImage) View.GONE else View.VISIBLE
            pageRow.value(if (s.displayPage == DisplayPage.PROMPTER) "提词器页" else "笔记页")
        }
        return page
    }

    private lateinit var perLine: EditText
    private lateinit var perPage: EditText
    private fun reloadLayout() {
        val s = service ?: return
        perLine.setText(s.charsPerLine.toString()); perPage.setText(s.linesPerPage.toString())
    }

    /** 和官方 app 切换：翻译、导航交给官方 app；用完怎么切回来。 */
    private fun buildOfficial(): View {
        val page = vbox()
        page.addView(section("交给官方 app"))
        val l = listBox()
        l.addRow(Row("翻译", chevron = true) { handOff("翻译") }.root)
        l.addRow(Row("导航", chevron = true) { handOff("导航") }.root)
        l.addRow(Row("只打开官方 app", chevron = true) {
            service?.handOffToOfficial(); uiHandler.postDelayed({ if (!OfficialApp.launch(this)) toast("没有找到 Nimo 官方 app") }, 600)
        }.root)
        page.addView(l)
        page.addView(note("眼镜同一时间只能连一个 app，萤读会先断开眼镜再打开官方 app。"))
        page.addView(section("切回萤读"))
        val c = card()
        c.addView(text("1. 点「停止官方 app」，在系统页面点「强行停止」\n2. 回来点「连接眼镜」", 13.5f).apply { setLineSpacing(0f, 1.4f) })
        c.addView(pillRow(pill("停止官方 app", dark = false, small = true) { if (!OfficialApp.openAppSettings(this)) toast("没有找到 Nimo 官方 app") },
            pill("连接眼镜", small = true) { pickDevice() }), lp(top = 12))
        page.addView(c)
        return page
    }

    /** 翻译、导航交给官方 app：萤读先收起画面、断开眼镜，再打开官方 app。 */
    private fun handOff(what: String) {
        if (OfficialApp.packageName(this) == null) { toast("没有找到 Nimo 官方 app"); return }
        AlertDialog.Builder(this)
            .setTitle("用官方 app $what")
            .setMessage("眼镜同一时间只能连一个 app。萤读会先断开眼镜，再打开官方 app。\n\n用完回到萤读：「我的」→「和官方 app 切换」→「停止官方 app」→ 强行停止 → 返回点「连接眼镜」。")
            .setPositiveButton("切换") { _, _ ->
                service?.handOffToOfficial()
                uiHandler.postDelayed({ OfficialApp.launch(this) }, 600)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // =====================================================================
    // 景点介绍
    // =====================================================================

    /** 为了开景点介绍而请求的定位权限（回来后打开开关）。 */
    private var sightsAsked = false

    private fun buildSights(): View {
        val page = vbox()
        val top = listBox()
        val onToggle = Toggle {
            val s = service ?: return@Toggle
            if (s.sightsOn) { s.sightsOn = false; return@Toggle }
            if (!s.hasFineLocation()) {
                sightsAsked = true
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_PERMS)
                return@Toggle
            }
            s.sightsOn = true
        }
        top.addRow(Row("开启景点介绍", "按精确位置找附近 3 公里的景点，在眼镜上弹出介绍").apply { accessory(onToggle.root) }.root)
        val perm = Row("定位权限", chevron = true) {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
        }
        top.addRow(perm.root)
        val everys = listOf(15, 30)
        val everySeg = Seg(listOf("每 15 分钟", "每 30 分钟")) { i -> service?.sightsEveryMin = everys[i]; refreshNow() }
        top.addRow(vbox().apply {
            setPadding(dp(14), dp(10), dp(14), dp(12))
            addView(text("多久查一次", 14f))
            addView(everySeg.root, lp(top = 8))
        })
        page.addView(top)

        page.addView(section("数据来源"))
        val srcBox = listBox()
        val srcs = listOf(SightsNet.SRC_AMAP, SightsNet.SRC_GOOGLE)
        val srcSeg = Seg(listOf("高德地图", "Google 地图")) { i -> service?.sightsSource = srcs[i]; refreshNow() }
        srcBox.addRow(vbox().apply {
            setPadding(dp(14), dp(10), dp(14), dp(12))
            addView(text("找附近的景点", 14f))
            addView(srcSeg.root, lp(top = 8))
        })
        fun editSightKey(title: String, get: (ReaderService) -> String, set: (ReaderService, String) -> Unit, hintText: String) {
            val s = service ?: return
            val f = field(hintText).apply { setText(get(s)) }
            AlertDialog.Builder(this).setTitle(title).setView(vbox().apply { setPadding(dp(20), dp(8), dp(20), 0); addView(f) })
                .setPositiveButton("保存") { _, _ -> set(s, f.text.toString()); refreshNow() }.setNegativeButton("取消", null).show()
        }
        fun maskedKey(k: String) = if (k.isEmpty()) "未填" else "••••" + k.takeLast(4)
        val amapRow = Row("高德 Key", chevron = true) { editSightKey("高德地图 Web 服务 Key", { it.amapKey }, { r, v -> r.amapKey = v }, "高德开放平台的 Web 服务 Key") }
        val googleRow = Row("Google Key", chevron = true) { editSightKey("Google Maps API Key", { it.googleMapsKey }, { r, v -> r.googleMapsKey = v }, "AIza…") }
        srcBox.addRow(amapRow.root)
        srcBox.addRow(googleRow.root)
        val intros = listOf(SightsNet.INTRO_BAIKE, SightsNet.INTRO_WIKI)
        val introSeg = Seg(listOf("百度百科优先", "维基百科优先")) { i -> service?.sightsIntro = intros[i]; refreshNow() }
        srcBox.addRow(vbox().apply {
            setPadding(dp(14), dp(10), dp(14), dp(12))
            addView(text("介绍", 14f))
            addView(introSeg.root, lp(top = 8))
        })
        page.addView(srcBox)
        page.addView(note("要填所选地图的 Key（国内用高德，海外用 Google），申请方法见右上角「说明」。"))

        page.addView(section("现在"))
        val statusBox = listBox()
        val status = Row("还没有查过")
        statusBox.addRow(status.root)
        page.addView(statusBox)
        val nowBtn = pill("现在介绍附近的景点") { service?.sightsCheck(manual = true) }
        page.addView(nowBtn, lp(top = 8, bottom = 4))

        val histSection = section("介绍过的", "清空" to {
            AlertDialog.Builder(this).setMessage("清空介绍记录？之后附近的景点会重新介绍一遍。")
                .setPositiveButton("清空") { _, _ -> service?.clearSightsSeen() }.setNegativeButton("取消", null).show()
        })
        page.addView(histSection)
        val (histBox, hist) = scrollListBox(440)
        page.addView(histBox)

        var histSig = ""
        onUpdate(Sub.SIGHTS) { s ->
            onToggle.set(s.sightsOn)
            perm.value(when {
                s.hasFineLocation() -> "精确位置"
                s.hasLocation() -> "只有大致位置，去改"
                else -> "未授权"
            })
            everySeg.select(everys.indexOf(s.sightsEveryMin).coerceAtLeast(0))
            srcSeg.select(srcs.indexOf(s.sightsSource).coerceAtLeast(0))
            introSeg.select(intros.indexOf(s.sightsIntro).coerceAtLeast(0))
            amapRow.value(maskedKey(s.amapKey))
            googleRow.value(maskedKey(s.googleMapsKey))
            status.titleV.text = s.sightsStatus.ifEmpty { if (s.sightsOn) "等下一次查" else "关着" }
            nowBtn.alpha = if (s.sightsBusy) 0.5f else 1f
            val h = s.sightsHistory
            val sig = h.size.toString() + (h.firstOrNull()?.time ?: 0)
            if (sig != histSig) {
                histSig = sig
                hist.removeAllViews()
                if (h.isEmpty()) hist.addRow(Row("还没有").root)
                val md = java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.US)
                h.forEach { n ->
                    hist.addRow(vbox().apply {
                        setPadding(dp(14), dp(10), dp(14), dp(10))
                        addView(text(n.name, 14f, INK, true))
                        addView(text(md.format(java.util.Date(n.time)) + " · " + n.dist + (if (n.src.isNotEmpty()) " · 来源：${n.src}" else ""), 11.5f, SUB), lp(top = 2))
                        addView(text(n.text, 13f).apply { setLineSpacing(0f, 1.35f) }, lp(top = 4))
                    })
                }
            }
        }
        return page
    }

    // =====================================================================
    // 实时字幕
    // =====================================================================

    private fun buildCaptions(): View {
        val page = vbox()
        val c = card(pad = 10).apply { setPadding(dp(10), dp(10), dp(10), dp(10)) }
        val preview = glassesImage()
        c.addView(preview)
        page.addView(c)
        val state = hint("")
        page.addView(state, lp(bottom = 8))
        page.addView(section("听到的话", "复制全部" to {
            val t = service?.captions?.lines?.filter { it.text.isNotBlank() }?.joinToString("\n") {
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(it.at)) + " " +
                    (if (it.me == true) "我：" else "") + it.text
            } ?: ""
            if (t.isEmpty()) toast("还没有字幕") else {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("字幕", t))
                toast("已复制")
            }
        }, "清空" to { service?.captions?.clear(); refreshNow() }))
        val (boxV, box) = scrollListBox(420)
        page.addView(boxV)
        page.addView(note("用「全天记忆 › 设置」里的转文字服务，说完一句约 1～2 秒出字。眼镜上双击镜腿清空。"))
        var shown = ""
        onUpdate(Sub.CAPTIONS) { s ->
            val k = s.captions
            state.text = when {
                !k.running -> if (s.memGet("asrKey").isEmpty()) "还没设置转文字服务（「全天记忆 › 设置」）" else "没开。点下面的按钮在眼镜上打开"
                k.error.isNotEmpty() -> k.error
                k.hearing -> "● 有人在说话"
                else -> "在听…"
            }
            val sig = k.lines.size.toString() + k.lines.joinToString("") { it.text.length.toString() + it.final + it.me } + k.running + k.hearing + k.error
            if (sig == shown) return@onUpdate
            shown = sig
            preview.setImageBitmap(glassesTint(k.draw(16).bitmap(), 1))
            box.removeAllViews()
            val ls = k.lines.filter { it.text.isNotBlank() }.asReversed()
            if (ls.isEmpty()) box.addRow(Row("还没有字幕").root)
            val hms = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            ls.forEach { l ->
                box.addRow(vbox().apply {
                    setPadding(dp(14), dp(9), dp(14), dp(9))
                    addView(text(hms.format(java.util.Date(l.at)) + (if (l.me == true) " · 我" else "") +
                        if (!l.final) " · 还在说" else "", 11f, SUB))
                    addView(text(l.text, 14.5f, if (l.final) INK else SUB).apply { setLineSpacing(0f, 1.35f) }, lp(top = 3))
                })
            }
        }
        return page
    }

    /** 全天记忆看哪一天（默认今天）。 */
    private var memDay: String? = null
    /** 纠正过说话人就加一，让转写的文字重画。 */
    private var speakerRev = 0

    /**
     * 全天记忆页最上面的日历：日（一周一行）/ 月 两种看法，‹ › 翻页。
     * 每天下面是那天总结的心情表情（有录音还没总结的是个小点），点一天看那天的总结和录音。
     */
    private inner class MemCalendar(private val onPick: (String) -> Unit) {
        private var month = false
        private var anchor = ""
        private var selected = ""
        private var today = ""
        private var info: (String) -> String? = { null }
        private var shownSig = ""
        private val title = text("", 16f, INK, true)
        private val back = text("回今天", 12f, TAN, true).apply { setPadding(dp(10), dp(6), dp(4), dp(6)) }
        private val grid = vbox()
        private val seg = Seg(listOf("日", "月")) { i -> pickMode(i) }
        private fun pickMode(i: Int) { month = i == 1; anchor = selected; seg.select(i); render(true) }
        private fun arrow(t: String, dir: Int) = text(t, 20f, SUB).apply {
            gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(10), dp(2))
            setOnClickListener { anchor = MemoryCalendar.shift(anchor, month, dir); render(true) }
        }
        val root = card().apply {
            setPadding(dp(10), dp(12), dp(10), dp(10))
            addView(hbox().apply {
                addView(arrow("‹", -1))
                addView(title)
                addView(arrow("›", 1))
                addView(back)
                addView(View(this@MainActivity), weighted())
                addView(seg.root, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            addView(hbox().apply {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { addView(text(it, 11.5f, FAINT).apply { gravity = Gravity.CENTER }, weighted()) }
            }, lp(top = 12))
            addView(grid, lp(top = 4))
        }
        init {
            seg.select(0)
            back.setOnClickListener { anchor = today; onPick(today) }
        }

        /** selected：正在看的那天；info(day)：这天的表情（""＝有录音没总结，null＝没有录音）。 */
        fun update(selected: String, today: String, sig: String, info: (String) -> String?) {
            if (anchor.isEmpty() || selected != this.selected) anchor = selected
            this.selected = selected; this.today = today; this.info = info
            render(false, sig)
        }

        private fun render(force: Boolean, sig: String = shownSig.substringAfter('|')) {
            val cells = MemoryCalendar.cells(anchor, month)
            val full = "$anchor/$month/$selected/$today|$sig"
            if (!force && full == shownSig) return
            shownSig = full
            title.text = MemoryCalendar.title(anchor)
            back.visibility = if (today in cells && selected == today) View.GONE else View.VISIBLE
            grid.removeAllViews()
            cells.chunked(7).forEach { week ->
                val row = hbox()
                week.forEach { d -> row.addView(cell(d), weighted()) }
                grid.addView(row, lp(top = 2))
            }
        }

        private fun cell(d: String): View {
            val future = d > today
            val sel = d == selected
            val other = month && !MemoryCalendar.sameMonth(d, anchor)
            val mood = if (future) null else info(d)
            return vbox().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
                if (sel) background = rounded(DARK, 12f)
                addView(text(MemoryCalendar.dayOfMonth(d).toString(), 15f, when {
                    sel -> Color.WHITE
                    future || other -> FAINT
                    d == today -> TAN
                    else -> INK
                }, bold = sel || d == today).apply { gravity = Gravity.CENTER })
                addView(text(when {
                    mood == null -> " "
                    mood.isEmpty() -> "•"
                    else -> mood
                }, if (mood.isNullOrEmpty()) 13f else 15f, if (sel) Color.WHITE else TAN).apply {
                    gravity = Gravity.CENTER; minHeight = dp(20)
                }, lp(top = 4))
                if (!future) setOnClickListener { onPick(d) }
            }
        }
    }

    private fun buildMemory(): View {
        val page = vbox()
        var shownSig = ""
        val calendar = MemCalendar { d ->
            val today = service?.memStore?.dayOf(System.currentTimeMillis())
            memDay = if (d == today) null else d; shownSig = ""; refreshNow()
        }
        page.addView(calendar.root)
        val c = card()
        val status = text("", 15f, INK, true)
        val detail = hint("")
        c.addView(status); c.addView(detail, lp(top = 4))
        page.addView(c)
        val sw = listBox()
        val onToggle = Toggle {
            val s = service ?: return@Toggle
            s.memSetEnabled(!s.memEnabled); refreshNow()
        }
        sw.addRow(Row("开启全天记忆", "连上眼镜后自动开始，只录说话").apply { accessory(onToggle.root) }.root)
        val listenRow = Row("收音方式和耗电", chevron = true) { openSub(Sub.MEMORY_LISTEN) }
        sw.addRow(listenRow.root)
        page.addView(sw)

        // 记忆检索：搜关键词（手机上，马上出结果）；问 AI（用总结的大模型，按材料回答并注明日期时间）
        // 页面顺序：日历、状态和开关，然后检索紧挨着当天的总结和转写（在最下面）
        val later = ArrayList<View>()          // 检索
        later.add(section("检索"))
        var onSearch: () -> Unit = {}
        var onAsk: () -> Unit = {}
        val qRow = hbox().apply { layoutParams = lp(bottom = 8) }
        val qField = field("搜人名、地点、说过的话，或者直接提问").apply {
            setSingleLine(); imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, id, _ -> if (id == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { onSearch(); true } else false }
        }
        qRow.addView(qField, weighted(right = 8))
        qRow.addView(pill("搜索", small = true) { onSearch() })
        qRow.addView(pill("问 AI", dark = false, small = true) { onAsk() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        later.add(qRow)
        val aiCard = card().apply { visibility = View.GONE }
        val aiText = text("", 14f).apply { setLineSpacing(0f, 1.45f); setTextIsSelectable(true) }
        aiCard.addView(aiText)
        later.add(aiCard)
        val searchNote = note("").apply { visibility = View.GONE }
        later.add(searchNote)
        val (hitsBoxV, hitsBox) = scrollListBox(420)
        hitsBoxV.visibility = View.GONE
        later.add(hitsBoxV)

        val daySection = section("今天", "生成总结" to { service?.let { it.memSummarize(memDay ?: it.memStore.dayOf(System.currentTimeMillis())) }; refreshNow() })
        later.forEach { page.addView(it) }
        page.addView(daySection)
        // 总结：最高 320dp，更长的在框里上下滑
        val sumCard = card().apply { background = null; layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        page.addView(InnerScroll(this, dp(320)).apply {
            addView(sumCard); isVerticalScrollBarEnabled = true
            background = rounded(CARD, 16f, 1, CARD_STROKE); clipToOutline = true
        }, lp(bottom = 10))
        // 转写的文字：固定高度的一块，自己上下滑（最新的在最上面）；长按复制
        val linesSection = section("转写的文字")
        page.addView(linesSection)
        val lines = vbox()
        val linesScroll = InnerScroll(this).apply {
            addView(lines); isVerticalScrollBarEnabled = true
            background = rounded(CARD, 16f, 1, CARD_STROKE); clipToOutline = true
        }
        page.addView(linesScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(420)).apply { bottomMargin = dp(10) })
        page.addView(note("只用眼镜麦克风录；录音转好文字就删掉，手机上只留文字。眼镜会录到旁边人的声音，请留意隐私。"))

        // ---- 检索 ----
        fun hideKeyboard() {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(qField.windowToken, 0)
        }
        val timeFmt = java.text.SimpleDateFormat("M月d日 E HH:mm", java.util.Locale.CHINA)
        val dayFmt = java.text.SimpleDateFormat("M月d日 E", java.util.Locale.CHINA)
        fun hitLabel(h: MemorySearch.Hit): String = if (h.group != null) timeFmt.format(java.util.Date(h.time))
            else (runCatching { dayFmt.format(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(h.day)!!) }.getOrDefault(h.day) + " · " + h.label)
        fun openHit(h: MemorySearch.Hit) {
            val s = service ?: return
            val acts = ArrayList<Pair<String, () -> Unit>>()
            acts.add("看那一天" to {
                memDay = if (h.day == s.memStore.dayOf(System.currentTimeMillis())) null else h.day
                shownSig = ""; refreshNow(); scroll.post { scroll.smoothScrollTo(0, daySection.top) }
            })
            acts.add("复制文字" to {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("记忆", h.text))
                toast("已复制")
            })
            AlertDialog.Builder(this).setTitle(hitLabel(h)).setItems(acts.map { it.first }.toTypedArray()) { _, i -> acts[i].second() }.show()
        }
        fun showHits(r: MemorySearch.Result) {
            hitsBox.removeAllViews()
            searchNote.visibility = View.VISIBLE
            searchNote.text = when {
                r.hits.isEmpty() -> "没找到。可以换个说法，或者点「问 AI」。"
                r.fuzzy -> "没有完全一样的，下面是意思相近的 ${r.hits.size} 条（按相似度排）"
                else -> "找到 ${r.hits.size} 条" + if (r.hits.size >= 100) "（只显示最新的 100 条）" else "（新的在前）"
            }
            hitsBoxV.visibility = if (r.hits.isEmpty()) View.GONE else View.VISIBLE
            for (h in r.hits) {
                val (snip, rs) = MemorySearch.snippet(h.text, h.ranges)
                val sp = android.text.SpannableString(snip)
                for (x in rs) {
                    sp.setSpan(android.text.style.ForegroundColorSpan(TAN), x.first, x.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sp.setSpan(android.text.style.StyleSpan(Typeface.BOLD), x.first, x.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                hitsBox.addRow(vbox().apply {
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    addView(text(hitLabel(h), 11.5f, SUB))
                    addView(text("", 13.5f).apply { text = sp; setLineSpacing(0f, 1.35f) }, lp(top = 3))
                    setOnClickListener { openHit(h) }
                })
            }
            hitsBoxV.scrollTo(0, 0)
        }
        onSearch = search@{
            val q = qField.text.toString().trim()
            hideKeyboard()
            if (q.isEmpty()) { hitsBoxV.visibility = View.GONE; searchNote.visibility = View.GONE; aiCard.visibility = View.GONE; return@search }
            val s = service ?: return@search
            searchNote.visibility = View.VISIBLE; searchNote.text = "搜索中…"
            Thread {
                val r = runCatching { s.memSearch(q) }
                runOnUiThread { r.onSuccess { showHits(it) }.onFailure { searchNote.text = "搜索出错：${it.message}" } }
            }.start()
        }
        var asking = false
        onAsk = ask@{
            val q = qField.text.toString().trim()
            if (q.isEmpty()) { toast("先输入想问的，比如：上周三我跟谁约了吃饭？"); return@ask }
            if (asking) return@ask
            val s = service ?: return@ask
            hideKeyboard()
            asking = true
            aiCard.visibility = View.VISIBLE
            aiText.setTextColor(SUB); aiText.text = "正在翻记忆…（用「${s.sumPreset.name}」）"
            s.memAsk(q) { r ->
                asking = false
                r.onSuccess { aiText.setTextColor(INK); aiText.text = it }
                    .onFailure { aiText.setTextColor(SUB); aiText.text = "没问成：${it.message}" }
            }
        }

        onUpdate(Sub.MEMORY) { s ->
            val e = s.memEngine
            val today = s.memStore.dayOf(System.currentTimeMillis())
            val day = memDay ?: today
            onToggle.set(s.memEnabled)
            listenRow.value(s.memMode.zh + if (s.memMode == ListenMode.DUTY) " · 每 ${s.memDutySec} 秒" else "")
            status.text = when {
                !s.memEnabled -> "关着"
                !e.running -> "等眼镜连上后开始"
                e.recording -> "● 正在录"
                s.linkState != LinkState.READY -> "眼镜没连上"
                e.mode == ListenMode.DUTY && e.probing -> "听一下有没有人说话…"
                e.mode == ListenMode.ALWAYS -> "眼镜麦克风开着，在听"
                else -> "等下一次听（每 ${s.memDutySec} 秒）"
            }
            val entries = s.memStore.entries(day)
            val pending = s.memPending
            detail.text = listOfNotNull(
                "今天转好 ${(if (day == today) entries else s.memStore.entries(today)).count { it.text.isNotBlank() }} 句" + (if (pending > 0) "，$pending 句等转文字" else ""),
                if (s.memGet("asrKey").isEmpty()) "还没设置转文字服务（右上角「设置」）" else null,
                if (s.memTranscribing) "正在转文字…" else null,
                if (s.memSummarizing) "正在生成总结…" else null,
                s.memLastError.takeIf { it.isNotEmpty() },
            ).joinToString("\n")
            // 当天：总结 + 文字
            (daySection.getChildAt(0) as TextView).text = (runCatching {
                dayFmt.format(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(day)!!)
            }.getOrDefault(day)) + if (day == today) " · 今天" else ""
            val sum = s.memStore.summary(day)
            val allDays = s.memStore.days()
            val sig = day + entries.size + (sum?.generatedAt ?: 0) + allDays.size + "/" + s.memMergeSec +
                "/" + s.memSpeakers + "/" + s.speakerModel.taught + "/" + speakerRev
            val daySet = allDays.toHashSet()
            calendar.update(day, today, "${allDays.size}/${sum?.generatedAt ?: 0}") { d ->
                if (d !in daySet) null else s.memStore.summary(d)?.mood ?: ""
            }
            if (sig != shownSig) {
                shownSig = sig
                sumCard.removeAllViews()
                if (sum == null) sumCard.addView(hint(if (entries.isEmpty()) "还没有内容" else "还没有总结。每天 %02d:%02d 自动生成，也可以点右上的「生成总结」。".format(s.memSummaryMinute / 60, s.memSummaryMinute % 60)))
                else {
                    if (sum.title.isNotBlank() || sum.mood.isNotBlank()) sumCard.addView(hbox().apply {
                        if (sum.mood.isNotBlank()) addView(text(sum.mood, 26f).apply { setPadding(0, 0, dp(10), 0) })
                        addView(text(sum.title.ifBlank { "这一天" }, 16f, INK, true).apply { setLineSpacing(0f, 1.3f) }, weighted())
                    }, lp(bottom = 10))
                    sumCard.addView(text(sum.overview, 14f).apply { setLineSpacing(0f, 1.4f) })
                    if (sum.todos.isNotEmpty()) {
                        sumCard.addView(text("要做的事", 12f, SUB), lp(top = 12))
                        sum.todos.forEach { t -> sumCard.addView(text("□ " + t.first + if (t.second.isNotBlank()) "（${t.second}）" else "", 13.5f), lp(top = 4)) }
                        sumCard.addView(pillRow(pill("加入待办", dark = false, small = true) { toast("加了 ${s.memAddTodos(sum)} 条") }), lp(top = 8))
                    }
                    if (sum.topics.isNotEmpty()) {
                        sumCard.addView(text("聊了什么", 12f, SUB), lp(top = 12))
                        sum.topics.forEach { sumCard.addView(text("· $it", 13.5f), lp(top = 4)) }
                    }
                    if (sum.notes.isNotEmpty()) {
                        sumCard.addView(text("值得记住", 12f, SUB), lp(top = 12))
                        sum.notes.forEach { sumCard.addView(text("· $it", 13.5f), lp(top = 4)) }
                    }
                    sumCard.addView(hint("由 ${sum.model} 在 " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(sum.generatedAt)) + " 生成"), lp(top = 10))
                }
                lines.removeAllViews()
                val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                val groups = MemoryGroup.group(entries, s.memMergeSec * 1000L).asReversed()
                (linesSection.getChildAt(0) as TextView).text = if (groups.isEmpty()) "转写的文字" else "转写的文字（${groups.size} 段）"
                if (groups.isEmpty()) lines.addRow(Row(if (day == today) "今天还没有转好的文字" else "这天没有文字").root)
                fun dur(ms: Long) = (ms / 1000).let { if (it < 60) "$it 秒" else "${it / 60} 分 ${it % 60} 秒" }
                groups.take(1000).forEach { g ->
                    val from = hm.format(java.util.Date(g.start)); val to = hm.format(java.util.Date(g.end))
                    val time = if (from == to) from else "$from–$to"
                    val meta = text(time + " · " + dur(g.speechMs) + (if (g.entries.size > 1) " · ${g.entries.size} 句" else ""), 11.5f, SUB)
                    val body = g.text
                    fun copy(t: String) {
                        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("转写", t))
                        toast("已复制")
                    }
                    lines.addRow(vbox().apply {
                        setPadding(dp(14), dp(10), dp(14), dp(10))
                        addView(meta)
                        for (l in g.lines(s::memIsMe)) {
                            val tag = when (l.me) { true -> "我  "; false -> "他人  "; null -> "" }
                            val sp = android.text.SpannableString(tag + l.text)
                            if (tag.isNotEmpty()) {
                                sp.setSpan(android.text.style.ForegroundColorSpan(if (l.me == true) TAN else SUB), 0, tag.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                sp.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, tag.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            addView(text("", 14f).apply {
                                text = sp; setLineSpacing(0f, 1.35f)
                                setOnLongClickListener {
                                    val acts = ArrayList<Pair<String, () -> Unit>>()
                                    acts.add("复制这句" to { copy(l.text) })
                                    acts.add("复制整段" to { copy(body) })
                                    if (l.voices.isNotEmpty() && s.memSpeakers) {
                                        acts.add("这句是我说的" to { l.voices.forEach { v -> s.speakerModel.teach(v, true) }; speakerRev++; toast("记住了"); refreshNow() })
                                        acts.add("这句是别人说的" to { l.voices.forEach { v -> s.speakerModel.teach(v, false) }; speakerRev++; toast("记住了"); refreshNow() })
                                    }
                                    AlertDialog.Builder(this@MainActivity).setItems(acts.map { it.first }.toTypedArray()) { _, i -> acts[i].second() }.show()
                                    true
                                }
                            }, lp(top = 3))
                        }
                        setOnLongClickListener { copy(body); true }
                    })
                }
                linesScroll.scrollTo(0, 0)
            }
        }
        return page
    }

    /** 全天记忆：怎么知道有人在说话、耗电对比。 */
    private fun buildMemoryListen(): View {
        val page = vbox()
        page.addView(section("怎么知道有人在说话"))
        val mc = card()
        val modes = ListenMode.values().toList()
        val modeSeg = Seg(modes.map { it.zh }) { i -> service?.memMode = modes[i]; refreshNow() }
        mc.addView(modeSeg.root)
        val modeHint = hint("")
        mc.addView(modeHint, lp(top = 8))
        val dutySecs = listOf(5, 10, 20, 30)
        val dutySeg = Seg(dutySecs.map { "每 $it 秒" }) { i -> service?.memDutySec = dutySecs[i]; refreshNow() }
        mc.addView(dutySeg.root, lp(top = 10))
        page.addView(mc)

        page.addView(section("耗电对比（本次开启以来）"))
        val pc = card()
        val power = text("", 13.5f).apply { setLineSpacing(0f, 1.45f) }
        pc.addView(power)
        page.addView(pc)

        onUpdate(Sub.MEMORY_LISTEN) { s ->
            val e = s.memEngine
            modeSeg.select(modes.indexOf(s.memMode))
            modeHint.text = s.memMode.desc
            dutySeg.root.visibility = if (s.memMode == ListenMode.DUTY) View.VISIBLE else View.GONE
            dutySeg.select(dutySecs.indexOf(s.memDutySec).coerceAtLeast(0))
            // 耗电对比
            if (e.running) {
                val st = e.stats
                val hours = (System.currentTimeMillis() - st.startedWall) / 3_600_000.0
                val mins = ((System.currentTimeMillis() - st.startedWall) / 60_000).toInt()
                fun drop(a: Int, b: Int) = if (a < 0 || b < 0) "" else "$a% → $b%" + if (hours >= 0.25) "（约每小时 %.1f%%）".format((a - b) / hours) else ""
                val onPct = if (mins > 0) e.glassesMicMs() * 100 / (System.currentTimeMillis() - st.startedWall).coerceAtLeast(1) else 0
                power.text = "方式：${e.mode.zh} · 已开 ${mins / 60} 小时 ${mins % 60} 分\n" +
                    "眼镜电量：${drop(st.glassesBatteryStart, s.battery)}\n" +
                    "手机电量：${drop(st.phoneBatteryStart, s.phoneBattery())}\n" +
                    "眼镜麦克风开着的时间：$onPct%\n" +
                    "录到 ${st.segments} 段（共 %.1f 分钟），太短丢掉 ${st.dropped} 段".format(st.speechMs / 60_000.0)
            } else power.text = "开启后显示两边电量掉了多少、眼镜麦克风开着的时间占比。"
        }
        return page
    }

    /** 全天记忆（和实时字幕）用的转文字服务、总结用的大模型。 */
    private fun buildMemoryApi(): View {
        val page = vbox()
        fun editKey(title: String, key: String, secret: Boolean, hintText: String) {
            val s = service ?: return
            val f = field(hintText).apply {
                setText(s.memGet(key)); if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            AlertDialog.Builder(this).setTitle(title).setView(vbox().apply { setPadding(dp(20), dp(8), dp(20), 0); addView(f) })
                .setPositiveButton("保存") { _, _ -> s.memSet(key, f.text.toString()); refreshNow() }.setNegativeButton("取消", null).show()
        }
        fun masked(k: String) = if (k.isEmpty()) "未填" else "••••" + k.takeLast(4)

        page.addView(section("转文字"))
        val asr = listBox()
        val asrPreset = Row("服务", chevron = true) {
            choose("转文字服务", SpeechToText.PRESETS.map { it.name + if (it.note.isNotEmpty()) "（${it.note}）" else "" }) { i ->
                service?.let { it.memSwitchPreset("asr", it.asrPreset.id, SpeechToText.PRESETS[i].id) }; refreshNow()
            }
        }
        val asrKey = Row("API Key", chevron = true) { editKey("转文字的 API Key", "asrKey", true, "sk-…") }
        val asrModel = Row("模型", chevron = true) { editKey("转文字模型", "asrModel", false, service?.asrPreset?.model ?: "") }
        val asrBase = Row("接口地址", chevron = true) { editKey("接口地址（到 /v1 为止）", "asrBase", false, service?.asrPreset?.base ?: "https://…/v1") }
        val langSeg = Seg(listOf("中文", "自动识别语言")) { i -> service?.memSet("asrLang", if (i == 0) "zh" else "auto"); refreshNow() }
        listOf(asrPreset, asrKey, asrModel, asrBase).forEach { asr.addRow(it.root) }
        asr.addRow(FrameLayout(this).apply { setPadding(dp(14), dp(10), dp(14), dp(12)); addView(langSeg.root) })
        page.addView(asr)
        page.addView(note("硅基流动 SenseVoice 免费；各家 Key 怎么申请见右上角「说明」。"))

        page.addView(section("每天的总结"))
        val sum = listBox()
        val sumPreset = Row("服务", chevron = true) {
            choose("总结用的大模型", DaySummarizer.PRESETS.map { it.name }) { i ->
                service?.let { it.memSwitchPreset("sum", it.sumPreset.id, DaySummarizer.PRESETS[i].id) }; refreshNow()
            }
        }
        val sumKey = Row("API Key", chevron = true) { editKey("总结的 API Key", "sumKey", true, "sk-…") }
        val sumModel = Row("模型", chevron = true) { editKey("总结模型", "sumModel", false, service?.sumPreset?.model ?: "") }
        val sumBase = Row("接口地址", chevron = true) { editKey("接口地址", "sumBase", false, service?.sumPreset?.base ?: "") }
        val sumTime = Row("每天几点总结", chevron = true) {
            val s = service ?: return@Row
            android.app.TimePickerDialog(this, { _, h, m -> s.memSummaryMinute = h * 60 + m; refreshNow() }, s.memSummaryMinute / 60, s.memSummaryMinute % 60, true).show()
        }
        listOf(sumPreset, sumKey, sumModel, sumBase, sumTime).forEach { sum.addRow(it.root) }
        page.addView(sum)

        onUpdate(Sub.MEMORY_API) { s ->
            asrPreset.value(s.asrPreset.name); asrKey.value(masked(s.memGet("asrKey")))
            asrModel.value(s.asrModel()); asrBase.value(if (s.memGet("asrBase").isEmpty()) "默认" else s.asrBase())
            langSeg.select(if (s.memGet("asrLang", "zh") == "zh") 0 else 1)
            sumPreset.value(s.sumPreset.name); sumKey.value(masked(s.memGet("sumKey")))
            sumModel.value(s.sumModel()); sumBase.value(if (s.memGet("sumBase").isEmpty()) "默认" else s.sumBase())
            sumTime.value("%02d:%02d".format(s.memSummaryMinute / 60, s.memSummaryMinute % 60))
        }
        return page
    }

    private fun buildMemorySettings(): View {
        val page = vbox()
        page.addView(section("服务"))
        val svc = listBox()
        val asrRow = Row("转文字", chevron = true) { openSub(Sub.MEMORY_API) }
        val sumRow = Row("每天的总结", chevron = true) { openSub(Sub.MEMORY_API) }
        svc.addRow(asrRow.root); svc.addRow(sumRow.root)
        page.addView(svc)

        page.addView(section("录音"))
        val rec = listBox()
        val denoiseToggle = Toggle { service?.let { it.memDenoise = !it.memDenoise }; refreshNow() }
        rec.addRow(Row("转文字前降噪", "压一压底噪，嘈杂的地方转得更准").apply { accessory(denoiseToggle.root) }.root)
        val merges = listOf(20, 60, 180, 0)
        val mergeSeg = Seg(listOf("20 秒", "1 分钟", "3 分钟", "不合并")) { i -> service?.memMergeSec = merges[i]; refreshNow() }
        rec.addRow(vbox().apply {
            setPadding(dp(14), dp(10), dp(14), dp(12))
            addView(text("隔多久以内算同一段对话", 14f))
            addView(hint("隔得比这短的合成一段显示"), lp(top = 3))
            addView(mergeSeg.root, lp(top = 10))
        })
        page.addView(rec)
        page.addView(note("只用眼镜麦克风录。录音只是等着转文字的临时文件，转好就删，手机上只留文字；一天内没转成的（比如没填 Key）也会删掉。"))

        page.addView(section("分辨说话人"))
        val spk = listBox()
        val spkToggle = Toggle { service?.let { it.memSpeakers = !it.memSpeakers }; refreshNow() }
        spk.addRow(Row("分辨自己和别人", "文字前面标「我」「他人」").apply { accessory(spkToggle.root) }.root)
        val spkTaught = Row("已纠正", chevron = true) {
            val s = service ?: return@Row
            AlertDialog.Builder(this).setTitle("清空纠正过的例子？").setMessage("清空后重新按音量自动判断。")
                .setPositiveButton("清空") { _, _ -> s.speakerModel.reset(); refreshNow() }.setNegativeButton("取消", null).show()
        }
        spk.addRow(spkTaught.root)
        page.addView(spk)
        page.addView(note("标错了：在转写的文字上长按那一句纠正，之后会按你纠正的来认。"))

        onUpdate(Sub.MEMORY_SET) { s ->
            asrRow.value(s.asrPreset.name + if (s.memGet("asrKey").isEmpty()) " · 未填 Key" else "")
            sumRow.value(s.sumPreset.name + if (s.memGet("sumKey").isEmpty()) " · 未填 Key" else " · %02d:%02d".format(s.memSummaryMinute / 60, s.memSummaryMinute % 60))
            mergeSeg.select(merges.indexOf(s.memMergeSec).let { if (it < 0) 1 else it })
            denoiseToggle.set(s.memDenoise)
            spkToggle.set(s.memSpeakers)
            spkTaught.value(s.speakerModel.let { if (it.taught == 0) "还没有（按音量自动分）" else "我 ${it.meCount} 句 · 别人 ${it.otherCount} 句" })
        }
        return page
    }

    private fun buildLog(): View {
        val page = vbox()
        page.addView(hbox().apply {
            addView(pill("复制日志", small = true) {
                val t = service?.logLines?.joinToString("\n").orEmpty()
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("萤读日志", t))
                toast("已复制 ${service?.logLines?.size ?: 0} 行")
            })
        }, lp(bottom = 8))
        val c = card()
        val logView = text("", 11f, 0xFF666666.toInt()).apply { typeface = Typeface.MONOSPACE; setLineSpacing(0f, 1.25f); setTextIsSelectable(true) }
        c.addView(logView)
        page.addView(c)
        onUpdate(Sub.LOG) { s -> logView.text = s.logLines.joinToString("\n").ifEmpty { "还没有日志" } }
        return page
    }

    /** 开源许可：用到的开源代码、模型和字体的许可全文（随 app 一起发布，见 assets/licenses、assets/fonts）。 */
    private fun buildLicenses(): View {
        val page = vbox()
        val files = listOf(
            "萤读和用到的开源项目" to "licenses/NOTICE.txt",
            "Apache License 2.0（萤读、MentraOS）" to "licenses/Apache-2.0.txt",
            "SIL OFL 1.1（GNU Unifont）" to "fonts/OFL-Unifont.txt",
            "SIL OFL 1.1（Fusion Pixel Font）" to "fonts/OFL-Fusion-Pixel.txt",
        )
        for ((title, path) in files) {
            page.addView(section(title))
            val body = runCatching { assets.open(path).use { String(it.readBytes(), Charsets.UTF_8) } }.getOrDefault("（读不到 $path）")
            page.addView(card().apply {
                addView(text(body.trim(), 10.5f, SUB).apply { typeface = Typeface.MONOSPACE; setLineSpacing(0f, 1.25f); setTextIsSelectable(true) })
            })
        }
        return page
    }

    // =====================================================================
    // 检查更新：每天一次；有新版就问「跳过 / 立即下载」，下载完交给系统安装器，用户自己点安装
    // =====================================================================

    private val updatePrefs by lazy { getSharedPreferences("update", Context.MODE_PRIVATE) }
    private var updateChecking = false
    /** 等用户在系统设置里允许安装后，再接着装的 APK。 */
    private var pendingApk: java.io.File? = null

    private val autoUpdateCheck: Boolean get() = updatePrefs.getBoolean("auto", true)

    /** manual = 用户点了「检查更新」：不管今天查没查过、跳没跳过这一版，都查，并且告诉结果。 */
    private fun checkUpdate(manual: Boolean) {
        if (updateChecking) return
        val now = System.currentTimeMillis()
        if (!manual && (!autoUpdateCheck || now - updatePrefs.getLong("checkedAt", 0L) < 24 * 3600_000L)) return
        updateChecking = true
        if (manual) toast("正在检查更新…")
        Thread {
            val r = runCatching { Updater.latest() }
            uiHandler.post {
                updateChecking = false
                if (isFinishing || isDestroyed) return@post
                r.onSuccess { rel ->
                    updatePrefs.edit().putLong("checkedAt", now).putString("latest", rel.version).apply()
                    val cur = versionName()
                    when {
                        !Updater.newer(rel.version, cur) -> if (manual) toast("已是最新版本（v$cur）")
                        !manual && updatePrefs.getString("skipped", "") == rel.version -> {}
                        else -> askUpdate(rel)
                    }
                    refreshNow()
                }.onFailure { if (manual) toast("检查更新失败：${it.message}") }
            }
        }.start()
    }

    private fun askUpdate(rel: Updater.Release) {
        AlertDialog.Builder(this)
            .setTitle("发现新版本 v${rel.version}")
            .setMessage("现在是 v${versionName()}。" + (if (rel.notes.isNotEmpty()) "\n\n" + rel.notes else ""))
            .setNegativeButton("跳过") { _, _ -> updatePrefs.edit().putString("skipped", rel.version).apply(); toast("这个版本不再提示，可以在「关于」里手动更新") }
            .setPositiveButton("立即下载") { _, _ -> downloadUpdate(rel) }
            .show()
    }

    private fun downloadUpdate(rel: Updater.Release) {
        val dir = java.io.File(cacheDir, "update").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val apk = java.io.File(dir, rel.apkName)
        val msg = text("正在连接…", 14f, INK)
        var cancelled = false
        val dlg = AlertDialog.Builder(this).setTitle("下载 v${rel.version}")
            .setView(vbox().apply { setPadding(dp(22), dp(10), dp(22), 0); addView(msg) })
            .setNegativeButton("取消") { _, _ -> cancelled = true }
            .setCancelable(false).show()
        var lastPct = -1
        Thread {
            val r = runCatching {
                Updater.download(rel, apk, { got, total ->
                    val pct = if (total > 0) (got * 100 / total).toInt() else -1
                    if (pct != lastPct) { lastPct = pct; uiHandler.post { msg.text = if (pct >= 0) "已下载 $pct%" else "已下载 ${got / 1024} KB" } }
                }, { cancelled })
            }
            uiHandler.post {
                runCatching { dlg.dismiss() }
                r.onSuccess { installUpdate(apk) }
                    .onFailure { if (!cancelled) AlertDialog.Builder(this).setTitle("下载失败").setMessage(it.message ?: "").setPositiveButton("知道了", null).show() }
            }
        }.start()
    }

    /** 交给系统安装器：系统会弹出确认，用户点「安装」才装（不会悄悄装）。没允许过萤读安装应用时先去系统设置里开。 */
    private fun installUpdate(apk: java.io.File) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingApk = apk
            AlertDialog.Builder(this).setTitle("允许萤读安装更新")
                .setMessage("下载好了。安卓要求先允许萤读「安装未知应用」，才能把新版交给系统安装：打开后在设置里允许，再回到萤读就会继续。")
                .setNegativeButton("取消", null)
                .setPositiveButton("去设置") { _, _ ->
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName"))) }
                        .onFailure { toast("打不开设置：${it.message}") }
                }.show()
            return
        }
        pendingApk = null
        runCatching {
            val pi = packageManager.packageInstaller
            val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(packageName)
            // 一定要用户确认（不静默更新）
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("yingdu.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                val i = Intent(this, MainActivity::class.java).setAction(ACTION_INSTALL_STATUS)
                val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0)
                s.commit(android.app.PendingIntent.getActivity(this, 7, i, flags).intentSender)
            }
        }.onFailure { toast("安装失败：${it.message}") }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action != ACTION_INSTALL_STATUS) return
        when (val st = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)) {
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) runCatching { startActivity(confirm) }.onFailure { toast("打不开安装确认：${it.message}") }
            }
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> toast("更新好了")
            android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED -> toast("已取消安装")
            else -> toast("安装没成功（$st）：" + (intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""))
        }
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置回来：允许了就接着装
        pendingApk?.let { if (packageManager.canRequestPackageInstalls() && it.exists()) installUpdate(it) }
    }

    private fun buildAbout(): View {
        val page = vbox()
        val c = card()
        val v = text("", 16f, INK, true)
        c.addView(v)
        c.addView(hint("萤读：取自「囊萤夜读」。Nimo 智能眼镜的第三方伴侣 app，翻译、导航请用 Nimo 官方 app。"), lp(top = 6))
        page.addView(c)
        val legal = listBox().apply { layoutParams = lp(top = 10, bottom = 10) }
        legal.addRow(Row("免责说明", chevron = true) { showLegal("免责说明", Legal.DISCLAIMER) }.root)
        legal.addRow(Row("隐私说明", chevron = true) { showLegal("隐私说明", Legal.PRIVACY) }.root)
        legal.addRow(Row("开源许可", chevron = true) { openSub(Sub.LICENSES) }.root)
        page.addView(legal)
        val up = listBox().apply { layoutParams = lp(bottom = 10) }
        val checkRow = Row("检查更新", chevron = true) { checkUpdate(manual = true) }
        val autoToggle = Toggle { updatePrefs.edit().putBoolean("auto", !autoUpdateCheck).apply(); refreshNow() }
        up.addRow(checkRow.root)
        up.addRow(Row("每天自动检查", "有新版时弹窗问你，选「立即下载」才下载，安装也要你确认") {
            updatePrefs.edit().putBoolean("auto", !autoUpdateCheck).apply(); refreshNow()
        }.apply { accessory(autoToggle.root) }.root)
        page.addView(up)
        page.addView(note("萤读以 Apache License 2.0 开源（github.com/codexmasterme/yingdu）。协议参考开源项目 MentraOS（Apache 2.0）。Opus 编解码用 Concentus（BSD）。" +
            "眼镜点阵字体基于 GNU Unifont 和 Fusion Pixel Font（SIL OFL 1.1）。许可全文见「开源许可」。\n" +
            "数据来源：行情 雅虎财经、腾讯证券、Robinhood、微牛；天气 Open-Meteo（CC BY 4.0）；反向地理编码 BigDataCloud；" +
            "景点 高德地图、Google 地图；景点介绍 百度百科、维基百科（CC BY-SA 4.0）。"))
        onUpdate(Sub.ABOUT) {
            v.text = "萤读 " + versionName().let { if (it.isEmpty()) "" else "v$it" }
            autoToggle.set(autoUpdateCheck)
            val latest = updatePrefs.getString("latest", "").orEmpty()
            checkRow.value(when {
                latest.isEmpty() -> ""
                Updater.newer(latest, versionName()) -> "有新版 v$latest"
                else -> "已是最新"
            })
        }
        return page
    }

    // =====================================================================
    // 小游戏：列表 → 点进去是手柄
    // =====================================================================

    private fun buildGames(): View {
        val page = vbox()
        val grid = vbox()
        page.addView(grid)
        page.addView(note("点一个游戏在眼镜上打开，手机变成手柄（方向键 + A、B）。玩法说明在游戏页右上角。"))
        onUpdate(Sub.GAMES) { s ->
            if (grid.childCount > 0) return@onUpdate
            s.games.chunked(3).forEach { row ->
                grid.addView(hbox().apply {
                    layoutParams = lp(bottom = 8)
                    row.forEachIndexed { i, g ->
                        addView(vbox().apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                            background = rounded(CARD, 14f, 1, CARD_STROKE)
                            setPadding(dp(4), dp(12), dp(4), dp(10))
                            addView(text(GAME_ICONS[g.title] ?: "▣", 20f, TAN).apply { gravity = Gravity.CENTER })
                            addView(text(g.title, 13f, INK, true).apply { gravity = Gravity.CENTER; maxLines = 1 }, lp(top = 6))
                            addView(text(g.intro, 10.5f, SUB).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(top = 3))
                            setOnClickListener { s.openApp(g); openSub(Sub.GAME) }
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            leftMargin = if (i == 0) 0 else dp(4); rightMargin = if (i == 2) 0 else dp(4)
                        })
                    }
                    repeat(3 - row.size) { addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f)) }
                })
            }
        }
        return page
    }

    private fun buildGame(): View {
        val page = vbox()
        val pad = card()
        val info = text("", 13f, SUB).apply { gravity = Gravity.CENTER }
        pad.addView(info)
        fun game(): GameApp? = service?.activeApp as? GameApp
        val up = Tile("▲", "") { game()?.dir(Dir.UP) }
        val left = Tile("◀︎", "") { game()?.dir(Dir.LEFT) }
        val right = Tile("▶︎", "") { game()?.dir(Dir.RIGHT) }
        val down = Tile("▼", "") { game()?.dir(Dir.DOWN) }
        val keyA = Tile("A", "", round = true) { game()?.a() }.also { it.setActive(true) }
        val keyB = Tile("B", "", round = true) { game()?.b() }
        val dpad = vbox().apply {
            addView(hbox().apply { gravity = Gravity.CENTER; addView(up.root) })
            addView(hbox().apply {
                gravity = Gravity.CENTER; layoutParams = lp(top = 4)
                addView(left.root, LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(right.root, LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            addView(hbox().apply { gravity = Gravity.CENTER; layoutParams = lp(top = 4); addView(down.root) })
        }
        val aLabel = text("", 11f, SUB).apply { gravity = Gravity.CENTER }
        val bLabel = text("", 11f, SUB).apply { gravity = Gravity.CENTER }
        val ab = vbox().apply {
            gravity = Gravity.CENTER
            addView(keyA.root); addView(aLabel, lp(top = 4))
            addView(keyB.root, lp(top = 12)); addView(bLabel, lp(top = 4))
        }
        pad.addView(hbox().apply {
            layoutParams = lp(top = 14)
            addView(dpad, weighted())
            addView(ab, LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        // 数字键（数独）
        val digits = vbox().apply { layoutParams = lp(top = 12) }
        for (r in 0 until 2) digits.addView(hbox().apply {
            layoutParams = lp(top = if (r == 0) 0 else 6)
            for (k in 1..5) {
                val n = if (r == 0) k else if (k == 5) 0 else k + 5
                addView(pill(if (n == 0) "清除" else n.toString(), dark = false, small = true) { game()?.number(n) }, weighted(left = 2, right = 2))
            }
        })
        pad.addView(digits)
        page.addView(pad)

        val optTitle = section("")
        page.addView(optTitle)
        val optBox = vbox()
        page.addView(optBox)
        page.addView(pill("重新开始", dark = false) { game()?.restart() }, lp(top = 10))
        val fps = note("")
        page.addView(fps.apply { setPadding(dp(4), dp(10), dp(4), 0) })

        var shownGame: GameApp? = null
        var seg: Seg? = null
        onUpdate(Sub.GAME) { s ->
            val g = s.activeApp as? GameApp
            info.text = g?.info() ?: "游戏已经关闭"
            keyA.root.visibility = if (g?.aLabel != null) View.VISIBLE else View.INVISIBLE
            keyB.root.visibility = if (g?.bLabel != null) View.VISIBLE else View.INVISIBLE
            aLabel.text = g?.aLabel ?: ""; bLabel.text = g?.bLabel ?: ""
            digits.visibility = if (g?.numberPad == true) View.VISIBLE else View.GONE
            if (g !== shownGame) {
                shownGame = g
                optBox.removeAllViews(); seg = null
                if (g != null && g.options.isNotEmpty()) {
                    val sg = Seg(g.options) { i -> g.option = i; refreshNow() }
                    optBox.addView(sg.root)
                    seg = sg
                }
                ((optTitle as LinearLayout).getChildAt(0) as TextView).text = g?.optionTitle ?: ""
                optTitle.visibility = if (seg == null) View.GONE else View.VISIBLE
            }
            g?.let { seg?.select(it.option) }
            fps.text = s.frameIntervalMs()?.let { "眼镜实测：发一帧约 $it 毫秒（每秒最多约 ${1000 / maxOf(1L, it)} 帧）" } ?: ""
        }
        return page
    }

    // =====================================================================
    // 像素表盘
    // =====================================================================

    private fun buildClock(): View {
        val page = vbox()
        val c = card(pad = 10).apply { setPadding(dp(10), dp(10), dp(10), dp(10)) }
        val preview = glassesImage()
        c.addView(preview)
        page.addView(c)
        val seg = Seg(PixelClock.STYLES) { i -> service?.clock?.style = i; refreshNow() }
        page.addView(seg.root, lp(bottom = 10))
        val l = listBox()
        val secToggle = Toggle { service?.clock?.let { it.showSeconds = !it.showSeconds }; refreshNow() }
        l.addRow(Row("显示秒", "数码管、二进制钟可用").apply { accessory(secToggle.root) }.root)
        page.addView(l)
        var shown = ""
        onUpdate(Sub.CLOCK) { s ->
            val k = s.clock
            seg.select(k.style)
            secToggle.set(k.showSeconds, enabled = k.style != PixelClock.FLIP)
            val cal = java.util.Calendar.getInstance()
            val key = "${k.style}${k.showSeconds}" + cal.get(java.util.Calendar.MINUTE) + (if (k.style != PixelClock.FLIP && k.showSeconds) cal.get(java.util.Calendar.SECOND) else "")
            if (key != shown) { shown = key; preview.setImageBitmap(glassesTint(k.preview(), 1)) }
        }
        return page
    }

    // =====================================================================
    // 番茄钟 + 时长设置
    // =====================================================================

    private fun buildPomo(): View {
        val page = vbox()
        val c = card().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(14), dp(18), dp(14), dp(16)) }
        val phase = text("", 12f, SUB).apply { gravity = Gravity.CENTER }
        val big = text("25:00", 52f, INK, true).apply { gravity = Gravity.CENTER; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        val dots = text("", 11f, TAN).apply { gravity = Gravity.CENTER; letterSpacing = 0.3f }
        val info = text("", 12f, SUB).apply { gravity = Gravity.CENTER }
        c.addView(phase); c.addView(big, lp(top = 6)); c.addView(dots, lp(top = 4)); c.addView(info, lp(top = 6))
        val reset = Tile("↺", "重来") { service?.pomodoro?.reset(); refreshNow() }
        val start = Tile("▶︎", "开始") { service?.pomodoro?.startPause(); refreshNow() }
        val skip = Tile("⇥", "跳过") { service?.pomodoro?.skip(); refreshNow() }
        c.addView(hbox().apply {
            gravity = Gravity.CENTER; layoutParams = lp(top = 16)
            listOf(reset, start, skip).forEach { addView(it.root, LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT)) }
        })
        page.addView(c)

        onUpdate(Sub.POMO) { s ->
            val p = s.pomodoro
            val st = p.state
            val set = p.settings
            phase.text = st.phase.zh + when { st.running -> " · 进行中"; PomoLogic.started(st) -> " · 已暂停"; else -> "" }
            big.text = PomoLogic.clock(p.remaining())
            val every = set.longEvery.coerceIn(1, 12)
            val inRound = if (st.phase == PomoPhase.FOCUS) st.done % every else (st.done - 1).mod(every) + 1
            dots.text = "■".repeat(inRound) + "□".repeat(every - inRound)
            info.text = "今天完成 ${st.done} 个 · ${set.focusMin} / ${set.shortMin} / ${set.longMin} 分钟"
            start.setLabel(if (st.running) "暂停" else if (PomoLogic.started(st)) "继续" else "开始")
            start.iconView.text = if (st.running) "❚❚" else "▶︎"
            start.setActive(true)
        }
        return page
    }

    private fun buildPomoSettings(): View {
        val page = vbox()
        page.addView(section("时长"))
        val l = listBox()
        fun row(label: String, unit: String): EditText {
            val f = smallField(56)
            l.addRow(hbox().apply {
                minimumHeight = dp(46)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                addView(text(label, 14f), weighted())
                addView(f); addView(text(unit, 13f, SUB))
            })
            return f
        }
        val focus = row("专注", "分钟"); val short = row("短休息", "分钟"); val long = row("长休息", "分钟"); val every = row("长休息间隔", "个番茄")
        page.addView(l)
        fun fill(p: PomodoroApp) = p.settings.let { focus.setText("${it.focusMin}"); short.setText("${it.shortMin}"); long.setText("${it.longMin}"); every.setText("${it.longEvery}") }
        loaders.add { s -> fill(s.pomodoro) }
        page.addView(pill("保存时长") {
            val p = service?.pomodoro ?: return@pill
            val old = p.settings
            p.settings = PomoSettings(focus.text.toString().toIntOrNull() ?: old.focusMin, short.text.toString().toIntOrNull() ?: old.shortMin,
                long.text.toString().toIntOrNull() ?: old.longMin, every.text.toString().toIntOrNull() ?: old.longEvery)
            fill(p)
            toast("已保存（正在计时的这一段不变）")
        }, lp(bottom = 10))
        val o = listBox()
        val auto = Toggle { service?.pomodoro?.let { it.autoFocus = !it.autoFocus }; refreshNow() }
        o.addRow(Row("休息完自动开始专注").apply { accessory(auto.root) }.root)
        page.addView(o)
        onUpdate(Sub.POMO_SET) { s -> auto.set(s.pomodoro.autoFocus) }
        return page
    }

    // =====================================================================
    // 聚会工具：顶部切换，一次只看一种
    // =====================================================================

    private fun buildParty(): View {
        val page = vbox()
        var shownTool = -1
        val seg = Seg(PartyApp.Tool.values().map { it.zh }) { i ->
            service?.party?.tool = PartyApp.Tool.values()[i]; refreshNow()
        }
        page.addView(seg.root, lp(bottom = 10))

        /** 先在眼镜上打开，再出结果。 */
        fun go(f: (PartyApp) -> Unit) {
            val s = service ?: return
            if (s.linkState != LinkState.READY) toast("眼镜还没连接，结果只会显示在眼镜上")
            if (s.activeApp !== s.party) s.openApp(s.party)
            f(s.party)
            refreshNow()
        }

        val c = card().apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
        c.addView(text("结果只显示在眼镜上", 12f, SUB).apply { gravity = Gravity.CENTER })
        // 骰子
        val dice = vbox()
        val count = Stepper({ service?.party?.let { it.diceCount-- }; refreshNow() }, { service?.party?.let { it.diceCount++ }; refreshNow() })
        dice.addView(hbox().apply {
            gravity = Gravity.CENTER; layoutParams = lp(top = 12, bottom = 12)
            addView(text("骰子个数", 14f)); addView(count.root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
        })
        dice.addView(pill("摇骰子") { go { it.rollDice() } })
        c.addView(dice)
        // 抽签
        val lots = vbox()
        val list = field("每行一支签（名字、任务、奖品……）", multi = true).apply { minLines = 4 }
        loaders.add { s -> list.setText(s.party.lotsText) }
        list.addTextChangedListener(onEdit { t -> service?.party?.let { if (it.lotsText.trim() != t) it.lotsText = t } })
        lots.addView(list, lp(top = 12))
        val left = text("", 12f, SUB)
        lots.addView(left, lp(top = 6, bottom = 10))
        lots.addView(pillRow(pill("抽一支") { go { it.drawLot() } }, pill("全部放回", dark = false) { service?.party?.resetLots(); refreshNow() }))
        c.addView(lots)
        // 真心话大冒险
        val truth = vbox()
        truth.addView(pillRow(pill("真心话") { go { it.truthOrDare(false) } }, pill("大冒险") { go { it.truthOrDare(true) } }), lp(top = 12))
        truth.addView(pill("随机", dark = false) { go { it.truthOrDare(null) } }, lp(top = 8))
        c.addView(truth)
        val status = text("", 12f, SUB).apply { gravity = Gravity.CENTER }
        c.addView(status, lp(top = 10))
        page.addView(c)
        page.addView(note("抽过的签不会再抽到，抽完自动放回。真心话、大冒险各 30 题，一轮之内不重复。"))

        onUpdate(Sub.PARTY) { s ->
            val p = s.party
            if (p.tool.ordinal != shownTool) {
                shownTool = p.tool.ordinal
                seg.select(shownTool)
                dice.visibility = if (p.tool == PartyApp.Tool.DICE) View.VISIBLE else View.GONE
                lots.visibility = if (p.tool == PartyApp.Tool.LOTS) View.VISIBLE else View.GONE
                truth.visibility = if (p.tool == PartyApp.Tool.TRUTH) View.VISIBLE else View.GONE
            }
            count.value.text = "${p.diceCount} 个"
            left.text = "签筒 ${p.lotsTotal} 支，还剩 ${p.lotsLeft} 支"
            status.text = if (p.count == 0) "还没开始" else "已经在眼镜上出了 ${p.count} 次结果"
        }
        return page
    }

    // =====================================================================
    // 股票（搜索 + 自选列表）
    // =====================================================================

    private fun buildStocks(): View {
        val page = vbox()
        val search = hbox().apply {
            background = rounded(CARD, 22f, 1, CARD_STROKE)
            setPadding(dp(14), dp(4), dp(4), dp(4))
            layoutParams = lp(bottom = 6)
        }
        val q = EditText(this).apply {
            hint = "搜索股票代码或名称（A 股可搜中文名、拼音）"; textSize = 14f; setSingleLine(); background = null
            setTextColor(INK); setHintTextColor(0xFFC9B8A6.toInt())
        }
        search.addView(text("⌕", 17f, SUB))
        search.addView(q, weighted(left = 6))
        val results = vbox()
        search.addView(pill("搜索", small = true) { doSearch(q.text.toString(), results) })
        page.addView(search)
        page.addView(results)

        page.addView(section("自选（勾选的才在看板上显示）"))
        val (listBoxV, list) = scrollListBox(400)
        page.addView(listBoxV)
        page.addView(note("取消勾选只是暂时不显示；美股含盘前、盘后、夜盘，日股、港股约延迟 15 分钟。"))

        onUpdate(Sub.STOCKS) { s ->
            val items = s.stocks.items
            val bySym = s.quotes.associateBy { it.symbol }
            list.removeAllViews()
            if (items.isEmpty()) list.addRow(Row("还没有自选股", "在上面搜索添加").root)
            items.forEach { it0 ->
                val q0 = bySym[it0.symbol]
                val price = q0?.price?.takeIf { it0.on }?.let { p -> "  " + (if (!q0.us) "" else q0.session?.let { "🌙 $it " } ?: "☀️ ") + String.format(java.util.Locale.US, "%.2f", p) +
                    (q0.changePct?.let { c -> String.format(java.util.Locale.US, "  %+.2f%%", c) } ?: "") } ?: ""
                val row = Row(it0.name, it0.symbol + price + (if (it0.market.isNotEmpty()) "  ·  ${it0.market}" else "") + if (it0.on) "" else "  ·  不显示")
                if (!it0.on) row.titleV.setTextColor(SUB)
                row.root.addView(check(it0.on) { s.stocks.setOn(it0.symbol, !it0.on); s.stocksChanged() }.apply {
                    (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(12)
                }, 0)
                row.accessory(hbox().apply {
                    addView(smallButton("↑") { s.stocks.move(it0.symbol, -1); s.stocksChanged() })
                    addView(smallButton("↓") { s.stocks.move(it0.symbol, 1); s.stocksChanged() })
                    addView(smallButton("✕") { s.stocks.remove(it0.symbol); s.stocksChanged(); toast("已移除 ${it0.symbol}") })
                })
                list.addRow(row.root)
            }
        }
        return page
    }

    private fun doSearch(q: String, results: LinearLayout) {
        if (q.isBlank()) return
        results.removeAllViews()
        results.addView(note("搜索中…"))
        Thread {
            val r = runCatching { StockStore.search(q.trim()) }
            runOnUiThread {
                results.removeAllViews()
                val list = r.getOrNull()
                if (list == null) { results.addView(note("搜索失败：${r.exceptionOrNull()?.message}")); return@runOnUiThread }
                if (list.isEmpty()) { results.addView(note("没有结果。可以试试英文名或代码（如 GOOG、7203.T、600519）。")); return@runOnUiThread }
                results.addView(section("搜索结果"))
                val (boxV, box) = scrollListBox(320)
                val mine = service?.stocks?.symbols().orEmpty().toSet()
                list.forEach { item ->
                    val row = Row(item.name, item.symbol + if (item.market.isNotEmpty()) "  ·  ${item.market}" else "")
                    row.accessory(check(item.symbol in mine) {
                        val s = service ?: return@check
                        if (item.symbol in s.stocks.symbols()) s.stocks.remove(item.symbol) else s.stocks.add(item)
                        s.stocksChanged(); doSearch(q, results)
                    })
                    box.addRow(row.root)
                }
                results.addView(boxV)
            }
        }.start()
    }

    // =====================================================================
    // 日程待办
    // =====================================================================

    private fun buildAgenda(): View {
        val page = vbox()
        page.addView(section("日程（今天和明天，来自手机日历）"))
        val (eventsBox, events) = scrollListBox(300)
        page.addView(eventsBox)
        val calBtn = pill("授权读取日历", dark = false) { if (need(Manifest.permission.READ_CALENDAR)) service?.refreshDashboard() }
        page.addView(calBtn, lp(bottom = 10))

        page.addView(section("待办", "清除已完成" to { val s = service; if (s != null) { s.todos.clearDone(); s.todosChanged() } }))
        val add = hbox().apply { layoutParams = lp(bottom = 8) }
        val input = field("添加一条待办")
        add.addView(input, weighted(right = 8))
        add.addView(pill("添加", small = true) { val s = service ?: return@pill; s.todos.add(input.text.toString()); input.setText(""); s.todosChanged() })
        page.addView(add)
        val (listBoxV, list) = scrollListBox(400)
        page.addView(listBoxV)

        onUpdate(Sub.AGENDA) { s ->
            val fmt = java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.US)
            events.removeAllViews()
            if (!CalendarReader.hasPermission(this)) events.addRow(Row("还没有授权读取日历").root)
            else if (s.calendarEvents.isEmpty()) events.addRow(Row("今天和明天没有日程").root)
            s.calendarEvents.forEach { e -> events.addRow(Row(e.title).apply { value(if (e.allDay) "全天" else fmt.format(java.util.Date(e.start))) }.root) }
            calBtn.visibility = if (CalendarReader.hasPermission(this)) View.GONE else View.VISIBLE
            list.removeAllViews()
            s.todos.items.forEachIndexed { i, t ->
                list.addRow(hbox().apply {
                    minimumHeight = dp(44)
                    setPadding(dp(14), dp(6), dp(8), dp(6))
                    addView(check(t.done) { s.todos.toggle(i); s.todosChanged() })
                    addView(text(t.text, 14f, if (t.done) 0xFFAAAAAA.toInt() else INK).apply {
                        if (t.done) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    }, weighted(left = 10))
                    addView(smallButton("✕") { s.todos.remove(i); s.todosChanged() })
                })
            }
            if (s.todos.items.isEmpty()) list.addRow(Row("还没有待办").root)
        }
        return page
    }

    // =====================================================================
    // 通知转发
    // =====================================================================

    private fun buildNotify(): View {
        val page = vbox()
        val top = listBox()
        val onToggle = Toggle { NotifyPrefs.setEnabled(this, !NotifyPrefs.enabled(this)); onReaderChanged() }
        top.addRow(Row("眼镜上弹出通知", "选中的 app 收到新消息时弹出").apply { accessory(onToggle.root) }.root)
        val perm = Row("通知使用权", chevron = true) {
            // 授权了但系统没连上（更新萤读后常见）：点这里强制重新连接；没授权就去系统设置
            if (PhoneNotificationService.isEnabled(this) && !PhoneNotificationService.connected) {
                PhoneNotificationService.rebindIfNeeded(this, "手动重连", force = true)
                toast("正在重新连接通知服务…几秒后再看")
                scroll.postDelayed({ refreshNow() }, 4_000)
            } else PhoneNotificationService.openSettings(this)
        }
        top.addRow(perm.root)
        val callToggle = Toggle { NotifyPrefs.setCallAlert(this, !NotifyPrefs.callAlert(this)); refreshNow() }
        top.addRow(Row("来电提醒", "电话、微信等来电时弹出来电人").apply { accessory(callToggle.root) }.root)
        top.addRow(Row("发送一条测试通知", chevron = true) {
            service?.onPhoneNotification("萤读", "测试", "这是一条测试通知，看到就说明通知弹窗可以用了。")
        }.root)
        page.addView(top)

        page.addView(section("哪些 app、什么时候转发"))
        page.addView(note("每个 app 选一个时段；再点一下已选的就是不转发。"))
        val filter = field("搜索 app")
        page.addView(filter, lp(bottom = 8))
        // app 列表：最高 440dp，在框里上下滑（手机上装的 app 很多）
        val (listBoxV, list) = scrollListBox(440)
        page.addView(listBoxV)

        var all: List<Pair<String, String>>? = null     // (包名, 名称)，后台加载一次
        var shownKey = ""
        fun rebuild() {
            val apps0 = all ?: return
            val slots = NotifyPrefs.slots(this)
            val allowed = slots.keys
            val q = filter.text.toString().trim()
            val key = q + "|" + slots.hashCode() + "|" + apps0.size
            if (key == shownKey) return
            shownKey = key
            val rows = apps0.filter { q.isEmpty() || it.second.contains(q, true) || it.first.contains(q, true) }
                .sortedWith(Comparator { a, b ->   // 已勾选的排前面，然后按名称
                    val x = (a.first !in allowed).compareTo(b.first !in allowed)
                    if (x != 0) x else a.second.compareTo(b.second)
                })
            list.removeAllViews()
            rows.take(80).forEach { (pkg, label) ->
                val mask = slots[pkg] ?: 0
                val chips = hbox()
                listOf(NotifySlots.ALL to "全天", NotifySlots.WORK to "工作", NotifySlots.OFF to "休息").forEach { (bit, t) ->
                    chips.addView(chip(t, mask == bit) { NotifyPrefs.setSlot(this, pkg, NotifySlots.pick(NotifyPrefs.slotOf(this, pkg), bit)); rebuild() },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(4) })
                }
                list.addRow(Row(label).apply { if (mask == 0) titleV.setTextColor(SUB); accessory(chips) }.root)
            }
            if (rows.size > 80) list.addRow(Row("还有 ${rows.size - 80} 个，用上面的搜索框找").root)
            if (rows.isEmpty()) list.addRow(Row("没有找到").root)
        }
        filter.addTextChangedListener(onEdit { rebuild() })
        onUpdate(Sub.NOTIFY) {
            onToggle.set(NotifyPrefs.enabled(this))
            callToggle.set(NotifyPrefs.callAlert(this))
            perm.value(when {
                !PhoneNotificationService.isEnabled(this) -> "去授权"
                PhoneNotificationService.connected -> "已连接"
                else -> "已授权但没连上，点这里重连"
            })
            if (all == null) {
                list.removeAllViews(); list.addRow(Row("正在读取 app 列表…").root)
                all = emptyList()
                Thread {
                    val pm = packageManager
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val found = pm.queryIntentActivities(intent, 0)
                        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                        .filter { it.first != packageName }
                        .distinctBy { it.first }
                    runOnUiThread { all = found; shownKey = ""; rebuild() }
                }.start()
            } else rebuild()
        }
        return page
    }

    // ---------- 选择设备 ----------

    @SuppressLint("MissingPermission")
    private fun pickDevice() {
        if (!hasBtPermission()) { requestPermissionsIfNeeded(); return }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "请先打开蓝牙", Toast.LENGTH_SHORT).show()
            return
        }
        val bonded = adapter.bondedDevices.orEmpty().toList()
        val nimo = bonded.filter { NimoClient.isNimoName(it.name) }
        if (nimo.size == 1) { startConnect(nimo[0]); return }
        // 找不到或有多台时，列出全部已配对设备让你自己选（Nimo 排在前面）
        val list: List<BluetoothDevice> = nimo + (bonded - nimo.toSet())
        if (list.isEmpty()) {
            Toast.makeText(this, "没有已配对的设备，请先用原厂 app 配对眼镜", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("选择眼镜")
            .setItems(list.map { "${it.name ?: "未知"}  ${it.address}" }.toTypedArray()) { _, i -> startConnect(list[i]) }
            .show()
    }

    private fun startConnect(device: BluetoothDevice) {
        Toast.makeText(this, "连接前请先在系统设置里强行停止原厂 app", Toast.LENGTH_LONG).show()
        service?.connect(device)
    }

    // ---------- 打开文件 / 目录 ----------

    private fun openFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            // MOBI/AZW3 在很多手机上没有登记类型，所以放开到所有文件，由 app 自己判断格式
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/epub+zip",
                "application/x-mobipocket-ebook", "application/vnd.amazon.ebook", "application/octet-stream", "*/*"))
        }
        startActivityForResult(intent, REQ_OPEN)
    }

    @Deprecated("Activity 基类的旧 API，足够用")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_OPEN || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        // 持久授权，下次启动能自动打开上次的书
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        service?.openBook(uri)
    }

    private fun showChapters() {
        val s = service ?: return
        val b = s.book ?: run { toast("还没有打开书"); return }
        if (b.chapters.isEmpty()) {
            Toast.makeText(this, "没有识别到章节标题", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("目录")
            .setItems(b.chapters.map { it.title }.toTypedArray()) { _, i -> s.goToOffset(b.chapters[i].offset) }
            .show()
    }

    // ---------- 权限 ----------

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val s = service ?: return
        if (Manifest.permission.ACCESS_COARSE_LOCATION in permissions &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (!sightsAsked) s.autoLocate = true
            s.onLocationPermission()
        }
        if (sightsAsked) { sightsAsked = false; if (s.hasFineLocation()) s.sightsOn = true else toast("景点介绍需要「精确位置」，请在系统设置里把萤读的位置权限改成精确") }
        if (StepsProvider.PERM_HEALTH in permissions || StepsProvider.PERM_SENSOR in permissions) s.steps.refresh()
        if (Manifest.permission.READ_CALENDAR in permissions) s.refreshDashboard()
        onReaderChanged()
    }

    private fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQ_PERMS)
    }
}

/**
 * 页面里的一块可以自己上下滑的区域（放在整页的 ScrollView 里）。
 * 手指在这块上时由它来滑；滑到顶或到底还继续往那个方向拖，就交还给外面的整页。
 */
class InnerScroll(ctx: android.content.Context, private val maxHeightPx: Int = 0) : ScrollView(ctx) {
    private var lastY = 0f
    /** 给了最高高度时：内容少就跟着变矮，内容多就停在这个高度、在框里滑。 */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (maxHeightPx > 0) super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST))
        else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) { lastY = ev.y; parent?.requestDisallowInterceptTouchEvent(true) }
        return super.onInterceptTouchEvent(ev)
    }
    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { lastY = ev.y; parent?.requestDisallowInterceptTouchEvent(true) }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - lastY; lastY = ev.y
                val atEdge = (dy > 0 && !canScrollVertically(-1)) || (dy < 0 && !canScrollVertically(1))
                parent?.requestDisallowInterceptTouchEvent(!atEdge)
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.onTouchEvent(ev)
    }
}

