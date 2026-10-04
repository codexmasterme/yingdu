package io.github.yingdu

import android.content.Context
import java.util.Random

/*
 * 聚会工具：骰子、抽签、真心话大冒险。结果只画在眼镜上——手机界面、通知栏、日志里都不出现，
 * 只有戴眼镜的人看得到。规则（PartyLogic）不依赖 Android，方便单元测试。
 */

/** 不重复地抽：抽完一轮再重新洗。 */
class Deck<T>(items: List<T>, private val random: Random) {
    private val all = items.toList()
    private val pool = ArrayList<T>()
    val size get() = all.size
    val left get() = pool.size

    fun draw(): T? {
        if (all.isEmpty()) return null
        if (pool.isEmpty()) { pool.addAll(all); pool.shuffle(random) }
        return pool.removeAt(pool.size - 1)
    }
}

object PartyLogic {
    fun roll(n: Int, random: Random): List<Int> = List(n.coerceIn(1, 6)) { 1 + random.nextInt(6) }

    /** 骰子点数在 3×3 格子里的位置（行, 列）。 */
    fun pips(face: Int): List<Pair<Int, Int>> = when (face) {
        1 -> listOf(1 to 1)
        2 -> listOf(0 to 2, 2 to 0)
        3 -> listOf(0 to 2, 1 to 1, 2 to 0)
        4 -> listOf(0 to 0, 0 to 2, 2 to 0, 2 to 2)
        5 -> listOf(0 to 0, 0 to 2, 1 to 1, 2 to 0, 2 to 2)
        6 -> listOf(0 to 0, 0 to 2, 1 to 0, 1 to 2, 2 to 0, 2 to 2)
        else -> emptyList()
    }

    /** 签筒：每行一支签，去掉空行和首尾空白。 */
    fun parseLots(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    const val DEFAULT_LOTS = "1 号\n2 号\n3 号\n4 号\n5 号\n6 号"

    val TRUTHS = listOf(
        "你最近一次哭是因为什么？", "你手机相册里最后一张照片是什么？", "说一个你从没告诉过别人的小秘密。",
        "你做过最尴尬的一件事是什么？", "在场的人里，你最想和谁交换一天生活？", "你小时候最怕什么？",
        "你最近一次说谎是什么时候？说了什么？", "你第一次心动是几岁？", "你做过最疯狂的一件事是什么？",
        "你最想删掉自己发过的哪条动态？", "如果可以隐身一天，你会做什么？", "你最近搜索过最奇怪的东西是什么？",
        "你有什么奇怪的小习惯？", "你对在场哪个人的第一印象和现在最不一样？", "你最后悔的一次花钱是什么？",
        "你收到过最难忘的礼物是什么？", "你手机里最常用的三个 app 是什么？", "你偷偷喜欢过谁？说出名字的首字母。",
        "你最近一次被夸是因为什么？", "你最想回到哪一年？为什么？", "你睡前最后一件事通常是做什么？",
        "你觉得自己最大的优点和缺点是什么？", "如果明天是世界末日，今天你会做什么？", "你有没有假装喜欢过别人送的礼物？",
        "你最想对十年前的自己说什么？", "你做过最幼稚的事是什么？", "你在社交软件上屏蔽过谁吗？为什么？",
        "你理想中的周末是什么样的？", "你最近一次熬夜是在干什么？", "说一件你一直想做却还没做的事。",
    )

    val DARES = listOf(
        "用播音腔念出你最近一条聊天记录。", "模仿在场的一个人，让大家猜是谁。", "原地转三圈，然后走一条直线。",
        "用三种不同的语气说「我好饿」。", "学一种动物叫，坚持 10 秒。", "做一个鬼脸，保持 5 秒。",
        "唱一首歌的副歌部分。", "用屁股写出自己的名字。", "和右边的人对视 10 秒，不许笑。",
        "一口气说完：四是四，十是十，十四是十四，四十是四十。", "用方言说一句「我爱你」。", "跳一段 10 秒的舞。",
        "表演一个你最近看过的电影片段。", "夸在场每个人一句，不许重复。", "用最深情的语气朗读一句广告词。",
        "单脚站立 20 秒。", "让右边的人给你起个外号，今晚大家都这么叫你。", "倒着说一句话，让大家猜原句。",
        "做 10 个深蹲。", "用表情模仿一个表情包，让大家猜是哪个。", "像机器人一样说话，直到下一轮。",
        "对着空气深情表白 10 秒。", "把自己的名字用 rap 唱出来。", "模仿一位老师或老板说话。",
        "到下一轮之前，说话都要用问句。", "用鼻子哼一首歌，让大家猜歌名。", "和左边的人交换一样随身小物，直到游戏结束。",
        "讲一个冷笑话，没人笑就再讲一个。", "用左手（或不常用的手）画一幅在场某人的肖像。", "给大家表演一段 10 秒的默剧。",
    )
}

class PartyApp(private val host: AppHost) : GlassesApp {
    enum class Tool(val zh: String) { DICE("骰子"), LOTS("抽签"), TRUTH("真心话大冒险") }

