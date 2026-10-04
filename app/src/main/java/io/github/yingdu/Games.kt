package io.github.yingdu

import android.content.Context

/**
 * 眼镜小游戏。都用手机当手柄：方向键 + A、B 两个键（有的游戏还有数字键）。
 * 眼镜的画面是整张图片通过蓝牙发过去的，每秒只能刷新几次，所以以回合制为主；
 * 需要实时的（贪吃蛇、俄罗斯方块）速度可以调，也有「自动」档跟着眼镜的刷新速度走。
 */
abstract class GameApp(protected val host: AppHost) : GlassesApp {
    protected val prefs = host.context.getSharedPreferences("games", Context.MODE_PRIVATE)

    /** 首页卡片上的一句介绍。 */
    abstract val intro: String
    /** 手机上的玩法说明。 */
    abstract val help: String
    open val aLabel: String? = null
    open val bLabel: String? = null
    /** 难度 / 速度 / 大小等选项（没有就是空的）。 */
    open val options: List<String> = emptyList()
    open val optionTitle = "难度"
    open val numberPad = false

    var option: Int
        get() = prefs.getInt("opt_$title", defaultOption).coerceIn(0, maxOf(0, options.size - 1))
        set(v) { prefs.edit().putInt("opt_$title", v).apply(); onOptionChanged() }
    protected open val defaultOption = 0
    protected open fun onOptionChanged() = restart()

    var best: Int
        get() = prefs.getInt("best_$title", 0)
        protected set(v) = prefs.edit().putInt("best_$title", v).apply()

    open fun dir(d: Dir) {}
    open fun a() {}
    open fun b() {}
    open fun number(n: Int) {}
    abstract fun restart()
    /** 手机上显示的一行状态（得分、步数……）。 */
    abstract fun info(): String

    protected fun show() { host.redraw(this); host.changed() }

    override fun onInput(input: GlassesInput) {
        when (input) {
            GlassesInput.CLICK_RIGHT -> a()
            GlassesInput.CLICK_LEFT -> b()
            else -> {}
        }
    }

    override fun status() = "$title · ${info()}"

    /** 顶部一行：游戏名 + 状态。 */
    protected fun Frame.top(right: String): Int = header("$title · ${info()}", right)
}

object Games {
    fun all(host: AppHost): List<GameApp> = listOf(
        Game2048App(host), SnakeApp(host), TetrisApp(host), MinesApp(host), SudokuApp(host),
        SlideApp(host), SokobanApp(host), GomokuApp(host), MemoryApp(host))

    /** 棋盘上的光标：四个角的小折线（不挡住格子里的内容）。 */
    fun cursor(f: Frame, x: Int, y: Int, w: Int, h: Int, len: Int = 4) {
        val c = Frame.FULL
        f.rect(x, y, len, 1, c); f.rect(x, y, 1, len, c)
        f.rect(x + w - len, y, len, 1, c); f.rect(x + w - 1, y, 1, len, c)
        f.rect(x, y + h - 1, len, 1, c); f.rect(x, y + h - len, 1, len, c)
        f.rect(x + w - len, y + h - 1, len, 1, c); f.rect(x + w - 1, y + h - len, 1, len, c)
    }
}

// =====================================================================

/** 2048：回合制，最适合眼镜。 */
class Game2048App(host: AppHost) : GameApp(host) {
    override val title = "2048"
    override val intro = "滑动合并数字"
    override val help = "方向键滑动，相同的数字撞在一起合并。凑出 2048 就赢了，可以接着玩。"
    val game = Game2048()

    init {
        // 接着上次的局面玩
        prefs.getString("state2048", null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 17 }?.let {
            game.load(it.take(16).toIntArray(), it[16])
        }
    }

    override fun dir(d: Dir) {
        if (game.move(d)) { if (game.score > best) best = game.score; save() }
        show()
    }

    override fun restart() { game.reset(); save(); show() }
    override fun info() = "${game.score} 分" + if (game.over) " · 结束" else ""

