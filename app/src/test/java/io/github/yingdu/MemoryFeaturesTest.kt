package io.github.yingdu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 合成的"人声"：基频 f0 附近起伏、带谐波，每 0.4 秒说 0.25 秒；amp 是音量，low 越大低频越重。 */
private fun voice(seconds: Double, f0: Double, amp: Double, rnd: java.util.Random, low: Double = 1.0): ShortArray {
    var ph = 0.0
    return ShortArray((seconds * 16000).toInt()) { i ->
        val t = i / 16000.0
        ph += 2 * Math.PI * (f0 * (1 + 0.15 * Math.sin(2 * Math.PI * 0.7 * t))) / 16000
        var s = 0.0
        for (k in 1..10) s += Math.sin(k * ph) / k * (if (k <= 2) low else 1.0)
        val env = if (t % 0.4 < 0.25) 1.0 else 0.0
        (amp * s * env + rnd.nextGaussian() * 300).toInt().coerceIn(-32768, 32767).toShort()
    }
}

private fun hiss(seconds: Double, rnd: java.util.Random) = ShortArray((seconds * 16000).toInt()) { (rnd.nextGaussian() * 300).toInt().toShort() }

class SummaryTitleTest {
    @Test fun titleAndMoodParsedAndKept() {
        val j = JSONObject("""{"title":"「社保窗口连轴转，车辆警报频触发」","mood":"😮‍💨 累","overview":"忙","topics":["a"],"todos":[],"notes":[]}""")
        val s = DaySummarizer.toSummary("2026-09-30", j, "m")
        assertEquals("社保窗口连轴转，车辆警报频触发", s.title)
        assertTrue(s.mood.startsWith("😮"))
        assertFalse(s.mood.contains("累"))
        val back = MemorySummary.fromJson(s.toJson())
        assertEquals(s.title, back.title); assertEquals(s.mood, back.mood)
        // 旧的总结没有这两项
        val old = MemorySummary.fromJson(JSONObject("""{"day":"2026-09-01","overview":"x","topics":[],"todos":[],"notes":[],"model":"m"}"""))
        assertEquals("", old.title); assertEquals("", old.mood)
        assertEquals("", DaySummarizer.moodOf("开心"))
        assertEquals("", DaySummarizer.moodOf(""))
    }

    @Test fun schemaAsksForTitleAndMood() {
        val req = DaySummarizer.schema().getJSONArray("required").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertTrue("title" in req && "mood" in req)
        assertTrue(DaySummarizer.SYSTEM.contains("吐槽"))
    }

    @Test fun transcriptMarksSpeakers() {
        val me = Speakers.Voice(-10.0, 120.0, 0.5); val other = Speakers.Voice(-30.0, 220.0, 0.2)
        val e = MemoryEntry(0, 5000, "a.ogg", "你好。在吗", listOf(MemoryTurn(0, "你好", me), MemoryTurn(2000, "在吗", other)))
        val old = MemoryEntry(10_000, 1000, "b.ogg", "旧的")
        val t = DaySummarizer.transcriptText(listOf(e, old)) { v -> v?.let { it.db > -20 } }
        val lines = t.lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].endsWith("我：你好")); assertTrue(lines[1].endsWith("他人：在吗")); assertTrue(lines[2].endsWith("] 旧的"))
        // 不分辨时一律不标
        assertFalse(DaySummarizer.transcriptText(listOf(e)).contains("我："))
    }
}