    private val prefs = host.context.getSharedPreferences("party", Context.MODE_PRIVATE)
    private val random = Random()

    override val title = "聚会工具"

    var tool: Tool = Tool.DICE
        set(v) { field = v; anim = 0; show() }

    var diceCount: Int
        get() = prefs.getInt("dice", 2).coerceIn(1, 6)
        set(v) { prefs.edit().putInt("dice", v.coerceIn(1, 6)).apply(); dice = emptyList(); show() }

    var lotsText: String
        get() = prefs.getString("lots", PartyLogic.DEFAULT_LOTS) ?: PartyLogic.DEFAULT_LOTS
        set(v) { prefs.edit().putString("lots", v).apply(); lots = Deck(PartyLogic.parseLots(v), random); lot = null; show() }

    // 结果（只画在眼镜上）
    private var dice: List<Int> = emptyList()
    private var lots = Deck(PartyLogic.parseLots(lotsText), random)
    private var lot: String? = null
    private val truths = Deck(PartyLogic.TRUTHS, random)
    private val dares = Deck(PartyLogic.DARES, random)
    private var card: Pair<Boolean, String>? = null   // (是大冒险, 题目)
    /** 这是第几次（手机上只显示次数，不显示结果）。 */
    var count = 0; private set
    /** 还剩几支签。 */
    val lotsLeft get() = if (lots.left == 0 && lot == null) lots.size else lots.left
    val lotsTotal get() = lots.size

    /** 摇骰子、抽签时先闪几帧随机的，再停在结果上。 */
    private var anim = 0

    // ---------- 手机上的操作 ----------

    fun rollDice() { tool = Tool.DICE; dice = PartyLogic.roll(diceCount, random); go() }

    fun drawLot() {
        tool = Tool.LOTS
        if (lots.size == 0) { lot = null; show(); return }
        lot = lots.draw(); go()
    }

    /** 把签全部放回签筒。 */
    fun resetLots() { lots = Deck(PartyLogic.parseLots(lotsText), random); lot = null; show() }

    /** dare：true 大冒险，false 真心话，null 随机。 */
    fun truthOrDare(dare: Boolean?) {
        tool = Tool.TRUTH
        val d = dare ?: random.nextBoolean()
        card = d to ((if (d) dares else truths).draw() ?: "")
        count++; anim = 0; show()
    }

    private fun go() { count++; anim = ANIM_FRAMES; show() }

    // ---------- 画面 ----------

    private var open = false

    private fun show() { if (open) { host.main.removeCallbacks(loop); host.main.post(loop) }; host.changed() }

    private val loop = object : Runnable {
        override fun run() {
            host.redraw(this@PartyApp)
            if (anim > 0) {
                anim--
                host.main.postDelayed(this, maxOf(160L, (host.frameIntervalMs() ?: 0) + 40))
            }
        }
    }

    override fun onOpen() { open = true; host.main.removeCallbacks(loop); host.main.post(loop) }
    override fun onClose() { open = false; anim = 0; host.main.removeCallbacks(loop) }

    override fun onInput(input: GlassesInput) {
        when (input) {
            GlassesInput.CLICK_RIGHT -> when (tool) { Tool.DICE -> rollDice(); Tool.LOTS -> drawLot(); Tool.TRUTH -> truthOrDare(null) }
            GlassesInput.CLICK_LEFT -> tool = Tool.values()[(tool.ordinal + 1) % Tool.values().size]
            else -> {}
        }
    }

    /** 通知栏里也不露结果。 */
    override fun status() = "$title · ${tool.zh}"

    override fun render(fontPx: Int): ByteArray {
        val f = Frame(fontPx)
        when (tool) {
            Tool.DICE -> drawDice(f)
            Tool.LOTS -> drawLots(f)
            Tool.TRUTH -> drawTruth(f)
        }
        return f.done()
    }