    private fun save() = prefs.edit().putString("state2048", (game.cells.toList() + game.score).joinToString(",")).apply()

    override fun render(fontPx: Int): ByteArray {
        val f = Frame(fontPx)
        val cell = 40
        val ox = 8
        val oy = (f.h - cell * 4) / 2
        val num = GlassesFonts.text(16)
        for (y in 0 until 4) for (x in 0 until 4) {
            val v = game.cells[y * 4 + x]
            val cx = ox + x * cell
            val cy = oy + y * cell
            if (v == 0) { f.rect(cx + cell / 2 - 1, cy + cell / 2 - 1, 2, 2, Frame.FAINT); continue }
            // 数字越大方框越亮，2048 以上画双框
            f.box(cx + 2, cy + 2, cell - 4, cell - 4, if (v >= 128) Frame.FULL else Frame.DIM)
            if (v >= 2048) f.box(cx + 4, cy + 4, cell - 8, cell - 8, Frame.FULL)
            val s = v.toString()
            val nf = if (num.measure(s) <= cell - 14) num else GlassesFonts.text(12)   // 四位数用小一号，不压框
            f.text(s, cx + cell / 2, cy + (cell - nf.height) / 2, Frame.FULL, nf, Frame.CENTER)
        }
        val rx = ox + cell * 4 + 20
        f.text("2048", rx, 12, Frame.FULL, GlassesFonts.text(24))
        f.text("得分 ${game.score}", rx, 50, Frame.FULL, f.small)
        f.text("最高 $best", rx, 72, Frame.DIM, f.small)
        f.text("最大 ${game.max()}", rx, 94, Frame.DIM, f.small)
        f.text(if (game.over) "走不动了，手机上点「重新开始」" else "手机方向键滑动", rx, 138, Frame.DIM, f.small)
        return f.done()
    }
}

// =====================================================================

/** 贪吃蛇：实时游戏，速度受眼镜刷新速度限制；「自动」档按实测的刷新速度走，不丢帧。 */
class SnakeApp(host: AppHost) : GameApp(host) {
    override val title = "贪吃蛇"
    override val intro = "实时，速度可调"
    override val help = "方向键转向，吃到方块变长，撞墙或撞到自己结束。A 键暂停 / 继续。眼镜刷新跟不上时画面会跳格，可以选「自动」或调慢一档。"
    override val aLabel = "暂停"
    override val optionTitle = "速度"
    override val options = listOf("自动", "慢", "中", "快", "很快", "极快")
    override val defaultOption = 3
    private val stepByOption = longArrayOf(0, 700, 450, 300, 200, 130)
    val game = SnakeGame(COLS, ROWS)
    var playing = false; private set

    override fun onOptionChanged() { host.changed() }

    /** 这一步隔多久：固定档位，或「自动」= 眼镜发一张图的实测时间（再留一点余量）。 */
    fun stepMs(): Long = if (option == 0) ((host.frameIntervalMs() ?: 350L) * 11 / 10).coerceIn(120L, 1000L) else stepByOption[option]

    fun start() {
        if (game.over) game.reset()
        playing = true
        host.main.removeCallbacks(tick); host.main.postDelayed(tick, stepMs())
        show()
    }

    fun pause() { playing = false; host.main.removeCallbacks(tick); show() }

