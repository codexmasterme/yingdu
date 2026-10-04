package io.github.yingdu

/*
 * 小游戏的规则（不依赖 Android，方便单元测试）。画面和操作在 Games.kt。
 * 随机数都可以注入，测试时结果固定。
 */

// =====================================================================
// 小游戏
// =====================================================================

enum class Dir(val dx: Int, val dy: Int) { UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0) }

/** 2048：4×4，滑动合并。random 可以注入，方便测试。 */
class Game2048(private val random: java.util.Random = java.util.Random()) {
    val size = 4
    var cells = IntArray(size * size); private set
    var score = 0; private set
    var over = false; private set

    init { reset() }

    fun reset() { cells = IntArray(size * size); score = 0; over = false; spawn(); spawn() }

    fun load(state: IntArray, score: Int) { cells = state.copyOf(); this.score = score; over = !canMove() }

    private fun spawn() {
        val empty = cells.indices.filter { cells[it] == 0 }
        if (empty.isEmpty()) return
        cells[empty[random.nextInt(empty.size)]] = if (random.nextInt(10) == 0) 4 else 2
    }

    /** 把一行向左合并，返回新的一行和得分。 */
    fun mergeLine(line: IntArray): Pair<IntArray, Int> {
        val tiles = line.filter { it != 0 }
        val out = ArrayList<Int>()
        var gained = 0
        var i = 0
        while (i < tiles.size) {
            if (i + 1 < tiles.size && tiles[i] == tiles[i + 1]) { out.add(tiles[i] * 2); gained += tiles[i] * 2; i += 2 }
            else { out.add(tiles[i]); i++ }
        }
        while (out.size < line.size) out.add(0)
        return out.toIntArray() to gained
    }

    /** 滑一下；格子有变化才生成新数字。返回是否动了。 */
    fun move(d: Dir): Boolean {
        if (over) return false
        val before = cells.copyOf()
        for (k in 0 until size) {
            // 按方向取出一行（从要靠过去的那一侧开始）
            val idx = IntArray(size) { j ->
                when (d) {
                    Dir.LEFT -> k * size + j
                    Dir.RIGHT -> k * size + (size - 1 - j)
                    Dir.UP -> j * size + k
                    Dir.DOWN -> (size - 1 - j) * size + k
                }
            }
            val (merged, gained) = mergeLine(IntArray(size) { cells[idx[it]] })
            for (j in 0 until size) cells[idx[j]] = merged[j]
            score += gained
        }
        val moved = !before.contentEquals(cells)
        if (moved) spawn()
        over = !canMove()
        return moved
    }

    fun canMove(): Boolean {
        if (cells.any { it == 0 }) return true
        for (y in 0 until size) for (x in 0 until size) {
            val v = cells[y * size + x]
            if (x + 1 < size && cells[y * size + x + 1] == v) return true
            if (y + 1 < size && cells[(y + 1) * size + x] == v) return true
        }
        return false
    }

    fun max(): Int = cells.maxOrNull() ?: 0
}

/** 贪吃蛇：w×h 的格子，撞墙或撞到自己就结束。 */
class SnakeGame(val w: Int = 24, val h: Int = 8, private val random: java.util.Random = java.util.Random()) {
    val body = ArrayDeque<Pair<Int, Int>>()   // 头在前
    var dir = Dir.RIGHT; private set
    private var nextDir = Dir.RIGHT
    var food = 0 to 0; private set
    var score = 0; private set
    var over = false; private set

    init { reset() }

    fun reset() {
        body.clear()
        for (i in 0 until 3) body.addLast((w / 2 - i) to h / 2)
        dir = Dir.RIGHT; nextDir = Dir.RIGHT; score = 0; over = false
        placeFood()
    }

    /** 转向（不能直接掉头）。 */
    fun turn(d: Dir) { if (d.dx != -dir.dx || d.dy != -dir.dy) nextDir = d }

    /** 相对当前方向左转 / 右转（给只有两个按键的场合用，例如左右镜腿）。 */
    fun turnLeft() = turn(when (dir) { Dir.UP -> Dir.LEFT; Dir.LEFT -> Dir.DOWN; Dir.DOWN -> Dir.RIGHT; Dir.RIGHT -> Dir.UP })
    fun turnRight() = turn(when (dir) { Dir.UP -> Dir.RIGHT; Dir.RIGHT -> Dir.DOWN; Dir.DOWN -> Dir.LEFT; Dir.LEFT -> Dir.UP })

