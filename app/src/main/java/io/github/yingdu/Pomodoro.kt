package io.github.yingdu

import android.content.Context
import android.os.PowerManager

/*
 * 番茄钟：专注 → 短休息 → 专注 …… 每 4 个番茄一次长休息。
 * 规则（PomoLogic）不依赖 Android，方便单元测试；PomodoroApp 负责计时、眼镜画面和时间到的提醒。
 */

enum class PomoPhase(val zh: String) { FOCUS("专注"), SHORT("短休息"), LONG("长休息") }

data class PomoSettings(val focusMin: Int = 25, val shortMin: Int = 5, val longMin: Int = 15, val longEvery: Int = 4)

/**
 * @param endAt 正在计时：到点的时刻（墙上时间）
 * @param leftMs 暂停中：还剩多少毫秒；-1 = 这一段还没开始
 * @param done 已经完成的专注段数
 */
data class PomoState(val phase: PomoPhase = PomoPhase.FOCUS, val running: Boolean = false, val endAt: Long = 0,
                     val leftMs: Long = -1, val done: Int = 0)

object PomoLogic {
    fun duration(p: PomoPhase, set: PomoSettings): Long = 60_000L * when (p) {
        PomoPhase.FOCUS -> set.focusMin; PomoPhase.SHORT -> set.shortMin; PomoPhase.LONG -> set.longMin
    }.coerceAtLeast(1)

    fun remaining(s: PomoState, set: PomoSettings, now: Long): Long = when {
        s.running -> (s.endAt - now).coerceAtLeast(0)
        s.leftMs >= 0 -> s.leftMs
        else -> duration(s.phase, set)
    }

    /** 已经过去的比例 0..1。 */
    fun progress(s: PomoState, set: PomoSettings, now: Long): Double =
        1.0 - remaining(s, set, now).toDouble() / duration(s.phase, set)

    fun started(s: PomoState) = s.running || s.leftMs >= 0

    fun start(s: PomoState, set: PomoSettings, now: Long) =
        if (s.running) s else s.copy(running = true, endAt = now + remaining(s, set, now), leftMs = -1)

    fun pause(s: PomoState, set: PomoSettings, now: Long) =
        if (!s.running) s else s.copy(running = false, leftMs = remaining(s, set, now))

    /** 这一段从头来（不计时）。 */
    fun reset(s: PomoState) = s.copy(running = false, leftMs = -1)

    /** 到点（或者跳过）：进入下一段，先不计时。专注完成后每 longEvery 个一次长休息。 */
    fun next(s: PomoState, set: PomoSettings): PomoState = when (s.phase) {
        PomoPhase.FOCUS -> {
            val d = s.done + 1
            PomoState(if (d % set.longEvery.coerceAtLeast(1) == 0) PomoPhase.LONG else PomoPhase.SHORT, done = d)
        }
        else -> PomoState(PomoPhase.FOCUS, done = s.done)
    }

    /** 到点时眼镜上弹出的通知。 */
    fun alert(finished: PomoPhase, next: PomoPhase, set: PomoSettings): Pair<String, String> = when (finished) {
        PomoPhase.FOCUS -> "番茄钟 · 专注结束" to "休息 ${if (next == PomoPhase.LONG) set.longMin else set.shortMin} 分钟吧"
        else -> "番茄钟 · 休息结束" to "开始下一个 ${set.focusMin} 分钟专注"
    }

    fun clock(ms: Long): String {
        val t = (ms + 999) / 1000
        return String.format(java.util.Locale.US, "%02d:%02d", t / 60, t % 60)
    }
}

class PomodoroApp(private val host: AppHost) : GlassesApp {
    private val prefs = host.context.getSharedPreferences("pomodoro", Context.MODE_PRIVATE)

    override val title = "番茄钟"

    var settings: PomoSettings
        get() = PomoSettings(prefs.getInt("focus", 25), prefs.getInt("short", 5), prefs.getInt("long", 15), prefs.getInt("every", 4))
        set(v) {
            val before = settings
            prefs.edit().putInt("focus", v.focusMin.coerceIn(1, 180)).putInt("short", v.shortMin.coerceIn(1, 60))
                .putInt("long", v.longMin.coerceIn(1, 120)).putInt("every", v.longEvery.coerceIn(1, 12)).apply()
            // 还没开始的一段按新时长来；正在计时的保持不变
            if (before != settings && !PomoLogic.started(state)) show()
        }