    override fun restart() { game.reset(); start() }
    override fun a() { if (playing) pause() else start() }
    override fun dir(d: Dir) { game.turn(d); if (!playing && !game.over) start() }
    override fun info() = "${game.score} 分" + when { game.over -> " · 结束"; !playing -> " · 暂停"; else -> "" }

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            game.step()
            if (game.score > best) best = game.score
            if (game.over) playing = false else host.main.postDelayed(this, stepMs())
            show()
        }
    }

    override fun onInput(input: GlassesInput) {
        when (input) {
            GlassesInput.CLICK_RIGHT -> game.turnRight()
            GlassesInput.CLICK_LEFT -> game.turnLeft()
            else -> {}
        }
    }

    override fun onClose() { pause() }

    override fun render(fontPx: Int): ByteArray {
        val f = Frame(fontPx)
        val top = f.top("最高 $best")
        val cell = minOf((f.w - 8) / COLS, (f.h - top - 4) / ROWS)
        val ox = (f.w - cell * COLS) / 2
        val oy = top + (f.h - top - cell * ROWS) / 2
        f.box(ox - 2, oy - 2, cell * COLS + 4, cell * ROWS + 4, Frame.FAINT)
        game.body.forEachIndexed { i, (x, y) ->
            val pad = if (i == 0) 1 else 2
            f.rect(ox + x * cell + pad, oy + y * cell + pad, cell - pad * 2, cell - pad * 2, if (i == 0) Frame.FULL else Frame.DIM)
        }
        val (fx, fy) = game.food
        f.box(ox + fx * cell + 3, oy + fy * cell + 3, cell - 6, cell - 6, Frame.FULL)
        if (game.over || !playing) overlay(f, if (game.over) "游戏结束 · ${game.score} 分" else "按方向键开始")
        return f.done()
    }

    companion object {
        const val COLS = 24
        const val ROWS = 7

        /** 画面中间一块黑底提示。 */
        fun overlay(f: Frame, msg: String) {
            val w = f.font.measure(msg) + 20
            f.rect(f.w / 2 - w / 2, f.h / 2 - f.font.height / 2 - 6, w, f.font.height + 12, 0xFF000000.toInt())
            f.box(f.w / 2 - w / 2, f.h / 2 - f.font.height / 2 - 6, w, f.font.height + 12, Frame.DIM)
            f.text(msg, f.w / 2, f.h / 2 - f.font.height / 2, Frame.FULL, align = Frame.CENTER)
        }
    }
}

// =====================================================================

/** 俄罗斯方块：竖着的 10×20 棋盘放在左边，右边是下一块和分数。 */
class TetrisApp(host: AppHost) : GameApp(host) {
    override val title = "俄罗斯方块"
    override val intro = "实时，越消越快"
    override val help = "←→ 移动，↑ 或 A 旋转，↓ 往下一格，B 直接落到底。消一行 100 分，一次消四行 800 分；每消 10 行升一级、下落变快。"
    override val aLabel = "旋转"
    override val bLabel = "落到底"
    val game = Tetris()
    var playing = false; private set

    fun start() {
        if (game.over) game.reset()
        playing = true
        host.main.removeCallbacks(gravity); host.main.postDelayed(gravity, game.gravityMs)
        show()
    }

    fun pause() { playing = false; host.main.removeCallbacks(gravity); show() }

    private fun after() { if (game.score > best) best = game.score; if (game.over) pause() else show() }

    private val gravity = object : Runnable {
        override fun run() {
            if (!playing) return
            game.tick(); after()
            if (playing) host.main.postDelayed(this, game.gravityMs)
        }
    }

    override fun dir(d: Dir) {
        if (!playing) { start(); return }
        when (d) {
            Dir.LEFT -> game.move(-1)
            Dir.RIGHT -> game.move(1)
            Dir.UP -> game.rotate()
            Dir.DOWN -> game.softDrop()
        }
        after()
    }

    override fun a() { if (!playing) start() else { game.rotate(); after() } }
    override fun b() { if (!playing) start() else { game.hardDrop(); after() } }
    override fun restart() { game.reset(); start() }
    override fun onClose() { pause() }
    override fun info() = "${game.score} 分 · ${game.lines} 行" + when { game.over -> " · 结束"; !playing -> " · 暂停"; else -> "" }

