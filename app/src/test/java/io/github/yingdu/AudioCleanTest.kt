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

class BrightnessTest {
    @Test fun levelsLikeTheOfficialApp() {
        // 0..100% → 0..16 档，舍去小数
        org.junit.Assert.assertEquals(0, NimoClient.brightnessLevel(0)); org.junit.Assert.assertEquals(16, NimoClient.brightnessLevel(100))
        org.junit.Assert.assertEquals(9, NimoClient.brightnessLevel(60)); org.junit.Assert.assertEquals(15, NimoClient.brightnessLevel(99))
        org.junit.Assert.assertEquals(0x15, NimoProtocol.SET_BRIGHTNESS_OFFSET)
    }
}

class UpdaterTest {
    @Test fun comparesVersions() {
        org.junit.Assert.assertTrue(Updater.newer("1.0.3", "1.0.2"))
        org.junit.Assert.assertTrue(Updater.newer("v1.0.10", "1.0.9"))
        org.junit.Assert.assertTrue(Updater.newer("1.1", "1.0.9"))
        org.junit.Assert.assertFalse(Updater.newer("1.0.2", "1.0.2"))
        org.junit.Assert.assertFalse(Updater.newer("1.0.2", "1.0.3"))
        org.junit.Assert.assertFalse(Updater.newer("1.0", "1.0.0"))
    }

    @Test fun parsesGithubRelease() {
        val sha = "7f49cdd55c8405684d02642ce828d273bace32ae017f1f42270667b7cf82d8bd"
        val json = """{"tag_name":"v1.0.3","draft":false,"prerelease":false,
            "body":"## 更新\n- **阅读**：音量键翻页\n- 亮度修复\n\nSHA-256：`$sha`\n\n> 非官方项目",
            "assets":[{"name":"Yingdu-v1.0.3.apk","browser_download_url":"https://github.com/codexmasterme/yingdu/releases/download/v1.0.3/Yingdu-v1.0.3.apk"}]}"""
        val r = Updater.parseGithub(json)!!
        org.junit.Assert.assertEquals("1.0.3", r.version)
        org.junit.Assert.assertEquals("Yingdu-v1.0.3.apk", r.apkName)
        org.junit.Assert.assertEquals(sha, r.sha256)
        org.junit.Assert.assertEquals("https://cdn.jsdelivr.net/gh/codexmasterme/yingdu@v1.0.3/releases/Yingdu-v1.0.3.apk", r.urls[0])
        org.junit.Assert.assertEquals("https://github.com/codexmasterme/yingdu/releases/download/v1.0.3/Yingdu-v1.0.3.apk", r.urls[1])
        org.junit.Assert.assertEquals("更新\n- 阅读：音量键翻页\n- 亮度修复\n\n> 非官方项目", r.notes)
        org.junit.Assert.assertNull(Updater.parseGithub("""{"tag_name":"v2.0.0","draft":true}"""))
        val u = Updater.parseUpdateJson("""{"version":"1.0.4","apk":"releases/Yingdu-v1.0.4.apk","sha256":"$sha","notes":"修复"}""")!!
        org.junit.Assert.assertEquals("Yingdu-v1.0.4.apk", u.apkName); org.junit.Assert.assertEquals(sha, u.sha256)
    }
}
