package io.github.yingdu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * 手机首页「功能」里的像素图标：实心色块，细节（字行、指针、骰子点、声波）挖空透出底色。
 * 每行一个字符串，'.' 透明，其他字符按 colors 上色。数据不依赖 Android，有单元测试。
 */
class PixelIcon(val rows: List<String>, val colors: Map<Char, Int>) {
    val w get() = rows.maxOf { it.length }
    val h get() = rows.size

    fun bitmap(scale: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w * scale, h * scale, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        rows.forEachIndexed { r, line ->
            line.forEachIndexed cell@{ col, ch ->
                p.color = colors[ch] ?: return@cell
                c.drawRect((col * scale).toFloat(), (r * scale).toFloat(), ((col + 1) * scale).toFloat(), ((r + 1) * scale).toFloat(), p)
            }
        }
        return bmp
    }
}

object PhoneIcons {
    private fun c(v: Long) = v.toInt()

    val READ = PixelIcon(listOf(
        ".#####oo#####.",
        "######oo######",
        "#....#oo#....#",
        "######oo######",
        "#....#oo#....#",
        "######oo######",
        "#....#oo#....#",
        "######oo######",
        "######oo######",
        ".####oooo####.",
        "...oooooooo...",
    ), mapOf('#' to c(0xFF5F87B3), 'o' to c(0xFF3F6590)))


    val POMO = PixelIcon(listOf(
        "......gg......",
        "...gg.gg.gg...",
        "....gggggg....",
        "..###gggg###..",
        ".############.",
        "##############",
        "##++##########",
        "#++###########",
        "#+############",
        "##############",
        ".############.",
        "..##########..",
        "....######....",
    ), mapOf('#' to c(0xFFDE5B49), '+' to c(0xFFF2A194), 'g' to c(0xFF6FA35B)))

    val CLOCK = PixelIcon(listOf(
        "....#####....",
        "..#########..",
        ".###########.",
        ".#####.#####.",
        "######.######",
        "######.######",
        "######....###",
        "#############",
        "#############",
        ".###########.",
        ".###########.",
        "..#########..",
        "....#####....",
    ), mapOf('#' to c(0xFF3F9AA3)))

    val GAMES = PixelIcon(listOf(
        ".#####....#####.",
        "################",
        "####.#######rr##",
        "###...##yy##rr##",
        "####.###yy######",
        "################",
        "######....######",
        "#####......#####",
        ".###........###.",
    ), mapOf('#' to c(0xFF58636F), 'r' to c(0xFFDE5B49), 'y' to c(0xFFE8B23E)))

    val PARTY = PixelIcon(listOf(
        ".##########.",
        "############",
        "##..####..##",
        "##..####..##",
        "############",
        "#####..#####",
        "#####..#####",
        "############",
        "##..####..##",
        "##..####..##",
        "############",
        ".##########.",
    ), mapOf('#' to c(0xFFD9A441)))

    val OFFICIAL = PixelIcon(listOf(
        "#######..#######",
        "#lllll####lllll#",
        "#l+lll#..#l+lll#",
        "#lllll#..#lllll#",
        "#lllll#..#lllll#",
        ".#####....#####.",
    ), mapOf('#' to c(0xFF3D3A36), 'l' to c(0xFF8FB9DA), '+' to c(0xFFFFFFFF)))

    val MEMORY = PixelIcon(listOf(
        ".#############.",
        "###############",
        "#######.#######",
        "#####.#.#.#####",
        "###.#.#.#.#.###",
        "#####.#.#.#####",
        "#######.#######",
        "###############",
        ".#############.",
        "..####.........",
        "..###..........",
        "..##...........",
    ), mapOf('#' to c(0xFFD9788F)))

    val SIGHTS = PixelIcon(listOf(
        "..######..",
        ".########.",
        "####..####",
        "###....###",
        "###....###",
        "####..####",
        ".########.",
        ".########.",
        "..######..",
        "...####...",
        "....##....",
        "oooooooooo",
    ), mapOf('#' to c(0xFF5B6BC0), 'o' to c(0xFFC5CBEB)))

    /** 使用手册：一本书，封面上挖出一个问号。 */
    val MANUAL = PixelIcon(listOf(
        ".###########",
        "############",
        "####....####",
        "###..##..###",
        "#######..###",
        "######..####",
        "#####..#####",
        "#####..#####",
        "############",
        "#####..#####",
        "############",
        "oooooooooooo",
    ), mapOf('#' to c(0xFF2E8B7A), 'o' to c(0xFFB8DDD5)))

    /** 实时字幕：一块屏幕，下面两行字。 */
    val CAPTIONS = PixelIcon(listOf(
        "##############",
        "#............#",
        "#............#",
        "#............#",
        "#.oooooooo...#",
        "#............#",
        "#.oooo.oooooo#",
        "#............#",
        "##############",
    ), mapOf('#' to c(0xFF4A7FB5), 'o' to c(0xFFE0A33A)))

    val ALL = listOf(READ, POMO, CLOCK, GAMES, PARTY, OFFICIAL, MEMORY, CAPTIONS, SIGHTS, MANUAL)
}