    override fun render(fontPx: Int): ByteArray {
        val f = Frame(fontPx)
        val cell = 8
        val ox = 16
        val oy = (f.h - cell * game.h) / 2
        f.box(ox - 2, oy - 2, cell * game.w + 4, cell * game.h + 4, Frame.DIM)
        fun block(x: Int, y: Int, color: Int) { if (y >= 0) f.rect(ox + x * cell, oy + y * cell, cell - 1, cell - 1, color) }
        for (y in 0 until game.h) for (x in 0 until game.w) if (game.cells[y * game.w + x] != 0) block(x, y, Frame.DIM)
        if (!game.over) {
            for ((x, y) in game.ghost()) if (y >= 0) f.box(ox + x * cell, oy + y * cell, cell - 1, cell - 1, Frame.FAINT)
            for ((x, y) in game.current()) block(x, y, Frame.FULL)
        }
        val rx = ox + cell * game.w + 24
        f.text("下一块", rx, 10, Frame.DIM, f.small)
        for ((x, y) in game.shape(game.next, 0)) f.rect(rx + x * cell, 34 + y * cell, cell - 1, cell - 1, Frame.FULL)
        f.text("得分 ${game.score}", rx, 72, Frame.FULL, f.small)
        f.text("消行 ${game.lines} · 第 ${game.level} 级", rx, 94, Frame.DIM, f.small)
        f.text("最高 $best", rx, 116, Frame.DIM, f.small)
        f.text(when { game.over -> "结束了，手机上点「重新开始」"; !playing -> "按任意键开始"; else -> "↑ 旋转　B 落到底" }, rx, 146, Frame.DIM, f.small)
        return f.done()
    }
}

// =====================================================================

/** 扫雷：光标移动，A 点开，B 插旗。 */
class MinesApp(host: AppHost) : GameApp(host) {
    override val title = "扫雷"
    override val intro = "回合制，三档难度"
    override val help = "方向键移动光标，A 点开，B 插旗 / 拔旗。第一下不会踩雷；在已经点开的数字上按 A，如果周围的旗子数够了，会自动点开其余的格子。"
    override val aLabel = "点开"
    override val bLabel = "插旗"
    override val options = listOf("简单", "普通", "困难")
    private val mineCount = intArrayOf(14, 22, 32)
    var game = Minesweeper(COLS, ROWS, mineCount[option]); private set
    var cx = COLS / 2; private set
    var cy = ROWS / 2; private set
    private var startAt = 0L
    private var endMs = 0L

    override fun restart() { game = Minesweeper(COLS, ROWS, mineCount[option]); startAt = 0; endMs = 0; show() }

    override fun dir(d: Dir) { cx = (cx + d.dx + COLS) % COLS; cy = (cy + d.dy + ROWS) % ROWS; show() }

    override fun a() {
        if (game.lost || game.won) { restart(); return }
        if (startAt == 0L) startAt = System.currentTimeMillis()
        game.reveal(cy * COLS + cx)
        if (game.won) {
            endMs = System.currentTimeMillis() - startAt
            val sec = (endMs / 1000).toInt()
            if (option == 2 && (best == 0 || sec < best)) best = sec
        } else if (game.lost) endMs = System.currentTimeMillis() - startAt
        show()
    }

    override fun b() { game.toggleFlag(cy * COLS + cx); show() }

    override fun info() = when {
        game.won -> "成功 · ${endMs / 1000} 秒"
        game.lost -> "踩雷了"
        else -> "剩 ${game.mines - game.flags()} 颗雷"
    }

    override fun render(fontPx: Int): ByteArray {
        val f = Frame(fontPx)
        val top = f.top(options[option])
        val cw = 21; val ch = 19
        val ox = (f.w - cw * COLS) / 2
        val oy = top + (f.h - top - ch * ROWS) / 2
        val num = GlassesFonts.text(12)
        for (y in 0 until ROWS) for (x in 0 until COLS) {
            val i = y * COLS + x
            val px = ox + x * cw; val py = oy + y * ch
            val showMine = game.mine[i] && (game.lost || game.won)
            when {
                showMine -> {
                    // 雷：实心小方块；踩到的那颗外面再加一圈
                    f.rect(px + cw / 2 - 3, py + ch / 2 - 3, 6, 6, Frame.FULL)
                    if (i == game.boom) f.box(px + 1, py + 1, cw - 2, ch - 2, Frame.FULL)
                }
                game.open[i] -> {
                    val n = game.count(i)
                    if (n > 0) f.text(n.toString(), px + cw / 2, py + (ch - num.height) / 2, Frame.FULL, num, Frame.CENTER)
                    else f.rect(px + cw / 2, py + ch / 2, 1, 1, Frame.FAINT)
                }
                game.flag[i] -> {
                    // 小旗子：一根杆 + 三角旗
                    f.rect(px + 8, py + 4, 1, 11, Frame.FULL)
                    for (k in 0 until 4) f.rect(px + 9, py + 4 + k, 5 - k, 1, Frame.FULL)
                    f.rect(px + 6, py + 14, 5, 1, Frame.FULL)
                }
                else -> f.rect(px + 2, py + 2, cw - 4, ch - 4, Frame.FAINT)
            }
        }
        Games.cursor(f, ox + cx * cw, oy + cy * ch, cw, ch)
        if (game.won) SnakeApp.overlay(f, "全部找到了！${endMs / 1000} 秒")
        if (game.lost) SnakeApp.overlay(f, "踩雷了 · 按 A 再来")
        return f.done()
    }

