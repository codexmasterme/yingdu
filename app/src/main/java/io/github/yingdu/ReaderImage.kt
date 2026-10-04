package io.github.yingdu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.roundToInt

/**
 * 把阅读的一屏文字画成图片，和看板一样显示在导航页的大图区域。
 * 这样阅读和看板都在同一个眼镜页面里，切换时不用退出页面，也就不会闪出官方主界面。
 */
object ReaderImage {
    const val W = NimoProtocol.NAV_LARGE_MAP_WIDTH
    const val H = NimoProtocol.NAV_LARGE_MAP_HEIGHT
    private const val PROGRESS_H = 5
    private const val LEFT = 2

    /** 某个字号的排版：字高、行间空隙、每行字数（留出一格给行末标点）、每屏行数。 */
    data class Layout(val px: Int, val gap: Int, val charsPerLine: Int, val rows: Int) {
        val lineH: Int get() = px + gap
    }

    /** 行距随字号等比例变化：16px 时行高 23px（和 v2.0 的「标准」一样）。 */
    fun layout(fontPx: Int): Layout {
        val px = fontPx.coerceIn(GlassesFonts.MIN_PX, GlassesFonts.MAX_PX)
        val gap = maxOf(2, (px * 0.44f).roundToInt())
        val rows = (H - PROGRESS_H + gap) / (px + gap)
        val chars = (W - LEFT) / px - 1
        return Layout(px, gap, chars, rows)
    }

    fun render(text: String, fontPx: Int, progress: Double?): ByteArray =
        DashboardImage.toGlasses(renderBitmap(text, fontPx, progress))

    /** 同样的画面画成位图（手机上预览用）。 */
    fun renderBitmap(text: String, fontPx: Int, progress: Double?): Bitmap {
        val lay = layout(fontPx)
        val font = GlassesFonts.text(lay.px)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        // 行块整体在进度条上方垂直居中
        val lines = text.split('\n').take(lay.rows)
        val block = lay.rows * lay.px + (lay.rows - 1) * lay.gap
        val top = ((H - PROGRESS_H - block) / 2).coerceAtLeast(0)
        lines.forEachIndexed { i, line -> font.draw(c, line, LEFT, top + lay.lineH * i, Color.WHITE) }
        // 底部一条细细的进度条，代替提词器顶部的文字状态
        if (progress != null) {
            val y = H - 2f
            c.drawRect(0f, y, W.toFloat(), y + 1f, Paint().apply { color = 0xFF505050.toInt() })
            c.drawRect(0f, y - 1f, (W * (progress / 100.0)).toFloat().coerceIn(0f, W.toFloat()), y + 1f,
                Paint().apply { color = 0xFFA0A0A0.toInt() })
        }
        return bmp
    }
}
