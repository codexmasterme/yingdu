package io.github.yingdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioCleanTest {
    private fun rms(x: ShortArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return Math.sqrt(s / (to - from))
    }

    /** 16 kHz：前 1 秒只有嘶嘶声，后 2 秒加上 200 Hz 的“人声”（带谐波），幅度按 scale 缩放。 */
    private fun signal(scale: Double): ShortArray {
        val r = java.util.Random(3)
        return ShortArray(48_000) { i ->
            var v = r.nextGaussian() * 300
            if (i >= 16_000) for (h in 1..5) v += 3000.0 / h * Math.sin(2 * Math.PI * 200 * h * i / 16_000)
            (v * scale).toInt().toShort()
        }
    }

    @Test fun quietRecordingIsBoostedAndPeaksLimited() {
        val quiet = signal(0.05)                                // 原本很小声（约 -40 dBFS）
        val y = AudioClean.process(quiet, AudioClean.Mode.NONE, boost = true)
        val gainDb = 20 * Math.log10(rms(y, 16_000, 48_000) / rms(quiet, 16_000, 48_000))
        assertTrue("gain $gainDb", gainDb > 15 && gainDb <= 24.01)
        // 很响的录音：只压小、不削波，峰值不超过 -1 dBFS
        val loud = ShortArray(32_000) { (32000 * Math.sin(2 * Math.PI * 300 * it / 16_000)).toInt().toShort() }
        val z = AudioClean.process(loud, AudioClean.Mode.NONE, boost = true)
        assertTrue(z.maxOf { Math.abs(it.toInt()) } <= (32767 * 0.891).toInt() + 1)
        // 不放大、不降噪：原样
        assertTrue(AudioClean.process(quiet, AudioClean.Mode.NONE, boost = false).contentEquals(quiet))
    }

    @Test fun limiterSmoothsSingleSpike() {
        val x = ShortArray(16_000) { if (it == 8000) 30000 else (1000 * Math.sin(it * 0.3)).toInt().toShort() }
        val y = AudioClean.Cleaner(x, AudioClean.Mode.NONE, 0f, boost = false).readAll()
        assertTrue(Math.abs(y[8000].toInt()) <= (32767 * 0.891).toInt() + 1)
        // 离尖峰远的地方不受影响
        assertEquals(x[2000].toInt(), y[2000].toInt())
        assertEquals(x[15_000].toInt().toDouble(), y[15_000].toDouble(), 30.0)
    }

    @Test fun wienerSuppressesNoise() {
        val x = signal(1.0)
        val y = AudioClean.process(x, AudioClean.Mode.WIENER, floor = 0.1f, boost = false)
        assertTrue(20 * Math.log10(rms(y, 2000, 14_000) / rms(x, 2000, 14_000)) < -10)
    }
}