    companion object {
        const val COLS = 20
        const val ROWS = 7
    }
}

// =====================================================================

/** 数独：光标移动，手机上的数字键填数。 */
class SudokuApp(host: AppHost) : GameApp(host) {
    override val title = "数独"
    override val intro = "回合制，三档难度"
    override val help = "方向键移动光标，数字键填数，0 清除。原题的数字是半亮的，你填的是全亮的；和同行、同列、同宫重复的格子会画框提示。"
    override val bLabel = "清除"
    override val options = listOf("简单", "普通", "困难")
    override val numberPad = true
    private val clues = intArrayOf(38, 31, 25)
    private val random = java.util.Random()
    var game = newGame(); private set
    var cx = 4; private set
    var cy = 4; private set

    private fun newGame(): Sudoku { val (p, s) = SudokuSolver.generate(random, clues[option]); return Sudoku(p, s) }

    override fun restart() { game = newGame(); show() }
    override fun dir(d: Dir) { cx = (cx + d.dx + 9) % 9; cy = (cy + d.dy + 9) % 9; show() }
    override fun number(n: Int) { game.set(cy * 9 + cx, n); show() }
    override fun b() = number(0)
    override fun info() = if (game.solved()) "完成！" else "已填 ${game.filled()} / 81"

    override fun render(fontPx: Int): ByteArray {
        val f = Frame(fontPx)
        val cell = 17
        val ox = 10
        val oy = (f.h - cell * 9) / 2
        val num = GlassesFonts.text(12)
        // 细线分格，粗线（全亮）分宫
        for (k in 0..9) {
            val c = if (k % 3 == 0) Frame.DIM else Frame.FAINT
            f.rect(ox + k * cell, oy, 1, cell * 9 + 1, c)
            f.rect(ox, oy + k * cell, cell * 9 + 1, 1, c)
        }
        for (i in 0 until 81) {
            val d = game.values[i]
            if (d == 0) continue
            val x = ox + (i % 9) * cell; val y = oy + (i / 9) * cell
            f.text(d.toString(), x + cell / 2 + 1, y + (cell - num.height) / 2 + 1, if (game.isGiven(i)) Frame.DIM else Frame.FULL, num, Frame.CENTER)
            if (game.conflict(i) && !game.isGiven(i)) f.box(x + 2, y + 2, cell - 3, cell - 3, Frame.FULL)
        }
        Games.cursor(f, ox + cx * cell, oy + cy * cell, cell + 1, cell + 1, 5)
        val rx = ox + cell * 9 + 22
        f.text("数独 · ${options[option]}", rx, 12, Frame.FULL, f.small)
        f.text("已填 ${game.filled()} / 81", rx, 40, Frame.DIM, f.small)
        f.text("第 ${cy + 1} 行 第 ${cx + 1} 列", rx, 62, Frame.DIM, f.small)
        f.text(if (game.solved()) "完成了！" else "手机数字键填数", rx, 140, if (game.solved()) Frame.FULL else Frame.DIM, f.small)
        return f.done()
    }
}

// =====================================================================