    fun placeFood(at: Pair<Int, Int>? = null) {
        if (at != null) { food = at; return }
        val free = ArrayList<Pair<Int, Int>>()
        for (y in 0 until h) for (x in 0 until w) if ((x to y) !in body) free.add(x to y)
        if (free.isNotEmpty()) food = free[random.nextInt(free.size)]
    }

    /** 走一步。 */
    fun step() {
        if (over) return
        dir = nextDir
        val (hx, hy) = body.first()
        val n = (hx + dir.dx) to (hy + dir.dy)
        val grow = n == food
        // 尾巴这一步会移开，所以撞到尾巴不算（除非这一步要变长）
        val blocked = if (grow) body else body.take(body.size - 1)
        if (n.first !in 0 until w || n.second !in 0 until h || n in blocked) { over = true; return }
        body.addFirst(n)
        if (grow) { score++; placeFood() } else body.removeLast()
    }
}

// =====================================================================
// 俄罗斯方块
// =====================================================================

/** 俄罗斯方块：10×20，7 种方块用「7 个一袋」随机，旋转带简单的踢墙。 */
class Tetris(private val random: java.util.Random = java.util.Random()) {
    val w = 10
    val h = 20
    /** 0 空；1..7 方块种类。 */
    val cells = IntArray(w * h)
    var type = 0; private set
    var rot = 0; private set
    var px = 0; private set
    var py = 0; private set
    var next = 0; private set
    var score = 0; private set
    var lines = 0; private set
    var over = false; private set
    private val bag = ArrayList<Int>()

    val level: Int get() = 1 + lines / 10
    /** 自动下落的间隔（毫秒），等级越高越快。 */
    val gravityMs: Long get() = maxOf(150L, 900L - (level - 1) * 80L)

    init { reset() }

    fun reset() {
        cells.fill(0); score = 0; lines = 0; over = false; bag.clear()
        next = draw(); spawn()
    }

    private fun draw(): Int {
        if (bag.isEmpty()) { bag.addAll(1..7); java.util.Collections.shuffle(bag, random) }
        return bag.removeAt(bag.size - 1)
    }

    private fun spawn() {
        type = next; next = draw(); rot = 0; px = 3; py = 0
        if (!fits(type, rot, px, py)) over = true
    }

    /** 某种方块、某个旋转状态下占的 4 个格子（相对左上角）。 */
    fun shape(t: Int, r: Int): List<Pair<Int, Int>> {
        val base = SHAPES[t - 1]
        val n = if (t == 1) 4 else if (t == 2) 2 else 3
        var cellsOf = base
        repeat(((r % 4) + 4) % 4) { cellsOf = cellsOf.map { (x, y) -> (n - 1 - y) to x } }
        return cellsOf
    }

    fun current(): List<Pair<Int, Int>> = shape(type, rot).map { (x, y) -> (px + x) to (py + y) }

    /** 当前方块直接落下会停在哪里（画落点提示用）。 */
    fun ghost(): List<Pair<Int, Int>> {
        var y = py
        while (fits(type, rot, px, y + 1)) y++
        return shape(type, rot).map { (x, yy) -> (px + x) to (y + yy) }
    }

    private fun fits(t: Int, r: Int, x0: Int, y0: Int): Boolean = shape(t, r).all { (x, y) ->
        val cx = x0 + x; val cy = y0 + y
        cx in 0 until w && cy < h && (cy < 0 || cells[cy * w + cx] == 0)
    }

    fun move(dx: Int): Boolean { if (over || !fits(type, rot, px + dx, py)) return false; px += dx; return true }

    fun rotate(): Boolean {
        if (over) return false
        val r = (rot + 1) % 4
        for (kick in intArrayOf(0, -1, 1, -2, 2)) if (fits(type, r, px + kick, py)) { rot = r; px += kick; return true }
        return false
    }

    /** 往下一格；落不下去就固定、消行、出下一块。返回是否固定了。 */
    fun tick(): Boolean {
        if (over) return false
        if (fits(type, rot, px, py + 1)) { py++; return false }
        lock(); return true
    }

    fun softDrop() { if (!over && fits(type, rot, px, py + 1)) { py++; score += 1 } else tick() }

    fun hardDrop() {
        if (over) return
        var n = 0
        while (fits(type, rot, px, py + 1)) { py++; n++ }
        score += n * 2
        lock()
    }

