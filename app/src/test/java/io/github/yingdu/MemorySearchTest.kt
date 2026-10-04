package io.github.yingdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class MemorySearchTest {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private fun e(day: String, hm: String, text: String, sec: Long = 10) =
        MemoryEntry(fmt.parse("$day $hm")!!.time, sec * 1000, "x.ogg", text)

    private val data = mapOf(
        "2026-09-29" to listOf(
            e("2026-09-29", "09:00", "明天下午三点和张三在星巴克见面"),
            e("2026-09-29", "09:00", "记得带上合同", 5).let { MemoryEntry(it.start + 12_000, 5000, "y.ogg", it.text) },
            e("2026-09-29", "15:30", "今天天气不错 Weather is nice"),
        ),
        "2026-09-28" to listOf(
            e("2026-09-28", "12:10", "中午和李四吃了火锅，他说下周去上海出差"),
        ),
    )
    private val sums = mapOf(
        "2026-09-28" to MemorySummary("2026-09-28", 0, "和李四吃饭，聊了出差", listOf("李四下周去上海"), listOf("订机票" to "周五前"), listOf("火锅店在人民路"), "m"),
    )
    private val days = data.keys.sortedDescending()
    private fun search(q: String, gap: Long = 60_000) = MemorySearch.search(days, { data[it].orEmpty() }, { sums[it] }, q, gap)

    @Test fun exactAllTermsNewestFirst() {
        val r = search("张三 星巴克")
        assertFalse(r.fuzzy)
        assertEquals(1, r.hits.size)
        val h = r.hits[0]
        assertEquals("2026-09-29", h.day)
        // 两句隔 2 秒，合成一段对话
        assertEquals(2, h.group!!.entries.size)
        assertTrue(h.text.contains("记得带上合同"))
        // 高亮位置正好是这两个词
        assertEquals(setOf("张三", "星巴克"), h.ranges.map { h.text.substring(it.first, it.last + 1) }.toSet())
    }

    @Test fun caseInsensitiveAndSummaryLines() {
        assertEquals(1, search("WEATHER").hits.size)
        val r = search("李四")
        // 当天回顾里的一条也能搜到，排在同一天的对话前面
        assertEquals(listOf("当天概要", "聊了什么", ""), r.hits.map { it.label })
        assertEquals("要做的事", search("机票").hits.single().label)
        assertTrue(search("机票").hits.single().text.contains("周五前"))
    }

    @Test fun fuzzyFallbackForQuestions() {
        val r = search("我跟张三约在哪见面")
        assertTrue(r.fuzzy)
        assertTrue(r.hits.isNotEmpty())
        assertTrue(r.hits[0].text.contains("星巴克"))
        assertTrue(search("完全无关的内容").hits.isEmpty())
        assertTrue(search("李四什么时候去上海").hits.any { it.text.contains("下周去上海") })
        // 去掉虚词后的关键词组，不跨段
        assertEquals(setOf("张三", "见面"), MemorySearch.bigrams("我跟张三约在哪见面"))
        assertTrue(search("  ").hits.isEmpty())
    }

    @Test fun snippetKeepsHighlightsAligned() {
        val text = "一".repeat(100) + "张三" + "二".repeat(200)
        val (s, rs) = MemorySearch.snippet(text, listOf(100..101), before = 10, total = 50)
        assertTrue(s.startsWith("…") && s.endsWith("…"))
        assertEquals("张三", s.substring(rs[0].first, rs[0].last + 1))
        val (s2, rs2) = MemorySearch.snippet("短句张三", listOf(2..3))
        assertEquals("短句张三", s2)
        assertEquals(listOf(2..3), rs2)
    }

    @Test fun askContextHasSummariesAndRelevantLines() {
        val ctx = MemorySearch.askContext(days, { data[it].orEmpty() }, { sums[it] }, "李四什么时候去上海", 60_000)
        assertTrue(ctx.contains("【2026-09-28】和李四吃饭"))
        assertTrue(ctx.contains("订机票（周五前）"))
        assertTrue(ctx.contains("[9月28日 12:10] 中午和李四吃了火锅"))
        assertFalse(ctx.contains("星巴克"))
        // 长度有上限
        assertTrue(MemorySearch.askContext(days, { data[it].orEmpty() }, { sums[it] }, "李四", 60_000, maxChars = 60).length <= 120)
    }

    @Test fun askRequestAndParse() {
        val b = MemoryAsk.claudeBody("claude-opus-5", "2026-09-30", "问题", "材料")
        assertEquals("default", b.getString("fallbacks"))
        assertTrue(b.getString("system").contains("今天是 2026-09-30"))
        assertTrue(b.getJSONArray("messages").getJSONObject(0).getString("content").endsWith("问题：问题"))
        val claude = """{"stop_reason":"end_turn","content":[{"type":"thinking","thinking":""},{"type":"text","text":"9月29日 09:00 说在星巴克。"}]}"""
        assertEquals("9月29日 09:00 说在星巴克。", MemoryAsk.parse("claude", claude))
        val openai = """{"choices":[{"message":{"content":" 没找到。 "}}]}"""
        assertEquals("没找到。", MemoryAsk.parse("siliconflow", openai))
        val refused = """{"stop_reason":"refusal","stop_details":{"category":"cyber"},"content":[]}"""
        assertTrue(runCatching { MemoryAsk.parse("claude", refused) }.exceptionOrNull()!!.message!!.contains("拒绝"))
    }
}
