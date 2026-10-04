package io.github.yingdu

import android.content.Context
import android.graphics.Bitmap
import java.util.Calendar

/**
 * 像素表盘（全屏）：翻页钟、数码管、二进制钟。数码管和二进制钟可以显示秒，每秒刷新一次；
 * 翻页钟一分钟翻一次，翻的那一刻先发一帧「翻到一半」（上半张新数字、下半张旧数字）。
 * 看板里也有同样的表盘卡片（只到分钟），见 DashboardImage。
 */
class ClockApp(private val host: AppHost) : GlassesApp {
    private val prefs = host.context.getSharedPreferences("clock", Context.MODE_PRIVATE)

    override val title = "像素表盘"

    var style: Int
        get() = prefs.getInt("style", PixelClock.FLIP).coerceIn(0, PixelClock.STYLES.size - 1)
        set(v) { prefs.edit().putInt("style", v).apply(); flipFrom = null; show() }

    /** 数码管、二进制钟显示秒（翻页钟不显示秒）。 */
    var showSeconds: Boolean
        get() = prefs.getBoolean("seconds", true)
        set(v) { prefs.edit().putBoolean("seconds", v).apply(); show() }

    private fun secondsOn() = style != PixelClock.FLIP && showSeconds

    /** 翻页钟正在翻：翻之前的「HHMM」。 */
    private var flipFrom: String? = null
    private var lastHM = ""

    private var open = false

    private fun show() { if (open) { host.main.removeCallbacks(loop); tick() }; host.changed() }

    private val loop = Runnable { tick() }

    override fun onOpen() { open = true; lastHM = ""; flipFrom = null; host.main.removeCallbacks(loop); host.main.post(loop) }

    override fun onClose() { open = false; host.main.removeCallbacks(loop); flipFrom = null }

    private fun tick() {
        val c = Calendar.getInstance()
        val hm = String.format(java.util.Locale.US, "%02d%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        if (style == PixelClock.FLIP && lastHM.isNotEmpty() && hm != lastHM && flipFrom == null) {
            // 先发一帧翻到一半的，稍后再发翻完的
            flipFrom = lastHM; lastHM = hm
            host.redraw(this)
            host.main.postDelayed(loop, maxOf(400L, (host.frameIntervalMs() ?: 0) + 50))
            return
        }
        flipFrom = null; lastHM = hm
        host.redraw(this)
        val now = System.currentTimeMillis()
        val next = if (secondsOn()) 1000 - now % 1000 else 60_000 - now % 60_000
        // 眼镜收图慢于一秒时不堆积（图片本来也是只发最新的一张）
        host.main.postDelayed(loop, maxOf(next + 30, if (secondsOn()) host.frameIntervalMs() ?: 0 else 0))
    }

    override fun onInput(input: GlassesInput) {
        when (input) {
            GlassesInput.CLICK_RIGHT, GlassesInput.DOUBLE_RIGHT -> style = (style + 1) % PixelClock.STYLES.size
            GlassesInput.CLICK_LEFT, GlassesInput.DOUBLE_LEFT -> style = (style + PixelClock.STYLES.size - 1) % PixelClock.STYLES.size
            else -> {}
        }
    }

    override fun status() = "$title · ${PixelClock.STYLES[style]}"

    override fun render(fontPx: Int): ByteArray = draw(fontPx, style, Calendar.getInstance(), flipFrom).done()

    /** 手机上的预览（放大 2 倍，最近邻）。 */
    fun preview(style: Int = this.style): Bitmap {
        val b = draw(16, style, Calendar.getInstance(), null).bitmap()
        return Bitmap.createScaledBitmap(b, b.width * 2, b.height * 2, false)
    }

    private fun draw(fontPx: Int, style: Int, c: Calendar, flip: String?): Frame {
        val f = Frame(fontPx)
        val secs = style != PixelClock.FLIP && showSeconds
        f.text(dateLine(c), f.w / 2, 6, Frame.DIM, f.small, Frame.CENTER)
        val s = c.get(Calendar.SECOND)
        PixelClock.draw(style, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), if (secs) s else null,
            6, 30, f.w - 12, f.h - 36, f.fill, prev = flip, colon = !secs || s % 2 == 0)
        return f
    }

    companion object {
        private val WEEK = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        fun dateLine(c: Calendar) = "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日 ${WEEK[c.get(Calendar.DAY_OF_WEEK) - 1]}"
    }
}