/** 数字华容道：方向键让数字往那个方向滑进空位。 */
class SlideApp(host: AppHost) : GameApp(host) {
    override val title = "数字华容道"
    override val intro = "回合制，拼回顺序"
    override val help = "方向键：让空位旁边的数字往这个方向滑进空位。把数字按 1、2、3… 的顺序排好，空位在右下角就完成了。"
    override val optionTitle = "大小"
    override val options = listOf("3×3", "4×4", "5×5")
    override val defaultOption = 1
    var game = SlidePuzzle(option + 3); private set

    override fun restart() { game = SlidePuzzle(option + 3); show() }

    override fun dir(d: Dir) {
        if (game.solved()) return
        if (game.slide(d) && game.solved() && (best == 0 || game.moves < best) && option == 1) best = game.moves
        show()
    }

    override fun info() = "${game.moves} 步" + if (game.solved()) " · 完成" else ""

    override fun render(fontPx: Int): ByteArray {
        val f = Frame(fontPx)
        val n = game.n
        val cell = (f.h - 12) / n
        val ox = 12
        val oy = (f.h - cell * n) / 2
        val num = GlassesFonts.text(if (cell >= 40) 24 else 16)
        for (i in 0 until n * n) {
            val v = game.tiles[i]
            if (v == 0) continue
            val x = ox + (i % n) * cell; val y = oy + (i / n) * cell
            val home = v == i + 1
            f.box(x + 2, y + 2, cell - 4, cell - 4, if (home) Frame.FULL else Frame.DIM)
            f.text(v.toString(), x + cell / 2, y + (cell - num.height) / 2, Frame.FULL, num, Frame.CENTER)
        }
        val rx = ox + cell * n + 24
        f.text("数字华容道", rx, 12, Frame.FULL, GlassesFonts.text(16))
        f.text("${game.moves} 步", rx, 44, Frame.FULL, f.small)
        f.text(if (option == 1 && best > 0) "4×4 最少 $best 步" else options[option], rx, 66, Frame.DIM, f.small)
        f.text(if (game.solved()) "完成了！" else "亮框 = 已经归位", rx, 140, if (game.solved()) Frame.FULL else Frame.DIM, f.small)
        return f.done()
    }
}

// =====================================================================

/** 推箱子：11 关，A 撤销一步，B 跳到下一关。 */
class SokobanApp(host: AppHost) : GameApp(host) {
    override val title = "推箱子"
    override val intro = "回合制，11 关"
    override val help = "方向键走，把所有箱子推到目标点上。箱子只能推不能拉，推到墙角就拿不出来了——A 撤销一步，「重新开始」重来本关，B 跳到下一关。"
    override val aLabel = "撤销"
    override val bLabel = "下一关"
    var level: Int
        get() = prefs.getInt("sokoLevel", 0).coerceIn(0, Sokoban.LEVELS.size - 1)
        private set(v) = prefs.edit().putInt("sokoLevel", v).apply()
    var game = Sokoban(Sokoban.LEVELS[level]); private set

    override fun restart() { game = Sokoban(Sokoban.LEVELS[level]); show() }

    override fun dir(d: Dir) {
        if (game.solved()) { b(); return }
        if (game.move(d) && game.solved()) {
            // 记录通过的最高一关
            if (level + 1 > best) best = level + 1
        }
        show()
    }

    override fun a() { game.undo(); show() }
    override fun b() { level = (level + 1) % Sokoban.LEVELS.size; restart() }
    override fun info() = "第 ${level + 1} / ${Sokoban.LEVELS.size} 关 · ${game.moves} 步" + if (game.solved()) " · 过关" else ""