    private fun lock() {
        for ((x, y) in current()) if (y >= 0) cells[y * w + x] = type
        var cleared = 0
        var y = h - 1
        while (y >= 0) {
            if ((0 until w).all { cells[y * w + it] != 0 }) {
                for (yy in y downTo 1) for (x in 0 until w) cells[yy * w + x] = cells[(yy - 1) * w + x]
                for (x in 0 until w) cells[x] = 0
                cleared++
            } else y--
        }
        if (cleared > 0) { score += intArrayOf(0, 100, 300, 500, 800)[cleared] * level; lines += cleared }
        spawn()
    }

    /** 测试用：直接摆好盘面。 */
    fun setCell(x: Int, y: Int, t: Int) { cells[y * w + x] = t }

    companion object {
        /** I O T S Z J L，旋转状态 0 的格子。 */
        private val SHAPES = listOf(
            listOf(0 to 1, 1 to 1, 2 to 1, 3 to 1),
            listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1),
            listOf(1 to 0, 0 to 1, 1 to 1, 2 to 1),
            listOf(1 to 0, 2 to 0, 0 to 1, 1 to 1),
            listOf(0 to 0, 1 to 0, 1 to 1, 2 to 1),
            listOf(0 to 0, 0 to 1, 1 to 1, 2 to 1),
            listOf(2 to 0, 0 to 1, 1 to 1, 2 to 1),
        )
    }
}

// =====================================================================
// 扫雷
// =====================================================================

/** 扫雷：第一下点开的位置和周围一圈保证没有雷；点开数字格且周围旗子数对上时，自动点开其余邻格。 */
class Minesweeper(val w: Int, val h: Int, val mines: Int, private val random: java.util.Random = java.util.Random()) {
    val mine = BooleanArray(w * h)
    val open = BooleanArray(w * h)
    val flag = BooleanArray(w * h)
    var started = false; private set
    var lost = false; private set
    var won = false; private set
    /** 踩到的那颗雷。 */
    var boom = -1; private set

    fun neighbors(i: Int): List<Int> {
        val x = i % w; val y = i / w
        val out = ArrayList<Int>(8)
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx; val ny = y + dy
            if (nx in 0 until w && ny in 0 until h) out.add(ny * w + nx)
        }
        return out
    }

    fun count(i: Int) = neighbors(i).count { mine[it] }
    fun flags() = flag.count { it }

    /** 测试用：直接布雷。 */
    fun place(indices: Collection<Int>) { mine.fill(false); indices.forEach { mine[it] = true }; started = true }

    private fun layMines(safe: Int) {
        val keepOut = (neighbors(safe) + safe).toSet()
        val pool = (0 until w * h).filter { it !in keepOut }.toMutableList()
        java.util.Collections.shuffle(pool, random)
        pool.take(minOf(mines, pool.size)).forEach { mine[it] = true }
        started = true
    }

    fun toggleFlag(i: Int) { if (!lost && !won && !open[i]) flag[i] = !flag[i] }

    /** 点开一格（点在已经打开的数字上 = 自动点开周围）。 */
    fun reveal(i: Int) {
        if (lost || won || flag[i]) return
        if (!started) layMines(i)
        if (open[i]) {
            val n = neighbors(i)
            if (count(i) > 0 && n.count { flag[it] } == count(i)) n.filter { !flag[it] && !open[it] }.forEach { reveal(it) }
            return
        }
        if (mine[i]) { lost = true; boom = i; return }
        // 空白格一路展开
        val stack = ArrayDeque<Int>().apply { add(i) }
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            if (open[c] || flag[c]) continue
            open[c] = true
            if (count(c) == 0) neighbors(c).filter { !open[it] && !mine[it] }.forEach { stack.add(it) }
        }
        if ((0 until w * h).all { mine[it] || open[it] }) won = true
    }
}

// =====================================================================
// 数独
// =====================================================================