    /** 休息结束后自动开始下一个专注（专注结束后总是先停下来，等你点开始休息）。 */
    var autoFocus: Boolean
        get() = prefs.getBoolean("autoFocus", false)
        set(v) { prefs.edit().putBoolean("autoFocus", v).apply(); host.changed() }

    var state: PomoState
        get() = PomoState(runCatching { PomoPhase.valueOf(prefs.getString("phase", "FOCUS")!!) }.getOrDefault(PomoPhase.FOCUS),
            prefs.getBoolean("running", false), prefs.getLong("endAt", 0), prefs.getLong("left", -1), doneToday())
        private set(v) {
            prefs.edit().putString("phase", v.phase.name).putBoolean("running", v.running).putLong("endAt", v.endAt)
                .putLong("left", v.leftMs).putInt("done", v.done).putLong("doneDay", today()).apply()
        }

    /** 完成的番茄数每天从 0 开始。 */
    private fun today() = (System.currentTimeMillis() + java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())) / 86_400_000L
    private fun doneToday() = if (prefs.getLong("doneDay", 0) == today()) prefs.getInt("done", 0) else 0

    fun remaining() = PomoLogic.remaining(state, settings, System.currentTimeMillis())

    // ---------- 手机上的操作 ----------

    fun startPause() {
        val now = System.currentTimeMillis()
        val s = state
        state = if (s.running) PomoLogic.pause(s, settings, now) else PomoLogic.start(s, settings, now)
        schedule(); show()
    }

    fun reset() { state = PomoLogic.reset(state); schedule(); show() }

    /** 跳过这一段（不弹提醒）。 */
    fun skip() { state = PomoLogic.next(state, settings); schedule(); show() }

    /** 服务启动时：接着上次的计时（手机重启、app 被杀之后）。 */
    fun restore() { schedule() }

    // ---------- 计时 ----------

    private var wake: PowerManager.WakeLock? = null

    /** 到点的提醒：手机休眠时 Handler 会停，所以计时期间拿一个到点就放的唤醒锁。 */
    private fun schedule() {
        host.main.removeCallbacks(due)
        wake?.let { if (it.isHeld) it.release() }; wake = null
        val s = state
        if (!s.running) return
        val left = (s.endAt - System.currentTimeMillis()).coerceAtLeast(0)
        host.main.postDelayed(due, left)
        runCatching {
            val pm = host.context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yingdu:pomodoro").apply { acquire(left + 60_000) }
        }
    }

    private val due = Runnable {
        val s = state
        if (!s.running) return@Runnable
        if (System.currentTimeMillis() < s.endAt - 50) { schedule(); return@Runnable }
        val set = settings
        var n = PomoLogic.next(s, set)
        if (autoFocus && n.phase == PomoPhase.FOCUS) n = PomoLogic.start(n, set, System.currentTimeMillis())
        state = n
        val (t, body) = PomoLogic.alert(s.phase, n.phase, set)
        host.popup(t, body)
        host.log(t)
        bounce = BOUNCE.size
        schedule(); show()
    }

    // ---------- 眼镜画面 ----------

    private var open = false
    /** 时间到了弹一下：进度环放大缩小几帧。 */
    private var bounce = 0

    private fun show() { if (open) { host.main.removeCallbacks(loop); host.main.post(loop) }; host.changed() }

    /** 打开时每秒刷新一次（弹跳时快一点）；眼镜刷新慢时跟着放慢。 */
    private val loop = object : Runnable {
        override fun run() {
            host.redraw(this@PomodoroApp)
            if (bounce > 0) bounce--
            val cost = host.frameIntervalMs() ?: 0
            val now = System.currentTimeMillis()
            val wait = if (bounce > 0) 250L else if (state.running) 1000 - (state.endAt - now).mod(1000L) + 20 else 60_000L
            host.main.postDelayed(this, maxOf(wait, cost + 50))
        }
    }

    override fun onOpen() { open = true; host.main.removeCallbacks(loop); host.main.post(loop) }
    override fun onClose() { open = false; bounce = 0; host.main.removeCallbacks(loop) }

    override fun onInput(input: GlassesInput) {
        when (input) {
            GlassesInput.CLICK_RIGHT -> startPause()
            GlassesInput.DOUBLE_RIGHT -> skip()
            GlassesInput.CLICK_LEFT -> reset()
            else -> {}
        }
    }

    override fun status(): String {
        val s = state
        return "$title · ${s.phase.zh} " + PomoLogic.clock(remaining()) + if (!s.running && PomoLogic.started(s)) "（暂停）" else ""
    }

    override fun render(fontPx: Int): ByteArray {
        val f = Frame(fontPx)
        val s = state
        val set = settings
        val now = System.currentTimeMillis()
        val left = PomoLogic.remaining(s, set, now)
        val prog = PomoLogic.progress(s, set, now)

        // 左边：60 格的像素进度环（剩下的全亮，走过的微亮），中间是剩余时间
        val cx = 104; val cy = 86
        val pulse = if (bounce > 0) BOUNCE[BOUNCE.size - bounce] else 0
        val r = 70 + pulse
        val cell = 6
        val n = 60
        val lit = Math.ceil((1 - prog) * n - 1e-9).toInt().coerceIn(0, n)
        PixelClock.ring(n, cx, cy, r).forEachIndexed { i, (x, y) ->
            // 从正上方开始顺时针：走过的在前面
            val level = if (i >= n - lit) PixelClock.FULL else PixelClock.FAINT
            f.fill(x - cell / 2, y - cell / 2, cell, cell, if (bounce > 0 && bounce % 2 == 1) PixelClock.FULL else level)
        }
        val u = 3
        val t = PomoLogic.clock(left)
        // MM:SS 用小号数码管（不画不亮的段，免得在环里太花）
        val tw = 4 * 6 * u + 2 * u + 6 * u + 2 * u
        var x = cx - tw / 2
        val y = cy - 11 * u / 2
        for ((i, ch) in t.withIndex()) {
            if (ch == ':') {
                f.fill(x + 2 * u, y + 3 * u, u + 1, u + 1, PixelClock.FULL); f.fill(x + 2 * u, y + 7 * u, u + 1, u + 1, PixelClock.FULL)
                x += 6 * u; continue
            }
            PixelClock.segmentDigit(ch - '0', x, y, u, f.fill, ghost = false)
            x += 6 * u + if (i == 0 || i == 3) 2 * u else 0
        }
        if (bounce > 0) f.text("时间到", cx, cy + 22, Frame.FULL, f.small, Frame.CENTER)

        // 右边：现在是哪一段、第几个番茄、状态
        val rx = 214
        f.text(s.phase.zh, rx, 22, Frame.FULL, GlassesFonts.text(maxOf(24, fontPx).coerceAtMost(32)))
        val st = when {
            s.running -> "进行中"
            PomoLogic.started(s) -> "已暂停"
            else -> "等你开始"
        }
        f.text(st, rx, 62, Frame.DIM, f.small)
        // 这一轮的番茄：完成的实心，没完成的空心
        val every = set.longEvery.coerceAtLeast(1)
        val inRound = if (s.phase == PomoPhase.FOCUS) s.done % every else (s.done - 1).mod(every) + 1
        for (i in 0 until every.coerceAtMost(12)) {
            val bx = rx + i * 18
            if (i < inRound) f.rect(bx, 92, 12, 12, Frame.FULL) else f.box(bx, 92, 12, 12, Frame.DIM)
        }
        f.text("今天完成 ${s.done} 个", rx, 114, Frame.DIM, f.small)
        f.text("右键 开始/暂停 · 左键 重来", rx, f.h - 22, Frame.FAINT, f.small)
        return f.done()
    }

    companion object {
        /** 弹一下：环的半径依次变化几个像素。 */
        private val BOUNCE = intArrayOf(8, -4, 6, -2, 3, 0)
    }
}