    override fun render(fontPx: Int): ByteArray {
        val f = Frame(fontPx)
        val top = f.top("推 ${game.pushes} 次")
        val cell = minOf(22, (f.h - top - 6) / game.h, (f.w - 16) / game.w)
        val ox = (f.w - cell * game.w) / 2
        val oy = top + (f.h - top - cell * game.h) / 2
        for (y in 0 until game.h) for (x in 0 until game.w) {
            val i = y * game.w + x
            val px = ox + x * cell; val py = oy + y * cell
            if (i in game.walls) {
                // 墙：半亮的实心块，留一道缝看出砖块
                f.rect(px, py, cell - 1, cell - 1, Frame.FAINT)
                continue
            }
            if (i in game.goals) f.box(px + cell / 2 - 3, py + cell / 2 - 3, 6, 6, Frame.DIM)
            if (i in game.boxes) {
                val on = i in game.goals
                f.box(px + 2, py + 2, cell - 4, cell - 4, Frame.FULL)
                if (on) f.rect(px + 5, py + 5, cell - 10, cell - 10, Frame.FULL)   // 推到位：实心
                else { f.rect(px + 2, py + cell / 2, cell - 4, 1, Frame.DIM) }
            }
            if (i == game.player) {
                // 小人：头 + 身体
                val c = px + cell / 2
                f.rect(c - 2, py + 3, 5, 5, Frame.FULL)
                f.rect(c - 1, py + 8, 3, cell - 13, Frame.FULL)
                f.rect(c - 5, py + 10, 11, 2, Frame.FULL)
                f.rect(c - 3, py + cell - 5, 2, 3, Frame.FULL); f.rect(c + 2, py + cell - 5, 2, 3, Frame.FULL)
            }
        }
        if (game.solved()) SnakeApp.overlay(f, if (level + 1 < Sokoban.LEVELS.size) "过关！按方向键进下一关" else "全部通关！")
        return f.done()
    }
}

// =====================================================================

/** 五子棋：人机对战，玩家执黑先走。 */
class GomokuApp(host: AppHost) : GameApp(host) {
    override val title = "五子棋"
    override val intro = "回合制，人机对战"
    override val help = "方向键移动光标，A 落子，电脑马上应一步。先连成五个的赢。B 悔棋（退回你的上一步和电脑的应手）。你是实心方块，电脑是空心圆圈。"
    override val aLabel = "落子"
    override val bLabel = "悔棋"
    var game = Gomoku(); private set
    var cx = 7; private set
    var cy = 7; private set

    override fun restart() { game = Gomoku(); cx = 7; cy = 7; show() }
    override fun dir(d: Dir) { cx = (cx + d.dx + 15) % 15; cy = (cy + d.dy + 15) % 15; show() }

    override fun a() {
        if (game.winner != 0) { restart(); return }
        if (game.play(cy * 15 + cx) && game.winner == 1) best += 1   // 记赢了几局
        show()
    }

    override fun b() { game.undo(); show() }
    override fun info() = when (game.winner) { 1 -> "你赢了"; 2 -> "电脑赢了"; else -> "第 ${(game.history.size + 1) / 2} 手" }

    override fun render(fontPx: Int): ByteArray {
        val f = Frame(fontPx)
        val g = 11
        val ox = 12
        val oy = (f.h - g * 14) / 2
        for (k in 0 until 15) {
            f.rect(ox + k * g, oy, 1, g * 14 + 1, Frame.FAINT)
            f.rect(ox, oy + k * g, g * 14 + 1, 1, Frame.FAINT)
        }
        val last = game.history.lastOrNull()
        for (i in 0 until 225) {
            val who = game.board[i]
            if (who == 0) continue
            val x = ox + (i % 15) * g; val y = oy + (i / 15) * g
            if (who == 1) f.rect(x - 4, y - 4, 9, 9, Frame.FULL)
            else {
                // 空心圆（八边形近似）
                f.rect(x - 2, y - 4, 5, 1, Frame.FULL); f.rect(x - 2, y + 4, 5, 1, Frame.FULL)
                f.rect(x - 4, y - 2, 1, 5, Frame.FULL); f.rect(x + 4, y - 2, 1, 5, Frame.FULL)
                f.rect(x - 3, y - 3, 1, 1, Frame.FULL); f.rect(x + 3, y - 3, 1, 1, Frame.FULL)
                f.rect(x - 3, y + 3, 1, 1, Frame.FULL); f.rect(x + 3, y + 3, 1, 1, Frame.FULL)
            }
            if (i == last) f.rect(x, y, 1, 1, if (who == 1) 0xFF000000.toInt() else Frame.FULL)
        }
        Games.cursor(f, ox + cx * g - 6, oy + cy * g - 6, 13, 13, 3)
        val rx = ox + g * 14 + 24
        f.text("五子棋", rx, 12, Frame.FULL, GlassesFonts.text(16))
        f.text("你 ■　电脑 ○", rx, 44, Frame.DIM, f.small)
        f.text("赢了 $best 局", rx, 66, Frame.DIM, f.small)
        f.text(when (game.winner) { 1 -> "你赢了！按 A 再来"; 2 -> "电脑赢了，按 A 再来"; else -> "A 落子　B 悔棋" }, rx, 140,
            if (game.winner != 0) Frame.FULL else Frame.DIM, f.small)
        return f.done()
    }
}