object SudokuSolver {
    /** 数一数有几个解（最多数到 limit 就停）。grid 里 0 是空格，会被原样还原。 */
    fun count(grid: IntArray, limit: Int = 2): Int {
        val g = grid.copyOf()
        var found = 0
        fun candidates(i: Int): Int {
            var used = 0
            val r = i / 9; val c = i % 9; val br = r / 3 * 3; val bc = c / 3 * 3
            for (k in 0 until 9) {
                used = used or (1 shl g[r * 9 + k]) or (1 shl g[k * 9 + c]) or (1 shl g[(br + k / 3) * 9 + bc + k % 3])
            }
            return used.inv() and 0x3FE
        }
        fun search(): Boolean {
            // 找候选最少的空格
            var best = -1; var bestMask = 0; var bestCount = 10
            for (i in 0 until 81) if (g[i] == 0) {
                val m = candidates(i)
                val n = Integer.bitCount(m)
                if (n < bestCount) { best = i; bestMask = m; bestCount = n; if (n <= 1) break }
            }
            if (best < 0) { found++; return found >= limit }
            if (bestCount == 0) return false
            var m = bestMask
            while (m != 0) {
                val d = Integer.numberOfTrailingZeros(m)
                m = m and (m - 1)
                g[best] = d
                if (search()) return true
                g[best] = 0
            }
            return false
        }
        search()
        return found
    }

    /** 随机生成一个填满的合法盘面。 */
    fun full(random: java.util.Random): IntArray {
        val g = IntArray(81)
        fun ok(i: Int, d: Int): Boolean {
            val r = i / 9; val c = i % 9; val br = r / 3 * 3; val bc = c / 3 * 3
            for (k in 0 until 9) if (g[r * 9 + k] == d || g[k * 9 + c] == d || g[(br + k / 3) * 9 + bc + k % 3] == d) return false
            return true
        }
        fun fill(i: Int): Boolean {
            if (i == 81) return true
            val ds = (1..9).shuffled(random)
            for (d in ds) if (ok(i, d)) { g[i] = d; if (fill(i + 1)) return true; g[i] = 0 }
            return false
        }
        fill(0)
        return g
    }

    /** 生成题目：从填满的盘面随机挖空，保证只有一个解，挖到剩 clues 个数字（挖不动就停）。返回 (题目, 答案)。 */
    fun generate(random: java.util.Random, clues: Int): Pair<IntArray, IntArray> {
        val solution = full(random)
        val puzzle = solution.copyOf()
        var filled = 81
        for (i in (0 until 81).shuffled(random)) {
            if (filled <= clues) break
            val keep = puzzle[i]
            puzzle[i] = 0
            if (count(puzzle) != 1) puzzle[i] = keep else filled--
        }
        return puzzle to solution
    }
}

class Sudoku(val givens: IntArray, val solution: IntArray) {
    val values = givens.copyOf()
    fun isGiven(i: Int) = givens[i] != 0

    fun set(i: Int, d: Int) { if (!isGiven(i)) values[i] = d.coerceIn(0, 9) }

    /** 和同行、同列、同宫里别的格子重复了。 */
    fun conflict(i: Int): Boolean {
        val d = values[i]
        if (d == 0) return false
        val r = i / 9; val c = i % 9; val br = r / 3 * 3; val bc = c / 3 * 3
        for (k in 0 until 9) {
            val a = r * 9 + k; val b = k * 9 + c; val q = (br + k / 3) * 9 + bc + k % 3
            if ((a != i && values[a] == d) || (b != i && values[b] == d) || (q != i && values[q] == d)) return true
        }
        return false
    }

    fun solved() = values.contentEquals(solution) || (values.none { it == 0 } && (0 until 81).none { conflict(it) })
    fun filled() = values.count { it != 0 }
}

// =====================================================================
// 数字华容道
// =====================================================================

/** 数字华容道：n×n，0 是空位；方向键 = 让一块数字往这个方向滑进空位。 */
class SlidePuzzle(val n: Int, private val random: java.util.Random = java.util.Random()) {
    var tiles = IntArray(n * n) { (it + 1) % (n * n) }; private set
    var moves = 0; private set

    init { shuffle() }

    /** 从拼好的状态随机走很多步打乱，所以一定有解。 */
    fun shuffle() {
        tiles = IntArray(n * n) { (it + 1) % (n * n) }
        var last: Dir? = null
        var steps = 0
        while (steps < n * n * 25 || solved()) {
            val d = Dir.values()[random.nextInt(4)]
            if (last != null && d.dx == -last.dx && d.dy == -last.dy) continue
            if (slide(d, count = false)) { last = d; steps++ }
        }
        moves = 0
    }

    fun load(t: IntArray) { tiles = t.copyOf(); moves = 0 }

    fun slide(d: Dir, count: Boolean = true): Boolean {
        val b = tiles.indexOf(0)
        val bx = b % n; val by = b / n
        val tx = bx - d.dx; val ty = by - d.dy
        if (tx !in 0 until n || ty !in 0 until n) return false
        tiles[b] = tiles[ty * n + tx]; tiles[ty * n + tx] = 0
        if (count) moves++
        return true
    }