    private fun drawDice(f: Frame) {
        val rolling = anim > 0
        val faces = if (rolling) List(diceCount) { 1 + random.nextInt(6) } else dice
        f.header("骰子 · ${diceCount} 个", if (!rolling && faces.size > 1) "合计 ${faces.sum()}" else "")
        if (faces.isEmpty()) { f.text("在手机上点「摇骰子」", f.w / 2, 80, Frame.DIM, f.small, Frame.CENTER); return }
        val n = faces.size
        val gap = 14
        val size = minOf(96, (f.w - 24 - gap * (n - 1)) / n)
        val total = n * size + (n - 1) * gap
        var x = (f.w - total) / 2
        val top = 32 + (f.h - 32 - size) / 2 + (if (rolling) (anim % 2) * 6 - 3 else 0)
        for (v in faces) { die(f, v, x, top, size, rolling); x += size + gap }
    }

    /** 一颗骰子：1 像素边框切圆角，点数是方块（摇的时候半亮）。 */
    private fun die(f: Frame, v: Int, x: Int, y: Int, s: Int, dim: Boolean) {
        val c = if (dim) Frame.DIM else Frame.FULL
        val r = maxOf(2, s / 14)
        f.rect(x + r, y, s - 2 * r, 2, c); f.rect(x + r, y + s - 2, s - 2 * r, 2, c)
        f.rect(x, y + r, 2, s - 2 * r, c); f.rect(x + s - 2, y + r, 2, s - 2 * r, c)
        // 切掉的四个角各补一个点，看起来是圆角
        val q = r / 2
        for ((cx, cy) in listOf(x + q to y + q, x + s - q - 2 to y + q, x + q to y + s - q - 2, x + s - q - 2 to y + s - q - 2)) f.rect(cx, cy, 2, 2, c)
        val p = maxOf(4, s / 6)
        val cell = (s - 2 * r) / 3
        for ((row, col) in PartyLogic.pips(v)) {
            f.rect(x + r + col * cell + (cell - p) / 2, y + r + row * cell + (cell - p) / 2, p, p, c)
        }
    }

    private fun drawLots(f: Frame) {
        val rolling = anim > 0
        val items = PartyLogic.parseLots(lotsText)
        f.header("抽签", if (items.isEmpty()) "" else "还剩 $lotsLeft/${items.size} 支")
        val shown = if (rolling && items.isNotEmpty()) items[random.nextInt(items.size)] else lot
        if (shown == null) {
            f.text(if (items.isEmpty()) "在手机上写好签（每行一支）" else "在手机上点「抽一支」", f.w / 2, 80, Frame.DIM, f.small, Frame.CENTER)
            return
        }
        // 签：一个竖着的框里写结果，太长就换小字、折行
        val big = GlassesFonts.text(32)
        val font = if (big.measure(shown) <= f.w - 40) big else f.font
        val lines = font.wrap(shown, f.w - 40).take(3)
        val lh = font.height + 6
        val bh = lines.size * lh + 20
        val bw = (lines.maxOf { font.measure(it) } + 40).coerceAtLeast(120)
        val bx = (f.w - bw) / 2; val by = 32 + (f.h - 32 - bh) / 2
        f.box(bx, by, bw, bh, if (rolling) Frame.FAINT else Frame.DIM)
        lines.forEachIndexed { i, l -> f.text(l, f.w / 2, by + 10 + i * lh + 3, if (rolling) Frame.DIM else Frame.FULL, font, Frame.CENTER) }
    }

    private fun drawTruth(f: Frame) {
        val c = card
        f.header("真心话大冒险", "")
        if (c == null) { f.text("在手机上点「真心话」「大冒险」或「随机」", f.w / 2, 80, Frame.DIM, f.small, Frame.CENTER); return }
        val (dare, q) = c
        val label = if (dare) "大冒险" else "真心话"
        val big = GlassesFonts.text(24)
        val lines = f.font.wrap(q, f.w - 24).take(4)
        val lh = f.font.height + 6
        // 「真心话 / 大冒险」和题目作为一块，在标题线下面上下居中
        val block = big.height + 14 + lines.size * lh - 6
        val top = 32 + ((f.h - 32 - block) / 2).coerceAtLeast(0)
        f.text(label, f.w / 2, top, Frame.FULL, big, Frame.CENTER)
        lines.forEachIndexed { i, l -> f.text(l, f.w / 2, top + big.height + 14 + i * lh, Frame.FULL, f.font, Frame.CENTER) }
    }

    companion object { private const val ANIM_FRAMES = 5 }
}