// =====================================================================

/** 记忆翻牌：4×6 张牌，12 对符号。 */
class MemoryApp(host: AppHost) : GameApp(host) {
    override val title = "记忆翻牌"
    override val intro = "回合制，考记性"
    override val help = "方向键移动光标，A 翻牌。一次翻两张，一样的就留着，不一样的过一会儿扣回去。用最少的步数全部配对。"
    override val aLabel = "翻牌"
    var game = MemoryGame(); private set
    var cx = 0; private set
    var cy = 0; private set

    override fun restart() { game = MemoryGame(); host.main.removeCallbacks(hide); show() }
    override fun dir(d: Dir) { cx = (cx + d.dx + game.cols) % game.cols; cy = (cy + d.dy + game.rows) % game.rows; show() }

    private val hide = Runnable { game.hide(); show() }

    override fun a() {
        if (game.won()) { restart(); return }
        host.main.removeCallbacks(hide)
        if (game.flip(cy * game.cols + cx)) {
            if (game.won() && (best == 0 || game.moves < best)) best = game.moves
            if (game.mismatchShowing()) host.main.postDelayed(hide, 1500)
        }
        show()
    }

    override fun onClose() { host.main.removeCallbacks(hide) }
    override fun info() = "${game.moves} 步" + if (game.won()) " · 完成" else ""

    override fun render(fontPx: Int): ByteArray {
        val f = Frame(fontPx)
        val cw = 46; val ch = 38
        val ox = 10
        val oy = (f.h - ch * game.rows) / 2
        val sym = GlassesFonts.text(24)
        for (i in 0 until game.cols * game.rows) {
            val x = ox + (i % game.cols) * cw; val y = oy + (i / game.cols) * ch
            val faceUp = game.matched[i] || i in game.up
            if (faceUp) {
                f.box(x + 2, y + 2, cw - 4, ch - 4, if (game.matched[i]) Frame.DIM else Frame.FULL)
                val s = SYMBOLS[game.cards[i] % SYMBOLS.length].toString()
                f.text(s, x + cw / 2, y + (ch - sym.height) / 2, if (game.matched[i]) Frame.DIM else Frame.FULL, sym, Frame.CENTER)
            } else {
                // 牌背：实心的暗块
                f.rect(x + 3, y + 3, cw - 6, ch - 6, Frame.FAINT)
            }
        }
        Games.cursor(f, ox + cx * cw, oy + cy * ch, cw, ch, 6)
        val rx = ox + cw * game.cols + 20
        f.text("记忆翻牌", rx, 12, Frame.FULL, GlassesFonts.text(16))
        f.text("${game.moves} 步", rx, 44, Frame.FULL, f.small)
        f.text(if (best > 0) "最少 $best 步" else "", rx, 66, Frame.DIM, f.small)
        f.text(if (game.won()) "全部配对！" else "A 翻牌", rx, 140, if (game.won()) Frame.FULL else Frame.DIM, f.small)
        return f.done()
    }

    companion object {
        const val SYMBOLS = "★♥♦♣♠●■▲◆♪☀☂"
    }
}