class SpeakerTurnsStoreTest {
    @Test fun turnsRoundTripAndMerge() {
        val dir = java.nio.file.Files.createTempDirectory("mem").toFile()
        val st = MemoryStore(dir)
        val a = Speakers.Voice(-12.0, 130.0, 0.4); val b = Speakers.Voice(-31.0, 230.0, 0.1)
        st.addEntry("2026-09-30", MemoryEntry(1, 6000, "x.ogg", "一。二。三",
            listOf(MemoryTurn(0, "一", a), MemoryTurn(1500, "二", a), MemoryTurn(3000, "三", b))))
        val e = st.entries("2026-09-30").single()
        assertEquals(3, e.turns.size)
        assertEquals(-31.0, e.turns[2].voice!!.db, 0.01)
        assertEquals(1500L, e.turns[1].offsetMs)
        val ls = e.lines { v -> v!!.db > -20 }
        assertEquals(2, ls.size)                   // 连着两轮都是我：接在一起
        assertEquals(true, ls[0].me); assertEquals("一。二", ls[0].text); assertEquals(2, ls[0].voices.size)
        assertEquals(false, ls[1].me)
        // 分不出是谁：全接成一句
        assertEquals(1, e.lines { null }.size)
        dir.deleteRecursively()
    }
}

class SpeakersTest {
    @Test fun splitsLoudOwnerFromQuietOther() {
        val rnd = java.util.Random(3)
        val me = voice(2.5, 120.0, 6000.0, rnd, low = 2.0)
        val other = voice(2.5, 230.0, 900.0, rnd)
        val pcm = hiss(1.0, rnd) + me + hiss(0.6, rnd) + other + hiss(0.5, rnd)
        val turns = Speakers.turns(pcm)
        assertEquals(2, turns.size)
        assertTrue("自己响得多：${turns[0].voice.db} vs ${turns[1].voice.db}", turns[0].voice.db > turns[1].voice.db + 8)
        assertTrue("音高：${turns[0].voice.pitch} ${turns[1].voice.pitch}", turns[0].voice.pitch < turns[1].voice.pitch)
        // 分界在两人之间的停顿里；整段不丢
        assertEquals(0, turns[0].from); assertEquals(pcm.size, turns.last().to)
        val cut = turns[1].from / 16000.0
        assertTrue("分界 $cut 秒", cut > 3.3 && cut < 4.2)
    }

    @Test fun oneSpeakerStaysOneTurn() {
        val rnd = java.util.Random(4)
        val pcm = hiss(1.0, rnd) + voice(2.0, 140.0, 5000.0, rnd) + hiss(0.5, rnd) + voice(2.0, 140.0, 5000.0, rnd)
        assertEquals(1, Speakers.turns(pcm).size)
        assertEquals(1, Speakers.turns(hiss(2.0, rnd)).size)   // 没人说话：整段一轮
    }

    @Test fun loudnessSplitNeedsTwoGroups() {
        val loud = List(20) { -10.0 + it % 3 }; val quiet = List(10) { -28.0 + it % 4 }
        val split = Speakers.loudSplit(loud + quiet)
        assertNotNull(split); assertTrue(split!! > -25 && split < -11)
        assertNull(Speakers.loudSplit(loud))                 // 只有一群
        assertNull(Speakers.loudSplit(loud + listOf(-30.0)))  // 少的一群太少
    }

    @Test fun modelLearnsFromLevelsAndCorrections() {
        val m = SpeakerModel(null)
        assertNull(m.isMe(Speakers.Voice(-10.0, 120.0, 0.4)))
        repeat(20) { m.observe(Speakers.Voice(-10.0 + it % 3, 120.0, 0.4)) }
        repeat(10) { m.observe(Speakers.Voice(-30.0 + it % 3, 220.0, 0.1)) }
        assertEquals(true, m.isMe(Speakers.Voice(-11.0, 125.0, 0.4)))
        assertEquals(false, m.isMe(Speakers.Voice(-29.0, 210.0, 0.1)))
        // 纠正过：按最像的例子
        m.teach(Speakers.Voice(-29.0, 210.0, 0.1), true)
        m.teach(Speakers.Voice(-11.0, 125.0, 0.4), false)
        assertEquals(true, m.isMe(Speakers.Voice(-28.0, 215.0, 0.12)))
        assertEquals(false, m.isMe(Speakers.Voice(-11.0, 120.0, 0.4)))
        assertEquals(2, m.taught)
    }

