package io.github.yingdu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import java.util.concurrent.Executor

/**
 * 眼镜上的全屏功能（小游戏、表盘、番茄钟……）：自己画画面、自己处理输入。
 * 打开后眼镜显示它的画面，直到回到阅读或看板。都在主线程上调用。
 */
interface GlassesApp {
    val title: String
    /** 画一帧（452×170，2bpp）。 */
    fun render(fontPx: Int): ByteArray
    /** 镜腿手势（导航页里不一定上报单击，所以每个操作手机上都要有按钮）。 */
    fun onInput(input: GlassesInput) {}
    /** 在眼镜上打开时。 */
    fun onOpen() {}
    /** 眼镜切走时（回到阅读或看板、打开别的功能）。 */
    fun onClose() {}
    /** 在眼镜上退出（暂停）后又恢复显示时。 */
    fun onResume() {}
    /** 通知栏里的一句状态。 */
    fun status(): String = title
}

/** 全屏功能要用到的服务能力。 */
interface AppHost {
    val context: Context
    val main: Handler
    val io: Executor
    /** 这个功能正在眼镜上显示时，重新画一帧并发过去。 */
    fun redraw(app: GlassesApp)
    /** 手机界面刷新。 */
    fun changed()
    fun log(msg: String)
    fun isShowing(app: GlassesApp): Boolean
    /** 连续发图时眼镜实际每张图隔多少毫秒（不知道时为 null）。 */
    fun frameIntervalMs(): Long?
    /** 在眼镜上弹一条通知（不管眼镜现在显示什么；没连接时什么也不做）。 */
    fun popup(title: String, text: String) {}
}

/** 画一帧眼镜画面的小工具：黑底、点阵字，最后转成眼镜的 2bpp 格式。 */
class Frame(fontPx: Int, val w: Int = NimoProtocol.NAV_LARGE_MAP_WIDTH, val h: Int = NimoProtocol.NAV_LARGE_MAP_HEIGHT) {
    private val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp).apply { drawColor(Color.BLACK) }
    /** 正文字号（跟随「眼镜字号」，但不超过 24px，免得放不下）。 */
    val font = GlassesFonts.text(fontPx.coerceAtMost(24))
    /** 小字：顶部状态行、底部提示。 */
    val small = GlassesFonts.text(16)
    private val paint = Paint()

    fun text(s: String, x: Int, y: Int, color: Int = FULL, f: PixelText = font, align: Int = LEFT): Int {
        val left = when (align) { RIGHT -> x - f.measure(s); CENTER -> x - f.measure(s) / 2; else -> x }
        return f.draw(canvas, s, left, y, color)
    }

    fun rect(x: Int, y: Int, rw: Int, rh: Int, color: Int = FULL) {
        paint.color = color
        canvas.drawRect(x.toFloat(), y.toFloat(), (x + rw).toFloat(), (y + rh).toFloat(), paint)
    }

    /** 1 像素的方框。 */
    fun box(x: Int, y: Int, rw: Int, rh: Int, color: Int = DIM) {
        rect(x, y, rw, 1, color); rect(x, y + rh - 1, rw, 1, color); rect(x, y, 1, rh, color); rect(x + rw - 1, y, 1, rh, color)
    }

    /** 虚线（和看板一样的分隔线）。 */
    fun dotted(y: Int, x0: Int = 6, x1: Int = w - 6) { var x = x0; while (x < x1) { rect(x, y, 2, 1, LINE); x += 5 } }

    /** 顶部一行：左边标题，右边附加信息（都半亮），下面一条虚线。返回正文可以开始的 y。 */
    fun header(left: String, right: String = ""): Int {
        text(small.ellipsize(left, w - 12 - (if (right.isEmpty()) 0 else small.measure(right) + 12)), 6, 6, DIM, small)
        if (right.isNotEmpty()) text(right, w - 6, 6, DIM, small, RIGHT)
        dotted(26)
        return 32
    }

    /** 底部一行提示（半亮）。 */
    fun footer(s: String) = text(small.ellipsize(s, w - 12), 6, h - small.height - 3, DIM, small)

    /** 按 PixelClock 的亮度画格子（像素表盘、进度环）。 */
    val fill: Fill = { x, y, rw, rh, level ->
        rect(x, y, rw, rh, when (level) { PixelClock.FULL -> FULL; PixelClock.DIM -> DIM; PixelClock.FAINT -> FAINT; else -> Color.BLACK })
    }

    fun done(): ByteArray = DashboardImage.toGlasses(bmp)

    /** 手机上的预览：直接拿画好的图（拿了就别再调 done）。 */
    fun bitmap(): Bitmap = bmp

    companion object {
        const val FULL = Color.WHITE
        const val DIM = 0xFFA0A0A0.toInt()
        const val FAINT = 0xFF606060.toInt()
        private const val LINE = 0xFF606060.toInt()
        const val LEFT = 0
        const val CENTER = 1
        const val RIGHT = 2
    }
}
