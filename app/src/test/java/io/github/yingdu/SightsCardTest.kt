package io.github.yingdu

import org.junit.Assert.assertEquals
import org.junit.Test

class SightsCardTest {
    @Test fun nearestFirst() {
        val here = 39.9087 to 116.3975
        val far = Sight("a1", "颐和园", 39.9999, 116.2755, "公园", famous = true)
        val near = Sight("a2", "故宫", 39.9163, 116.3972, "博物馆", famous = true)
        val mid = Sight("a3", "景山公园", 39.9254, 116.3967, "公园")
        val rows = Sights.cardRows(listOf(far, mid, near), here.first, here.second)
        assertEquals(listOf("★故宫", "景山公园", "★颐和园"), rows.map { it.left })
        assertEquals("", rows[0].mid)
        assertEquals("850 米", rows[0].right)
        // 存下来再读回来一样
        val back = Sights.fromJson(Sights.toJson(listOf(far, near)))
        assertEquals(listOf("颐和园", "故宫"), back.map { it.name })
        assertEquals(true, back[0].famous)
        assertEquals(emptyList<Sight>(), Sights.fromJson("bad"))
    }

    @Test fun collapseInsideOneSite() {
        val gugong = Sight("a1", "故宫博物院", 39.9163, 116.3972, "博物馆", distance = 900, famous = true)
        val hall = Sight("a2", "太和殿", 39.9150, 116.3970, "景点", distance = 750, parent = "a1")
        val case = Sight("a3", "故宫博物院-钟表馆展柜", 39.9170, 116.3980, "景点", distance = 950)
        val gate = Sight("a4", "故宫博物院午门入口", 39.9130, 116.3971, "景点", distance = 500)
        val spot = Sight("a5", "网红打卡点", 39.9120, 116.3960, "景点", distance = 300)
        // 整体没搜到的子点：同一个父 POI 的并成一个，名字相同前缀的用前缀
        val p1 = Sight("a6", "人民公园-儿童乐园", 39.93, 116.40, "公园", distance = 1200)
        val p2 = Sight("a7", "人民公园(游船码头)", 39.931, 116.401, "公园", distance = 1300, famous = true)
        val east = Sight("a8", "上海博物馆(东馆)", 39.95, 116.42, "博物馆", distance = 2000)
        val far = Sight("a9", "故宫博物院-南京分院", 32.0, 118.8, "博物馆", distance = 2900)
        val en1 = Sight("g1", "Saint-Pierre", 48.0, 2.0, "教堂", distance = 100)
        val en2 = Sight("g2", "Saint-Michel", 48.001, 2.001, "教堂", distance = 200)
        val out = Sights.collapse(listOf(gugong, hall, case, gate, spot, p1, p2, east, far, en1, en2))
        assertEquals(listOf("Saint-Pierre", "Saint-Michel", "故宫博物院", "人民公园", "上海博物馆(东馆)", "故宫博物院-南京分院"),
            out.map { it.name })
        assertEquals(1200, out[3].distance)
        assertEquals(true, out[3].famous)
        // 父 POI 存下来再读回来
        assertEquals("a1", Sights.fromJson(Sights.toJson(listOf(hall)))[0].parent)
    }
}