    @Test fun modelPersists() {
        val f = java.io.File(java.nio.file.Files.createTempDirectory("spk").toFile(), "speaker.json")
        SpeakerModel(f).teach(Speakers.Voice(-12.0, 130.0, 0.3), true)
        assertEquals(1, SpeakerModel(f).meCount)
        f.parentFile.deleteRecursively()
    }
}

class MemoryCalendarTest {
    @Test fun weekAndMonthCells() {
        val week = MemoryCalendar.cells("2026-09-30", month = false)   // 周三
        assertEquals((28..30).map { "2026-09-$it" } + (1..4).map { "2026-10-0$it" }, week)
        val month = MemoryCalendar.cells("2026-09-15", month = true)  // 9 月 1 日是周二
        assertEquals(35, month.size)
        assertEquals("2026-08-31", month.first()); assertEquals("2026-10-04", month.last())
        assertEquals(42, MemoryCalendar.cells("2026-03-10", month = true).size)  // 3 月 1 日是周日：6 行
        assertEquals("2026-09-23", MemoryCalendar.shift("2026-09-30", false, -1))
        assertEquals("2026-10-01", MemoryCalendar.shift("2026-09-30", true, 1))
        assertEquals("2026-01-01", MemoryCalendar.shift("2026-02-28", true, -1))
        assertEquals("2026年9月", MemoryCalendar.title("2026-09-30"))
        assertEquals(7, MemoryCalendar.dayOfMonth("2026-09-07"))
    }
}

class CaptionSegmenterTest {
    private class Rec : CaptionSegmenter.Listener {
        val partials = ArrayList<Pair<Int, Int>>(); val finals = ArrayList<Pair<Int, Int>>(); val dropped = ArrayList<Int>()
        override fun partial(id: Int, pcm: ShortArray) { partials.add(id to pcm.size) }
        override fun final(id: Int, pcm: ShortArray) { finals.add(id to pcm.size) }
        override fun dropped(id: Int) { dropped.add(id) }
    }

    private fun feedChunks(s: CaptionSegmenter, pcm: ShortArray) {
        var i = 0
        while (i < pcm.size) { val n = minOf(320, pcm.size - i); s.feed(pcm.copyOfRange(i, i + n)); i += n }
    }

    @Test fun sentencesWithPartials() {
        val rnd = java.util.Random(5)
        val r = Rec(); val s = CaptionSegmenter(r)
        feedChunks(s, hiss(1.0, rnd) + voice(3.0, 150.0, 5000.0, rnd) + hiss(1.5, rnd) + voice(2.0, 200.0, 5000.0, rnd) + hiss(1.5, rnd))
        assertEquals(2, r.finals.size)
        assertEquals(listOf(1, 2), r.finals.map { it.first })
        val sec = r.finals[0].second / 16000.0
        assertTrue("第一句 $sec 秒", sec in 2.8..4.2)
        assertTrue(r.partials.any { it.first == 1 })
        assertTrue(r.partials.filter { it.first == 1 }.zipWithNext().all { (a, b) -> b.second > a.second })
        assertNull(s.current)
        assertTrue(r.dropped.isEmpty())
    }

    @Test fun longSpeechIsCutAndFlushed() {
        val rnd = java.util.Random(6)
        val r = Rec(); val s = CaptionSegmenter(r)
        feedChunks(s, hiss(1.0, rnd) + voice(15.0, 150.0, 5000.0, rnd))
        assertEquals(1, r.finals.size)                 // 12 秒先断一句
        assertNotNull(s.current)
        s.flush()
        assertEquals(2, r.finals.size)
        assertNull(s.current)
    }

    @Test fun silenceProducesNothing() {
        val r = Rec(); val s = CaptionSegmenter(r)
        feedChunks(s, hiss(5.0, java.util.Random(7)))
        assertTrue(r.finals.isEmpty() && r.partials.isEmpty())
    }
}
