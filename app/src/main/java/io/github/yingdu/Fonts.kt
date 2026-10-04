package io.github.yingdu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint

/**
 * 眼镜画面用的点阵字体，字号从 MIN_PX 到 MAX_PX 每 1 像素一档：
 *  - 12～15px、24px 以上：Fusion Pixel Font 12 点阵放大（24px 正好 2 倍）；
 *  - 16～23px：GNU Unifont 16 点阵（字形和眼镜自带界面一致，16px 是原大，1 像素细笔画）。
 * 其余字号按最近邻缩放（边缘干净，个别笔画 2 像素宽），见 PixelText。
 * 两者都按许可证（SIL OFL 1.1）转成了萤读自己的点阵格式，见 tools/make_pixel_fonts.py。
 */
object GlassesFonts {
    const val MIN_PX = 12
    const val MAX_PX = 32
    const val DEFAULT_PX = 16

    private var pixel16: PixelFont? = null
    private var pixel12: PixelFont? = null
    private val texts = HashMap<Int, PixelText>()

    fun init(ctx: Context) {
        if (pixel16 == null) pixel16 = load(ctx, "fonts/yingdu_pixel16.ydpf")
        if (pixel12 == null) pixel12 = load(ctx, "fonts/yingdu_pixel12.ydpf")
    }

    /** 直接给出两套点阵（测试和效果图用）。 */
    fun init(p16: PixelFont, p12: PixelFont) { pixel16 = p16; pixel12 = p12; texts.clear() }

    private fun load(ctx: Context, path: String) = PixelFont.parse(ctx.assets.open(path).use { it.readBytes() })

    /** 某个字号（字高像素）的点阵字；同一字号共用缩放好的字形缓存。 */
    @Synchronized
    fun text(px: Int): PixelText {
        val h = px.coerceIn(MIN_PX, MAX_PX)
        return texts.getOrPut(h) { textFor(pixel16!!, pixel12!!, h) }
    }

    /** 选哪套点阵：只放大、不缩小，笔画才不会丢。 */
    fun textFor(p16: PixelFont, p12: PixelFont, px: Int): PixelText =
        if (px in 16..23) PixelText(p16, px, fallback = p12) else PixelText(p12, px, fallback = p16)
}

/** 在画布上画点阵字（不抗锯齿，一个点就是一个像素）。 */
fun PixelText.draw(c: Canvas, s: CharSequence, x: Int, top: Int, color: Int, alignRight: Boolean = false): Int {
    val p = Paint().apply { this.color = color }
    val left = if (alignRight) x - measure(s) else x
    return draw(s, left, top) { fx, fy, w, h ->
        c.drawRect(fx.toFloat(), fy.toFloat(), (fx + w).toFloat(), (fy + h).toFloat(), p)
    }
}