    fun solved() = (0 until n * n).all { tiles[it] == (it + 1) % (n * n) }
}

// =====================================================================
// 推箱子
// =====================================================================

/** 推箱子：关卡用常见的 XSB 文本（# 墙、@ 人、$ 箱子、. 目标、* 箱子在目标上、+ 人在目标上）。 */
class Sokoban(level: String) {
    val rows = level.trim('\n').lines()
    val h = rows.size
    val w = rows.maxOf { it.length }
    val walls = HashSet<Int>()
    val goals = HashSet<Int>()
    var boxes = HashSet<Int>(); private set
    var player = 0; private set
    var moves = 0; private set
    var pushes = 0; private set
    private val history = ArrayList<Triple<Int, Set<Int>, Int>>()   // (人, 箱子, 推了几次)

    init {
        rows.forEachIndexed { y, r ->
            r.forEachIndexed { x, c ->
                val i = y * w + x
                when (c) {
                    '#' -> walls.add(i)
                    '.' -> goals.add(i)
                    '$' -> boxes.add(i)
                    '*' -> { boxes.add(i); goals.add(i) }
                    '@' -> player = i
                    '+' -> { player = i; goals.add(i) }
                }
            }
        }
    }

    fun move(d: Dir): Boolean {
        val x = player % w; val y = player / w
        val t = (y + d.dy) * w + (x + d.dx)
        if (x + d.dx !in 0 until w || y + d.dy !in 0 until h || t in walls) return false
        if (t in boxes) {
            val bx = x + 2 * d.dx; val by = y + 2 * d.dy
            val b = by * w + bx
            if (bx !in 0 until w || by !in 0 until h || b in walls || b in boxes) return false
            history.add(Triple(player, HashSet(boxes), pushes))
            boxes.remove(t); boxes.add(b); pushes++
        } else history.add(Triple(player, HashSet(boxes), pushes))
        player = t; moves++
        return true
    }

    fun undo(): Boolean {
        val (p, b, pu) = history.removeLastOrNull() ?: return false
        player = p; boxes = HashSet(b); pushes = pu; moves--
        return true
    }

    fun solved() = boxes == goals

    companion object {
        /** 关卡按难度排；每一关都用单元测试里的求解器验证过有解。 */
        val LEVELS = listOf(
            "#######\n#@ \$ .#\n#######",
            "######\n#    #\n# #\$ #\n# \$@.#\n#.   #\n######",
            " #####\n #   ##\n # \$  #\n## \$  #\n#.. @ #\n#######",
            "########\n#      #\n# .**\$@#\n#      #\n#####  #\n    ####",
            "########\n#      #\n# .\$\$. #\n#  @   #\n# .\$\$. #\n#      #\n########",
            "  ########\n  #   #  #\n### \$\$ . #\n#   \$@## #\n# .\$ .   #\n### . ####\n  #####",
            "  ######\n  #    #\n### \$\$ #\n#  \$   #\n# . .###\n#  .@#\n######",
            " #######\n##  .  ##\n# \$ # \$ #\n#  .@.  #\n# \$ # \$ #\n##  .  ##\n #######",
            " ####\n #  ###\n #@\$  #\n### # ##\n#.# #  #\n#.\$  # #\n#.   \$ #\n########",
            "#########\n#   #   #\n# \$ \$ \$ #\n#.# @ #.#\n#.  \$  .#\n#########",
            "##########\n#   ##   #\n# \$    \$ #\n#  #..#  #\n#@ #..#  #\n# \$    \$ #\n#        #\n##########",
        )
    }
}

// =====================================================================
// 五子棋
// =====================================================================

/** 五子棋：15×15，玩家执黑先走，电脑执白；电脑按棋形打分（进攻和防守一起算）选点。 */
class Gomoku(private val random: java.util.Random = java.util.Random()) {
    val n = 15
    /** 0 空，1 黑（玩家），2 白（电脑）。 */
    val board = IntArray(n * n)
    val history = ArrayList<Int>()
    var winner = 0; private set

    fun full() = history.size == n * n

    /** 玩家下一步，电脑马上应一步。返回是否下成功。 */
    fun play(i: Int): Boolean {
        if (winner != 0 || board[i] != 0) return false
        put(i, 1)
        if (winner == 0 && !full()) put(aiMove(), 2)
        return true
    }

    private fun put(i: Int, who: Int) {
        board[i] = who; history.add(i)
        if (fiveAt(i)) winner = who
    }

    /** 悔棋：退回玩家的上一步（连同电脑的应手）。 */
    fun undo(): Boolean {
        if (history.isEmpty()) return false
        val back = if (board[history.last()] == 2 && history.size >= 2) 2 else 1
        repeat(back) { board[history.removeAt(history.size - 1)] = 0 }
        winner = 0
        return true
    }

    private val dirs = arrayOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)

    fun fiveAt(i: Int): Boolean {
        val who = board[i]
        if (who == 0) return false
        val x = i % n; val y = i / n
        return dirs.any { (dx, dy) -> 1 + run(x, y, dx, dy, who) + run(x, y, -dx, -dy, who) >= 5 }
    }

    private fun run(x: Int, y: Int, dx: Int, dy: Int, who: Int): Int {
        var k = 0
        var cx = x + dx; var cy = y + dy
        while (cx in 0 until n && cy in 0 until n && board[cy * n + cx] == who) { k++; cx += dx; cy += dy }
        return k
    }

    private fun openEnd(x: Int, y: Int, dx: Int, dy: Int, len: Int): Boolean {
        val cx = x + dx * (len + 1); val cy = y + dy * (len + 1)
        return cx in 0 until n && cy in 0 until n && board[cy * n + cx] == 0
    }

    /** 假设 who 下在 i，这一步的棋形分。 */
    fun score(i: Int, who: Int): Int {
        val x = i % n; val y = i / n
        var total = 0
        for ((dx, dy) in dirs) {
            val a = run(x, y, dx, dy, who); val b = run(x, y, -dx, -dy, who)
            val len = 1 + a + b
            val open = (if (openEnd(x, y, dx, dy, a)) 1 else 0) + (if (openEnd(x, y, -dx, -dy, b)) 1 else 0)
            total += when {
                len >= 5 -> 1_000_000
                len == 4 && open == 2 -> 100_000
                len == 4 && open == 1 -> 10_000
                len == 3 && open == 2 -> 8_000
                len == 3 && open == 1 -> 500
                len == 2 && open == 2 -> 300
                len == 2 && open == 1 -> 50
                len == 1 && open == 2 -> 10
                else -> 1
            }
        }
        return total
    }

    fun aiMove(): Int {
        if (history.isEmpty()) return (n / 2) * n + n / 2
        var best = -1; var bestScore = -1
        for (i in 0 until n * n) {
            if (board[i] != 0 || !nearStone(i)) continue
            // 自己能赢优先，其次堵对方
            val s = score(i, 2) * 11 / 10 + score(i, 1) + random.nextInt(3)
            if (s > bestScore) { bestScore = s; best = i }
        }
        return if (best >= 0) best else board.indexOfFirst { it == 0 }
    }

    private fun nearStone(i: Int): Boolean {
        val x = i % n; val y = i / n
        for (dy in -2..2) for (dx in -2..2) {
            val cx = x + dx; val cy = y + dy
            if (cx in 0 until n && cy in 0 until n && board[cy * n + cx] != 0) return true
        }
        return false
    }
}

// =====================================================================
// 记忆翻牌
// =====================================================================

/** 记忆翻牌：成对的符号扣着放，一次翻两张，一样就留着。 */
class MemoryGame(val cols: Int = 6, val rows: Int = 4, private val random: java.util.Random = java.util.Random()) {
    val cards: IntArray
    val matched = BooleanArray(cols * rows)
    /** 当前翻开还没配对的牌（最多两张）。 */
    val up = ArrayList<Int>(2)
    var moves = 0; private set

    init {
        val pairs = cols * rows / 2
        cards = ((0 until pairs) + (0 until pairs)).shuffled(random).toIntArray()
    }

    /** 两张不一样的牌还翻着时，要先扣回去。 */
    fun mismatchShowing() = up.size == 2 && cards[up[0]] != cards[up[1]]

    fun hide() { if (mismatchShowing()) up.clear() }

    fun flip(i: Int): Boolean {
        if (matched[i] || i in up) return false
        if (up.size == 2) up.clear()
        up.add(i)
        if (up.size == 2) {
            moves++
            if (cards[up[0]] == cards[up[1]]) { matched[up[0]] = true; matched[up[1]] = true; up.clear() }
        }
        return true
    }

    fun won() = matched.all { it }
}
